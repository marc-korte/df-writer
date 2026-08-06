package com.dfwriter.slate

import android.text.SpannableStringBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Exercises the live-preview engine against a real Android text buffer. This is
 * the part that would break silently on the device: spans are invisible in the
 * file, so a mistake here shows up only as text that looks wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class StylerTest {

    private lateinit var prefs: Prefs
    private lateinit var styler: MarkdownStyler

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        prefs = Prefs(ctx)
        Scale.init(ctx, prefs)
        styler = MarkdownStyler(prefs)
        styler.overrideBodyPx = 40f
    }

    private fun style(src: String, caret: Int = -1): SpannableStringBuilder {
        val sb = SpannableStringBuilder(src)
        styler.restyleAll(sb, caret)
        return sb
    }

    private inline fun <reified T> spansOn(
        sb: SpannableStringBuilder, from: Int, to: Int
    ): List<T> = sb.getSpans(from, to, T::class.java).toList()

    // ------------------------------------------------- the defining trick

    @Test
    fun `heading markers are hidden when the caret is elsewhere`() {
        val src = "# Title\n\nbody"
        val sb = style(src, caret = src.length)   // caret is down in "body"

        // "# " collapses to nothing…
        assertTrue(
            "expected the hash marker to be hidden",
            spansOn<HiddenSpan>(sb, 0, 2).isNotEmpty()
        )
        // …while the heading itself is enlarged and bold.
        assertTrue(spansOn<SizeSpan>(sb, 2, 7).isNotEmpty())
        assertTrue(spansOn<WeightSpan>(sb, 2, 7).isNotEmpty())
        // The buffer is untouched, so the file on disk stays plain Markdown.
        assertEquals(src, sb.toString())
    }

    @Test
    fun `heading markers reappear in grey when the caret enters the line`() {
        val src = "# Title\n\nbody"
        val sb = style(src, caret = 3)            // caret inside the heading

        assertTrue(
            "markers must be revealed on the caret's line",
            spansOn<MarkerSpan>(sb, 0, 2).isNotEmpty()
        )
        assertTrue(spansOn<HiddenSpan>(sb, 0, 2).isEmpty())
    }

    @Test
    fun `emphasis markers hide but the emphasis itself is applied`() {
        val src = "a **bold** and *italic* here"
        val sb = style(src, caret = -1)

        val boldOpen = src.indexOf("**")
        val boldClose = src.indexOf("**", boldOpen + 2)
        assertTrue(spansOn<HiddenSpan>(sb, boldOpen, boldOpen + 2).isNotEmpty())
        assertTrue(spansOn<HiddenSpan>(sb, boldClose, boldClose + 2).isNotEmpty())
        assertTrue(spansOn<WeightSpan>(sb, boldOpen, boldClose + 2).isNotEmpty())

        val itOpen = src.indexOf("*italic*")
        assertTrue(spansOn<WeightSpan>(sb, itOpen, itOpen + 8).isNotEmpty())
    }

    @Test
    fun `inline code wins over emphasis inside it`() {
        val src = "run `a * b * c` now"
        val sb = style(src)
        val open = src.indexOf('`')
        assertTrue(spansOn<InlineCodeSpan>(sb, open, src.indexOf('`', open + 1) + 1).isNotEmpty())
        // The asterisks inside the code span must not have been eaten as italics.
        assertEquals(src, sb.toString())
    }

    // ----------------------------------------------------------- blocks

    @Test
    fun `a bullet marker is replaced by a drawn glyph`() {
        val src = "- first\n- second"
        val sb = style(src)
        assertTrue(spansOn<GlyphSpan>(sb, 0, 2).isNotEmpty())
        assertTrue(spansOn<HangingIndentSpan>(sb, 0, 8).isNotEmpty())
    }

    @Test
    fun `task items get a checkbox and completed ones are struck through`() {
        val src = "- [x] done\n- [ ] todo"
        val sb = style(src)
        assertTrue(spansOn<GlyphSpan>(sb, 0, 6).isNotEmpty())
        assertTrue(
            "a finished task should be struck through",
            spansOn<StrikeSpan>(sb, 6, 10).isNotEmpty()
        )
    }

    @Test
    fun `blockquotes get a margin bar`() {
        val sb = style("> quoted line")
        assertTrue(spansOn<QuoteSpan>(sb, 0, 13).isNotEmpty())
    }

    @Test
    fun `a horizontal rule is drawn and its dashes hidden`() {
        val sb = style("before\n\n---\n\nafter")
        val at = "before\n\n".length
        assertTrue(spansOn<RuleSpan>(sb, at, at + 3).isNotEmpty())
        assertTrue(spansOn<HiddenSpan>(sb, at, at + 3).isNotEmpty())
    }

    @Test
    fun `fenced code is monospaced across every line of the block`() {
        val src = "text\n\n```kotlin\nval a = 1\nval b = 2\n```\n\nafter"
        val sb = style(src)
        val inside = src.indexOf("val a")
        assertTrue(spansOn<MonoSpan>(sb, inside, inside + 5).isNotEmpty())
        assertTrue(spansOn<CodeBlockSpan>(sb, inside, inside + 5).isNotEmpty())

        // Text after the closing fence must be ordinary prose again.
        val after = src.indexOf("after")
        assertTrue(spansOn<MonoSpan>(sb, after, after + 5).isEmpty())
    }

    @Test
    fun `a tilde fence closes as reliably as a backtick one`() {
        // The restyle range is only widened to the end of the document when the
        // edit touches a fence. Recognising only backticks left every line after
        // a ~~~ fence styled with a stale in-fence state.
        val src = "before\n\n~~~\nfenced line\n~~~\n\nafter"
        val sb = SpannableStringBuilder(src)
        // Restyle a range confined to the fence line, as a keystroke there would.
        val at = src.indexOf("~~~")
        styler.restyleRange(sb, at, at + 3, at + 1)

        val inside = src.indexOf("fenced")
        assertTrue(
            "content between tilde fences should be monospaced",
            spansOn<MonoSpan>(sb, inside, inside + 6).isNotEmpty()
        )
        val after = src.indexOf("after")
        assertTrue(
            "text past the closing tilde fence must be ordinary prose",
            spansOn<MonoSpan>(sb, after, after + 5).isEmpty()
        )
    }

    @Test
    fun `a hash inside a fence is not turned into a heading`() {
        val src = "```\n# not a heading\n```"
        val sb = style(src)
        val hash = src.indexOf('#')
        assertTrue(spansOn<SizeSpan>(sb, hash, hash + 3).isEmpty())
    }

    // ------------------------------------------------------------ focus

    @Test
    fun `focus mode dims every paragraph but the caret's`() {
        prefs.focusMode = true
        val src = "first para\n\nsecond para\n\nthird para"
        val sb = SpannableStringBuilder(src)
        val caret = src.indexOf("second") + 2
        styler.restyleAll(sb, caret)
        styler.applyFocus(sb, caret)

        assertTrue(spansOn<DimTextSpan>(sb, 0, 10).isNotEmpty())
        assertTrue(spansOn<DimTextSpan>(sb, src.indexOf("third"), src.length).isNotEmpty())
        val s = src.indexOf("second")
        assertTrue(
            "the caret's own paragraph must stay black",
            spansOn<DimTextSpan>(sb, s, s + 6).isEmpty()
        )

        prefs.focusMode = false
        styler.applyFocus(sb, caret)
        assertTrue(spansOn<DimTextSpan>(sb, 0, src.length).isEmpty())
    }

    // --------------------------------------------------------- behaviour

    @Test
    fun `source mode shows every marker`() {
        prefs.sourceMode = true
        val sb = style("# Title\n\n**bold**", caret = -1)
        assertTrue(
            "raw source mode must not hide anything",
            spansOn<HiddenSpan>(sb, 0, sb.length).isEmpty()
        )
        prefs.sourceMode = false
    }

    @Test
    fun `restyling a range does not disturb text outside it`() {
        val src = "# One\n\nbody text\n\n## Two"
        val sb = SpannableStringBuilder(src)
        styler.restyleAll(sb, -1)
        val before = sb.toString()

        val at = src.indexOf("body")
        styler.restyleRange(sb, at, at + 4, at)
        assertEquals(before, sb.toString())
        // The far heading keeps its styling through a local restyle.
        val two = src.indexOf("## Two")
        assertTrue(spansOn<SizeSpan>(sb, two + 3, two + 6).isNotEmpty())
    }

    @Test
    fun `every keystroke position in a mixed document is safe`() {
        // Guards against index errors in the parser: style once per caret
        // position across a document using every construct at once.
        val src = """
            # Title

            Some **bold**, *italic*, ~~struck~~ and `code`.

            - [ ] a task
            - a bullet
              - nested

            > quoted

            ```
            fenced code
            ```

            | A | B |
            | --- | --- |
            | 1 | 2 |

            ---

            [link](http://example.com) and ![img](x.png)
        """.trimIndent()

        for (caret in 0..src.length) {
            val sb = SpannableStringBuilder(src)
            styler.restyleAll(sb, caret)
            styler.applyFocus(sb, caret)
            assertEquals("buffer changed at caret $caret", src, sb.toString())
        }
    }

    @Test
    fun `incremental edits stay consistent with a full restyle`() {
        val sb = SpannableStringBuilder()
        val typed = "# Heading\nsome **bold** words\n- item one\n"
        for (c in typed) {
            val at = sb.length
            sb.append(c)
            styler.restyleRange(sb, at, sb.length, sb.length)
        }
        val incremental = countSpans(sb)

        val fresh = SpannableStringBuilder(typed)
        styler.restyleAll(fresh, typed.length)
        val full = countSpans(fresh)

        assertEquals("typing must converge on the same styling", full, incremental)
    }

    private fun countSpans(sb: SpannableStringBuilder): Map<String, Int> =
        sb.getSpans(0, sb.length, SlateSpan::class.java)
            .groupingBy { it.javaClass.simpleName }
            .eachCount()

    @Test
    fun `styling a large document is fast enough to run on every keystroke`() {
        val doc = buildString {
            repeat(300) {
                append("## Section $it\n\nSome **bold** and `code` text here.\n\n- a\n- b\n\n")
            }
        }
        val sb = SpannableStringBuilder(doc)
        styler.restyleAll(sb, 0)

        // The realistic case: one keystroke in the middle of a long document.
        val at = doc.length / 2
        val started = System.nanoTime()
        repeat(20) { styler.restyleRange(sb, at, at + 1, at) }
        val perEdit = (System.nanoTime() - started) / 20 / 1_000_000.0
        assertTrue("a single edit restyle took ${perEdit}ms", perEdit < 60.0)
    }

    @Test
    fun `an empty document and a document of newlines do not throw`() {
        assertFalse(styleThrows(""))
        assertFalse(styleThrows("\n"))
        assertFalse(styleThrows("\n\n\n"))
        assertFalse(styleThrows("#"))
        assertFalse(styleThrows("```"))
        assertFalse(styleThrows("- "))
        assertFalse(styleThrows("> "))
        assertFalse(styleThrows("***"))
    }

    private fun styleThrows(src: String): Boolean = try {
        for (caret in -1..src.length) {
            val sb = SpannableStringBuilder(src)
            styler.restyleAll(sb, caret)
        }
        false
    } catch (e: Throwable) {
        e.printStackTrace(); true
    }
}
