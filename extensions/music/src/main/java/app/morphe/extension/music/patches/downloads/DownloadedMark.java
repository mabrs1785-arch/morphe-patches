/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;

import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.ui.IconSpan;

/**
 * The mark the songs already downloaded carry after their details in the lists of the app.
 */
public final class DownloadedMark {

    /** Replaced by the icon, and shown as is if the icon could not be loaded. */
    private static final String PLACEHOLDER = "◎";
    private static final String SEPARATOR = " ";
    private static final float ICON_SIZE = 0.9f;
    private static final float ICON_OPACITY = 1f;
    @Nullable
    private static final Drawable ICON = ResourceUtils.getDrawable("morphe_music_downloaded_mark");

    private static final int VIDEO_ID_LENGTH = 11;
    /** Wire type of the length delimited fields, in the low bits of a tag. */
    private static final int WIRE_TYPE_MASK = 0x07;
    private static final int WIRE_TYPE_LENGTH_DELIMITED = 2;

    /** If the catalogue was asked for once, which reads it ahead of the first list. */
    private static volatile boolean prepared;

    private DownloadedMark() {
    }

    /**
     * Reads the catalogue in the background the first time, from the first text the app shows,
     * so the first rows of a list already get their marks. Reading it takes a moment with
     * thousands of tracks.
     */
    public static void prepare() {
        if (prepared) return;
        prepared = true;
        OfflineCatalogue.readIfNeeded();
    }

    /**
     * @return The text with the mark at its end, or the text itself if it already has one.
     */
    public static CharSequence addTo(CharSequence text) {
        if (text instanceof Spanned spanned
                && spanned.getSpans(0, spanned.length(), IconSpan.class).length > 0) {
            return text;
        }

        SpannableStringBuilder marked = new SpannableStringBuilder(text)
                .append(SEPARATOR).append(PLACEHOLDER);
        if (ICON != null) {
            marked.setSpan(new IconSpan(ICON, ICON_SIZE, ICON_OPACITY),
                    marked.length() - PLACEHOLDER.length(), marked.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return marked;
    }

    /**
     * Looks through the component of a song row for a video id of a downloaded track. The row
     * holds its song in the video id of its play endpoint and of the endpoints of its menu, each
     * a length delimited field of 11 characters.
     *
     * @return The video id, or null if the row is not of a downloaded song.
     */
    @Nullable
    public static String downloadedIdIn(byte[] buffer) {
        for (int i = 0, last = buffer.length - VIDEO_ID_LENGTH - 2; i <= last; i++) {
            if (buffer[i + 1] != VIDEO_ID_LENGTH || !isStringFieldTag(buffer[i])) continue;
            final int start = i + 2;
            if (!isVideoId(buffer, start)) continue;

            String videoId = new String(buffer, start, VIDEO_ID_LENGTH, StandardCharsets.US_ASCII);
            if (OfflineCatalogue.contains(videoId)) return videoId;
        }
        return null;
    }

    /** A single byte tag, of the fields 1 to 15, with the length delimited wire type. */
    private static boolean isStringFieldTag(byte tag) {
        return tag > 0 && (tag & WIRE_TYPE_MASK) == WIRE_TYPE_LENGTH_DELIMITED;
    }

    private static boolean isVideoId(byte[] buffer, int start) {
        for (int i = start, end = start + VIDEO_ID_LENGTH; i < end; i++) {
            final byte b = buffer[i];
            if (!((b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9')
                    || b == '-' || b == '_')) {
                return false;
            }
        }
        return true;
    }
}
