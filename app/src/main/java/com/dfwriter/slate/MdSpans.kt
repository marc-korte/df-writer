package com.dfwriter.slate

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.ReplacementSpan
import android.text.style.UpdateAppearance

/**
 * Tag for everything [MarkdownStyler] applies, so a restyle can strip its own
 * work without touching selection or spell-check spans owned by the framework.
 */
interface SlateSpan

/** Focus-mode dimming, recomputed on caret movement rather than on edit. */
interface FocusSpan : SlateSpan

object Ink {
    const val TEXT = 0xFF000000.toInt()
    const val MARKER = 0xFF9AA0A6.toInt()
    const val DIM = 0xFFB4B4B4.toInt()
    const val RULE = 0xFF767676.toInt()
    const val CODE_BG = 0xFFEDEDED.toInt()
    const val QUOTE_BAR = 0xFF5A5A5A.toInt()
}

/**
 * Collapses a run of characters to zero width. This is how the syntax markers
 * disappear: the text stays in the buffer, so the file on disk is still plain
 * Markdown and the caret can still be placed inside the run, but nothing is
 * painted.
 */
class HiddenSpan : ReplacementSpan(), SlateSpan {
    override fun getSize(
        paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?
    ): Int = 0

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint
    ) = Unit
}

/** A visible-but-quiet syntax marker, used on the caret's own line. */
class MarkerSpan(private val color: Int = Ink.MARKER) : CharacterStyle(), UpdateAppearance, SlateSpan {
    override fun updateDrawState(tp: TextPaint) {
        tp.color = color
    }
}

class SizeSpan(private val px: Int) : CharacterStyle(), UpdateAppearance, SlateSpan {
    override fun updateDrawState(tp: TextPaint) {
        tp.textSize = px.toFloat()
    }
}

class WeightSpan(private val style: Int) : CharacterStyle(), UpdateAppearance, SlateSpan {
    override fun updateDrawState(tp: TextPaint) {
        val old = tp.typeface
        // Combine with whatever weight is already in force, so italic inside a
        // heading comes out bold italic rather than replacing the bold. Resolved
        // to a named constant because Typeface.create takes one of the four,
        // not an arbitrary combination of bits.
        val combined = (old?.style ?: Typeface.NORMAL) or style
        val bold = combined and Typeface.BOLD != 0
        val italic = combined and Typeface.ITALIC != 0
        val want = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        tp.typeface = Typeface.create(old ?: Typeface.DEFAULT, want)
    }
}

class MonoSpan(private val scale: Float = 0.94f) : CharacterStyle(), UpdateAppearance, SlateSpan {
    override fun updateDrawState(tp: TextPaint) {
        tp.typeface = Typeface.MONOSPACE
        tp.textSize = tp.textSize * scale
    }
}

class StrikeSpan : CharacterStyle(), UpdateAppearance, SlateSpan {
    override fun updateDrawState(tp: TextPaint) {
        tp.isStrikeThruText = true
        tp.color = Ink.RULE
    }
}

class LinkTextSpan : CharacterStyle(), UpdateAppearance, SlateSpan {
    override fun updateDrawState(tp: TextPaint) {
        tp.isUnderlineText = true
    }
}

class InlineCodeSpan : CharacterStyle(), UpdateAppearance, SlateSpan {
    override fun updateDrawState(tp: TextPaint) {
        tp.typeface = Typeface.MONOSPACE
        tp.textSize = tp.textSize * 0.92f
        tp.bgColor = Ink.CODE_BG
    }
}

class DimTextSpan : CharacterStyle(), UpdateAppearance, FocusSpan {
    override fun updateDrawState(tp: TextPaint) {
        tp.color = Ink.DIM
    }
}

/** Fenced-code slab: light fill plus a hard left rule that survives dithering. */
class CodeBlockSpan(private val pad: Int, private val bar: Int) :
    LineBackgroundSpan, SlateSpan {
    override fun drawBackground(
        canvas: Canvas, paint: Paint, left: Int, right: Int,
        top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, lineNumber: Int
    ) {
        val old = paint.color
        paint.color = Ink.CODE_BG
        canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
        paint.color = Ink.TEXT
        canvas.drawRect(
            left.toFloat(), top.toFloat(), (left + bar).toFloat(), bottom.toFloat(), paint
        )
        paint.color = old
    }
}

/** Horizontal rule drawn across the line whose `---` text is hidden. */
class RuleSpan(private val thickness: Int) : LineBackgroundSpan, SlateSpan {
    override fun drawBackground(
        canvas: Canvas, paint: Paint, left: Int, right: Int,
        top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, lineNumber: Int
    ) {
        val old = paint.color
        paint.color = Ink.RULE
        val y = (top + bottom) / 2f
        canvas.drawRect(left.toFloat(), y, right.toFloat(), y + thickness, paint)
        paint.color = old
    }
}

/** Blockquote: indent plus a vertical bar in the margin. */
class QuoteSpan(private val indent: Int, private val bar: Int, private val gap: Int) :
    LeadingMarginSpan, SlateSpan {

    override fun getLeadingMargin(first: Boolean): Int = indent

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?
    ) {
        val old = p.color
        val oldStyle = p.style
        p.color = Ink.QUOTE_BAR
        p.style = Paint.Style.FILL
        c.drawRect(
            (x + gap).toFloat(), top.toFloat(), (x + gap + bar * dir).toFloat(), bottom.toFloat(), p
        )
        p.color = old
        p.style = oldStyle
    }
}

/** Hanging indent so wrapped list lines line up under the text, not the bullet. */
class HangingIndentSpan(private val first: Int, private val rest: Int) :
    LeadingMarginSpan, SlateSpan {
    override fun getLeadingMargin(firstLine: Boolean): Int = if (firstLine) first else rest
    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, firstLine: Boolean, layout: Layout?
    ) = Unit
}

/**
 * Draws a glyph in place of a list marker while reserving the marker's width,
 * so `- ` reads as a real bullet without the file ever containing one.
 */
class GlyphSpan(
    private val glyph: String,
    private val widthEm: Float = 1.6f,
    private val color: Int? = null
) : ReplacementSpan(), SlateSpan {

    /**
     * Always reserves the same width, whether it is drawing a bullet or the raw
     * `- ` revealed under the caret. If the two differed, every line would jump
     * sideways as the caret arrived — on E Ink, a full repaint of that line for
     * no reason.
     */
    override fun getSize(
        paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?
    ): Int = Math.max(
        Math.round(paint.textSize * widthEm),
        Math.round(paint.measureText(glyph))
    )

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint
    ) {
        val old = paint.color
        if (color != null) paint.color = color
        canvas.drawText(glyph, x, y.toFloat(), paint)
        paint.color = old
    }
}

/**
 * Draws a picture in place of its `![alt](path)` source. Until the decode
 * finishes — or if it never can — it draws a labelled frame the same shape, so
 * the page does not jump when the image arrives.
 */
class ImageSpan(
    private val bitmap: android.graphics.Bitmap?,
    private val maxWidth: Int,
    private val alt: String,
    private val broken: Boolean,
    private val bodyPx: Float
) : ReplacementSpan(), SlateSpan {

    private fun drawnWidth(): Int =
        if (bitmap != null) minOf(bitmap.width, maxWidth) else maxWidth

    private fun drawnHeight(): Int = if (bitmap != null) {
        val w = drawnWidth()
        Math.max(1, Math.round(bitmap.height * (w.toFloat() / bitmap.width)))
    } else {
        Math.round(bodyPx * 3f)
    }

    override fun getSize(
        paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?
    ): Int {
        val h = drawnHeight()
        if (fm != null) {
            // The whole picture hangs above the baseline, with a little air.
            val pad = Math.round(bodyPx * 0.35f)
            fm.ascent = -h - pad
            fm.top = fm.ascent
            fm.descent = pad
            fm.bottom = fm.descent
        }
        return drawnWidth()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint
    ) {
        val w = drawnWidth()
        val h = drawnHeight()
        val pad = Math.round(bodyPx * 0.35f)
        val t = (y - h - pad).toFloat()

        if (bitmap != null) {
            val dst = android.graphics.RectF(x, t, x + w, t + h)
            canvas.drawBitmap(bitmap, null, dst, null)
            return
        }

        val old = paint.color
        val oldStyle = paint.style
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Math.max(1f, bodyPx * 0.06f)
        paint.color = Ink.RULE
        canvas.drawRect(x, t, x + w, t + h, paint)

        paint.style = Paint.Style.FILL
        paint.color = if (broken) Ink.RULE else Ink.MARKER
        val label = when {
            broken && alt.isNotBlank() -> "$alt — image not found"
            broken -> "image not found"
            alt.isNotBlank() -> alt
            else -> "loading…"
        }
        val size = paint.textSize
        paint.textSize = bodyPx * 0.8f
        canvas.drawText(label, x + bodyPx * 0.5f, t + h / 2f, paint)
        paint.textSize = size

        paint.color = old
        paint.style = oldStyle
    }
}

/**
 * A whole rendered table row, drawn in place of its pipe syntax.
 *
 * One span per row rather than one per cell: a cell can be empty, and a
 * zero-length span cannot be attached, so per-cell spans would fail on exactly
 * the tables people write by hand. This also draws the column rules, which keeps
 * the grid aligned with the text that sits inside it.
 */
class TableRowSpan(
    private val cells: List<String>,
    private val widths: IntArray,
    private val aligns: IntArray,
    private val header: Boolean,
    private val pad: Float,
    private val rule: Int,
    private val firstRow: Boolean,
    private val lastRow: Boolean
) : ReplacementSpan(), SlateSpan {

    private fun total(): Int = widths.sum()

    override fun getSize(
        paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?
    ): Int {
        if (fm != null) {
            // Reset from the paint before adjusting. getSize may be called more
            // than once for the same line, and adjusting the incoming metrics in
            // place would add the padding again every time, so the rows would
            // creep further apart the longer the table was on screen.
            paint.getFontMetricsInt(fm)
            val extra = Math.round(pad)
            fm.ascent -= extra
            fm.top = fm.ascent
            fm.descent += extra
            fm.bottom = fm.descent
        }
        return total()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint
    ) {
        val face = paint.typeface
        val oldColour = paint.color
        val oldStyle = paint.style

        // Rules first, so text sits on top of them.
        paint.style = Paint.Style.FILL
        paint.color = Ink.RULE
        var edge = x
        for (w in widths) {
            canvas.drawRect(edge, top.toFloat(), edge + rule, bottom.toFloat(), paint)
            edge += w
        }
        canvas.drawRect(edge - rule, top.toFloat(), edge, bottom.toFloat(), paint)
        if (firstRow) {
            canvas.drawRect(x, top.toFloat(), edge, top + rule.toFloat(), paint)
        }
        if (lastRow) {
            canvas.drawRect(x, bottom - rule.toFloat(), edge, bottom.toFloat(), paint)
        }

        paint.color = oldColour
        if (header) paint.typeface = Typeface.create(face ?: Typeface.DEFAULT, Typeface.BOLD)

        var cx = x
        for (i in widths.indices) {
            val w = widths[i]
            val label = cells.getOrElse(i) { "" }
            if (label.isNotEmpty()) {
                val avail = w - pad * 2
                val shown = ellipsise(label, paint, avail)
                val tw = paint.measureText(shown)
                val dx = when (aligns.getOrElse(i) { ALIGN_LEFT }) {
                    ALIGN_RIGHT -> w - pad - tw
                    ALIGN_CENTER -> (w - tw) / 2f
                    else -> pad
                }
                canvas.drawText(shown, cx + dx, y.toFloat(), paint)
            }
            cx += w
        }

        paint.typeface = face
        paint.style = oldStyle
    }

    private fun ellipsise(s: String, paint: Paint, avail: Float): String {
        if (avail <= 0f) return ""
        if (paint.measureText(s) <= avail) return s
        var n = s.length
        while (n > 0 && paint.measureText(s.substring(0, n) + "…") > avail) n--
        return if (n <= 0) "" else s.substring(0, n) + "…"
    }

    companion object {
        const val ALIGN_LEFT = 0
        const val ALIGN_CENTER = 1
        const val ALIGN_RIGHT = 2
    }
}

/**
 * Collapses a table's `| --- | --- |` row to the rule under the header. The row
 * still exists in the file; it just stops taking a line's worth of height.
 */
class TableDividerSpan(
    private val widths: IntArray,
    private val rule: Int
) : ReplacementSpan(), SlateSpan {

    override fun getSize(
        paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?
    ): Int {
        if (fm != null) {
            val h = rule * 3
            fm.ascent = -h
            fm.top = fm.ascent
            fm.descent = 0
            fm.bottom = 0
        }
        return widths.sum()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint
    ) {
        val old = paint.color
        val oldStyle = paint.style
        paint.style = Paint.Style.FILL
        paint.color = Ink.TEXT

        val width = widths.sum()
        canvas.drawRect(x, top.toFloat(), x + width, top + rule * 1.6f, paint)

        paint.color = Ink.RULE
        var edge = x
        for (w in widths) {
            canvas.drawRect(edge, top.toFloat(), edge + rule, bottom.toFloat(), paint)
            edge += w
        }
        canvas.drawRect(edge - rule, top.toFloat(), edge, bottom.toFloat(), paint)

        paint.color = old
        paint.style = oldStyle
    }
}

/** Extra breathing room above a block, in the spirit of Typora's spacing. */
class SpaceAboveSpan(private val px: Int) : android.text.style.LineHeightSpan, SlateSpan {
    override fun chooseHeight(
        text: CharSequence, start: Int, end: Int,
        spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt
    ) {
        fm.ascent -= px
        fm.top -= px
    }
}
