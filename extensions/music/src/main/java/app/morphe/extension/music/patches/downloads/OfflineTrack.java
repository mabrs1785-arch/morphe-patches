/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;

import app.morphe.extension.shared.Logger;

/**
 * Persistent metadata paired with one locally downloaded audio file.
 * <p>
 * The size and the date are read once, when the track enters the catalogue. Sorting a few
 * thousand tracks otherwise asks the storage for them tens of thousands of times.
 *
 * @param modifiedMillis A finished download is renamed from its partial file and never written
 *                       again, so this is when it was downloaded.
 */
public record OfflineTrack(String videoId, String title, String artist, String album,
                           int durationSeconds, File audioFile, File artworkFile,
                           long sizeBytes, long modifiedMillis) {

    public static OfflineTrack load(File audioFile) {
        String fileName = audioFile.getName();
        int dot = fileName.lastIndexOf('.');
        String videoId = dot > 0 ? fileName.substring(0, dot) : fileName;
        File metadata = new File(audioFile.getParentFile(), videoId + ".json");
        File artwork = new File(audioFile.getParentFile(), videoId + ".jpg");
        final long size = audioFile.length();
        final long modified = audioFile.lastModified();

        try {
            if (metadata.isFile()) {
                JSONObject json = new JSONObject(OfflineStorage.readText(metadata));
                return new OfflineTrack(videoId, json.optString("title", videoId),
                        json.optString("artist", ""), json.optString("album", ""),
                        json.optInt("durationSeconds", 0), audioFile, artwork, size, modified);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read offline metadata: " + metadata, ex);
        }
        return new OfflineTrack(videoId, videoId, "", "", 0, audioFile, artwork, size, modified);
    }

    /** Rebuilds a track from the catalogue index, without touching its files. */
    @Nullable
    static OfflineTrack fromIndex(File directory, JSONObject json) {
        String fileName = json.optString("file", "");
        if (fileName.isEmpty()) return null;
        String videoId = json.optString("videoId", "");
        if (videoId.isEmpty()) return null;
        return new OfflineTrack(videoId, json.optString("title", videoId),
                json.optString("artist", ""), json.optString("album", ""),
                json.optInt("durationSeconds", 0), new File(directory, fileName),
                new File(directory, videoId + ".jpg"),
                json.optLong("size", 0), json.optLong("modified", 0));
    }

    JSONObject toIndex() throws Exception {
        return new JSONObject()
                .put("file", audioFile.getName())
                .put("videoId", videoId)
                .put("title", title)
                .put("artist", artist)
                .put("album", album)
                .put("durationSeconds", durationSeconds)
                .put("size", sizeBytes)
                .put("modified", modifiedMillis);
    }

    public static void save(File directory, String videoId, String title, String artist,
                            String album, int durationSeconds, @Nullable Bitmap artwork) {
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IllegalStateException("Could not create " + directory);
            }

            JSONObject json = new JSONObject()
                    .put("videoId", videoId)
                    .put("title", emptyFallback(title, videoId))
                    .put("artist", emptyFallback(artist, ""))
                    .put("album", emptyFallback(album, ""))
                    .put("durationSeconds", durationSeconds);
            OfflineStorage.writeText(new File(directory, videoId + ".json"), json.toString());

            if (artwork != null) {
                OfflineStorage.writeJpeg(new File(directory, videoId + ".jpg"), artwork);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not save offline metadata: " + videoId, ex);
        }
    }

    public String path() {
        return audioFile.getAbsolutePath();
    }

    @Nullable
    public Bitmap artwork(int maxSize) {
        return OfflineStorage.decodeArtwork(artworkFile, maxSize);
    }

    public String displayTitle() {
        return title.isBlank() ? videoId : title;
    }

    public String displayArtist() {
        return artist.isBlank() ? "YouTube Music" : artist;
    }

    private static String emptyFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
