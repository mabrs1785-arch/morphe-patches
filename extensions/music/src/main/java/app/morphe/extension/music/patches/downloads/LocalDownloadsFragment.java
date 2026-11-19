/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceFragment;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.search.BaseSearchViewController;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;

/** Local catalogue with a compact player matching the stock bottom player of YouTube Music. */
@SuppressWarnings("deprecation")
public final class LocalDownloadsFragment extends PreferenceFragment
        implements OfflinePlaybackService.PlaybackListener {

    private static final int SEEK_BAR_HEIGHT_DP = 3;

    /** The bar is drawn thin but stays easy to hit. */
    private static final int SEEK_BAR_TOUCH_DP = 18;
    private static final int SEEK_THUMB_DP = 12;

    private static final int SEARCH_BAR_HEIGHT_DP = 46;
    private static final long SEARCH_ANIMATION_MILLISECONDS = 180;

    /** Dimming the foreground gives a readable secondary on any background. */
    private static final float SECONDARY_DIM = 0.72f;

    /** How much the background of the playing row is lifted out of the surrounding one. */
    private static final float PLAYING_ROW_BRIGHTNESS = 1.35f;

    private static final int ROW_CORNER_DP = 8;

    /** Rows are 56dp, so a sample of the stored cover is already more than enough. */
    private static final int ROW_ARTWORK_PIXELS = 256;

    private static final int ARTWORK_CORNER_DP = 4;

    private static final int MENU_WIDTH_DP = 220;
    private static final int MENU_CORNER_DP = 12;

    /** Icon buttons are square, otherwise their round ripple is drawn as a stretched oval. */
    private static final int ICON_BUTTON_DP = 48;

    /** The full player shows the cover across the screen, so it is decoded at a larger size. */
    private static final int FULL_ARTWORK_PIXELS = 1024;
    private static final int FULL_GUTTER_DP = 24;
    private static final int FULL_PLAY_BUTTON_DP = 72;
    private static final int FULL_SKIP_BUTTON_DP = 56;

    private File musicRoot;
    private ImageView miniArtwork;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageButton miniPlay;
    private ImageButton miniPrevious;
    private ImageButton miniNext;
    private SeekBar miniSeek;
    private LinearLayout miniPlayer;
    private LinearLayout songsList;

    private boolean showingPause;

    /**
     * Every downloaded track, read once per refresh instead of on every list or playback update.
     */
    private List<OfflineTrack> tracks = new ArrayList<>();

    /** Each row, kept so the playing one can be marked without redrawing the list. */
    private final List<View> rowViews = new ArrayList<>();

    private List<String> queuePaths = new ArrayList<>();
    private String playingPath = "";
    private String query = "";
    private TextView summary;
    private EditText searchBar;
    private boolean userSeeking;

    /** Opened by tapping a track or the mini player, like the player of online playback. */
    @Nullable
    private Dialog fullPlayer;
    private ImageView fullArtwork;
    private TextView fullTitle;
    private TextView fullArtist;
    private TextView fullElapsed;
    private TextView fullDuration;
    private ImageButton fullPlay;
    private ImageButton fullPrevious;
    private ImageButton fullNext;
    private SeekBar fullSeek;

    /** How the catalogue is ordered, remembered between visits. */
    private enum SortOrder {
        // New values go at the end, the saved order is stored by name.
        ARTIST, TITLE, SIZE, NEWEST, OLDEST;

        static SortOrder saved() {
            try {
                return valueOf(Settings.DOWNLOADS_SORT.get());
            } catch (Exception ignored) {
                return ARTIST;
            }
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        musicRoot = new File(getActivity().getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Morphe");

        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background());

        // The header stays out of the list, so typing a query does not rebuild the search bar.
        root.addView(createHeader(), new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(getActivity());
        songsList = new LinearLayout(getActivity());
        songsList.setOrientation(LinearLayout.VERTICAL);
        songsList.setPadding(dp(8), 0, dp(8), dp(10));
        scroll.addView(songsList);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        showTracks();

        miniPlayer = createStockMiniPlayer();
        miniPlayer.setVisibility(View.GONE);
        root.addView(miniPlayer, new LinearLayout.LayoutParams(-1, -2));
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        OfflinePlaybackService.addListener(this);
    }

    @Override
    public void onStop() {
        OfflinePlaybackService.removeListener(this);
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        if (fullPlayer != null) fullPlayer.dismiss();
        fullPlayer = null;
        super.onDestroyView();
    }

    /**
     * Reads the catalogue from disk. Every other method works off this snapshot.
     */
    private void loadTracks() {
        tracks = new ArrayList<>();
        File[] files = musicRoot.listFiles(file -> file.isFile()
                && (file.getName().endsWith(".webm") || file.getName().endsWith(".m4a")));
        if (files == null) return;

        for (File file : files) {
            tracks.add(OfflineTrack.load(file));
        }
        tracks.sort(comparator());
    }

    private Comparator<OfflineTrack> comparator() {
        Comparator<OfflineTrack> byTitle =
                Comparator.comparing(track -> track.displayTitle().toLowerCase(Locale.ROOT));

        return switch (SortOrder.saved()) {
            case TITLE -> byTitle;
            case SIZE -> Comparator.comparingLong(
                    (OfflineTrack track) -> track.audioFile().length()).reversed();
            // A finished download is renamed from its partial file and never written again,
            // so its modification time is when it was downloaded.
            case NEWEST -> Comparator.comparingLong(
                    (OfflineTrack track) -> track.audioFile().lastModified()).reversed()
                    .thenComparing(byTitle);
            case OLDEST -> Comparator.comparingLong(
                    (OfflineTrack track) -> track.audioFile().lastModified())
                    .thenComparing(byTitle);
            default -> Comparator.comparing(
                    (OfflineTrack track) -> track.displayArtist().toLowerCase(Locale.ROOT))
                    .thenComparing(byTitle);
        };
    }

    private boolean matchesQuery(OfflineTrack track) {
        if (query.isEmpty()) return true;
        return track.displayTitle().toLowerCase(Locale.ROOT).contains(query)
                || track.displayArtist().toLowerCase(Locale.ROOT).contains(query);
    }

    /**
     * The search bar and the summary sit above the list, so they survive a redraw of the rows.
     */
    private LinearLayout createHeader() {
        LinearLayout header = new LinearLayout(getActivity());
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(8), dp(16), dp(6));

        // Hidden until asked for, the way the settings screen reveals its own search.
        searchBar = CustomDialog.createSearchBar(getActivity(),
                str("morphe_music_downloads_search_hint"), text -> {
                    query = text.trim().toLowerCase(Locale.ROOT);
                    showTracks();
                });
        searchBar.setVisibility(View.GONE);
        searchBar.setAlpha(0f);
        header.addView(searchBar, new LinearLayout.LayoutParams(-1, 0));

        LinearLayout line = new LinearLayout(getActivity());
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(8), 0, 0);

        summary = text("", 13, secondary());
        line.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton search = icon(BaseSearchViewController.getSearchIconDrawable(),
                str("morphe_music_downloads_search_hint"));
        search.setOnClickListener(v -> toggleSearch());
        line.addView(search, new LinearLayout.LayoutParams(dp(40), dp(40)));

        ImageButton sort = icon("yt_outline_experimental_sort_vd_theme_24",
                str("morphe_music_downloads_sort"));
        sort.setOnClickListener(v -> showSortMenu(sort));
        line.addView(sort, new LinearLayout.LayoutParams(dp(40), dp(40)));

        ImageButton deleteAll = icon("yt_outline_experimental_trash_can_vd_theme_24",
                str("morphe_music_downloads_delete_all"));
        deleteAll.setOnClickListener(v -> confirmDeleteAll());
        line.addView(deleteAll, new LinearLayout.LayoutParams(dp(40), dp(40)));

        header.addView(line, new LinearLayout.LayoutParams(-1, -2));
        return header;
    }

    /** Opens the search field, or closes it and drops the query. */
    private void toggleSearch() {
        final boolean opening = searchBar.getVisibility() != View.VISIBLE;
        InputMethodManager keyboard = (InputMethodManager)
                getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);

        if (opening) {
            searchBar.setVisibility(View.VISIBLE);
            slideSearchBar(dp(SEARCH_BAR_HEIGHT_DP));
            searchBar.animate().alpha(1f).setDuration(SEARCH_ANIMATION_MILLISECONDS).start();

            searchBar.requestFocus();
            if (keyboard != null) keyboard.showSoftInput(searchBar, InputMethodManager.SHOW_IMPLICIT);
            return;
        }

        if (keyboard != null) keyboard.hideSoftInputFromWindow(searchBar.getWindowToken(), 0);
        searchBar.setText("");
        searchBar.clearFocus();

        slideSearchBar(0);
        searchBar.animate().alpha(0f).setDuration(SEARCH_ANIMATION_MILLISECONDS)
                .withEndAction(() -> searchBar.setVisibility(View.GONE)).start();
    }

    /** The height is animated by hand, since a collapsing child otherwise jumps the list. */
    private void slideSearchBar(int target) {
        ValueAnimator animator = ValueAnimator.ofInt(searchBar.getHeight(), target);
        animator.setDuration(SEARCH_ANIMATION_MILLISECONDS);
        animator.addUpdateListener(value -> {
            searchBar.getLayoutParams().height = (int) value.getAnimatedValue();
            searchBar.requestLayout();
        });
        animator.start();
    }

    private void showSortMenu(View anchor) {
        String[] items = {
                str("morphe_music_downloads_sort_artist"),
                str("morphe_music_downloads_sort_title"),
                str("morphe_music_downloads_sort_size"),
                str("morphe_music_downloads_sort_newest"),
                str("morphe_music_downloads_sort_oldest"),
        };
        showMenu(anchor, items, SortOrder.saved().ordinal(), position -> {
            Settings.DOWNLOADS_SORT.save(SortOrder.values()[position].name());
            showTracks();
        });
    }

    @Nullable
    private OfflineTrack findByPath(String path) {
        if (path.isEmpty()) return null;
        for (OfflineTrack track : tracks) {
            if (track.audioFile().getAbsolutePath().equals(path)) return track;
        }
        return null;
    }

    private void showTracks() {
        loadTracks();
        songsList.removeAllViews();
        rowViews.clear();
        queuePaths = new ArrayList<>();

        long totalBytes = 0;
        for (OfflineTrack track : tracks) {
            totalBytes += track.audioFile().length();
        }
        summary.setText(str("morphe_music_downloads_summary",
                tracks.size(), formatSize(totalBytes)));

        for (OfflineTrack track : tracks) {
            if (!matchesQuery(track)) continue;
            queuePaths.add(track.audioFile().getAbsolutePath());
            songsList.addView(songRow(track));
        }

        if (queuePaths.isEmpty()) {
            TextView empty = text(str(tracks.isEmpty()
                    ? "morphe_music_downloads_empty"
                    : "morphe_music_downloads_nothing_found"), 16, secondary());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(80), 0, 0);
            songsList.addView(empty);
            return;
        }
        markPlayingRow();
    }

    private View songRow(OfflineTrack track) {
        LinearLayout row = new LinearLayout(getActivity());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(7), dp(8), dp(7));
        rowViews.add(row);
        row.addView(artworkView(track.artwork(ROW_ARTWORK_PIXELS)),
                new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout labels = new LinearLayout(getActivity());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(14), 0, dp(8), 0);

        TextView name = text(track.displayTitle(), 16, foreground());
        name.setSingleLine(true);

        TextView detail = text(str("morphe_music_downloads_track_subtitle",
                track.displayArtist(), formatDuration(track.durationSeconds()),
                formatSize(track.audioFile().length())), 13, secondary());
        detail.setSingleLine(true);

        labels.addView(name);
        labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton menu = icon("yt_outline_experimental_overflow_vertical_vd_theme_24",
                str("morphe_music_downloads_actions_for", track.displayTitle()));
        menu.setOnClickListener(v -> showTrackMenu(menu, track));
        row.addView(menu, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        row.setOnClickListener(v -> play(track));
        return row;
    }

    /** Lifts the card of the track being played out of the background. */
    private void markPlayingRow() {
        int playingIndex = queuePaths.indexOf(playingPath);
        for (int i = 0; i < rowViews.size(); i++) {
            rowViews.get(i).setBackground(rowBackground(i == playingIndex));
        }
    }

    /** The ripple is masked to the card, so it does not spill into the neighboring rows. */
    private Drawable rowBackground(boolean playing) {
        Drawable card = playing
                ? CustomDialog.createRoundedBackground(ROW_CORNER_DP, Utils.adjustColorBrightness(
                        ThemeUtils.getAppBackgroundColor(), PLAYING_ROW_BRIGHTNESS))
                : null;
        return new RippleDrawable(ColorStateList.valueOf(rippleColor()), card,
                CustomDialog.createRoundedBackground(ROW_CORNER_DP, Color.WHITE));
    }

    private static int rippleColor() {
        final int foreground = foreground();
        return Color.argb(60, Color.red(foreground),
                Color.green(foreground), Color.blue(foreground));
    }

    private LinearLayout createStockMiniPlayer() {
        LinearLayout outer = new LinearLayout(getActivity());
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(ThemeUtils.getDialogBackgroundColor());

        // The surface color of a theme can sit very close to its background, so the bar is
        // separated by a rule rather than by the fill alone.
        View divider = new View(getActivity());
        divider.setBackgroundColor(rippleColor());
        outer.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        // Lifts the bar off the gesture area, so the seek bar is not on the screen edge.
        outer.setPadding(0, 0, 0, dp(12));

        LinearLayout line = new LinearLayout(getActivity());
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(16), 0, 0, 0);

        miniArtwork = artworkView(null);
        line.addView(miniArtwork, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout labels = new LinearLayout(getActivity());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.setPadding(dp(16), 0, dp(4), 0);

        miniTitle = text("", 14, foreground());
        miniTitle.setSingleLine(true);
        miniTitle.setTypeface(Typeface.DEFAULT_BOLD);

        miniArtist = text("", 14, secondary());
        miniArtist.setSingleLine(true);

        labels.addView(miniTitle);
        labels.addView(miniArtist);
        line.addView(labels, new LinearLayout.LayoutParams(0, dp(56), 1));

        miniPrevious = icon("yt_fill_experimental_skip_previous_vd_theme_24",
                str("morphe_music_downloads_previous"));
        miniPrevious.setOnClickListener(v -> OfflinePlaybackService.skipPrevious(getActivity()));
        line.addView(miniPrevious, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        miniPlay = icon("yt_fill_experimental_play_vd_theme_24",
                str("morphe_music_downloads_play_pause"));
        // The morph drawable of the app is 48dp, so whatever the button shows is scaled down to
        // the 24dp of the icons beside it.
        miniPlay.setScaleType(ImageView.ScaleType.FIT_CENTER);
        miniPlay.setPadding(dp(12), dp(12), dp(12), dp(12));
        miniPlay.setOnClickListener(v -> OfflinePlaybackService.toggle(getActivity()));
        line.addView(miniPlay, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        miniNext = icon("yt_fill_experimental_skip_next_vd_theme_24",
                str("morphe_music_downloads_next"));
        miniNext.setOnClickListener(v -> OfflinePlaybackService.skipNext(getActivity()));
        line.addView(miniNext, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        outer.addView(line, new LinearLayout.LayoutParams(-1, dp(60)));
        miniSeek = createSeekBar(false);
        outer.addView(miniSeek, new LinearLayout.LayoutParams(-1, dp(SEEK_BAR_TOUCH_DP)));

        // The buttons keep their own taps, anywhere else on the bar opens the full player.
        line.setOnClickListener(v -> openFullPlayer(findByPath(playingPath)));
        return outer;
    }

    /**
     * @param alwaysShowThumb The full player keeps its thumb, the mini player only shows it
     *                        while dragging.
     */
    private SeekBar createSeekBar(boolean alwaysShowThumb) {
        SeekBar seek = new SeekBar(getActivity());
        // The bar keeps the gutters of the list, so it reads as a control rather than an edge.
        seek.setPadding(dp(16), 0, dp(16), 0);
        seek.setProgressDrawable(createSeekBarDrawable());
        // The stock thumb draws a ripple far wider than the bar, so it is replaced.
        seek.setThumb(alwaysShowThumb ? createThumb() : null);
        seek.setThumbOffset(alwaysShowThumb ? dp(SEEK_THUMB_DP) / 2 : 0);
        seek.setBackground(null);
        seek.setSplitTrack(false);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                userSeeking = true;
                if (!alwaysShowThumb) {
                    bar.setThumb(createThumb());
                    bar.setThumbOffset(dp(SEEK_THUMB_DP) / 2);
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                userSeeking = false;
                if (!alwaysShowThumb) bar.setThumb(null);
                OfflinePlaybackService.seekTo(bar.getProgress());
            }

            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && bar == fullSeek && fullElapsed != null) {
                    fullElapsed.setText(formatTime(value));
                }
            }
        });
        return seek;
    }

    /**
     * A full screen player like the one of online playback: large cover, title, artist,
     * a seek bar with times, and the playback controls. Back or the arrow closes it.
     */
    private void openFullPlayer(@Nullable OfflineTrack track) {
        Activity activity = getActivity();
        if (activity == null || track == null) return;
        if (fullPlayer != null && fullPlayer.isShowing()) {
            applyFullTrackVisuals(track);
            return;
        }

        Dialog dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background());
        root.setPadding(dp(FULL_GUTTER_DP - 12), dp(8), dp(FULL_GUTTER_DP - 12), dp(24));

        // Top bar with the button that collapses the player back to the list.
        LinearLayout top = new LinearLayout(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton collapse = icon(collapseIcon(), str("morphe_music_downloads_collapse_player"));
        collapse.setColorFilter(foreground());
        collapse.setOnClickListener(v -> dialog.dismiss());
        top.addView(collapse, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // The cover is square and as wide as the screen allows, centered in the free space.
        LinearLayout artworkArea = new LinearLayout(activity);
        artworkArea.setGravity(Gravity.CENTER);
        artworkArea.setPadding(dp(12), dp(8), dp(12), dp(8));
        fullArtwork = artworkView(null);
        final int screenWidth = getResources().getDisplayMetrics().widthPixels;
        final int artworkSize = screenWidth - 2 * dp(FULL_GUTTER_DP);
        artworkArea.addView(fullArtwork, new LinearLayout.LayoutParams(artworkSize, artworkSize));
        root.addView(artworkArea, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12), dp(16), dp(12), dp(8));
        fullTitle = text("", 22, foreground());
        fullTitle.setTypeface(Typeface.DEFAULT_BOLD);
        fullTitle.setSingleLine(true);
        fullTitle.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        fullTitle.setMarqueeRepeatLimit(-1);
        fullTitle.setSelected(true);
        fullArtist = text("", 16, secondary());
        fullArtist.setSingleLine(true);
        fullArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(fullTitle);
        labels.addView(fullArtist);
        root.addView(labels, new LinearLayout.LayoutParams(-1, -2));

        fullSeek = createSeekBar(true);
        // The thumb would be cut at the ends without room for its half width.
        fullSeek.setPadding(dp(12), 0, dp(12), 0);
        root.addView(fullSeek, new LinearLayout.LayoutParams(-1, dp(32)));

        LinearLayout times = new LinearLayout(activity);
        times.setPadding(dp(12), 0, dp(12), 0);
        fullElapsed = text("0:00", 12, secondary());
        fullDuration = text("", 12, secondary());
        fullDuration.setGravity(Gravity.END);
        times.addView(fullElapsed, new LinearLayout.LayoutParams(0, -2, 1));
        times.addView(fullDuration, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(times, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout controls = new LinearLayout(activity);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(16), 0, dp(8));

        fullPrevious = icon("yt_fill_experimental_skip_previous_vd_theme_24",
                str("morphe_music_downloads_previous"));
        fullPrevious.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fullPrevious.setPadding(dp(12), dp(12), dp(12), dp(12));
        fullPrevious.setOnClickListener(v -> OfflinePlaybackService.skipPrevious(activity));

        // A round button filled with the text color, like the play button of the stock player.
        fullPlay = new ImageButton(activity);
        fullPlay.setContentDescription(str("morphe_music_downloads_play_pause"));
        fullPlay.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fullPlay.setPadding(dp(20), dp(20), dp(20), dp(20));
        ShapeDrawable circle = new ShapeDrawable(new OvalShape());
        circle.getPaint().setColor(foreground());
        fullPlay.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()), circle, null));
        fullPlay.setOnClickListener(v -> OfflinePlaybackService.toggle(activity));

        fullNext = icon("yt_fill_experimental_skip_next_vd_theme_24",
                str("morphe_music_downloads_next"));
        fullNext.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fullNext.setPadding(dp(12), dp(12), dp(12), dp(12));
        fullNext.setOnClickListener(v -> OfflinePlaybackService.skipNext(activity));

        LinearLayout.LayoutParams skipParams =
                new LinearLayout.LayoutParams(dp(FULL_SKIP_BUTTON_DP), dp(FULL_SKIP_BUTTON_DP));
        LinearLayout.LayoutParams playParams =
                new LinearLayout.LayoutParams(dp(FULL_PLAY_BUTTON_DP), dp(FULL_PLAY_BUTTON_DP));
        playParams.setMargins(dp(32), 0, dp(32), 0);
        controls.addView(fullPrevious, skipParams);
        controls.addView(fullPlay, playParams);
        controls.addView(fullNext, new LinearLayout.LayoutParams(skipParams));
        root.addView(controls, new LinearLayout.LayoutParams(-1, -2));

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setStatusBarColor(background());
            window.setNavigationBarColor(background());
        }
        dialog.setOnDismissListener(d -> {
            if (fullPlayer == dialog) fullPlayer = null;
        });

        fullPlayer = dialog;
        applyFullTrackVisuals(track);
        updateFullPlayIcon(showingPause);
        if (miniSeek != null) {
            updateFullProgress(miniSeek.getProgress(), miniSeek.getMax());
        }
        dialog.show();
    }

    @Nullable
    private Drawable collapseIcon() {
        Drawable icon = ResourceUtils.getDrawable("yt_outline_experimental_chevron_down_vd_theme_24");
        if (icon == null) icon = ResourceUtils.getDrawable("yt_outline_chevron_down_vd_theme_24");
        if (icon == null) icon = getActivity().getDrawable(android.R.drawable.arrow_down_float);
        return icon;
    }

    private boolean fullPlayerShowing() {
        return fullPlayer != null && fullPlayer.isShowing();
    }

    private void applyFullTrackVisuals(OfflineTrack track) {
        if (fullPlayer == null) return;
        fullTitle.setText(track.displayTitle());
        fullArtist.setText(track.displayArtist());

        Bitmap bitmap = track.artwork(FULL_ARTWORK_PIXELS);
        if (bitmap != null) {
            fullArtwork.clearColorFilter();
            fullArtwork.setPadding(0, 0, 0, 0);
            fullArtwork.setImageBitmap(bitmap);
            fullArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            fullArtwork.setImageDrawable(
                    ResourceUtils.getDrawable("yt_fill_experimental_play_circle_vd_theme_24"));
            fullArtwork.setColorFilter(foreground());
            fullArtwork.setScaleType(ImageView.ScaleType.FIT_CENTER);
            fullArtwork.setPadding(dp(64), dp(64), dp(64), dp(64));
        }

        int index = queuePaths.indexOf(track.audioFile().getAbsolutePath());
        setButtonEnabled(fullPrevious, index > 0);
        setButtonEnabled(fullNext, index >= 0 && index + 1 < queuePaths.size());
    }

    private void updateFullPlayIcon(boolean playing) {
        if (fullPlay == null) return;
        Drawable drawable = ResourceUtils.getDrawable(playing
                ? "yt_fill_experimental_pause_vd_theme_24"
                : "yt_fill_experimental_play_vd_theme_24");
        fullPlay.setImageDrawable(drawable);
        // The button is filled with the text color, so the icon takes the background color.
        fullPlay.setColorFilter(background());
    }

    private void updateFullProgress(int position, int duration) {
        if (fullSeek == null) return;
        fullSeek.setMax(Math.max(1, duration));
        if (!userSeeking) {
            fullSeek.setProgress(position, true);
            fullElapsed.setText(formatTime(position));
        }
        fullDuration.setText(duration > 1 ? formatTime(duration) : "");
    }

    private Drawable createThumb() {
        ShapeDrawable thumb = new ShapeDrawable(new OvalShape());
        thumb.setIntrinsicWidth(dp(SEEK_THUMB_DP));
        thumb.setIntrinsicHeight(dp(SEEK_THUMB_DP));
        thumb.getPaint().setColor(ThemeUtils.getAppForegroundColor());
        return thumb;
    }

    /**
     * The thickness comes from the drawable because {@code setMinHeight} and
     * {@code setMaxHeight} are only available from API 29.
     */
    private Drawable createSeekBarDrawable() {
        final int foreground = ThemeUtils.getAppForegroundColor();

        // Dimming the foreground works on any background, unlike lightening a black one.
        Drawable track = roundedBar(Utils.adjustColorBrightness(foreground, 0.3f));
        Drawable played = roundedBar(foreground);

        LayerDrawable layers = new LayerDrawable(new Drawable[]{
                track, new ClipDrawable(played, Gravity.START, ClipDrawable.HORIZONTAL)});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);

        // A progress drawable is stretched to the view, and the thickness can only be capped
        // with setMaxHeight from API 29, so the band is inset to the height it should have.
        final int inset = (dp(SEEK_BAR_TOUCH_DP) - dp(SEEK_BAR_HEIGHT_DP)) / 2;
        layers.setLayerInset(0, 0, inset, 0, inset);
        layers.setLayerInset(1, 0, inset, 0, inset);
        return layers;
    }

    private Drawable roundedBar(int color) {
        return CustomDialog.createRoundedBackground(SEEK_BAR_HEIGHT_DP, color);
    }

    private void showTrackMenu(View anchor, OfflineTrack track) {
        String[] items = {
                str("morphe_music_downloads_play"),
                str("morphe_music_downloads_delete_track"),
        };
        showMenu(anchor, items, -1, position -> {
            if (position == 1) confirmDelete(track);
            else play(track);
        });
    }

    /**
     * @param selected Index drawn as the active choice, or -1 when the items are plain actions.
     */
    private void showMenu(View anchor, String[] items, int selected, IntConsumer onPick) {
        ListPopupWindow popup = new ListPopupWindow(getActivity());
        popup.setAnchorView(anchor);
        popup.setDropDownGravity(Gravity.END);
        popup.setWidth(dp(MENU_WIDTH_DP));
        popup.setModal(true);
        popup.setBackgroundDrawable(menuBackground());
        // The list draws its own square selector, which would bleed past the rounded background.
        popup.setListSelector(new RippleDrawable(ColorStateList.valueOf(rippleColor()), null,
                CustomDialog.createRoundedBackground(MENU_CORNER_DP, Color.WHITE)));
        popup.setAdapter(new ArrayAdapter<>(getActivity(), 0, items) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView view = text(getItem(position), 15,
                        selected < 0 || position == selected ? foreground() : secondary());
                view.setPadding(dp(20), dp(14), dp(20), dp(14));
                return view;
            }
        });
        popup.setOnItemClickListener((parent, view, position, id) -> {
            popup.dismiss();
            onPick.accept(position);
        });
        popup.show();
    }

    /**
     * The surface color of a theme can sit very close to its background, so the menu is outlined
     * rather than left to rely on the two being far enough apart.
     */
    private Drawable menuBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(ThemeUtils.getDialogBackgroundColor());
        background.setCornerRadius(dp(MENU_CORNER_DP));
        background.setStroke(dp(1), rippleColor());
        return background;
    }

    private void confirmDelete(OfflineTrack track) {
        confirm(str("morphe_music_downloads_delete_track_title"),
                str("morphe_music_downloads_delete_track_message", track.displayTitle()),
                () -> deleteTrack(track));
    }

    private void confirmDeleteAll() {
        if (tracks.isEmpty()) return;
        confirm(str("morphe_music_downloads_delete_all_title"),
                str("morphe_music_downloads_delete_all_message"), this::deleteAll);
    }

    private void confirm(String title, String message, Runnable onConfirm) {
        CustomDialog.create(getActivity(), title, message, null,
                str("morphe_music_downloads_delete"), onConfirm, () -> {
                }, null, null, false).first.show();
    }

    private void deleteAll() {
        for (OfflineTrack track : new ArrayList<>(tracks)) {
            OfflineStorage.delete(track.audioFile());
            deleteSidecars(track.videoId());
        }
        showTracks();
        Utils.showToastShort(str("morphe_music_downloads_all_deleted"));
    }

    private void deleteSidecars(String videoId) {
        OfflineStorage.delete(new File(musicRoot, videoId + ".json"));
        OfflineStorage.delete(new File(musicRoot, videoId + ".jpg"));
        OfflineStorage.delete(new File(musicRoot, videoId + ".webm.part"));
        OfflineStorage.delete(new File(musicRoot, videoId + ".m4a.part"));
    }

    private void deleteTrack(OfflineTrack track) {
        final boolean deleted = track.audioFile().delete();
        deleteSidecars(track.videoId());

        if (!deleted) {
            Utils.showToastShort(str("morphe_music_downloads_track_delete_failed"));
            return;
        }
        showTracks();
        Utils.showToastShort(str("morphe_music_downloads_track_deleted"));
    }

    private void play(OfflineTrack track) {
        try {
            ArrayList<String> queue = new ArrayList<>(queuePaths);
            final int queueIndex = Math.max(0, queue.indexOf(track.audioFile().getAbsolutePath()));

            Intent intent = new Intent(getActivity(), OfflinePlaybackService.class)
                    .setAction(OfflinePlaybackService.ACTION_PLAY_FILE)
                    .putExtra(OfflinePlaybackService.EXTRA_PATH, track.audioFile().getAbsolutePath())
                    .putExtra(OfflinePlaybackService.EXTRA_TITLE, track.displayTitle())
                    .putExtra(OfflinePlaybackService.EXTRA_ARTIST, track.displayArtist())
                    .putExtra(OfflinePlaybackService.EXTRA_ARTWORK_PATH,
                            track.artworkFile().getAbsolutePath())
                    .putStringArrayListExtra(OfflinePlaybackService.EXTRA_QUEUE, queue)
                    .putExtra(OfflinePlaybackService.EXTRA_QUEUE_INDEX, queueIndex);
            getActivity().startForegroundService(intent);

            applyTrackVisuals(track);
            openFullPlayer(track);
        } catch (Exception ex) {
            Logger.printException(() -> "Offline playback failed: " + track.audioFile(), ex);
            Utils.showToastShort(str("morphe_music_downloads_play_failed"));
        }
    }

    @Override
    public void onPlaybackChanged(String path, String title, boolean playing,
                                  int position, int duration) {
        if (getActivity() == null) return;

        getActivity().runOnUiThread(() -> {
            if (miniPlayer == null) return;
            final boolean wasVisible = miniPlayer.getVisibility() == View.VISIBLE;
            miniPlayer.setVisibility(title.isEmpty() ? View.GONE : View.VISIBLE);

            // Decoding the cover on every tick would read the disk twice a second.
            if (!path.equals(playingPath)) {
                playingPath = path;
                OfflineTrack track = findByPath(path);
                if (track != null) applyTrackVisuals(track);
                else miniTitle.setText(title);

                int index = queuePaths.indexOf(path);
                setButtonEnabled(miniPrevious, index > 0);
                setButtonEnabled(miniNext, index >= 0 && index + 1 < queuePaths.size());
                markPlayingRow();
                if (track != null && fullPlayerShowing()) applyFullTrackVisuals(track);
            }

            // The service stops itself at the end of the queue or when closed.
            if (title.isEmpty() && fullPlayerShowing()) fullPlayer.dismiss();

            if (fullPlayerShowing()) {
                if (playing != showingPause) updateFullPlayIcon(playing);
                updateFullProgress(position, duration);
            }

            // This runs on every playback tick, so the icon only morphs when the state
            // actually flips while the bar is on screen to see it happen.
            updatePlayIcon(playing, wasVisible && playing != showingPause);
            miniSeek.setMax(Math.max(1, duration));
            if (!userSeeking) miniSeek.setProgress(position, true);
        });
    }

    private void updatePlayIcon(boolean playing, boolean morph) {
        showingPause = playing;
        if (morph && startIconMorph(playing)) return;

        miniPlay.setImageDrawable(ResourceUtils.getDrawable(playing
                ? "yt_fill_experimental_pause_vd_theme_24"
                : "yt_fill_experimental_play_vd_theme_24"));
    }

    /**
     * The app ships the drawable its own player uses to morph between the two icons.
     */
    private boolean startIconMorph(boolean playing) {
        String name = playing
                ? "player_play_pause_vector_transition"
                : "player_pause_play_vector_transition";

        int identifier = ResourceUtils.getDrawableIdentifier(name + "_delhi");
        if (identifier == 0) identifier = ResourceUtils.getDrawableIdentifier(name);

        Activity activity = getActivity();
        if (identifier == 0 || activity == null) return false;

        // It fills itself with a theme attribute of the player, so it has to be read with the
        // theme of this screen, which defines that attribute as white for the icon tint to work.
        Drawable drawable = activity.getDrawable(identifier);
        if (!(drawable instanceof AnimatedVectorDrawable morph)) return false;

        miniPlay.setImageDrawable(morph);
        morph.start();
        return true;
    }

    private void applyTrackVisuals(OfflineTrack track) {
        miniTitle.setText(track.displayTitle());
        miniArtist.setText(track.displayArtist());

        Bitmap bitmap = track.artwork(ROW_ARTWORK_PIXELS);
        if (bitmap == null) return;

        miniArtwork.clearColorFilter();
        miniArtwork.setPadding(0, 0, 0, 0);
        miniArtwork.setImageBitmap(bitmap);
        miniArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
    }

    private void setButtonEnabled(ImageButton button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : .35f);
    }

    private ImageView artworkView(@Nullable Bitmap bitmap) {
        ImageView view = new ImageView(getActivity());
        view.setBackgroundColor(Utils.adjustColorBrightness(foreground(), 0.2f));
        roundCorners(view);

        if (bitmap != null) {
            view.setImageBitmap(bitmap);
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            view.setImageDrawable(
                    ResourceUtils.getDrawable("yt_fill_experimental_play_circle_vd_theme_24"));
            view.setColorFilter(foreground());
            view.setPadding(dp(16), dp(16), dp(16), dp(16));
        }
        return view;
    }

    /** Matches the softly rounded covers the app uses on the home feed. */
    private void roundCorners(View view) {
        final float radius = dp(ARTWORK_CORNER_DP);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View outlined, Outline outline) {
                outline.setRoundRect(0, 0, outlined.getWidth(), outlined.getHeight(), radius);
            }
        });
        view.setClipToOutline(true);
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(getActivity());
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private ImageButton icon(String drawable, String description) {
        ImageButton button = icon(ResourceUtils.getDrawable(drawable), description);
        // Icons of the app carry no color of their own.
        button.setColorFilter(foreground());
        return button;
    }

    /** For drawables that already carry the colors of Morphe. */
    private ImageButton icon(Drawable drawable, String description) {
        ImageButton button = new ImageButton(getActivity());
        button.setImageDrawable(drawable);
        button.setContentDescription(description);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));

        button.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()),
                null, new ShapeDrawable(new OvalShape())));
        return button;
    }

    private static int foreground() {
        return ThemeUtils.getAppForegroundColor();
    }

    private static int secondary() {
        return Utils.adjustColorBrightness(foreground(), SECONDARY_DIM);
    }

    private static int background() {
        return ThemeUtils.getAppBackgroundColor();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    private static String formatDuration(int seconds) {
        if (seconds <= 0) return "";
        if (seconds < 3600) {
            return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%d:%02d:%02d",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    /** Position in milliseconds as minutes and seconds, starting at 0:00. */
    private static String formatTime(int milliseconds) {
        final int seconds = Math.max(0, milliseconds / 1000);
        if (seconds == 0) return "0:00";
        return formatDuration(seconds);
    }

    private static String formatSize(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / 1048576.0);
    }
}
