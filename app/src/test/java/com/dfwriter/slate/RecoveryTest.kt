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

    private fun start(): MainActivity {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        return c.get()
    }

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
