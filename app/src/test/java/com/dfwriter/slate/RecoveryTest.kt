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
 * Starting up after text failed to reach disk. The shadow copy is only worth
 * writing if something reads it back, so that path is tested from the outside:
 * arrange the leftovers, start the app, see what it offers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class RecoveryTest {

    private lateinit var prefs: Prefs
    private lateinit var lib: File
    private lateinit var doc: File

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        prefs = Prefs(ctx)
        lib = File(ctx.cacheDir, "lib-${System.nanoTime()}").apply { mkdirs() }
        prefs.libraryPath = lib.absolutePath

        doc = File(lib, "draft.md")
        doc.writeText("what reached the disk")
        prefs.lastFile = doc.absolutePath
        prefs.lastCaret = 0
    }

    private fun leaveScratch(body: String, owner: String?) {
        val ctx = RuntimeEnvironment.getApplication()
        File(ctx.filesDir, "scratch.md").writeText(body)
        File(ctx.filesDir, "scratch.path").writeText(owner ?: "")
    }

    private fun clearScratch() {
        val ctx = RuntimeEnvironment.getApplication()
        File(ctx.filesDir, "scratch.md").delete()
        File(ctx.filesDir, "scratch.path").delete()
    }

    private fun scratchFile() = File(RuntimeEnvironment.getApplication().filesDir, "scratch.md")

    private fun scratchBody(): String? =
        scratchFile().takeIf { it.exists() }?.readText()

    private fun scratchOwner(): String? =
        File(RuntimeEnvironment.getApplication().filesDir, "scratch.path")
            .takeIf { it.exists() }?.readText()

    private fun launch() =
        Robolectric.buildActivity(MainActivity::class.java).setup()
            .also { shadowOf(Looper.getMainLooper()).idle() }

    private fun start(): MainActivity = launch().get()

    private fun find(root: View, match: (View) -> Boolean): View? {
        if (match(root)) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) find(root.getChildAt(i), match)?.let { return it }
        }
        return null
    }

    private fun visibleTexts(a: MainActivity): List<String> {
        val out = ArrayList<String>()
        fun walk(v: View) {
            if (v is android.widget.TextView && v.isShown) out.add(v.text.toString())
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(a.window.decorView)
        return out
    }

    private fun editorOf(a: MainActivity) =
        find(a.window.decorView) { it is MarkdownEditor } as MarkdownEditor

    @Test
    fun `unsaved text from a previous run is offered on startup`() {
        leaveScratch("what reached the disk plus the words that did not", doc.absolutePath)
        val a = start()

        val texts = visibleTexts(a)
        assertTrue(
            "expected a recovery prompt, saw $texts",
            texts.any { it.contains("recovered", ignoreCase = true) }
        )
        assertTrue(texts.any { it.contains("Restore") })
        assertTrue(texts.any { it.contains("Keep what is on disk") })
    }

    @Test
    fun `a clean exit produces no prompt`() {
        // After a normal pause the shadow copy matches the file exactly.
        leaveScratch("what reached the disk", doc.absolutePath)
        val a = start()
        assertFalse(
            "recovery must stay silent when there is nothing to recover",
            visibleTexts(a).any { it.contains("recovered", ignoreCase = true) }
        )
        assertEquals("what reached the disk", editorOf(a).text.toString())
    }

    @Test
    fun `no shadow copy at all produces no prompt`() {
        clearScratch()
        val a = start()
        assertFalse(visibleTexts(a).any { it.contains("recovered", ignoreCase = true) })
    }

    @Test
    fun `a shadow copy from a different document is ignored`() {
        leaveScratch("text belonging to something else", File(lib, "other.md").absolutePath)
        val a = start()
        assertFalse(
            "a draft from another file must not be offered here",
            visibleTexts(a).any { it.contains("recovered", ignoreCase = true) }
        )
        assertEquals("what reached the disk", editorOf(a).text.toString())
    }

    // ------------------------------------------------------- writing it out

    @Test
    fun `pausing leaves a shadow copy of the open document`() {
        clearScratch()
        val c = launch()
        assertFalse("nothing may exist before the pause", scratchFile().exists())

        c.pause()
        shadowOf(Looper.getMainLooper()).idle()

        // Nothing was edited, so the shadow copy can only have come from the
        // pause itself. Without this half there is never anything to recover.
        assertEquals("what reached the disk", scratchBody())
        assertEquals(doc.absolutePath, scratchOwner())
    }

    @Test
    fun `pausing shadows what is on screen rather than what was loaded`() {
        clearScratch()
        val c = launch()
        val editor = editorOf(c.get())
        editor.setText("what reached the disk and then some more")
        shadowOf(Looper.getMainLooper()).idle()

        c.pause()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("what reached the disk and then some more", scratchBody())
        assertEquals(doc.absolutePath, scratchOwner())
        assertEquals(
            "a pause saves as well, so the file must have caught up too",
            "what reached the disk and then some more", doc.readText()
        )
    }

    @Test
    fun `pausing with the offer still unanswered does not destroy the draft`() {
        val recovered = "what reached the disk plus the words that did not"
        leaveScratch(recovered, doc.absolutePath)
        val c = launch()
        assertTrue(
            "the prompt must be up for this to mean anything",
            visibleTexts(c.get()).any { it.contains("recovered", ignoreCase = true) }
        )
        // The editor is showing the text from disk while the prompt waits, so a
        // pause that wrote it through would overwrite the very draft on offer.
        assertEquals("what reached the disk", editorOf(c.get()).text.toString())

        c.pause()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("the offered draft must survive an unanswered pause", recovered, scratchBody())
        assertEquals(doc.absolutePath, scratchOwner())
    }

    @Test
    fun `a save after the prompt is dismissed does not destroy the draft`() {
        val recovered = "what reached the disk plus the words that did not"
        leaveScratch(recovered, doc.absolutePath)
        val a = start()
        assertTrue(
            "the prompt must be up for this to mean anything",
            visibleTexts(a).any { it.contains("recovered", ignoreCase = true) }
        )

        // Dismiss without choosing — the offer is meant to come back next
        // start — then keep writing. The autosave that lands the edit also
        // refreshes the shadow copy on every other day, and this is the one
        // day it must not.
        a.dispatchKeyEvent(
            android.view.KeyEvent(
                0, 0, android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_ESCAPE, 0
            )
        )
        shadowOf(Looper.getMainLooper()).idle()
        editorOf(a).text.insert(0, "More words. ")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        a.drainSaves()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("the file should have taken the edit",
            doc.readText().startsWith("More words. "))
        assertEquals(
            "the offered draft must survive a save made while unanswered",
            recovered, scratchBody()
        )
        assertEquals(doc.absolutePath, scratchOwner())
    }

    @Test
    fun `switching documents with the offer unanswered keeps the draft as a file`() {
        val recovered = "what reached the disk plus the words that did not"
        leaveScratch(recovered, doc.absolutePath)
        val a = start()
        assertTrue(
            "the prompt must be up for this to mean anything",
            visibleTexts(a).any { it.contains("recovered", ignoreCase = true) }
        )

        // Dismiss without choosing, then walk away to a fresh document. The
        // offer cannot come back — the next start opens the other file — and
        // the new document needs the shadow slot for itself.
        a.dispatchKeyEvent(
            android.view.KeyEvent(
                0, 0, android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_ESCAPE, 0
            )
        )
        shadowOf(Looper.getMainLooper()).idle()
        a.dispatchKeyEvent(
            android.view.KeyEvent(
                0, 0, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_N, 0,
                android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON
            )
        )
        shadowOf(Looper.getMainLooper()).idle()

        val parked = (lib.listFiles() ?: emptyArray()).filter { it.name.contains("recovered") }
        assertEquals("the draft must survive as an ordinary file", 1, parked.size)
        assertEquals(recovered, parked[0].readText())

        // The slot is free again: the new document gets crash protection back.
        editorOf(a).text.insert(0, "New words. ")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        a.drainSaves()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "the new document must be shadowed again, saw: ${scratchBody()?.take(40)}",
            scratchBody()?.startsWith("New words. ") == true
        )
    }

    @Test
    fun `restoring puts the recovered text in the editor and on disk`() {
        val recovered = "what reached the disk plus the words that did not"
        leaveScratch(recovered, doc.absolutePath)
        val a = start()

        // The first row of the prompt is the restore choice.
        val row = find(a.window.decorView) { v ->
            v is android.widget.TextView && v.isShown && v.text.toString().startsWith("Restore")
        }!!
        var target: View = row
        while (target.parent is View && !(target.parent as View).isClickable) {
            target = target.parent as View
        }
        (target.parent as View).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(recovered, editorOf(a).text.toString())
        assertEquals("the restored text must be written through", recovered, doc.readText())
    }
}
