package com.dfwriter.slate

import android.text.SpannableStringBuilder
import android.text.TextPaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A page handed to the styler may open in the middle of a fenced code block.
 * The styler cannot see above the page, so the paged editor tells it whether
 * offset 0 is already inside a fence — and everything after must style as if
 * the missing text were there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class FenceParityTest {

    private fun styler(): MarkdownStyler {
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = Prefs(ctx)
        Scale.init(ctx, prefs)
        return MarkdownStyler(prefs).apply {
            overrideBodyPx = 40f
            contentWidthPx = 1200
            measure = TextPaint().apply { textSize = 40f }
        }
    }

    private fun codeSpansOnFirstLine(sb: SpannableStringBuilder): Int =
        sb.getSpans(0, 5, CodeBlockSpan::class.java).size

    @Test
    fun `a page opening mid-fence styles its first lines as code`() {
        val s = styler()
        // What a page cut inside a ``` block looks like: no opening fence in
        // sight, prose-shaped lines that are actually code.
        val sb = SpannableStringBuilder("code_line = 1\n\nmore = 2\n```\n\nProse after.\n")

        s.baseFenceParity = true
        s.restyleAll(sb, -1)
        assertTrue(
            "the first line must style as fence body when parity says so",
            codeSpansOnFirstLine(sb) > 0
        )
        assertEquals(
            "and the prose after the closing fence must not",
            0, sb.getSpans(sb.length - 5, sb.length, CodeBlockSpan::class.java).size
        )
    }

    @Test
    fun `parity false is exactly today's behavior`() {
        val s = styler()
        val sb = SpannableStringBuilder("plain prose\n\n```\ninside\n```\n")
        s.baseFenceParity = false
        s.restyleAll(sb, -1)
        assertEquals("prose must not style as code", 0, codeSpansOnFirstLine(sb))
        assertTrue(
            "the fenced body must",
            sb.getSpans(sb.indexOf("inside"), sb.indexOf("inside") + 3, CodeBlockSpan::class.java)
                .isNotEmpty()
        )
    }
}
