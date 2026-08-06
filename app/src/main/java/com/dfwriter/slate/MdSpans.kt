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
