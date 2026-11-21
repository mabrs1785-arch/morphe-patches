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

import java.util.ArrayList;
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
    private static final int NOTIFICATION_ARTWORK_PIXELS = 512;
    private static final float DUCK_VOLUME = 0.2f;

    /** Unreadable files in a row before playback gives up instead of skipping on. */
    private static final int MAX_CONSECUTIVE_ERRORS = 3;

    private static final String PREFERENCES = "morphe_offline_player";
    private static final String KEY_QUEUE = "queue";
    private static final String KEY_INDEX = "index";
    private static final String KEY_POSITION = "position";
    private static final String KEY_REPEAT = "repeat";

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
        if (service != null && service.playerReady()) {
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
        if (target == before && instance != null && instance.playerReady()) {
            seekTo(0);
            if (!instance.player.isPlaying()) instance.resume();
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
            SharedPreferences preferences = preferences(context);
            String saved = preferences.getString(KEY_QUEUE, "");
            if (saved == null || saved.isEmpty()) return;

            List<OfflineTrack> tracks = new ArrayList<>();
            final int savedIndex = Math.max(0, preferences.getInt(KEY_INDEX, 0));
            int restoredIndex = 0;
            String[] paths = saved.split("\n");
            for (int i = 0; i < paths.length; i++) {
                OfflineTrack track = OfflineLibrary.find(paths[i]);
                if (track == null) continue;
                if (i <= savedIndex) restoredIndex = tracks.size();
                tracks.add(track);
            }
            if (tracks.isEmpty()) return;

            queue.set(tracks, restoredIndex, false);
            try {
                queue.setRepeat(OfflineQueue.Repeat.valueOf(
                        preferences.getString(KEY_REPEAT, OfflineQueue.Repeat.OFF.name())));
            } catch (Exception ignored) {
                queue.setRepeat(OfflineQueue.Repeat.OFF);
            }
            OfflineTrack current = queue.current();
            pendingSeek = current != null && paths[Math.min(savedIndex, paths.length - 1)]
                    .equals(current.path()) ? preferences.getInt(KEY_POSITION, 0) : 0;
            position = pendingSeek;
            duration = current == null ? 0 : current.durationSeconds() * 1000;
            playing = false;
            notifyQueueChanged();
            notifyProgress();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not restore the offline queue", ex);
        }
    }

    /** Deleted downloads leave the queue, and the player moves on if one was playing. */
    static void onTracksDeleted(Context context, List<String> paths) {
        boolean currentChanged = false;
        List<OfflineTrack> items = queue.items();
        for (int i = items.size() - 1; i >= 0; i--) {
            if (paths.contains(items.get(i).path())) currentChanged |= queue.remove(i);
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

    private static void saveState(Context context) {
        try {
            StringBuilder paths = new StringBuilder();
            for (OfflineTrack track : queue.items()) {
                if (paths.length() > 0) paths.append('\n');
                paths.append(track.path());
            }
            preferences(context).edit()
                    .putString(KEY_QUEUE, paths.toString())
                    .putInt(KEY_INDEX, queue.index())
                    .putInt(KEY_POSITION, position)
                    .putString(KEY_REPEAT, queue.repeat().name())
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

        // The system only sends this to receivers registered at runtime.
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
            toggle(this);
        } else if (ACTION_NEXT.equals(action)) {
            next(this);
        } else if (ACTION_PREVIOUS.equals(action)) {
            previous(this);
        } else if (ACTION_STOP.equals(action)) {
            stop(this);
        } else if (ACTION_DISMISS.equals(action)) {
            if (queue.current() != null) pendingSeek = position;
            saveState(this);
            stopPlayback();
            notifyProgress();
        } else if (queue.current() == null) {
            stopSelf();
        }
        return START_NOT_STICKY;
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
                if (playWhenPrepared) resume();
                else publishState(true);
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
            leaveForeground();
            notifyQueueChanged();
            saveState(this);
            return;
        }
        pendingSeek = 0;
        loadCurrent(true);
        onQueueEdited();
    }

    private void onTrackFailed(OfflineTrack track, String reason) {
        Logger.printException(() -> "Offline player could not play " + track.audioFile() + ": " + reason);
        releasePlayer();
        consecutiveErrors++;
        if (consecutiveErrors < MAX_CONSECUTIVE_ERRORS && queue.next() >= 0) {
            Utils.showToastShort(str("morphe_music_downloads_play_failed"));
            pendingSeek = 0;
            loadCurrent(true);
            onQueueEdited();
            return;
        }
        consecutiveErrors = 0;
        playing = false;
        Utils.showToastShort(str("morphe_music_downloads_play_failed"));
        publishState(true);
        leaveForeground();
    }

    private void resume() {
        if (!playerReady()) {
            if (queue.current() != null) loadCurrent(true);
            return;
        }
        if (!requestAudioFocus()) return;
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
     * @param resumesSoon A call or a voice prompt interrupts playback. The service stays in the
     *                    foreground, which it may not enter again from the background.
     */
    private void pause(boolean resumesSoon) {
        if (!playerReady() || !player.isPlaying()) return;
        player.pause();
        publishState(true);
        // Paused playback can be swiped away, like the notification of online playback.
        if (!resumesSoon) leaveForeground();
        saveState(this);
    }

    private void stopPlayback() {
        releasePlayer();
        handler.removeCallbacks(progressTicker);
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
        stopSelf();
    }

    /**
     * Asking a player that is still preparing about its state is an invalid operation, which it
     * answers with an error that stops playback, so nothing may reach it before it is ready.
     */
    private boolean playerReady() {
        return prepared && player != null;
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
            case AudioManager.AUDIOFOCUS_LOSS -> {
                // Another player took over for good, such as online playback.
                resumeOnFocusGain = false;
                pause();
            }
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
        playing = nowPlaying;
        if (playerReady()) {
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

    private void goForeground() {
        try {
            startForeground(NOTIFICATION_ID, notification());
            foreground = true;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not show the offline playback notification", ex);
        }
    }

    private void leaveForeground() {
        if (!foreground) return;
        foreground = false;
        stopForeground(STOP_FOREGROUND_DETACH);
        updateNotification();
    }

    @SuppressLint("NotificationPermission") // The patch declares POST_NOTIFICATIONS.
    private void updateNotification() {
        if (queue.current() == null) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification());
    }

    @SuppressLint("WrongConstant")
    private Notification notification() {
        OfflineTrack track = queue.current();
        final boolean nowPlaying = playerReady() && player.isPlaying();
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
        if (queue.current() != null) saveState(this);
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
        // The system may stop a paused player, the next play picks up where it was.
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
