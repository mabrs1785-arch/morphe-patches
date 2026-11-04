/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.components;

import androidx.annotation.Nullable;

import app.morphe.extension.music.patches.downloads.DownloadedMark;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.patches.DownloadMode;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;

/**
 * Finds the song rows of a downloaded track, so the Downloads patch can mark their texts.
 * Nothing is hidden here.
 */
@SuppressWarnings("unused")
public final class DownloadedMarkFilter extends Filter {

    /** In the path of a song row and of every component inside it. */
    public static final String SONG_ROW_PATH = "music_list_item.";
    /**
     * Separators after {@link #SONG_ROW_PATH} in the path of the row itself, such as
     * "music_list_item.eml-fe|e1bdbb31ea4fcde4|CellType|". The components inside the row have more.
     */
    private static final int ROW_PATH_SEPARATORS = 3;

    /**
     * The downloaded song of the row being built on this thread, or null if the row is not of a
     * downloaded song. The components of a row are built one after the other on the same thread,
     * the row itself first, and each text right after its own component.
     */
    private static final ThreadLocal<String> downloadedRow = new ThreadLocal<>();

    public DownloadedMarkFilter() {
        addPathCallbacks(new StringFilterGroup(Settings.DOWNLOADS_MARK, SONG_ROW_PATH));
    }

    @Override
    public boolean isFiltered(ContextInterface contextInterface, String identifier, String accessibility,
                              CharSequence path, byte[] buffer, BufferAsciiStrings asciiStrings,
                              StringFilterGroup matchedGroup, FilterContentType contentType,
                              int contentIndex) {
        if (Settings.DOWNLOAD_MODE.get() != DownloadMode.IN_APP) return false;

        if (isRow(path, contentIndex)) {
            String videoId = DownloadedMark.downloadedIdIn(buffer);
            downloadedRow.set(videoId);
            if (videoId != null) Logger.printDebug(() -> "Downloaded song row of " + videoId);
        }
        return false;
    }

    private static boolean isRow(CharSequence path, int matchIndex) {
        int separators = 0;
        for (int i = matchIndex, length = path.length(); i < length; i++) {
            if (path.charAt(i) == '|') separators++;
        }
        return separators == ROW_PATH_SEPARATORS;
    }

    /**
     * @return The downloaded song of the row whose text is being built on this thread, or null if
     *         the row is not of a downloaded song.
     */
    @Nullable
    public static String downloadedRow() {
        return downloadedRow.get();
    }
}
