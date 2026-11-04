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

    /** Start of the path of the components of a song row. */
    private static final String SONG_ROW_PATH = "music_list_item.";

    /**
     * The row built last on this thread, which the texts built next belong to. The components of
     * a row share its context, and its texts are built right after its components.
     */
    private record Row(ContextInterface context, @Nullable String downloadedId) {
    }

    private static final ThreadLocal<Row> currentRow = new ThreadLocal<>();

    public DownloadedMarkFilter() {
        addPathCallbacks(new StringFilterGroup(Settings.DOWNLOADS_MARK, SONG_ROW_PATH));
    }

    @Override
    public boolean isFiltered(ContextInterface contextInterface, String identifier, String accessibility,
                              CharSequence path, byte[] buffer, BufferAsciiStrings asciiStrings,
                              StringFilterGroup matchedGroup, FilterContentType contentType,
                              int contentIndex) {
        if (Settings.DOWNLOAD_MODE.get() != DownloadMode.IN_APP) return false;

        Row row = currentRow.get();
        if (row == null || row.context() != contextInterface) {
            row = new Row(contextInterface, null);
            currentRow.set(row);
        }
        // The song is in the first component that holds the endpoints of the row.
        if (row.downloadedId() == null) {
            String videoId = DownloadedMark.downloadedIdIn(buffer);
            if (videoId != null) {
                currentRow.set(new Row(contextInterface, videoId));
                Logger.printDebug(() -> "Downloaded song row: " + path + " " + videoId);
            }
        }
        return false;
    }

    /**
     * @return The downloaded song of the row the text being built belongs to, or null if the row
     *         is not of a downloaded song.
     */
    @Nullable
    public static String downloadedIdOf(ContextInterface context) {
        Row row = currentRow.get();
        return row != null && row.context() == context ? row.downloadedId() : null;
    }
}
