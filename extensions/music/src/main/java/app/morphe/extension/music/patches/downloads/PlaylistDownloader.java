/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    private static final AtomicBoolean running = new AtomicBoolean();

    private PlaylistDownloader() {
    }

    /**
     * Must be called from the main thread.
     *
     * @param playlistId Id of the playlist or the album, such as 'LM' for Liked music,
     *                   'OLAK5uy_…' for the playlist of an album, or the 'MPREb_…' browse id of an album.
     */
    public static void start(@NonNull String playlistId) {
        if (!running.compareAndSet(false, true)) {
            Utils.showToastShort(str("morphe_music_downloads_playlist_already_running"));
            return;
        }

        String browseId = toBrowseId(playlistId);
        Logger.printDebug(() -> "Listing the tracks of " + browseId + " to download");
        Utils.showToastShort(str("morphe_music_downloads_playlist_listing"));
        LocalDownloadManager.showDownloadNotification(NOTIFICATION_KEY,
                str("morphe_music_downloads_playlist_title"), 0, true,
                str("morphe_music_downloads_playlist_listing"));

        Utils.runOnBackgroundThread(() -> {
            List<String> videoIds = PlaylistTracksRequest.fetchVideoIds(browseId, tracksListed ->
                    LocalDownloadManager.showDownloadNotification(NOTIFICATION_KEY,
                            str("morphe_music_downloads_playlist_title"), -1, true,
                            str("morphe_music_downloads_playlist_listed", tracksListed)));

            if (videoIds == null || videoIds.isEmpty()) {
                running.set(false);
                LocalDownloadManager.showDownloadNotification(NOTIFICATION_KEY,
                        str("morphe_music_downloads_playlist_title"), 0, false,
                        str("morphe_music_downloads_playlist_empty"));
                Utils.showToastShort(str("morphe_music_downloads_playlist_empty"));
                return;
            }

            List<String> missing = new ArrayList<>(videoIds.size());
            for (String videoId : videoIds) {
                if (!LocalDownloadManager.isDownloaded(videoId)) missing.add(videoId);
            }
            final int alreadySaved = videoIds.size() - missing.size();
            Logger.printDebug(() -> "Playlist has " + videoIds.size() + " tracks, "
                    + alreadySaved + " already downloaded");

            if (missing.isEmpty()) {
                running.set(false);
                LocalDownloadManager.showDownloadNotification(NOTIFICATION_KEY,
                        str("morphe_music_downloads_playlist_title"), 100, false,
                        str("morphe_music_downloads_playlist_all_saved", videoIds.size()));
                Utils.showToastShort(str("morphe_music_downloads_playlist_all_saved", videoIds.size()));
                return;
            }

            Utils.runOnMainThread(() -> confirm(videoIds.size(), missing));
        });
    }

    /**
     * 'LM' is the playlist id of Liked music, and its page is 'VLLM' like any other playlist.
     */
    private static String toBrowseId(String playlistId) {
        if (playlistId.startsWith("VL") || playlistId.startsWith("MPRE")) return playlistId;
        return "VL" + playlistId;
    }

    private static void confirm(int trackCount, List<String> missing) {
        Activity activity = Utils.getActivity();
        if (activity == null) {
            running.set(false);
            clearNotification();
            return;
        }

        final long megabytes = missing.size() * AVERAGE_TRACK_BYTES / (1024 * 1024);
        String message = str("morphe_music_downloads_playlist_confirm_message",
                trackCount, missing.size(), megabytes >= 1024
                        ? String.format(Locale.ROOT, "%.1f GB", megabytes / 1024f)
                        : megabytes + " MB");

        CustomDialog.create(activity,
                str("morphe_music_downloads_playlist_title"), message, null,
                str("morphe_music_downloads_playlist_confirm_button"),
                () -> Utils.runOnBackgroundThread(() -> downloadAll(missing)),
                () -> {
                    running.set(false);
                    clearNotification();
                }, null, null, true).first.show();
    }

    private static void downloadAll(List<String> videoIds) {
        try {
            final int total = videoIds.size();
            int saved = 0;
            int failed = 0;

            for (int i = 0; i < total; i++) {
                final int done = i;
                LocalDownloadManager.showDownloadNotification(NOTIFICATION_KEY,
                        str("morphe_music_downloads_playlist_title"), done * 100 / total, true,
                        str("morphe_music_downloads_playlist_progress", done + 1, total));

                if (LocalDownloadManager.downloadPlaylistTrack(videoIds.get(i))) {
                    saved++;
                } else {
                    failed++;
                }
            }

            final int savedCount = saved;
            final int failedCount = failed;
            Logger.printDebug(() -> "Playlist download finished, saved: " + savedCount
                    + ", failed: " + failedCount);

            String result = failed == 0
                    ? str("morphe_music_downloads_playlist_complete", saved)
                    : str("morphe_music_downloads_playlist_complete_with_failures", saved, failed);
            LocalDownloadManager.showDownloadNotification(NOTIFICATION_KEY,
                    str("morphe_music_downloads_playlist_title"), 100, false, result);
            Utils.showToastShort(result);
        } catch (Exception ex) {
            Logger.printException(() -> "Playlist download failed", ex);
        } finally {
            running.set(false);
        }
    }

    private static void clearNotification() {
        LocalDownloadManager.showDownloadNotification(NOTIFICATION_KEY,
                str("morphe_music_downloads_playlist_title"), 0, false,
                str("morphe_music_downloads_playlist_cancelled"));
    }
}
