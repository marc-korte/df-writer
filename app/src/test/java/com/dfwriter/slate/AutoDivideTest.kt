package com.dfwriter.slate

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/**
 * A document long enough to drag divides itself, and the writer is meant not to
 * notice: same words, same place in them, nothing to confirm.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AutoDivideTest {

    private lateinit var prefs: Prefs
    private lateinit var lib: File
    private lateinit var doc: File

    private val sentence = "The morning began badly and it went on from there, as mornings will. "

    private fun novel(words: Int): String {
        val sb = StringBuilder("# The Long Road\n\nAn opening note.\n\n")
        var w = 0
        var ch = 1
        while (w < words) {
            sb.append("## Chapter ").append(ch).append("\n\n")
            repeat(20) { sb.append(sentence.repeat(8)).append("\n\n"); w += 96 }
            ch++
        }
        return sb.toString()
    }

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        prefs = Prefs(ctx)
        lib = File(ctx.cacheDir, "lib-${System.nanoTime()}").apply { mkdirs() }
        prefs.libraryPath = lib.absolutePath
        prefs.sourceMode = false
        doc = File(lib, "novel.md")
        doc.writeText(novel(60_000))
        prefs.lastFile = doc.absolutePath
        prefs.lastCaret = 0
        File(ctx.filesDir, "scratch.md").delete()
        File(ctx.filesDir, "scratch.path").delete()
    }

    private fun start(): MainActivity {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return a
    }

    /**
     * Lets the autosave tick come round, which is when the check happens —
     * and walks the division's hops: the plan is made on the save thread,
     * applied on the main one, and the rest of the book swept behind it.
     */
    private fun tick(a: MainActivity) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        repeat(2) {
            a.drainSaves()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun editorOf(a: MainActivity): MarkdownEditor {
        fun find(v: View): MarkdownEditor? = when {
            v is MarkdownEditor -> v
            v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return find(a.window.decorView)!!
    }

    private fun statusTexts(a: MainActivity): List<String> {
        val out = ArrayList<String>()
        fun walk(v: View) {
            if (v is android.widget.TextView && v.isShown) out.add(v.text.toString())
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(a.window.decorView)
        return out
    }

    @Test
    fun `a long document divides itself and keeps every word`() {
        val original = doc.readText()
        val a = start()
        tick(a)

        val folder = File(lib, "novel")
        assertTrue("the piece should have been divided", folder.isDirectory)
        val parts = Manuscript.chapters(folder)
        assertTrue("expected several parts, got ${parts.size}", parts.size >= 2)

        assertEquals(
            "the parts on disk must be the book, character for character",
            original, parts.joinToString("") { it.readText() }
        )
        assertTrue("the original must be kept", File(lib, "novel.md.bak").isFile)
        assertFalse("and no longer look like a document", doc.exists())
        editorOf(a)
    }

    @Test
    fun `the writer is left at the start, where the caret was`() {
        val a = start()
        val editor = editorOf(a)
        editor.setSelection(0)
        shadowOf(Looper.getMainLooper()).idle()

        tick(a)

        val now = editorOf(a)
        val parts = Manuscript.chapters(File(lib, "novel"))
        assertTrue(
            "expected to be left in the first part, showing: ${now.text.take(40)}",
            now.text.startsWith(parts.first().readText().take(40))
        )
        assertEquals("the caret should not have moved", 0, now.selectionStart)
    }

    @Test
    fun `a caret deep in the book lands in the part that holds it`() {
        val a = start()
        val editor = editorOf(a)
        val whole = editor.documentText()
        // Somewhere in the last third, at a word boundary. Global offsets:
        // under the paged buffer the Editable holds only a page of the book.
        val at = whole.indexOf("## Chapter", (whole.length * 0.8).toInt())
        editor.setSelectionGlobal(at)
        shadowOf(Looper.getMainLooper()).idle()

        tick(a)

        val now = editorOf(a)
        val around = now.documentText()
            .substring(now.globalSelectionStart().coerceIn(0, now.docLength()))
            .take(12)
        assertTrue(
            "expected to land on the same chapter, landed on: $around",
            around.startsWith("## Chapter")
        )
    }

    @Test
    fun `the whole piece is counted, not just the part on screen`() {
        val original = doc.readText()
        val a = start()
        tick(a)

        val expected = DocStore.countWords(original)
        assertTrue(
            "the status bar should show the whole book, saw ${statusTexts(a)}",
            statusTexts(a).any { it.contains("$expected words") }
        )
    }

    @Test
    fun `a divided piece is not divided again`() {
        val a = start()
        tick(a)
        val after = Manuscript.chapters(File(lib, "novel")).size
        tick(a)
        tick(a)
        assertEquals(
            "the parts should be left alone once made",
            after, Manuscript.chapters(File(lib, "novel")).size
        )
        editorOf(a)
    }

    @Test
    fun `the contents drawer covers the whole piece, not just the open part`() {
        val a = start()
        tick(a)
        val parts = Manuscript.chapters(File(lib, "novel"))
        assertTrue("this test needs a divided piece", parts.size >= 2)

        a.dispatchKeyEvent(
            android.view.KeyEvent(
                0, 0, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_T, 0,
                android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON
            )
        )
        shadowOf(Looper.getMainLooper()).idle()

        val shown = statusTexts(a)
        for (p in parts) {
            assertTrue(
                "the drawer left out ${p.nameWithoutExtension}, showing $shown",
                shown.contains(p.nameWithoutExtension)
            )
        }
        assertTrue(
            "chapters from the other parts should be listed too",
            shown.count { it.startsWith("Chapter ") } > 5
        )
    }

    @Test
    fun `exporting a divided piece exports the whole book`() {
        val original = doc.readText()
        val a = start()
        tick(a)
        val parts = Manuscript.chapters(File(lib, "novel"))
        assertTrue("this test needs a divided piece", parts.size >= 2)

        // Ctrl Shift M — export to HTML — from inside the first part.
        a.dispatchKeyEvent(
            android.view.KeyEvent(
                0, 0, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_M, 0,
                android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON or
                        android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_SHIFT_LEFT_ON
            )
        )
        shadowOf(Looper.getMainLooper()).idle()
        // The page is written on the save thread; wait the way a save is waited for.
        a.drainSaves()
        shadowOf(Looper.getMainLooper()).idle()

        val html = File(File(lib, "Exports"), "novel.html")
        assertTrue("nothing was exported, library holds ${lib.list()?.toList()}", html.isFile)
        val text = html.readText()

        // The last chapter of the last part is the proof: it is nowhere in the
        // part that was open when the export ran.
        val lastChapter = Regex("## (Chapter \\d+)")
            .findAll(parts.last().readText()).last().groupValues[1]
        assertFalse(
            "this test proves nothing if the open part already held $lastChapter",
            parts.first().readText().contains(lastChapter)
        )
        assertTrue(
            "the export stopped at the open part: $lastChapter is missing",
            text.contains(lastChapter)
        )

        // And every chapter of the book, in order.
        val exported = Regex("Chapter (\\d+)").findAll(text).map { it.groupValues[1].toInt() }.toList()
        val whole = Regex("## Chapter (\\d+)").findAll(original).map { it.groupValues[1].toInt() }.toList()
        assertEquals("the export must hold every chapter, in reading order", whole, exported)
    }

    @Test
    fun `nothing is exported from parts that could not be brought up to date`() {
        val a = start()
        tick(a)
        val folder = File(lib, "novel")
        assertTrue("this test needs a divided piece", Manuscript.chapters(folder).size >= 2)

        // A card that will not take the save: the parts on it are now older
        // than what is on screen.
        org.junit.Assume.assumeTrue(
            "this test needs a folder that can be made read-only",
            folder.setWritable(false) &&
                    !runCatching { File(folder, "probe.tmp").createNewFile() }.getOrDefault(false)
        )
        try {
            val editor = editorOf(a)
            editor.text.insert(0, "A line the card will never hold.\n\n")
            shadowOf(Looper.getMainLooper()).idle()

            a.dispatchKeyEvent(
                android.view.KeyEvent(
                    0, 0, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_M, 0,
                    android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON or
                            android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_SHIFT_LEFT_ON
                )
            )
            shadowOf(Looper.getMainLooper()).idle()
            a.drainSaves()
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse(
                "an export written from stale parts would be missing the newest work",
                File(File(lib, "Exports"), "novel.html").isFile
            )
        } finally {
            folder.setWritable(true)
        }

        // The same keystroke, once the card will take the save: this is what
        // says the export was stopped by the failure and not by the test.
        a.dispatchKeyEvent(
            android.view.KeyEvent(
                0, 0, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_M, 0,
                android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON or
                        android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_SHIFT_LEFT_ON
            )
        )
        shadowOf(Looper.getMainLooper()).idle()
        a.drainSaves()
        shadowOf(Looper.getMainLooper()).idle()
        val html = File(File(lib, "Exports"), "novel.html")
        assertTrue("the export should go through once the save can", html.isFile)
        assertTrue(
            "and it should hold the line that could not be saved before",
            html.readText().contains("A line the card will never hold")
        )
    }

    @Test
    fun `the rest of the book divides in the background`() {
        // A book already divided under an older, larger limit: the open part
        // converts in place, and every closed part must follow on its own,
        // without being visited — walking into a chapter must never cost the
        // stall of dividing it first.
        doc.delete()
        val folder = File(lib, "novel").apply { mkdirs() }
        val one = File(folder, "01 One.md").apply { writeText(novel(12_000)) }
        val two = File(folder, "02 Two.md").apply { writeText(novel(12_000)) }
        val whole = one.readText() + two.readText()
        prefs.lastFile = one.absolutePath

        val a = start()
        tick(a)
        tick(a)

        val parts = Manuscript.chapters(folder)
        assertTrue(
            "the open part should have divided in place: ${parts.map { it.name }}",
            parts.count { it.name.startsWith("01") } >= 2
        )
        assertTrue(
            "the closed part should have divided in the background: ${parts.map { it.name }}",
            parts.count { it.name.startsWith("02") } >= 2
        )
        assertEquals(
            "and the book must still be the book, character for character",
            whole, parts.joinToString("") { it.readText() }
        )
    }

    @Test
    fun `switched off, a long document stays one file`() {
        prefs.autoDivideWords = 0
        val a = start()
        tick(a)
        assertTrue("division is off; the file must be left alone", doc.isFile)
        assertFalse(File(lib, "novel").isDirectory)
        editorOf(a)
    }

    @Test
    fun `a short document is left as one file`() {
        doc.writeText(novel(1_500))
        val a = start()
        tick(a)
        assertTrue("a short piece must not be divided", doc.isFile)
        assertFalse(File(lib, "novel").isDirectory)
        editorOf(a)
    }
}
