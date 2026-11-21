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
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceFragment;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.search.BaseSearchViewController;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;

/**
 * The downloads screen: the catalogue, a mini player, a full player and the play queue, laid
 * out like online playback.
 * <p>
 * The catalogue is a recycled list, so only the rows on screen exist, and covers are decoded
 * off the main thread into a shared cache. Opening the screen with thousands of downloads used
 * to build and decode every row at once on the main thread, which froze the app for minutes and
 * could run it out of memory.
 */
@SuppressWarnings("deprecation")
public final class LocalDownloadsFragment extends PreferenceFragment
        implements OfflinePlaybackService.PlaybackListener, OfflineLibrary.Listener {

    private static final int SEEK_BAR_HEIGHT_DP = 3;
    /** The bar is drawn thin but stays easy to hit. */
    private static final int SEEK_BAR_TOUCH_DP = 18;
    private static final int SEEK_THUMB_DP = 12;
    private static final int SEARCH_BAR_HEIGHT_DP = 46;
    private static final long SEARCH_ANIMATION_MILLISECONDS = 180;
    private static final long SEARCH_DEBOUNCE_MILLISECONDS = 200;
    /** Dimming the foreground gives a readable secondary on any background. */
    private static final float SECONDARY_DIM = 0.72f;
    /** How much the background of the playing row is lifted out of the surrounding one. */
    private static final float PLAYING_ROW_BRIGHTNESS = 1.35f;
    private static final int ROW_CORNER_DP = 8;
    private static final int ROW_ARTWORK_DP = 56;
    private static final int ARTWORK_CORNER_DP = 4;
    private static final int MENU_WIDTH_DP = 240;
    private static final int MENU_CORNER_DP = 12;
    /** Icon buttons are square, otherwise their round ripple is drawn as a stretched oval. */
    private static final int ICON_BUTTON_DP = 48;
    private static final int FULL_ARTWORK_PIXELS = 1024;
    private static final int FULL_GUTTER_DP = 24;
    private static final int FULL_PLAY_BUTTON_DP = 72;
    private static final int FULL_SKIP_BUTTON_DP = 56;
    /** Dimmed icons mark a mode that is off, like shuffle and repeat of online playback. */
    private static final float INACTIVE_ALPHA = 0.45f;

    /** Covers of the list, kept across visits of the screen. Sized in kilobytes. */
    private static final LruCache<String, Bitmap> artworkCache = new LruCache<>(
            (int) Math.min(32 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 8)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };
    private static final ExecutorService artworkLoader = Executors.newFixedThreadPool(2);

    /** The one downloads screen that is open, so a second one replaces it instead of stacking. */
    private static WeakReference<Activity> openScreen = new WeakReference<>(null);

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** The catalogue as shown: filtered by the search and sorted. */
    private List<OfflineTrack> visible = new ArrayList<>();
    private String query = "";
    private final Runnable applyQuery = this::showTracks;

    private ListView list;
    private TrackAdapter adapter;
    private TextView summary;
    private EditText searchBar;
    private TextView emptyView;

    private LinearLayout miniPlayer;
    private ImageView miniArtwork;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageButton miniPlay;
    private ImageButton miniPrevious;
    private ImageButton miniNext;
    private SeekBar miniSeek;
    private boolean showingPause;
    /** Whether the play button already shows a state since the screen started. */
    private boolean iconSynced;
    private boolean userSeeking;

    /** Path of the track the screen currently shows as playing. */
    private String shownPath = "";

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
    private ImageButton fullShuffle;
    private ImageButton fullRepeat;
    private SeekBar fullSeek;
    private String fullArtworkPath = "";

    @Nullable
    private Dialog queueDialog;
    @Nullable
    private QueueAdapter queueAdapter;
    private ListView queueList;

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

    /** Whether a downloads screen is already open, so the app does not stack another one. */
    public static boolean isOpen() {
        Activity activity = openScreen.get();
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    // ------------------------------------------------------------------------------------------
    // Lifecycle.
    // ------------------------------------------------------------------------------------------

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        Activity activity = getActivity();
        Activity previous = openScreen.get();
        if (previous != null && previous != activity && !previous.isFinishing()) previous.finish();
        openScreen = new WeakReference<>(activity);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background());
        root.addView(createHeader(), new LinearLayout.LayoutParams(-1, -2));

        FrameLayout listArea = new FrameLayout(activity);
        list = new ListView(activity);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setClipToPadding(false);
        list.setPadding(dp(8), 0, dp(8), dp(10));
        list.setFastScrollEnabled(true);
        list.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        adapter = new TrackAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> onTrackTapped(position));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < visible.size()) showTrackMenu(view, visible.get(position));
            return true;
        });
        listArea.addView(list, new FrameLayout.LayoutParams(-1, -1));

        emptyView = text("", 16, secondary());
        emptyView.setGravity(Gravity.CENTER_HORIZONTAL);
        emptyView.setPadding(dp(24), dp(80), dp(24), 0);
        emptyView.setVisibility(View.GONE);
        listArea.addView(emptyView, new FrameLayout.LayoutParams(-1, -2));
        root.addView(listArea, new LinearLayout.LayoutParams(-1, 0, 1));

        miniPlayer = createMiniPlayer();
        miniPlayer.setVisibility(View.GONE);
        root.addView(miniPlayer, new LinearLayout.LayoutParams(-1, -2));

        OfflineLibrary.addListener(this);
        OfflineLibrary.ensureLoaded(activity);
        showTracks();

        Intent intent = activity.getIntent();
        if (intent != null && intent.getBooleanExtra(OfflinePlaybackService.EXTRA_OPEN_PLAYER, false)) {
            // Only once, not again when the screen is rebuilt.
            intent.removeExtra(OfflinePlaybackService.EXTRA_OPEN_PLAYER);
            root.post(this::openFullPlayer);
        }
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        // The first state shown after coming back is drawn as is, not animated into.
        iconSynced = false;
        OfflinePlaybackService.addListener(this);
        OfflinePlaybackService.restoreIfIdle(getActivity());
    }

    @Override
    public void onStop() {
        OfflinePlaybackService.removeListener(this);
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        OfflineLibrary.removeListener(this);
        handler.removeCallbacksAndMessages(null);
        if (fullPlayer != null) fullPlayer.dismiss();
        if (queueDialog != null) queueDialog.dismiss();
        fullPlayer = null;
        queueDialog = null;
        if (openScreen.get() == getActivity()) openScreen = new WeakReference<>(null);
        super.onDestroyView();
    }

    // ------------------------------------------------------------------------------------------
    // Catalogue.
    // ------------------------------------------------------------------------------------------

    @Override
    public void onLibraryChanged(boolean loading, int scanned, int total) {
        if (list == null) return;
        showTracks();
        if (!loading) OfflinePlaybackService.restoreIfIdle(getActivity());
    }

    /** Filters and sorts the catalogue in memory, which takes a few milliseconds. */
    private void showTracks() {
        if (list == null) return;
        List<OfflineTrack> all = OfflineLibrary.tracks();
        List<OfflineTrack> shown = new ArrayList<>(all.size());
        long totalBytes = 0;
        for (OfflineTrack track : all) {
            totalBytes += track.sizeBytes();
            if (matchesQuery(track)) shown.add(track);
        }
        shown.sort(comparator());
        visible = shown;
        adapter.notifyDataSetChanged();

        final boolean loading = OfflineLibrary.isLoading();
        if (loading && OfflineLibrary.total() > 0) {
            summary.setText(str("morphe_music_downloads_loading",
                    OfflineLibrary.scanned(), OfflineLibrary.total()));
        } else {
            summary.setText(str("morphe_music_downloads_summary", all.size(), formatSize(totalBytes)));
        }

        if (shown.isEmpty() && !loading) {
            emptyView.setText(str(all.isEmpty()
                    ? "morphe_music_downloads_empty"
                    : "morphe_music_downloads_nothing_found"));
            emptyView.setVisibility(View.VISIBLE);
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    private Comparator<OfflineTrack> comparator() {
        Comparator<OfflineTrack> byTitle = Comparator.comparing(
                track -> track.displayTitle().toLowerCase(Locale.ROOT));

        return switch (SortOrder.saved()) {
            case TITLE -> byTitle;
            case SIZE -> Comparator.comparingLong(OfflineTrack::sizeBytes).reversed();
            case NEWEST -> Comparator.comparingLong(OfflineTrack::modifiedMillis).reversed()
                    .thenComparing(byTitle);
            case OLDEST -> Comparator.comparingLong(OfflineTrack::modifiedMillis)
                    .thenComparing(byTitle);
            default -> Comparator.comparing(
                            (OfflineTrack track) -> track.displayArtist().toLowerCase(Locale.ROOT))
                    .thenComparing(byTitle);
        };
    }

    private boolean matchesQuery(OfflineTrack track) {
        if (query.isEmpty()) return true;
        return track.displayTitle().toLowerCase(Locale.ROOT).contains(query)
                || track.displayArtist().toLowerCase(Locale.ROOT).contains(query)
                || track.album().toLowerCase(Locale.ROOT).contains(query);
    }

    private void onTrackTapped(int position) {
        if (position < 0 || position >= visible.size()) return;
        OfflineTrack track = visible.get(position);
        // Tapping the song that plays only brings its player up, like online playback.
        OfflineTrack current = OfflinePlaybackService.current();
        if (current == null || !current.path().equals(track.path())) {
            OfflinePlaybackService.playList(getActivity(), new ArrayList<>(visible), position, false);
        }
        openFullPlayer();
    }

    private void shuffleAll() {
        if (visible.isEmpty()) return;
        OfflinePlaybackService.playList(getActivity(), new ArrayList<>(visible), -1, true);
        openFullPlayer();
    }

    // ------------------------------------------------------------------------------------------
    // Header.
    // ------------------------------------------------------------------------------------------

    /** The search bar and the summary sit above the list, so they survive a refresh of it. */
    private LinearLayout createHeader() {
        Activity activity = getActivity();
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(8), dp(8), dp(6));

        // Hidden until asked for, the way the settings screen reveals its own search.
        searchBar = CustomDialog.createSearchBar(activity,
                str("morphe_music_downloads_search_hint"), text -> {
                    query = text.trim().toLowerCase(Locale.ROOT);
                    // Waits for a pause in typing instead of filtering on every letter.
                    handler.removeCallbacks(applyQuery);
                    handler.postDelayed(applyQuery, SEARCH_DEBOUNCE_MILLISECONDS);
                });
        searchBar.setVisibility(View.GONE);
        searchBar.setAlpha(0f);
        header.addView(searchBar, new LinearLayout.LayoutParams(-1, 0));

        LinearLayout line = new LinearLayout(activity);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(8), 0, 0);

        summary = text("", 13, secondary());
        summary.setSingleLine(true);
        summary.setEllipsize(TextUtils.TruncateAt.END);
        line.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton shuffle = icon("yt_outline_experimental_shuffle_vd_theme_24",
                str("morphe_music_downloads_shuffle_all"));
        shuffle.setOnClickListener(v -> shuffleAll());
        line.addView(shuffle, new LinearLayout.LayoutParams(dp(44), dp(44)));

        ImageButton search = icon(BaseSearchViewController.getSearchIconDrawable(),
                str("morphe_music_downloads_search_hint"));
        search.setOnClickListener(v -> toggleSearch());
        line.addView(search, new LinearLayout.LayoutParams(dp(44), dp(44)));

        ImageButton sort = icon("yt_outline_experimental_sort_vd_theme_24",
                str("morphe_music_downloads_sort"));
        sort.setOnClickListener(v -> showSortMenu(sort));
        line.addView(sort, new LinearLayout.LayoutParams(dp(44), dp(44)));

        ImageButton deleteAll = icon("yt_outline_experimental_trash_can_vd_theme_24",
                str("morphe_music_downloads_delete_all"));
        deleteAll.setOnClickListener(v -> confirmDeleteAll());
        line.addView(deleteAll, new LinearLayout.LayoutParams(dp(44), dp(44)));

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
            list.setSelection(0);
        });
    }

    // ------------------------------------------------------------------------------------------
    // Rows.
    // ------------------------------------------------------------------------------------------

    private static final class RowHolder {
        ImageView artwork;
        TextView title;
        TextView detail;
        ImageButton menu;
        View row;
    }

    private final class TrackAdapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            RowHolder holder = convertView == null ? createRow() : (RowHolder) convertView.getTag();
            OfflineTrack track = visible.get(position);
            final String size = formatSize(track.sizeBytes());
            // A track saved without a length leaves that slot out instead of an empty one.
            bindRow(holder, track, track.path().equals(shownPath), track.durationSeconds() > 0
                    ? str("morphe_music_downloads_track_subtitle", track.displayArtist(),
                            formatDuration(track.durationSeconds()), size)
                    : str("morphe_music_downloads_track_subtitle_no_duration",
                            track.displayArtist(), size));
            holder.menu.setOnClickListener(v -> showTrackMenu(v, track));
            return holder.row;
        }
    }

    private RowHolder createRow() {
        Activity activity = getActivity();
        RowHolder holder = new RowHolder();
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(7), dp(4), dp(7));
        row.setLayoutParams(new AbsListView.LayoutParams(-1, -2));

        holder.artwork = artworkView();
        row.addView(holder.artwork, new LinearLayout.LayoutParams(dp(ROW_ARTWORK_DP), dp(ROW_ARTWORK_DP)));

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(14), 0, dp(8), 0);
        holder.title = text("", 16, foreground());
        holder.title.setSingleLine(true);
        holder.title.setEllipsize(TextUtils.TruncateAt.END);
        holder.detail = text("", 13, secondary());
        holder.detail.setSingleLine(true);
        holder.detail.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(holder.title);
        labels.addView(holder.detail);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        holder.menu = icon("yt_outline_experimental_overflow_vertical_vd_theme_24",
                str("morphe_music_downloads_actions_for", ""));
        // The list handles taps on the row itself, the button keeps its own.
        holder.menu.setFocusable(false);
        row.addView(holder.menu, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        holder.row = row;
        row.setTag(holder);
        return holder;
    }

    private void bindRow(RowHolder holder, OfflineTrack track, boolean playing, String detail) {
        holder.title.setText(track.displayTitle());
        holder.detail.setText(detail);
        holder.menu.setContentDescription(str("morphe_music_downloads_actions_for", track.displayTitle()));
        holder.row.setBackground(rowBackground(playing));
        loadArtwork(holder.artwork, track.artworkFile(), dp(ROW_ARTWORK_DP));
    }

    /**
     * Shows a cover from the cache, or decodes it in the background. A recycled row may be
     * showing another track by the time the cover is ready, which the tag catches.
     */
    private void loadArtwork(ImageView view, File file, int sizePixels) {
        final String key = file.getAbsolutePath();
        view.setTag(key);
        Bitmap cached = artworkCache.get(key);
        if (cached != null) {
            showArtwork(view, cached);
            return;
        }
        showPlaceholder(view);
        artworkLoader.execute(() -> {
            if (!key.equals(view.getTag())) return;
            Bitmap bitmap = decodeThumbnail(file, sizePixels);
            if (bitmap == null) return;
            artworkCache.put(key, bitmap);
            handler.post(() -> {
                if (key.equals(view.getTag())) showArtwork(view, bitmap);
            });
        });
    }

    /** Covers are stored at 1200px, a list row only needs a fraction of that. */
    @Nullable
    private static Bitmap decodeThumbnail(File file, int sizePixels) {
        try {
            Bitmap sampled = OfflineStorage.decodeArtwork(file, sizePixels);
            if (sampled == null) return null;
            if (sampled.getWidth() <= sizePixels * 1.25f) return sampled;
            final int height = Math.max(1, sampled.getHeight() * sizePixels / sampled.getWidth());
            Bitmap scaled = Bitmap.createScaledBitmap(sampled, sizePixels, height, true);
            if (scaled != sampled) sampled.recycle();
            return scaled;
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not decode cover " + file, ex);
            return null;
        } catch (OutOfMemoryError error) {
            // A missing cover is better than a crash, the cache frees memory as rows scroll by.
            artworkCache.evictAll();
            return null;
        }
    }

    private void showArtwork(ImageView view, Bitmap bitmap) {
        view.clearColorFilter();
        view.setPadding(0, 0, 0, 0);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setImageBitmap(bitmap);
    }

    private void showPlaceholder(ImageView view) {
        view.setImageDrawable(ResourceUtils.getDrawable("yt_fill_experimental_play_circle_vd_theme_24"));
        view.setColorFilter(foreground());
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        final int inset = Math.max(dp(12), view.getLayoutParams() == null
                ? dp(12) : view.getLayoutParams().width / 4);
        view.setPadding(inset, inset, inset, inset);
    }

    /** Lifts the card of the track being played out of the background. */
    private Drawable rowBackground(boolean playing) {
        Drawable card = playing
                ? CustomDialog.createRoundedBackground(ROW_CORNER_DP, Utils.adjustColorBrightness(
                        ThemeUtils.getAppBackgroundColor(), PLAYING_ROW_BRIGHTNESS))
                : null;
        return new RippleDrawable(ColorStateList.valueOf(rippleColor()), card,
                CustomDialog.createRoundedBackground(ROW_CORNER_DP, Color.WHITE));
    }

    // ------------------------------------------------------------------------------------------
    // Menus and deletion.
    // ------------------------------------------------------------------------------------------

    private void showTrackMenu(View anchor, OfflineTrack track) {
        String[] items = {
                str("morphe_music_downloads_play_next"),
                str("morphe_music_downloads_add_to_queue"),
                str("morphe_music_downloads_play"),
                str("morphe_music_downloads_delete_track"),
        };
        showMenu(anchor, items, -1, position -> {
            Activity activity = getActivity();
            switch (position) {
                case 0 -> {
                    OfflinePlaybackService.playNext(activity, track);
                    Utils.showToastShort(str("morphe_music_downloads_added_next"));
                }
                case 1 -> {
                    OfflinePlaybackService.addToQueue(activity, track);
                    Utils.showToastShort(str("morphe_music_downloads_added_to_queue"));
                }
                case 2 -> {
                    int index = visible.indexOf(track);
                    if (index >= 0) onTrackTapped(index);
                }
                default -> confirmDelete(track);
            }
        });
    }

    /**
     * @param selected Index drawn as the active choice, or -1 when the items are plain actions.
     */
    private void showMenu(View anchor, String[] items, int selected, IntConsumer onPick) {
        Activity activity = getActivity();
        if (activity == null) return;
        ListPopupWindow popup = new ListPopupWindow(activity);
        popup.setAnchorView(anchor);
        popup.setDropDownGravity(Gravity.END);
        popup.setWidth(dp(MENU_WIDTH_DP));
        popup.setModal(true);
        popup.setBackgroundDrawable(menuBackground());
        // The list draws its own square selector, which would bleed past the rounded background.
        popup.setListSelector(new RippleDrawable(ColorStateList.valueOf(rippleColor()), null,
                CustomDialog.createRoundedBackground(MENU_CORNER_DP, Color.WHITE)));
        popup.setAdapter(new ArrayAdapter<>(activity, 0, items) {
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
                () -> deleteTracks(Collections.singletonList(track)));
    }

    private void confirmDeleteAll() {
        if (OfflineLibrary.tracks().isEmpty()) return;
        confirm(str("morphe_music_downloads_delete_all_title"),
                str("morphe_music_downloads_delete_all_message"),
                () -> deleteTracks(new ArrayList<>(OfflineLibrary.tracks())));
    }

    private void confirm(String title, String message, Runnable onConfirm) {
        CustomDialog.create(getActivity(), title, message, null,
                str("morphe_music_downloads_delete"), onConfirm, () -> {
                }, null, null, false).first.show();
    }

    /** Files are removed in the background, thousands of them take a while. */
    private void deleteTracks(List<OfflineTrack> tracks) {
        Context context = getActivity().getApplicationContext();
        File root = OfflineLibrary.root(context);
        Utils.submitOnBackgroundThread(() -> {
            List<OfflineTrack> deleted = new ArrayList<>();
            for (OfflineTrack track : tracks) {
                if (!track.audioFile().delete() && track.audioFile().exists()) continue;
                deleted.add(track);
                OfflineStorage.delete(new File(root, track.videoId() + ".json"));
                OfflineStorage.delete(track.artworkFile());
                OfflineStorage.delete(new File(root, track.videoId() + ".webm.part"));
                OfflineStorage.delete(new File(root, track.videoId() + ".m4a.part"));
                artworkCache.remove(track.artworkFile().getAbsolutePath());
            }
            final boolean allDeleted = deleted.size() == tracks.size();
            Utils.runOnMainThread(() -> {
                OfflineLibrary.onTracksDeleted(context, deleted);
                Utils.showToastShort(str(!allDeleted
                        ? "morphe_music_downloads_track_delete_failed"
                        : tracks.size() > 1
                        ? "morphe_music_downloads_all_deleted"
                        : "morphe_music_downloads_track_deleted"));
            });
            return null;
        });
    }

    // ------------------------------------------------------------------------------------------
    // Mini player.
    // ------------------------------------------------------------------------------------------

    private LinearLayout createMiniPlayer() {
        Activity activity = getActivity();
        LinearLayout outer = new LinearLayout(activity);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(ThemeUtils.getDialogBackgroundColor());

        // The surface color of a theme can sit very close to its background, so the bar is
        // separated by a rule rather than by the fill alone.
        View divider = new View(activity);
        divider.setBackgroundColor(rippleColor());
        outer.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        // Lifts the bar off the gesture area, so the seek bar is not on the screen edge.
        outer.setPadding(0, 0, 0, dp(12));

        LinearLayout line = new LinearLayout(activity);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(16), 0, 0, 0);

        miniArtwork = artworkView();
        line.addView(miniArtwork, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.setPadding(dp(16), 0, dp(4), 0);
        miniTitle = text("", 14, foreground());
        miniTitle.setSingleLine(true);
        miniTitle.setEllipsize(TextUtils.TruncateAt.END);
        miniTitle.setTypeface(Typeface.DEFAULT_BOLD);
        miniArtist = text("", 14, secondary());
        miniArtist.setSingleLine(true);
        miniArtist.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(miniTitle);
        labels.addView(miniArtist);
        line.addView(labels, new LinearLayout.LayoutParams(0, dp(56), 1));

        miniPrevious = icon("yt_fill_experimental_skip_previous_vd_theme_24",
                str("morphe_music_downloads_previous"));
        miniPrevious.setOnClickListener(v -> OfflinePlaybackService.previous(getActivity()));
        line.addView(miniPrevious, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        miniPlay = icon("yt_fill_experimental_play_vd_theme_24", str("morphe_music_downloads_play_pause"));
        // The morph drawable of the app is 48dp, so whatever the button shows is scaled down to
        // the 24dp of the icons beside it.
        miniPlay.setScaleType(ImageView.ScaleType.FIT_CENTER);
        miniPlay.setPadding(dp(12), dp(12), dp(12), dp(12));
        miniPlay.setOnClickListener(v -> OfflinePlaybackService.toggle(getActivity()));
        line.addView(miniPlay, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        miniNext = icon("yt_fill_experimental_skip_next_vd_theme_24", str("morphe_music_downloads_next"));
        miniNext.setOnClickListener(v -> OfflinePlaybackService.next(getActivity()));
        line.addView(miniNext, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        outer.addView(line, new LinearLayout.LayoutParams(-1, dp(60)));
        miniSeek = createSeekBar(false);
        outer.addView(miniSeek, new LinearLayout.LayoutParams(-1, dp(SEEK_BAR_TOUCH_DP)));

        // The buttons keep their own taps, anywhere else on the bar opens the full player.
        line.setOnClickListener(v -> openFullPlayer());
        return outer;
    }

    // ------------------------------------------------------------------------------------------
    // Playback updates.
    // ------------------------------------------------------------------------------------------

    @Override
    public void onQueueChanged() {
        if (miniPlayer == null) return;
        OfflineTrack current = OfflinePlaybackService.current();
        miniPlayer.setVisibility(current == null ? View.GONE : View.VISIBLE);

        final String path = current == null ? "" : current.path();
        if (!path.equals(shownPath)) {
            shownPath = path;
            adapter.notifyDataSetChanged();
        }
        if (current != null) {
            miniTitle.setText(current.displayTitle());
            miniArtist.setText(current.displayArtist());
            loadArtwork(miniArtwork, current.artworkFile(), dp(48));
        }
        setButtonEnabled(miniNext, OfflinePlaybackService.hasNext());

        if (current == null) {
            if (fullPlayer != null) fullPlayer.dismiss();
            if (queueDialog != null) queueDialog.dismiss();
        } else if (fullPlayerShowing()) {
            applyFullPlayer(current);
        }
        if (queueAdapter != null) queueAdapter.refresh();
    }

    @Override
    public void onProgress(boolean playing, int position, int duration) {
        if (miniPlayer == null) return;
        // The icon only morphs when the state actually flips while the bar is on screen.
        if (playing != showingPause || !iconSynced) {
            updateMiniPlayIcon(playing, iconSynced && miniPlayer.getVisibility() == View.VISIBLE);
            updateFullPlayIcon(playing);
            iconSynced = true;
        }
        miniSeek.setMax(Math.max(1, duration));
        if (!userSeeking) miniSeek.setProgress(position);
        updateFullProgress(position, duration);
    }

    private void updateMiniPlayIcon(boolean playing, boolean morph) {
        showingPause = playing;
        if (morph && startIconMorph(playing)) return;
        miniPlay.setImageDrawable(ResourceUtils.getDrawable(playing
                ? "yt_fill_experimental_pause_vd_theme_24"
                : "yt_fill_experimental_play_vd_theme_24"));
        miniPlay.setColorFilter(foreground());
    }

    /** The app ships the drawable its own player uses to morph between the two icons. */
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

    // ------------------------------------------------------------------------------------------
    // Full player.
    // ------------------------------------------------------------------------------------------

    private boolean fullPlayerShowing() {
        return fullPlayer != null && fullPlayer.isShowing();
    }

    /**
     * A full screen player like the one of online playback: cover, title, artist, a seek bar
     * with times, shuffle, previous, play, next, repeat and the queue. Back or the arrow closes it.
     */
    private void openFullPlayer() {
        Activity activity = getActivity();
        OfflineTrack current = OfflinePlaybackService.current();
        if (activity == null || activity.isFinishing() || current == null) return;
        if (fullPlayerShowing()) {
            applyFullPlayer(current);
            return;
        }

        Dialog dialog = fullScreenDialog(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background());
        applySystemBarPadding(root, dp(FULL_GUTTER_DP - 12), dp(8), dp(24));

        LinearLayout top = new LinearLayout(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton collapse = icon(collapseIcon(), str("morphe_music_downloads_collapse_player"));
        collapse.setColorFilter(foreground());
        collapse.setOnClickListener(v -> dialog.dismiss());
        top.addView(collapse, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));
        top.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
        ImageButton queueButton = icon("yt_outline_experimental_queue_vd_theme_24",
                str("morphe_music_downloads_queue"));
        queueButton.setOnClickListener(v -> openQueue());
        top.addView(queueButton, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // The cover takes the free space and stays square, so the controls below it are always
        // on screen whatever the height of the phone.
        FrameLayout artworkArea = new FrameLayout(activity);
        artworkArea.setPadding(dp(12), dp(8), dp(12), dp(8));
        fullArtwork = new SquareImageView(activity);
        fullArtwork.setBackgroundColor(Utils.adjustColorBrightness(foreground(), 0.2f));
        roundCorners(fullArtwork);
        artworkArea.addView(fullArtwork, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addView(artworkArea, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12), dp(16), dp(12), dp(8));
        fullTitle = text("", 22, foreground());
        fullTitle.setTypeface(Typeface.DEFAULT_BOLD);
        fullTitle.setSingleLine(true);
        fullTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        fullTitle.setMarqueeRepeatLimit(-1);
        fullTitle.setSelected(true);
        fullArtist = text("", 16, secondary());
        fullArtist.setSingleLine(true);
        fullArtist.setEllipsize(TextUtils.TruncateAt.END);
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
        controls.setPadding(0, dp(12), 0, dp(8));

        fullShuffle = icon("yt_outline_experimental_shuffle_vd_theme_24", str("morphe_music_downloads_shuffle"));
        fullShuffle.setOnClickListener(v -> {
            OfflinePlaybackService.toggleShuffle();
            updateModeButtons();
        });

        fullPrevious = largeIcon("yt_fill_experimental_skip_previous_vd_theme_24",
                str("morphe_music_downloads_previous"));
        fullPrevious.setOnClickListener(v -> OfflinePlaybackService.previous(activity));

        // A round button filled with the text color, like the play button of online playback.
        fullPlay = new ImageButton(activity);
        fullPlay.setContentDescription(str("morphe_music_downloads_play_pause"));
        fullPlay.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fullPlay.setPadding(dp(20), dp(20), dp(20), dp(20));
        ShapeDrawable circle = new ShapeDrawable(new OvalShape());
        circle.getPaint().setColor(foreground());
        fullPlay.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()), circle, null));
        fullPlay.setOnClickListener(v -> OfflinePlaybackService.toggle(activity));

        fullNext = largeIcon("yt_fill_experimental_skip_next_vd_theme_24", str("morphe_music_downloads_next"));
        fullNext.setOnClickListener(v -> OfflinePlaybackService.next(activity));

        fullRepeat = icon("yt_outline_experimental_repeat_vd_theme_24", str("morphe_music_downloads_repeat_off"));
        fullRepeat.setOnClickListener(v -> {
            OfflineQueue.Repeat repeat = OfflinePlaybackService.cycleRepeat();
            updateModeButtons();
            Utils.showToastShort(str(repeatLabel(repeat)));
        });

        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP));
        LinearLayout.LayoutParams skipParams = new LinearLayout.LayoutParams(dp(FULL_SKIP_BUTTON_DP), dp(FULL_SKIP_BUTTON_DP));
        skipParams.setMargins(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(FULL_PLAY_BUTTON_DP), dp(FULL_PLAY_BUTTON_DP));
        playParams.setMargins(dp(12), 0, dp(12), 0);
        controls.addView(fullShuffle, modeParams);
        controls.addView(fullPrevious, skipParams);
        controls.addView(fullPlay, playParams);
        controls.addView(fullNext, new LinearLayout.LayoutParams(skipParams));
        controls.addView(fullRepeat, new LinearLayout.LayoutParams(modeParams));
        root.addView(controls, new LinearLayout.LayoutParams(-1, -2));

        // "Up next", the way the bottom of the online player opens the queue.
        TextView upNext = text(str("morphe_music_downloads_queue"), 15, foreground());
        upNext.setTypeface(Typeface.DEFAULT_BOLD);
        upNext.setGravity(Gravity.CENTER);
        upNext.setPadding(0, dp(14), 0, dp(14));
        upNext.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()), null,
                CustomDialog.createRoundedBackground(ROW_CORNER_DP, Color.WHITE)));
        upNext.setOnClickListener(v -> openQueue());
        root.addView(upNext, new LinearLayout.LayoutParams(-1, -2));

        dialog.setContentView(root);
        sizeDialog(dialog);
        dialog.setOnDismissListener(d -> {
            if (fullPlayer == dialog) fullPlayer = null;
            fullArtworkPath = "";
        });

        fullPlayer = dialog;
        fullArtworkPath = "";
        applyFullPlayer(current);
        updateFullPlayIcon(OfflinePlaybackService.isPlaying());
        updateFullProgress(miniSeek.getProgress(), miniSeek.getMax());
        dialog.show();
    }

    private void applyFullPlayer(OfflineTrack track) {
        if (fullPlayer == null) return;
        fullTitle.setText(track.displayTitle());
        fullArtist.setText(track.displayArtist());
        setButtonEnabled(fullNext, OfflinePlaybackService.hasNext());
        updateModeButtons();

        // The big cover is decoded in the background, the small one stands in meanwhile.
        final String key = track.artworkFile().getAbsolutePath();
        if (key.equals(fullArtworkPath)) return;
        fullArtworkPath = key;
        Bitmap small = artworkCache.get(key);
        if (small != null) showArtwork(fullArtwork, small);
        else showFullPlaceholder();
        ImageView target = fullArtwork;
        artworkLoader.execute(() -> {
            Bitmap bitmap = track.artwork(FULL_ARTWORK_PIXELS);
            handler.post(() -> {
                if (!key.equals(fullArtworkPath) || target != fullArtwork) return;
                if (bitmap != null) showArtwork(target, bitmap);
                else if (small == null) showFullPlaceholder();
            });
        });
    }

    private void showFullPlaceholder() {
        fullArtwork.setImageDrawable(ResourceUtils.getDrawable("yt_fill_experimental_play_circle_vd_theme_24"));
        fullArtwork.setColorFilter(foreground());
        fullArtwork.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fullArtwork.setPadding(dp(64), dp(64), dp(64), dp(64));
    }

    private void updateModeButtons() {
        if (fullShuffle == null || fullRepeat == null) return;
        fullShuffle.setAlpha(OfflinePlaybackService.isShuffled() ? 1f : INACTIVE_ALPHA);
        OfflineQueue.Repeat repeat = OfflinePlaybackService.repeatMode();
        Drawable icon = ResourceUtils.getDrawable(repeat == OfflineQueue.Repeat.ONE
                ? "yt_outline_experimental_repeat1_vd_theme_24"
                : "yt_outline_experimental_repeat_vd_theme_24");
        if (icon != null) fullRepeat.setImageDrawable(icon);
        fullRepeat.setColorFilter(foreground());
        fullRepeat.setAlpha(repeat == OfflineQueue.Repeat.OFF ? INACTIVE_ALPHA : 1f);
        fullRepeat.setContentDescription(str(repeatLabel(repeat)));
    }

    private static String repeatLabel(OfflineQueue.Repeat repeat) {
        return switch (repeat) {
            case OFF -> "morphe_music_downloads_repeat_off";
            case ALL -> "morphe_music_downloads_repeat_all";
            case ONE -> "morphe_music_downloads_repeat_one";
        };
    }

    private void updateFullPlayIcon(boolean playing) {
        if (fullPlay == null) return;
        fullPlay.setImageDrawable(ResourceUtils.getDrawable(playing
                ? "yt_fill_experimental_pause_vd_theme_24"
                : "yt_fill_experimental_play_vd_theme_24"));
        // The button is filled with the text color, so the icon takes the background color.
        fullPlay.setColorFilter(background());
    }

    private void updateFullProgress(int position, int duration) {
        if (fullSeek == null || fullPlayer == null) return;
        fullSeek.setMax(Math.max(1, duration));
        if (!userSeeking) {
            fullSeek.setProgress(position);
            fullElapsed.setText(formatTime(position));
        }
        fullDuration.setText(duration > 1 ? formatTime(duration) : "");
    }

    @Nullable
    private Drawable collapseIcon() {
        Drawable icon = ResourceUtils.getDrawable("yt_outline_experimental_chevron_down_vd_theme_24");
        if (icon == null) icon = ResourceUtils.getDrawable("yt_outline_chevron_down_vd_theme_24");
        if (icon == null) icon = getActivity().getDrawable(android.R.drawable.arrow_down_float);
        return icon;
    }

    /** Measures as the largest square that fits, so the cover never pushes the controls away. */
    private static final class SquareImageView extends ImageView {
        SquareImageView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            final int height = MeasureSpec.getSize(heightMeasureSpec);
            final int side = height > 0 ? Math.min(width, height) : width;
            setMeasuredDimension(side, side);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Queue.
    // ------------------------------------------------------------------------------------------

    /** The play queue, the "Up next" list of online playback. */
    private void openQueue() {
        Activity activity = getActivity();
        if (activity == null || OfflinePlaybackService.current() == null) return;
        if (queueDialog != null && queueDialog.isShowing()) return;

        Dialog dialog = fullScreenDialog(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background());
        applySystemBarPadding(root, dp(4), dp(8), dp(8));

        LinearLayout top = new LinearLayout(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton collapse = icon(collapseIcon(), str("morphe_music_downloads_collapse_player"));
        collapse.setColorFilter(foreground());
        collapse.setOnClickListener(v -> dialog.dismiss());
        top.addView(collapse, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));
        TextView title = text(str("morphe_music_downloads_queue"), 20, foreground());
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(8), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton shuffle = icon("yt_outline_experimental_shuffle_vd_theme_24", str("morphe_music_downloads_shuffle"));
        shuffle.setAlpha(OfflinePlaybackService.isShuffled() ? 1f : INACTIVE_ALPHA);
        shuffle.setOnClickListener(v -> {
            shuffle.setAlpha(OfflinePlaybackService.toggleShuffle() ? 1f : INACTIVE_ALPHA);
            updateModeButtons();
            queueList.setSelection(Math.max(0, OfflinePlaybackService.queueIndex()));
        });
        top.addView(shuffle, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        queueList = new ListView(activity);
        queueList.setDivider(null);
        queueList.setSelector(new ColorDrawable(Color.TRANSPARENT));
        queueList.setCacheColorHint(Color.TRANSPARENT);
        queueList.setFastScrollEnabled(true);
        queueList.setPadding(dp(8), dp(4), dp(8), dp(8));
        queueList.setClipToPadding(false);
        QueueAdapter queueItems = new QueueAdapter();
        queueAdapter = queueItems;
        queueList.setAdapter(queueItems);
        queueList.setOnItemClickListener((parent, view, position, id) ->
                OfflinePlaybackService.jumpTo(activity, position));
        queueList.setOnItemLongClickListener((parent, view, position, id) -> {
            showQueueMenu(view, position);
            return true;
        });
        root.addView(queueList, new LinearLayout.LayoutParams(-1, 0, 1));

        dialog.setContentView(root);
        sizeDialog(dialog);
        dialog.setOnDismissListener(d -> {
            if (queueDialog == dialog) {
                queueDialog = null;
                queueAdapter = null;
            }
        });
        queueDialog = dialog;
        dialog.show();
        queueList.setSelection(Math.max(0, OfflinePlaybackService.queueIndex()));
    }

    private void showQueueMenu(View anchor, int position) {
        String[] items = {
                str("morphe_music_downloads_play_next"),
                str("morphe_music_downloads_remove_from_queue"),
        };
        showMenu(anchor, items, -1, choice -> {
            if (choice == 0) OfflinePlaybackService.moveNext(position);
            else OfflinePlaybackService.removeFromQueue(getActivity(), position);
        });
    }

    private final class QueueAdapter extends BaseAdapter {
        private List<OfflineTrack> items = OfflinePlaybackService.queueItems();
        private int current = OfflinePlaybackService.queueIndex();

        void refresh() {
            items = OfflinePlaybackService.queueItems();
            current = OfflinePlaybackService.queueIndex();
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            RowHolder holder = convertView == null ? createRow() : (RowHolder) convertView.getTag();
            OfflineTrack track = items.get(position);
            bindRow(holder, track, position == current,
                    track.displayArtist() + (track.durationSeconds() > 0
                            ? " • " + formatDuration(track.durationSeconds()) : ""));
            // Songs already played are dimmed, like the history part of the online queue.
            holder.row.setAlpha(position < current ? 0.55f : 1f);
            holder.menu.setOnClickListener(v -> showQueueMenu(v, position));
            return holder.row;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Shared views.
    // ------------------------------------------------------------------------------------------

    private Dialog fullScreenDialog(Activity activity) {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        return dialog;
    }

    private void sizeDialog(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        window.setBackgroundDrawable(new ColorDrawable(background()));
        window.setStatusBarColor(background());
        window.setNavigationBarColor(background());
    }

    /**
     * The app draws behind the status and navigation bars, so the dialogs keep their content
     * out of them. Without this the top buttons sat under the clock and the controls under the
     * gesture bar.
     */
    private void applySystemBarPadding(View root, int side, int top, int bottom) {
        root.setPadding(side, top, side, bottom);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(side + insets.getSystemWindowInsetLeft(),
                    top + insets.getSystemWindowInsetTop(),
                    side + insets.getSystemWindowInsetRight(),
                    bottom + insets.getSystemWindowInsetBottom());
            return insets;
        });
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

    private void setButtonEnabled(ImageButton button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : .35f);
    }

    private ImageView artworkView() {
        ImageView view = new ImageView(getActivity());
        view.setBackgroundColor(Utils.adjustColorBrightness(foreground(), 0.2f));
        roundCorners(view);
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

    /** The skip buttons of the full player are drawn larger than the icons beside them. */
    private ImageButton largeIcon(String drawable, String description) {
        ImageButton button = icon(drawable, description);
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        return button;
    }

    /** For drawables that already carry the colors of Morphe. */
    private ImageButton icon(@Nullable Drawable drawable, String description) {
        ImageButton button = new ImageButton(getActivity());
        button.setImageDrawable(drawable);
        button.setContentDescription(description);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()),
                null, new ShapeDrawable(new OvalShape())));
        return button;
    }

    private static int rippleColor() {
        final int foreground = foreground();
        return Color.argb(60, Color.red(foreground), Color.green(foreground), Color.blue(foreground));
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
