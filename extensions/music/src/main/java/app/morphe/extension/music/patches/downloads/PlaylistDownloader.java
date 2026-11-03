/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.CustomDialog;

/**
 * Downloads every track of a playlist or an album with the local downloads, one track at a time.
 * <p>
 * The tracks are listed first, and the download starts once the number of tracks is confirmed,
 * since a large playlist such as Liked music can take hours and many gigabytes.
 */
public final class PlaylistDownloader {

    /** Key of the single notification that shows the progress of the playlist. */
    private static final String NOTIFICATION_KEY = "morphe_playlist_download";

    /** Rough size of a track, only used to tell how much space the playlist needs. */
    private static final long AVERAGE_TRACK_BYTES = 4L * 1024 * 1024;

    /** How long a lost connection is waited for before the download stops. */
    private static final long MAX_NETWORK_WAIT_MILLISECONDS = 10 * 60 * 1000;
    private static final long NETWORK_CHECK_INTERVAL_MILLISECONDS = 5000;

    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 7341;

    /**
     * Set while the tracks are listed or downloaded. It is not set while the confirmation waits,
     * so a dialog closed in any way never leaves it set.
     */
    private static final AtomicBoolean running = new AtomicBoolean();

    private PlaylistDownloader() {
    }

    /**
     * Called for every request the app builds, to list the playlists that need the account.
     */
    public static void rememberAppHeaders(@Nullable String url, @Nullable Map<String, String> requestHeaders) {
        PlaylistTracksRequest.rememberAppHeaders(url, requestHeaders);
    }

    /**
     * @param playlistId Id of the playlist or the album, such as 'LM' for Liked music,
     *                   'OLAK5uy_…' for the playlist of an album, or the 'MPREb_…' browse id of an album.
     */
    public static void start(@NonNull String playlistId) {
        if (!running.compareAndSet(false, true)) {
            Utils.showToastShort(str("morphe_music_downloads_playlist_already_running"));
            return;
        }

        String browseId = toBrowseId(playlistId);
        Logger.printInfo(() -> "Listing the tracks of " + browseId + " to download");
        Utils.showToastShort(str("morphe_music_downloads_playlist_listing"));
        requestNotificationPermission();
        LocalDownloadManager.showDownloadNotification(NOTIFICATION_KEY,
                str("morphe_music_downloads_playlist_title"), -1, true,
                str("morphe_music_downloads_playlist_listing"));

        Utils.runOnBackgroundThread(() -> {
            try {
                listAndConfirm(browseId);
            } catch (Exception ex) {
                running.set(false);
                endWithMessage(str("morphe_music_downloads_playlist_empty"));
                Logger.printException(() -> "Playlist listing failed", ex);
            }
        });
    }

    private static void listAndConfirm(String browseId) {
        PlaylistTracksRequest.Result result = PlaylistTracksRequest.fetchVideoIds(browseId, tracksListed -> {
            if (LocalDownloadManager.canPostProgress()) {
                LocalDownloadManager.showDownloadNotification(NOTIFICATION_KEY,
                        str("morphe_music_downloads_playlist_title"), -1, true,
                        str("morphe_music_downloads_playlist_listed", tracksListed));
            }
        });

        if (result == PlaylistTracksRequest.NEEDS_ACCOUNT) {
            running.set(false);
            endWithMessage(str("morphe_music_downloads_playlist_needs_account"));
            return;
        }
        if (result == null || result.videoIds().isEmpty()) {
            running.set(false);
            endWithMessage(str("morphe_music_downloads_playlist_empty"));
            return;
        }

        List<String> videoIds = result.videoIds();
        List<String> missing = new ArrayList<>(videoIds.size());
        for (String videoId : videoIds) {
            if (!LocalDownloadManager.isDownloaded(videoId)) missing.add(videoId);
        }
        final int alreadySaved = videoIds.size() - missing.size();
        Logger.printInfo(() -> "Playlist has " + videoIds.size() + " tracks, "
                + alreadySaved + " already downloaded");

        // Nothing runs while the answer is awaited.
        running.set(false);

        if (missing.isEmpty()) {
            endWithMessage(result.complete()
                    ? str("morphe_music_downloads_playlist_all_saved", videoIds.size())
                    : str("morphe_music_downloads_playlist_listed_all_saved", videoIds.size()));
            return;
        }

        LocalDownloadManager.cancelDownloadNotification(NOTIFICATION_KEY);
        Utils.runOnMainThread(() -> confirm(videoIds.size(), missing, result.complete()));
    }

    /**
     * 'LM' is the playlist id of Liked music, and its page is 'VLLM' like any other playlist.
     */
    private static String toBrowseId(String playlistId) {
        if (playlistId.startsWith("VL") || playlistId.startsWith("MPRE")) return playlistId;
        return "VL" + playlistId;
    }

    /**
     * Without the notification permission, the progress of a download that takes hours is never shown.
     */
    private static void requestNotificationPermission() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
            Activity activity = Utils.getActivity();
            if (activity == null || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                return;
            }
            Logger.printInfo(() -> "Asking for the notification permission");
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST_CODE);
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not ask for the notification permission", ex);
        }
    }

    private static void confirm(int trackCount, List<String> missing, boolean complete) {
        Activity activity = Utils.getActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Logger.printInfo(() -> "No activity to confirm the playlist download");
            return;
        }

        final long megabytes = missing.size() * AVERAGE_TRACK_BYTES / (1024 * 1024);
        String message = str("morphe_music_downloads_playlist_confirm_message",
                trackCount, missing.size(), megabytes >= 1024
                        ? String.format(Locale.getDefault(), "%.1f GB", megabytes / 1024f)
                        : megabytes + " MB");
        if (!complete) {
            message += "\n\n" + str("morphe_music_downloads_playlist_incomplete");
        }

        try {
            CustomDialog.create(activity,
                    str("morphe_music_downloads_playlist_title"), message, null,
                    str("morphe_music_downloads_playlist_confirm_button"),
                    () -> {
                        if (!running.compareAndSet(false, true)) {
                            Utils.showToastShort(str("morphe_music_downloads_playlist_already_running"));
                            return;
                        }
                        Utils.showToastShort(str("morphe_music_downloads_playlist_started", missing.size()));
                        Utils.runOnBackgroundThread(() -> downloadAll(missing));
                    },
                    () -> {
                    }, null, null, true).first.show();
        } catch (RuntimeException ex) {
            // The activity can be gone while the tracks were listed.
            Logger.printInfo(() -> "Could not show the playlist confirmation", ex);
        }
    }

    private static void downloadAll(List<String> videoIds) {
        final int total = videoIds.size();
        int saved = 0;
        int failed = 0;
        int done = 0;

        try {
            for (; done < total; done++) {
                if (!awaitNetwork(done, total)) {
                    final int doneCount = done;
                    Logger.printInfo(() -> "No network, playlist download stopped at " + doneCount + "/" + total);
                    endWithMessage(str("morphe_music_downloads_playlist_stopped", done, total));
                    return;
                }

                if (LocalDownloadManager.canPostProgress()) {
                    LocalDownloadManager.showDownloadNotification(NOTIFICATION_KEY,
                            str("morphe_music_downloads_playlist_title"), done * 100 / total, true,
                            str("morphe_music_downloads_playlist_progress", done + 1, total));
                }

                String videoId = videoIds.get(done);
                boolean trackSaved = LocalDownloadManager.downloadPlaylistTrack(videoId);
                // A connection lost in the middle of a track fails it, so it is tried again once the network is back.
                if (!trackSaved && !Utils.isNetworkConnected() && awaitNetwork(done, total)) {
                    trackSaved = LocalDownloadManager.downloadPlaylistTrack(videoId);
                }
                if (trackSaved) {
                    saved++;
                } else {
                    failed++;
                }
            }

            final int savedCount = saved;
            final int failedCount = failed;
            Logger.printInfo(() -> "Playlist download finished, saved: " + savedCount + ", failed: " + failedCount);

            endWithMessage(failed == 0
                    ? str("morphe_music_downloads_playlist_complete", saved)
                    : str("morphe_music_downloads_playlist_complete_with_failures", saved, failed));
        } catch (Exception ex) {
            Logger.printException(() -> "Playlist download failed", ex);
            endWithMessage(str("morphe_music_downloads_playlist_stopped", done, total));
        } finally {
            running.set(false);
        }
    }

    /**
     * @return If the network is connected, after waiting for it if it was lost.
     */
    private static boolean awaitNetwork(int done, int total) {
        if (Utils.isNetworkConnected()) return true;

        LocalDownloadManager.awaitNotificationSlot();
        LocalDownloadManager.showDownloadNotification(NOTIFICATION_KEY,
                str("morphe_music_downloads_playlist_title"), done * 100 / total, true,
                str("morphe_music_downloads_playlist_waiting_network"));

        final long giveUpTime = System.currentTimeMillis() + MAX_NETWORK_WAIT_MILLISECONDS;
        while (System.currentTimeMillis() < giveUpTime) {
            try {
                //noinspection BusyWait
                Thread.sleep(NETWORK_CHECK_INTERVAL_MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (Utils.isNetworkConnected()) return true;
        }
        return false;
    }

    /**
     * Shows the result as a toast too, since the notification is not shown without the permission.
     */
    private static void endWithMessage(String message) {
        LocalDownloadManager.awaitNotificationSlot();
        LocalDownloadManager.showDownloadNotification(NOTIFICATION_KEY,
                str("morphe_music_downloads_playlist_title"), 100, false, message);
        Utils.showToastShort(message);
    }
}
