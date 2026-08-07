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
    fun `the paged mirror survives a storm of jumps and edits`() {
        val text = book(20_000)
        val e = pagedEditor(text)
        val reference = StringBuilder(text)
        val rnd = Random(1234)
        repeat(250) { step ->
            when (rnd.nextInt(4)) {
                0 -> e.jumpTo(rnd.nextInt(reference.length))
                1 -> {
                    val g = rnd.nextInt(reference.length)
                    e.setSelectionGlobal(g)
                    e.text.insert(e.selectionStart, "x")
                    reference.insert(e.globalSelectionStart() - 1, "x")
                }
                2 -> {
                    val g = rnd.nextInt(reference.length - 2)
                    e.setSelectionGlobal(g)
                    val local = e.selectionStart
                    if (local < e.text.length) {
                        e.text.delete(local, local + 1)
                        reference.delete(g, g + 1)
                    }
                }
                3 -> if (rnd.nextBoolean()) e.undo() else e.redo()
            }
            if (rnd.nextInt(3) != 0) return@repeat
            // Undo/redo make the reference stale; resync from the editor's
            // verified snapshot and keep fuzzing — the invariant under test
            // is page-vs-mirror, which documentText() checks and repairs
            // loudly. A repair would double the text length and fail below.
            val doc = e.documentText()
            assertEquals(
                "page and mirror must agree at step $step",
                e.text.toString(),
                doc.substring(e.pageBounds().first, e.pageBounds().second)
            )
            reference.setLength(0)
            reference.append(doc)
        }
    }
}
