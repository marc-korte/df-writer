package com.dfwriter.slate

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/**
 * The four-second autosave, and the pause that has to finish the job. The
 * writing itself happens on a thread of its own — a whole document fsync'd onto
 * a card is far too slow to do between keystrokes — so what these tests watch
 * for is text that reaches the card late, out of order, or not at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AutosaveTest {

    private lateinit var prefs: Prefs
    private lateinit var lib: File
    private lateinit var doc: File
    private var controller: ActivityController<MainActivity>? = null

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
        File(ctx.filesDir, "scratch.md").delete()
        File(ctx.filesDir, "scratch.path").delete()
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Far enough forward for the four-second autosave to come round. */
    private fun advanceToTick() =
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4000))

    /**
     * A whole autosave: the tick that snapshots the text, the wait for the save
     * thread that writes it — the same wait the pause path makes — and then the
     * main thread again for whatever the write posted back.
     */
    private fun tick() {
        advanceToTick()
        controller!!.get().drainSaves()
        idle()
    }

    private fun launch(): MainActivity {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller = c
        idle()
        return c.get()
    }

    private fun find(root: View, match: (View) -> Boolean): View? {
        if (match(root)) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) find(root.getChildAt(i), match)?.let { return it }
        }
        return null
    }

    private fun editorOf(a: MainActivity) =
        find(a.window.decorView) { it is MarkdownEditor } as MarkdownEditor

    private fun visibleTexts(a: MainActivity): List<String> {
        val out = ArrayList<String>()
        fun walk(v: View) {
            if (v is android.widget.TextView && v.isShown) out.add(v.text.toString())
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(a.window.decorView)
        return out
    }

    private fun scratchBody(): String? =
        File(RuntimeEnvironment.getApplication().filesDir, "scratch.md")
            .takeIf { it.exists() }?.readText()

    private fun type(a: MainActivity, text: String) {
        editorOf(a).setText(text)
        idle()
    }

    // ------------------------------------------------------------- the tick

    @Test
    fun `the autosave tick writes what was typed`() {
        val a = launch()
        type(a, "words typed since the last save")
        assertEquals(
            "nothing is owed to the card until the tick comes round",
            "what reached the disk", doc.readText()
        )

        tick()

        assertEquals("words typed since the last save", doc.readText())
        assertEquals(
            "the shadow copy has to track the file, or the next launch offers it back",
            "words typed since the last save", scratchBody()
        )
        assertTrue(
            "the document should not still be marked unsaved, saw ${visibleTexts(a)}",
            visibleTexts(a).none { it.contains("•") }
        )
    }

    @Test
    fun `an untouched document is not rewritten`() {
        launch()
        // Changed behind the app's back. An autosave that ran without anything
        // having been edited would put the editor's copy back over this.
        doc.writeText("changed underneath")

        tick()

        assertEquals("changed underneath", doc.readText())
    }

    @Test
    fun `an edit made while a save is in flight is saved by the next tick`() {
        val a = launch()
        type(a, "the first version")
        // The tick alone: the text is snapshotted and handed over, and the
        // document is marked clean against that snapshot.
        advanceToTick()
        type(a, "the second version")

        tick()

        assertEquals(
            "the edit landed after the snapshot, so the next tick owed it a save",
            "the second version", doc.readText()
        )
    }

    @Test
    fun `saves land in the order they were taken`() {
        val a = launch()
        type(a, "the first version")
        advanceToTick()          // handed over, not waited for
        type(a, "the second version")
        advanceToTick()          // handed over behind the first

        controller!!.get().drainSaves()
        idle()

        // One save thread and a queue, so the older text cannot overtake the
        // newer one on its way to the card.
        assertEquals("the second version", doc.readText())
        assertEquals("the second version", scratchBody())
    }

    // ------------------------------------------------------------- failures

    @Test
    fun `a failed autosave reaches the status bar and keeps the text`() {
        val a = launch()
        // A directory where the document was makes every write to it fail.
        doc.delete()
        doc.mkdirs()
        type(a, "words that cannot reach the card")

        tick()

        assertTrue(
            "a failed save must not be silent, saw ${visibleTexts(a)}",
            visibleTexts(a).any { it.contains("Save failed") }
        )
        assertEquals(
            "text that never reached the card has to be kept somewhere",
            "words that cannot reach the card", scratchBody()
        )

        // And the document is left dirty, so the next tick tries again rather
        // than treating the words as saved.
        doc.deleteRecursively()
        tick()
        assertEquals("words that cannot reach the card", doc.readText())
    }

    // ---------------------------------------------------------------- pause

    @Test
    fun `pausing writes the text before it returns`() {
        val a = launch()
        type(a, "typed and then put away")

        controller!!.pause()

        // Deliberately no drain and no idling first: this process can be killed
        // the moment onPause returns, so a write still queued behind it is a
        // draft that never existed.
        assertEquals("typed and then put away", doc.readText())
        assertEquals("typed and then put away", scratchBody())
    }

    @Test
    fun `pausing writes the text with the save thread taken away`() {
        val a = launch()
        type(a, "typed and then put away")
        // Stood down, and anything queued on it discarded. This is the pause
        // that matters: the one where the process is killed a moment later and
        // nothing handed to another thread ever runs. Written this way because
        // a background write racing the assertion below would usually win it,
        // and a test that passes by winning a race proves nothing.
        a.saveIo.shutdownNow()

        controller!!.pause()

        assertEquals("typed and then put away", doc.readText())
        assertEquals("typed and then put away", scratchBody())
    }

    @Test
    fun `a pause does not let a save already in flight land last`() {
        val a = launch()
        type(a, "the first version")
        advanceToTick()                  // the first version is on its way
        type(a, "the second version")

        controller!!.pause()

        assertEquals(
            "the older text must not be written over the newer one",
            "the second version", doc.readText()
        )
        controller!!.get().drainSaves()
        idle()
        assertEquals("and must not arrive afterwards either", "the second version", doc.readText())
    }
}
