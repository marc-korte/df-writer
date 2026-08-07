package com.dfwriter.slate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * The splice engine: the Editable holds a page of the document, and the page
 * moves. What these tests defend is the document — a page may be cut, moved
 * and rebuilt at any moment, and not one character of the document may move
 * with it unless the writer typed it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SpliceTest {

    private fun book(words: Int): String {
        val sb = StringBuilder("# The Book\n\n")
        val sentence = "The morning began badly and it went on from there, as mornings will. "
        var w = 0
        var ch = 1
        while (w < words) {
            sb.append("## Chapter ").append(ch).append("\n\n")
            repeat(10) { sb.append(sentence.repeat(6)).append("\n\n"); w += 78 }
            if (ch % 5 == 3) {
                sb.append("```python\n\n# a comment\n\nvalue = ").append(ch).append("\n\n```\n\n")
                w += 6
            }
            ch++
        }
        return sb.toString()
    }

    private fun pagedEditor(text: String): MarkdownEditor {
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = Prefs(ctx)
        prefs.pagedBuffer = true
        Scale.init(ctx, prefs)
        val e = MarkdownEditor(ctx)
        e.bind(prefs, MarkdownStyler(prefs))
        e.setText(text)
        e.layout(0, 0, 1600, 1200)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return e
    }

    @Test
    fun `a deep selection narrows the page and loses nothing`() {
        val text = book(20_000)
        val e = pagedEditor(text)
        e.setSelectionGlobal(text.length / 2)
        val (from, to) = e.pageBounds()
        assertTrue("the page should be a slice, was [$from..$to) of ${text.length}",
            to - from < text.length / 2)
        assertTrue("the page must hold the caret",
            e.globalSelectionStart() in from..to)
        assertEquals("the caret must land where it was sent",
            text.length / 2, e.globalSelectionStart())
        assertEquals("the document must be untouched", text, e.documentText())
    }

    @Test
    fun `typing in a page edits the document at the right place`() {
        val text = book(20_000)
        val e = pagedEditor(text)
        val at = text.indexOf("## Chapter 9")
        e.setSelectionGlobal(at)
        e.text.insert(e.selectionStart, "MARKER ")
        val expect = StringBuilder(text).insert(at, "MARKER ").toString()
        assertEquals("the edit must land at the global offset", expect, e.documentText())
    }

    @Test
    fun `page cuts land on blank-line starts outside fences`() {
        val text = book(20_000)
        val e = pagedEditor(text)
        // Jump around a lot; inspect the cut every time.
        val rnd = Random(7)
        repeat(12) {
            e.jumpTo(rnd.nextInt(text.length))
            val (from, to) = e.pageBounds()
            if (from > 0) {
                assertEquals("a cut must sit at a line start",
                    from, MarkdownStyler.lineStartOf(text, from))
                assertTrue("the line before a cut must be blank",
                    text.substring(
                        MarkdownStyler.lineStartOf(text, from - 1), from - 1
                    ).isBlank())
                val fences = text.substring(0, from).lineSequence()
                    .count { it.trimStart().startsWith("```") }
                assertEquals("a cut must not open inside a fence", 0, fences % 2)
            }
            if (to < text.length) {
                assertEquals("the far cut too",
                    to, MarkdownStyler.lineStartOf(text, to))
            }
            assertEquals("and the document never changes", text, e.documentTextRaw())
        }
    }

    @Test
    fun `a page opening mid-book styles a fence it cannot see the start of`() {
        // A fence long enough to force a cut inside it would be skipped by the
        // cut chooser; parity is exercised the other way — cut ABOVE a fence,
        // then verify the page still styles its interior as code.
        val text = book(20_000)
        val e = pagedEditor(text)
        val inside = text.indexOf("value = 3")
        e.jumpTo(inside)
        val (from, _) = e.pageBounds()
        val local = inside - from
        assertTrue("the fenced body must style as code in the paged view",
            e.text.getSpans(local, local + 5, CodeBlockSpan::class.java).isNotEmpty())
    }

    @Test
    fun `undo reaches a change the page has moved away from`() {
        val text = book(20_000)
        val e = pagedEditor(text)
        val at = text.indexOf("## Chapter 3")
        e.setSelectionGlobal(at)
        e.text.insert(e.selectionStart, "EDIT")
        // Walk far away — the page follows.
        e.jumpTo(text.length - 100)
        assertTrue("the page should have moved off the edit",
            at < e.pageBounds().first)
        assertTrue("undo must still work", e.undo())
        assertEquals("and put the document back", text, e.documentText())
        assertTrue("with the caret back at the edit",
            e.globalSelectionStart() in at..(at + 4))
    }

    @Test
    fun `undo reaches a mid-sized change bigger than the page tail`() {
        // Between the tail budget and the whole page budget: too big to fit
        // behind the caret after a splice, small enough that the old code
        // routed it through the splice path anyway — and wiped both stacks.
        val text = book(20_000)
        val e = pagedEditor(text)
        val at = text.indexOf("## Chapter 3")
        e.setSelectionGlobal(at)
        e.text.insert(
            e.selectionStart,
            // Paragraph-shaped, so a page cut can legally land inside it —
            // one unbroken run would just stretch the page around itself.
            ("x".repeat(60) + "\n\n").repeat(80)
        )
        e.jumpTo(text.length - 100)
        assertTrue("undo must survive a mid-sized change", e.undo())
        assertEquals("and put the document back", text, e.documentText())
    }

    @Test
    fun `turning paging off restores the whole document to the page`() {
        val text = book(20_000)
        val e = pagedEditor(text)
        e.setSelectionGlobal(text.length / 2)
        assertTrue("this test needs a real slice", e.pageBounds().first > 0)
        val caretG = e.globalSelectionStart()

        Prefs(RuntimeEnvironment.getApplication()).pagedBuffer = false
        e.onPagedPreferenceChanged()

        assertEquals("the page must be the whole document again",
            0 to text.length, e.pageBounds())
        assertEquals("the Editable must hold all of it", text, e.text.toString())
        assertEquals("the caret must not move", caretG, e.globalSelectionStart())
        assertEquals("and the document must be intact", text, e.documentText())
    }

    @Test
    fun `a save with paging freshly off must not truncate the document`() {
        // The setting can be flipped while the page is still a slice — the
        // repair logic must key on the page's geometry, not the preference,
        // or the next save writes one page over the whole book.
        val text = book(20_000)
        val e = pagedEditor(text)
        e.setSelectionGlobal(text.length / 2)
        assertTrue(e.pageBounds().first > 0)
        Prefs(RuntimeEnvironment.getApplication()).pagedBuffer = false
        assertEquals("documentText must survive the stale slice", text, e.documentText())
    }

    @Test
    fun `the paged mirror survives a storm of jumps and edits`() {
        val text = book(20_000)
        val e = pagedEditor(text)
        // The reference exists only to bound the random positions; the
        // invariant under test is page-vs-mirror, read RAW — the repairing
        // snapshot would paper over exactly the drift this hunts. Any repair
        // firing at all is a failure.
        var repaired = false
        e.onMirrorRepair = { repaired = true }
        val reference = StringBuilder(text)
        val rnd = Random(1234)
        repeat(250) { step ->
            when (rnd.nextInt(4)) {
                0 -> e.jumpTo(rnd.nextInt(reference.length))
                1 -> {
                    val g = rnd.nextInt(reference.length)
                    e.setSelectionGlobal(g)
                    e.text.insert(e.selectionStart, "x")
                }
                2 -> {
                    val g = rnd.nextInt(reference.length - 2)
                    e.setSelectionGlobal(g)
                    val local = e.selectionStart
                    if (local < e.text.length) e.text.delete(local, local + 1)
                }
                3 -> if (rnd.nextBoolean()) e.undo() else e.redo()
            }
            if (rnd.nextInt(3) != 0) return@repeat
            val raw = e.documentTextRaw()
            assertEquals(
                "page and mirror must agree at step $step",
                e.text.toString(),
                raw.substring(e.pageBounds().first, e.pageBounds().second)
            )
            reference.setLength(0)
            reference.append(raw)
        }
        e.documentText()
        assertTrue("no mirror repair may ever fire", !repaired)
    }
}
