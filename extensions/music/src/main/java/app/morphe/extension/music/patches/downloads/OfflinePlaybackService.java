/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.audiofx.AudioEffect;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseActivityHook;

/**
 * Offline player with a real play queue, audio focus, a media session and lock screen controls.
 * <p>
 * The queue and the playback state are static and only touched on the main thread, so the
 * downloads screen reads and edits them directly. Nothing large ever goes through an intent,
 * a queue of thousands of tracks used to exceed what an intent can carry. The service itself
 * only owns the media player, and is started whenever something has to be played.
 * <p>
 * Like online playback, the service stays in the foreground while paused, so the lock screen,
 * headsets and Bluetooth can resume it. It stops itself after a while paused.
 */
public final class OfflinePlaybackService extends Service {

    public interface PlaybackListener {
        /** The current track, the queue, shuffle or repeat changed. */
        void onQueueChanged();

        /** Playing state or position changed, a few times a second while playing. */
        void onProgress(boolean playing, int positionMilliseconds, int durationMilliseconds);
    }

    /** Opens the downloads screen with its full player, from the notification. */
    public static final String EXTRA_OPEN_PLAYER = "morphe_open_offline_player";

    private static final String ACTION_PLAY_CURRENT = "app.morphe.action.PLAY_OFFLINE_CURRENT";
    private static final String ACTION_TOGGLE = "app.morphe.action.TOGGLE_OFFLINE_PLAYBACK";
    private static final String ACTION_STOP = "app.morphe.action.STOP_OFFLINE_PLAYBACK";
    /** The paused notification was swiped away: the player stops, the queue stays. */
    private static final String ACTION_DISMISS = "app.morphe.action.DISMISS_OFFLINE_PLAYBACK";
    private static final String ACTION_NEXT = "app.morphe.action.NEXT_OFFLINE_TRACK";
    private static final String ACTION_PREVIOUS = "app.morphe.action.PREVIOUS_OFFLINE_TRACK";

    private static final String CHANNEL_ID = "morphe_offline_playback";
    private static final int NOTIFICATION_ID = 8841;
    private static final int PROGRESS_INTERVAL_MILLISECONDS = 500;
    private static final int SAVE_INTERVAL_MILLISECONDS = 10_000;
    /** Updates of one track change arrive in a burst, which is posted as one notification. */
    private static final int NOTIFICATION_COALESCE_MILLISECONDS = 150;
    /** How long a paused player keeps its notification and lock screen controls. */
    private static final long PAUSED_TIMEOUT_MILLISECONDS = 10 * 60_000L;
    private static final int NOTIFICATION_ARTWORK_PIXELS = 512;
    private static final float DUCK_VOLUME = 0.2f;

    /** Unreadable files in a row before playback gives up instead of skipping on. */
    private static final int MAX_CONSECUTIVE_ERRORS = 3;

    /** Small values written often: where playback is. */
    private static final String PREFERENCES = "morphe_offline_player";
    /** The queue itself, thousands of entries, only written when it changes. */
    private static final String QUEUE_PREFERENCES = "morphe_offline_queue";
    private static final String KEY_QUEUE = "queue";
    private static final String KEY_ORDER = "order";
    private static final String KEY_INDEX = "index";
    private static final String KEY_POSITION = "position";
    private static final String KEY_REPEAT = "repeat";
    private static final String KEY_SHUFFLED = "shuffled";

    // Shared state, main thread only.
    private static final OfflineQueue<OfflineTrack> queue = new OfflineQueue<>();
    private static final Set<PlaybackListener> listeners = new CopyOnWriteArraySet<>();
    @Nullable
    private static OfflinePlaybackService instance;
    private static boolean playing;
    private static int position;
    private static int duration;
    /** Where the current track starts once prepared, for a queue restored after a restart. */
    private static int pendingSeek;
    private static boolean restoreAttempted;
    @Nullable
    private static String lastSavedQueue;
    @Nullable
    private static String lastSavedOrder;

    private static final ExecutorService artworkLoader = Executors.newSingleThreadExecutor();

    // ------------------------------------------------------------------------------------------
    // Public API, main thread only.
    // ------------------------------------------------------------------------------------------

    public static void addListener(PlaybackListener listener) {
        listeners.add(listener);
        listener.onQueueChanged();
        listener.onProgress(playing, position, duration);
    }

    public static void removeListener(PlaybackListener listener) {
        listeners.remove(listener);
    }

    @Nullable
    public static OfflineTrack current() {
        return queue.current();
    }

    public static List<OfflineTrack> queueItems() {
        return queue.items();
    }

    public static int queueIndex() {
        return queue.index();
    }

    public static boolean isShuffled() {
        return queue.isShuffled();
    }

    public static OfflineQueue.Repeat repeatMode() {
        return queue.repeat();
    }

    public static boolean isPlaying() {
        return playing;
    }

    public static boolean hasNext() {
        return queue.hasNext();
    }

    /** Plays a list from one of its tracks, the way tapping a song of a playlist does. */
    public static void playList(Context context, List<OfflineTrack> list, int start, boolean shuffle) {
        if (list.isEmpty()) return;
        queue.set(list, start, shuffle);
        pendingSeek = 0;
        startCurrent(context);
        onQueueEdited();
    }

    public static void playNext(Context context, OfflineTrack track) {
        final boolean wasEmpty = queue.isEmpty();
        queue.playNext(track);
        if (wasEmpty) startCurrent(context);
        onQueueEdited();
    }

    public static void addToQueue(Context context, OfflineTrack track) {
        final boolean wasEmpty = queue.isEmpty();
        queue.add(track);
        if (wasEmpty) startCurrent(context);
        onQueueEdited();
    }

    public static void jumpTo(Context context, int queuePosition) {
        if (queuePosition < 0 || queuePosition >= queue.size()) return;
        OfflinePlaybackService service = instance;
        // The song that plays is not restarted, like tapping it in the online queue.
        if (queuePosition == queue.index() && service != null
                && (service.playerReady() || service.preparing())) {
            if (!playing) toggle(context);
            return;
        }
        queue.jumpTo(queuePosition);
        pendingSeek = 0;
        startCurrent(context);
        onQueueEdited();
    }

    public static void removeFromQueue(Context context, int queuePosition) {
        if (queue.remove(queuePosition)) onCurrentRemoved(context);
        onQueueEdited();
    }

    public static void moveNext(int queuePosition) {
        queue.moveNext(queuePosition);
        onQueueEdited();
    }

    public static void move(int from, int to) {
        queue.move(from, to);
        onQueueEdited();
    }

    public static boolean toggleShuffle() {
        final boolean shuffled = queue.toggleShuffle();
        onQueueEdited();
        return shuffled;
    }

    public static OfflineQueue.Repeat cycleRepeat() {
        OfflineQueue.Repeat repeat = queue.cycleRepeat();
        onQueueEdited();
        return repeat;
    }

    public static void toggle(Context context) {
        OfflinePlaybackService service = instance;
        if (service != null && service.preparing()) {
            // Still loading: the tap decides whether it starts once loaded.
            if (service.playWhenPrepared) service.pause();
            else service.resume();
        } else if (service != null && service.playerReady()) {
            if (service.player.isPlaying()) service.pause();
            else service.resume();
        } else if (queue.current() != null) {
            startCurrent(context);
        }
    }

    public static void next(Context context) {
        if (queue.next() < 0) return;
        pendingSeek = 0;
        startCurrent(context);
        onQueueEdited();
    }

    public static void previous(Context context) {
        final int before = queue.index();
        final int target = queue.previous(position);
        if (target < 0) return;
        pendingSeek = 0;
        OfflinePlaybackService service = instance;
        if (target == before && service != null && service.playerReady()) {
            seekTo(0);
            if (!service.player.isPlaying()) service.resume();
            return;
        }
        startCurrent(context);
        onQueueEdited();
    }

    public static void seekTo(int milliseconds) {
        OfflinePlaybackService service = instance;
        if (service != null && service.playerReady()) {
            service.player.seekTo(Math.max(0, Math.min(milliseconds, service.player.getDuration())));
            service.publishState(true);
        } else {
            // Not loaded yet, the track starts there once it is.
            pendingSeek = Math.max(0, milliseconds);
            position = pendingSeek;
            notifyProgress();
        }
    }

    /** Stops playback and empties the queue, like closing the player of online playback. */
    public static void stop(Context context) {
        OfflinePlaybackService service = instance;
        if (service != null) service.stopPlayback();
        queue.clear();
        playing = false;
        position = 0;
        duration = 0;
        pendingSeek = 0;
        clearSavedState(context);
        // The service may already be gone while its last notification is still shown.
        cancelNotification(context);
        notifyQueueChanged();
        notifyProgress();
    }

    /**
     * Brings back the queue of the last session, paused where it was left, once the catalogue
     * it refers to is in memory.
     */
    public static void restoreIfIdle(Context context) {
        if (restoreAttempted || !queue.isEmpty() || OfflineLibrary.isLoading()) return;
        restoreAttempted = true;
        try {
            SharedPreferences state = preferences(context);
            SharedPreferences saved = queuePreferences(context);
            String savedQueue = saved.getString(KEY_QUEUE, "");
            if (savedQueue == null || savedQueue.isEmpty()) return;

            final File root = OfflineLibrary.root(context);
            final int savedIndex = Math.max(0, state.getInt(KEY_INDEX, 0));
            String[] names = savedQueue.split("\n");
            String savedCurrent = names[Math.min(savedIndex, names.length - 1)];

            List<OfflineTrack> tracks = new ArrayList<>(names.length);
            int restoredIndex = 0;
            for (int i = 0; i < names.length; i++) {
                OfflineTrack track = resolve(root, names[i]);
                if (track == null) continue;
                if (i <= savedIndex) restoredIndex = tracks.size();
                tracks.add(track);
            }
            if (tracks.isEmpty()) return;

            List<OfflineTrack> original = null;
            String savedOrder = saved.getString(KEY_ORDER, "");
            if (state.getBoolean(KEY_SHUFFLED, false) && savedOrder != null && !savedOrder.isEmpty()) {
                original = new ArrayList<>();
                for (String name : savedOrder.split("\n")) {
                    OfflineTrack track = resolve(root, name);
                    if (track != null) original.add(track);
                }
            }
            queue.restore(tracks, restoredIndex, original);
            try {
                queue.setRepeat(OfflineQueue.Repeat.valueOf(
                        state.getString(KEY_REPEAT, OfflineQueue.Repeat.OFF.name())));
            } catch (Exception ignored) {
                queue.setRepeat(OfflineQueue.Repeat.OFF);
            }
            // Nothing to write back, this is what is saved.
            lastSavedQueue = savedQueue;
            lastSavedOrder = savedOrder == null ? "" : savedOrder;

            OfflineTrack current = queue.current();
            pendingSeek = current != null && savedCurrent.equals(current.audioFile().getName())
                    ? state.getInt(KEY_POSITION, 0) : 0;
            position = pendingSeek;
            duration = current == null ? 0 : current.durationSeconds() * 1000;
            playing = false;
            notifyQueueChanged();
            notifyProgress();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not restore the offline queue", ex);
        }
    }

    @Nullable
    private static OfflineTrack resolve(File root, String name) {
        if (name.isEmpty()) return null;
        return OfflineLibrary.find(new File(root, name).getAbsolutePath());
    }

    /** Deleted downloads leave the queue, and the player moves on if one was playing. */
    static void onTracksDeleted(Context context, List<String> paths) {
        Set<String> deleted = new HashSet<>(paths);
        boolean currentChanged = false;
        List<OfflineTrack> items = queue.items();
        for (int i = items.size() - 1; i >= 0; i--) {
            if (deleted.contains(items.get(i).path())) currentChanged |= queue.remove(i);
        }
        if (currentChanged) onCurrentRemoved(context);
        onQueueEdited();
    }

    private static void onCurrentRemoved(Context context) {
        if (queue.isEmpty()) {
            stop(context);
            return;
        }
        pendingSeek = 0;
        if (playing) {
            startCurrent(context);
        } else if (instance != null) {
            instance.loadCurrent(false);
        } else {
            OfflineTrack current = queue.current();
            position = 0;
            duration = current == null ? 0 : current.durationSeconds() * 1000;
            notifyProgress();
        }
    }

    private static void startCurrent(Context context) {
        OfflinePlaybackService service = instance;
        if (service != null) {
            service.loadCurrent(true);
            return;
        }
        try {
            context.startForegroundService(new Intent(context, OfflinePlaybackService.class)
                    .setAction(ACTION_PLAY_CURRENT));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not start offline playback", ex);
            Utils.showToastShort(str("morphe_music_downloads_play_failed"));
        }
    }

    private static void onQueueEdited() {
        notifyQueueChanged();
        Context context = Utils.getContext();
        if (context != null) saveState(context);
    }

    private static void notifyQueueChanged() {
        for (PlaybackListener listener : listeners) listener.onQueueChanged();
    }

    private static void notifyProgress() {
        for (PlaybackListener listener : listeners) listener.onProgress(playing, position, duration);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static SharedPreferences queuePreferences(Context context) {
        return context.getSharedPreferences(QUEUE_PREFERENCES, Context.MODE_PRIVATE);
    }

    private static String joinNames(List<OfflineTrack> tracks) {
        StringBuilder names = new StringBuilder(tracks.size() * 20);
        for (OfflineTrack track : tracks) {
            if (names.length() > 0) names.append('\n');
            // Names rather than full paths keep the saved queue a fraction of the size.
            names.append(track.audioFile().getName());
        }
        return names.toString();
    }

    /**
     * Saves where playback is. The queue goes to its own file, rewritten only when it actually
     * changed, so the frequent saves of the position stay a few hundred bytes.
     */
    private static void saveState(Context context) {
        // An empty queue is either a fresh process or a stop, which clears the state itself.
        if (queue.isEmpty()) return;
        try {
            String joined = joinNames(queue.items());
            String order = queue.isShuffled() ? joinNames(queue.unshuffledItems()) : "";
            if (!joined.equals(lastSavedQueue) || !order.equals(lastSavedOrder)) {
                lastSavedQueue = joined;
                lastSavedOrder = order;
                queuePreferences(context).edit()
                        .putString(KEY_QUEUE, joined)
                        .putString(KEY_ORDER, order)
                        .apply();
            }
            preferences(context).edit()
                    .putInt(KEY_INDEX, queue.index())
                    .putInt(KEY_POSITION, position)
                    .putString(KEY_REPEAT, queue.repeat().name())
                    .putBoolean(KEY_SHUFFLED, queue.isShuffled())
                    .apply();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not save the offline queue", ex);
        }
    }

    private static void savePosition(Context context) {
        preferences(context).edit().putInt(KEY_POSITION, position).apply();
    }

    private static void clearSavedState(Context context) {
        preferences(context).edit().clear().apply();
        queuePreferences(context).edit().clear().apply();
        lastSavedQueue = null;
        lastSavedOrder = null;
    }

    private static void cancelNotification(Context context) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }

    // ------------------------------------------------------------------------------------------
    // Service.
    // ------------------------------------------------------------------------------------------

    @Nullable
    private MediaPlayer player;
    private boolean prepared;
    private boolean playWhenPrepared;
    private MediaSession session;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean resumeOnFocusGain;
    private int audioEffectSessionId;
    private int consecutiveErrors;
    private boolean foreground;
    private long lastSave;
    @Nullable
    private OfflineLibrary.Listener pendingRestore;

    /** The path the media player and the metadata belong to. */
    private String loadedPath = "";
    @Nullable
    private Bitmap artwork;
    private boolean lastPublishedPlaying;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            publishState(false);
            if (playerReady() && player.isPlaying()) {
                handler.postDelayed(this, PROGRESS_INTERVAL_MILLISECONDS);
            }
        }
    };

    private final Runnable postNotification = () -> {
        if (instance != this || queue.current() == null) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification());
    };

    /** Paused long enough: the player goes away, the queue stays for the next play. */
    private final Runnable pausedTimeout = () -> {
        if (playerReady() && player.isPlaying()) return;
        if (queue.current() != null) pendingSeek = position;
        saveState(this);
        stopPlayback();
        notifyProgress();
    };

    /** Unplugged headphones or a disconnected speaker pause instead of playing out loud. */
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) pause();
        }
    };

    @SuppressLint({"UnspecifiedRegisterReceiverFlag", "WrongConstant"})
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createFocusRequest();
        createChannel();

        session = new MediaSession(this, "MorpheOfflinePlayback");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(OfflinePlaybackService.this); }
            @Override public void onSkipToPrevious() { previous(OfflinePlaybackService.this); }
            @Override public void onStop() { stop(OfflinePlaybackService.this); }
            @Override public void onSeekTo(long pos) {
                seekTo((int) Math.min(Integer.MAX_VALUE, pos));
            }
        });
        session.setSessionActivity(openPlayerIntent());
        session.setActive(true);

        // A system broadcast, only delivered to receivers registered at runtime.
        registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PLAY_CURRENT.equals(action)) {
            // Started as a foreground service, which has to show itself right away.
            goForeground();
            loadCurrent(true);
        } else if (ACTION_TOGGLE.equals(action)) {
            withQueue(() -> toggle(this));
        } else if (ACTION_NEXT.equals(action)) {
            withQueue(() -> next(this));
        } else if (ACTION_PREVIOUS.equals(action)) {
            withQueue(() -> previous(this));
        } else if (ACTION_STOP.equals(action)) {
            stop(this);
        } else if (ACTION_DISMISS.equals(action)) {
            if (queue.current() != null) {
                pendingSeek = position;
                saveState(this);
            }
            stopPlayback();
            notifyProgress();
        } else if (queue.current() == null) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    /**
     * A notification button can outlive the process, which then starts again with an empty
     * queue. The saved queue is brought back first, or the stale notification goes away.
     */
    private void withQueue(Runnable action) {
        if (!queue.isEmpty()) {
            action.run();
            return;
        }
        if (!OfflineLibrary.isLoading()) {
            restoreIfIdle(this);
            runOrGiveUp(action);
            return;
        }
        if (pendingRestore != null) OfflineLibrary.removeListener(pendingRestore);
        OfflineLibrary.Listener listener = new OfflineLibrary.Listener() {
            @Override
            public void onLibraryChanged(boolean loading, int scanned, int total) {
                if (loading) return;
                OfflineLibrary.removeListener(this);
                if (pendingRestore == this) pendingRestore = null;
                if (instance != OfflinePlaybackService.this) return;
                restoreIfIdle(OfflinePlaybackService.this);
                runOrGiveUp(action);
            }
        };
        pendingRestore = listener;
        OfflineLibrary.addListener(listener);
        OfflineLibrary.ensureLoaded(this);
    }

    private void runOrGiveUp(Runnable action) {
        if (queue.isEmpty()) {
            cancelNotification(this);
            stopSelf();
            return;
        }
        action.run();
    }

    /**
     * Prepares the current track of the queue.
     *
     * @param play Whether it starts once prepared, or waits paused.
     */
    private void loadCurrent(boolean play) {
        OfflineTrack track = queue.current();
        if (track == null) {
            stopPlayback();
            return;
        }
        releasePlayer();
        if (!loadedPath.equals(track.path())) {
            loadedPath = track.path();
            artwork = null;
            loadArtwork(track);
        }
        position = pendingSeek;
        duration = track.durationSeconds() * 1000;
        playWhenPrepared = play;
        // Shown as playing while it loads, so the buttons already offer pause.
        playing = play;
        if (play) goForeground();
        publishMetadata(track);

        if (!track.audioFile().isFile()) {
            onTrackFailed(track, "missing file");
            return;
        }

        try {
            MediaPlayer mediaPlayer = new MediaPlayer();
            player = mediaPlayer;
            mediaPlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mediaPlayer.setDataSource(track.path());
            // Skipping quickly leaves the previous player mid prepare. Its callbacks are
            // cleared on release, and each one still checks that it belongs to the current
            // track, so a discarded player can never tear down the one that replaced it.
            mediaPlayer.setOnPreparedListener(mp -> {
                if (mp != player) return;
                prepared = true;
                consecutiveErrors = 0;
                duration = mp.getDuration();
                openAudioEffectSession(mp);
                if (pendingSeek > 0 && pendingSeek < duration) mp.seekTo(pendingSeek);
                pendingSeek = 0;
                if (playWhenPrepared) {
                    resume();
                } else {
                    publishState(true);
                    if (foreground) scheduleIdleStop();
                }
                publishMetadata(track);
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                if (mp != player) return;
                onTrackCompleted();
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                if (mp != player) return true;
                onTrackFailed(track, what + "/" + extra);
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception ex) {
            onTrackFailed(track, ex.toString());
        }
        notifyProgress();
    }

    private void onTrackCompleted() {
        final int nextIndex = queue.afterCompletion();
        if (nextIndex < 0) {
            // End of the queue: the last track stays, paused at its start.
            if (playerReady()) player.seekTo(0);
            position = 0;
            playing = false;
            publishState(true);
            scheduleIdleStop();
            notifyQueueChanged();
            saveState(this);
            return;
        }
        pendingSeek = 0;
        loadCurrent(true);
        onQueueEdited();
    }

    private void onTrackFailed(OfflineTrack track, String reason) {
        // A paused load stays paused when it skips to the next track.
        final boolean play = playWhenPrepared;
        Logger.printException(() -> "Offline player could not play " + track.audioFile() + ": " + reason);
        releasePlayer();
        consecutiveErrors++;
        if (consecutiveErrors < MAX_CONSECUTIVE_ERRORS && queue.next() >= 0) {
            Utils.showToastShort(str("morphe_music_downloads_play_failed"));
            pendingSeek = 0;
            loadCurrent(play);
            onQueueEdited();
            return;
        }
        consecutiveErrors = 0;
        playing = false;
        Utils.showToastShort(str("morphe_music_downloads_play_failed"));
        publishState(true);
        if (foreground) scheduleIdleStop();
    }

    private void resume() {
        if (preparing()) {
            playWhenPrepared = true;
            playing = true;
            notifyProgress();
            goForeground();
            return;
        }
        if (!playerReady()) {
            if (queue.current() != null) loadCurrent(true);
            return;
        }
        if (!requestAudioFocus()) {
            // A call holds the audio. Shown as paused rather than as a player that is silent.
            playing = false;
            publishState(true);
            if (foreground) scheduleIdleStop();
            return;
        }
        player.setVolume(1f, 1f);
        player.start();
        goForeground();
        publishState(true);
        handler.removeCallbacks(progressTicker);
        handler.post(progressTicker);
    }

    private void pause() {
        pause(false);
    }

    /**
     * @param resumesSoon A call or a voice prompt interrupts playback, which comes back once it
     *                    is over. Any other pause cancels that.
     */
    private void pause(boolean resumesSoon) {
        if (!resumesSoon) resumeOnFocusGain = false;
        if (preparing()) {
            playWhenPrepared = false;
            playing = false;
            notifyProgress();
            if (resumesSoon) updateNotification();
            else scheduleIdleStop();
            return;
        }
        if (!playerReady() || !player.isPlaying()) {
            // Already paused by a call: an explicit pause still starts the idle countdown.
            if (!resumesSoon && foreground) scheduleIdleStop();
            return;
        }
        player.pause();
        publishState(true);
        if (!resumesSoon) scheduleIdleStop();
        saveState(this);
    }

    private void stopPlayback() {
        handler.removeCallbacks(progressTicker);
        handler.removeCallbacks(postNotification);
        handler.removeCallbacks(pausedTimeout);
        releasePlayer();
        abandonAudioFocus();
        playing = false;
        loadedPath = "";
        artwork = null;
        if (session != null) {
            session.setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_STOPPED, 0, 0).build());
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        // Removing it explicitly also covers a notification the service no longer owns.
        cancelNotification(this);
        stopSelf();
    }

    /**
     * Asking a player that is still preparing about its state is an invalid operation, which it
     * answers with an error that stops playback, so nothing may reach it before it is ready.
     */
    private boolean playerReady() {
        return prepared && player != null;
    }

    private boolean preparing() {
        return player != null && !prepared;
    }

    private void releasePlayer() {
        prepared = false;
        handler.removeCallbacks(progressTicker);
        closeAudioEffectSession();

        MediaPlayer released = player;
        if (released == null) return;

        // Cleared first, otherwise releasing a player that is still preparing reports an error
        // that would stop whatever is playing by then.
        player = null;
        released.setOnPreparedListener(null);
        released.setOnCompletionListener(null);
        released.setOnErrorListener(null);
        try {
            released.reset();
            released.release();
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not release the offline player", ex);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Audio focus.
    // ------------------------------------------------------------------------------------------

    private void createFocusRequest() {
        if (audioManager == null) return;
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                // Ducking is done here, so a navigation prompt lowers the music instead of
                // pausing it.
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(this::onAudioFocusChange, handler)
                .build();
    }

    private void onAudioFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_GAIN -> {
                if (playerReady()) player.setVolume(1f, 1f);
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false;
                    resume();
                }
            }
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (playerReady()) player.setVolume(DUCK_VOLUME, DUCK_VOLUME);
            }
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // A call or a voice prompt, playback comes back once it is over.
                if (playerReady() && player.isPlaying()) {
                    resumeOnFocusGain = true;
                    pause(true);
                }
            }
            // Another player took over for good, such as online playback.
            case AudioManager.AUDIOFOCUS_LOSS -> pause();
            default -> {
            }
        }
    }

    /** @return Whether playback may start. Also pauses online playback, which yields focus. */
    private boolean requestAudioFocus() {
        resumeOnFocusGain = false;
        return audioManager != null && focusRequest != null
                && audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocus() {
        if (audioManager != null && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        }
    }

    private void openAudioEffectSession(MediaPlayer mediaPlayer) {
        int sessionId = mediaPlayer.getAudioSessionId();
        if (sessionId <= 0) return;

        audioEffectSessionId = sessionId;
        sendBroadcast(new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName())
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC));
    }

    private void closeAudioEffectSession() {
        int sessionId = audioEffectSessionId;
        if (sessionId <= 0) return;

        audioEffectSessionId = 0;
        sendBroadcast(new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName()));
    }

    // ------------------------------------------------------------------------------------------
    // State, metadata and notification.
    // ------------------------------------------------------------------------------------------

    /**
     * @param stateChanged Whether the media session and the notification must follow. The
     *                     regular ticks only update the screen, the system extrapolates the
     *                     position from the last state on its own.
     */
    private void publishState(boolean stateChanged) {
        final boolean nowPlaying = playerReady() && player.isPlaying();
        if (playerReady()) {
            playing = nowPlaying;
            position = player.getCurrentPosition();
            duration = player.getDuration();
        }
        notifyProgress();

        final long now = System.currentTimeMillis();
        if (nowPlaying && now - lastSave > SAVE_INTERVAL_MILLISECONDS) {
            lastSave = now;
            savePosition(this);
        }

        if (!stateChanged && nowPlaying == lastPublishedPlaying) return;
        lastPublishedPlaying = nowPlaying;

        if (session != null) {
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                            | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_SKIP_TO_NEXT
                            | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .setState(nowPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            position, nowPlaying ? 1f : 0f)
                    .build());
        }
        updateNotification();
    }

    private void publishMetadata(OfflineTrack track) {
        if (session == null) return;
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.displayTitle())
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.displayArtist())
                .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album())
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration);
        if (artwork != null) {
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork);
        }
        session.setMetadata(metadata.build());
        updateNotification();
    }

    /** Covers are decoded off the main thread, the notification gets them a moment later. */
    private void loadArtwork(OfflineTrack track) {
        final String path = track.path();
        artworkLoader.execute(() -> {
            Bitmap bitmap = track.artwork(NOTIFICATION_ARTWORK_PIXELS);
            handler.post(() -> {
                if (!path.equals(loadedPath) || instance != this) return;
                artwork = bitmap;
                publishMetadata(track);
            });
        });
    }

    /**
     * Enters the foreground once, right away as the system requires. While already there,
     * updates are coalesced instead.
     */
    private void goForeground() {
        handler.removeCallbacks(pausedTimeout);
        if (foreground) {
            updateNotification();
            return;
        }
        try {
            handler.removeCallbacks(postNotification);
            startForeground(NOTIFICATION_ID, notification());
            foreground = true;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not show the offline playback notification", ex);
        }
    }

    /** Paused: the controls stay, and the player stops itself if nothing happens for a while. */
    private void scheduleIdleStop() {
        handler.removeCallbacks(pausedTimeout);
        handler.postDelayed(pausedTimeout, PAUSED_TIMEOUT_MILLISECONDS);
        updateNotification();
    }

    /**
     * A track change updates the notification half a dozen times in a few hundred milliseconds,
     * past the rate at which the system drops updates. Only the last one of a burst is posted.
     */
    private void updateNotification() {
        handler.removeCallbacks(postNotification);
        handler.postDelayed(postNotification, NOTIFICATION_COALESCE_MILLISECONDS);
    }

    @SuppressLint("WrongConstant")
    private Notification notification() {
        OfflineTrack track = queue.current();
        final boolean nowPlaying = playing;
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_headset)
                .setContentTitle(track == null ? "YouTube Music" : track.displayTitle())
                .setContentText(track == null ? "" : track.displayArtist())
                .setLargeIcon(artwork)
                .setContentIntent(openPlayerIntent())
                .setDeleteIntent(serviceIntent(ACTION_DISMISS, 6))
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                // Paused, it can be swiped away like the notification of online playback.
                .setOngoing(nowPlaying)
                .addAction(action(android.R.drawable.ic_media_previous,
                        str("morphe_music_downloads_previous"), serviceIntent(ACTION_PREVIOUS, 4)))
                .addAction(action(
                        nowPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        str(nowPlaying ? "morphe_music_downloads_pause" : "morphe_music_downloads_play"),
                        serviceIntent(ACTION_TOGGLE, 1)))
                .addAction(action(android.R.drawable.ic_media_next,
                        str("morphe_music_downloads_next"), serviceIntent(ACTION_NEXT, 5)))
                .addAction(action(android.R.drawable.ic_menu_close_clear_cancel,
                        str("morphe_music_downloads_close"), serviceIntent(ACTION_STOP, 2)));
        if (session != null) {
            builder.setStyle(new Notification.MediaStyle()
                    .setMediaSession(session.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
        }
        return builder.build();
    }

    /** Opens the downloads screen straight on the full player. */
    private PendingIntent openPlayerIntent() {
        Intent intent = new Intent()
                .setClassName(this, "com.google.android.gms.common.api.GoogleApiActivity")
                .setPackage(getPackageName())
                .setData(Uri.parse(BaseActivityHook.MORPHE_DOWNLOADS_INTENT))
                .putExtra(EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 3, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification.Action action(int iconResource, String title, PendingIntent intent) {
        return new Notification.Action.Builder(
                Icon.createWithResource(this, iconResource), title, intent).build();
    }

    private PendingIntent serviceIntent(String action, int code) {
        Intent intent = new Intent(this, OfflinePlaybackService.class).setAction(action);
        return PendingIntent.getService(this, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void createChannel() {
        //noinspection WrongConstant
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                str("morphe_music_downloads_playback_channel"), NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (pendingRestore != null) {
            OfflineLibrary.removeListener(pendingRestore);
            pendingRestore = null;
        }
        saveState(this);
        releasePlayer();
        abandonAudioFocus();
        try {
            unregisterReceiver(noisyReceiver);
        } catch (Exception ignored) {
        }
        if (session != null) {
            session.release();
            session = null;
        }
        if (instance == this) instance = null;
        playing = false;
        // The next play picks up where this one was.
        if (queue.current() != null) pendingSeek = position;
        notifyProgress();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
