/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.Spanned;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.ui.Dim;

/**
 * The mark the songs already downloaded carry before their details in the lists of the app.
 */
public final class DownloadedMark {

    private static final float ICON_DP = 14;
    private static final float GAP_DP = 4;
    /** Null if the icon could not be loaded, in which case the texts are left as they are. */
    @Nullable
    private static final Drawable ICON = ResourceUtils.getDrawable("morphe_music_downloaded_mark");

    private DownloadedMark() {
    }

    /**
     * @return If a track with this video id is downloaded. The downloads folder is listed the
     *         first time, so this is called off the main thread.
     */
    public static boolean isDownloaded(String videoId) {
        return OfflineCatalogue.contains(videoId);
    }

    /**
     * @return The text with the mark before it, or the text itself if it already has one.
     */
    public static CharSequence addTo(CharSequence text) {
        if (ICON == null) return text;
        // A reused text reaches the hook again with the mark it was given when it was created.
        if (text instanceof Spanned spanned
                && spanned.getSpans(0, spanned.length(), LeadingIconSpan.class).length > 0) {
            return text;
        }

        SpannableString marked = new SpannableString(text);
        marked.setSpan(new LeadingIconSpan(ICON, Dim.dp(ICON_DP), Dim.dp(GAP_DP)),
                0, marked.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        return marked;
    }
}
