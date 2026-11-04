/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;

/**
 * Draws a drawable before the first line of a text, in a margin the text is laid out after,
 * tinted with the color of the text. No character is added to the text, so the spans the app sets
 * by character index stay in place, and a text cut with an ellipsis keeps the drawable, which a
 * span at the end of the text would lose.
 */
final class LeadingIconSpan implements LeadingMarginSpan {

    private static final int OPAQUE = 0xFF000000;

    private final Drawable drawable;
    private final int size;
    private final int gap;

    /**
     * @param drawable Drawable that is tinted, so it should be drawn in a single color. It is
     *                 shared by every use of this span, and only drawn on the main thread.
     * @param size     Width and height of the icon in pixels.
     * @param gap      Space between the icon and the text in pixels.
     */
    LeadingIconSpan(Drawable drawable, int size, int gap) {
        this.drawable = drawable;
        this.size = size;
        this.gap = gap;
    }

    @Override
    public int getLeadingMargin(boolean first) {
        // Every line is laid out after the margin, so the lines of a text line up.
        return size + gap;
    }

    @Override
    public void drawLeadingMargin(Canvas canvas, Paint paint, int x, int dir, int top, int baseline,
                                  int bottom, CharSequence text, int start, int end, boolean first,
                                  Layout layout) {
        Spanned spanned = (Spanned) text;
        // The icon goes on the line the text starts on.
        if (spanned.getSpanStart(this) != start) return;

        // A right to left line is laid out before the margin.
        final int left = dir < 0 ? x - size : x;
        final int iconTop = (top + bottom - size) / 2;
        final int color = textColor(spanned, start, paint.getColor());
        drawable.setTint(color | OPAQUE);
        drawable.setAlpha(Color.alpha(color));
        drawable.setBounds(left, iconTop, left + size, iconTop + size);
        drawable.draw(canvas);
    }

    /** The color of the text can be set by a span, which is not applied to the paint given here. */
    private static int textColor(Spanned text, int start, int paintColor) {
        ForegroundColorSpan[] colors = text.getSpans(start, start + 1, ForegroundColorSpan.class);
        return colors.length > 0 ? colors[colors.length - 1].getForegroundColor() : paintColor;
    }
}
