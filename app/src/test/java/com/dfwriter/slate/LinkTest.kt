package com.dfwriter.slate

import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

/** Following a link: to the web, to a document beside it, and to a heading. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LinkTest {

    private lateinit var prefs: Prefs
    private lateinit var lib: File
    private lateinit var doc: File
    private lateinit var activity: MainActivity
    private lateinit var editor: MarkdownEditor

    private val body = "[go](other.md) and [web](www.google.com) and [top](#Chapter one)\n" +
            "\n## Chapter one\n\nthe end\n"

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        prefs = Prefs(ctx)
        lib = File(ctx.cacheDir, "lib-${System.nanoTime()}").apply { mkdirs() }
        prefs.libraryPath = lib.absolutePath
        prefs.sourceMode = false
        prefs.hideMarkers = true

        File(lib, "other.md").writeText("the other document")
        doc = File(lib, "doc.md")
        doc.writeText(body)
        prefs.lastFile = doc.absolutePath
        prefs.lastCaret = 0
        File(ctx.filesDir, "scratch.md").delete()
        File(ctx.filesDir, "scratch.path").delete()

        activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        editor = find(activity.window.decorView) { it is MarkdownEditor } as MarkdownEditor
        // Startup asks for storage permission, which queues an intent of its own.
        // Left in place it would be mistaken for the link having been followed.
        drainIntents()
    }

    private fun drainIntents() {
        while (shadowOf(activity).nextStartedActivity != null) {
            // popping
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun find(root: View, match: (View) -> Boolean): View? {
        if (match(root)) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) find(root.getChildAt(i), match)?.let { return it }
        }
        return null
    }

    /** Puts the caret in the middle of a link's visible text. */
    private fun caretInsideLinkText(label: String) {
        val at = editor.text.toString().indexOf("[$label](") + 1 + label.length / 2
        editor.setSelection(at)
        idle()
    }

    private fun pressCtrlEnter() {
        activity.dispatchKeyEvent(
            KeyEvent(
                0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0,
                KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            )
        )
        idle()
    }

    // ----------------------------------------------------------------- reads

    @Test
    fun `the target is readable from the caret for every link on the line`() {
        caretInsideLinkText("go")
        assertEquals("other.md", editor.linkAtCaret())
        caretInsideLinkText("web")
        assertEquals("www.google.com", editor.linkAtCaret())
    }

    // ------------------------------------------------------------- following

    @Test
    fun `a bare address is opened as https`() {
        caretInsideLinkText("web")
        pressCtrlEnter()

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("expected an intent to be fired", started)
        assertEquals(Intent.ACTION_VIEW, started!!.action)
        assertEquals(
            "a target with no scheme should be treated as the web",
            "https://www.google.com", started.data.toString()
        )
    }

    @Test
    fun `a link to a document beside this one opens it here`() {
        caretInsideLinkText("go")
        pressCtrlEnter()

        assertNull(
            "a sibling document must not be handed to another app",
            shadowOf(activity).nextStartedActivity
        )
        val now = find(activity.window.decorView) { it is MarkdownEditor } as MarkdownEditor
        assertEquals("the other document", now.text.toString())
    }

    @Test
    fun `a heading anchor moves the caret instead of leaving the app`() {
        caretInsideLinkText("top")
        pressCtrlEnter()

        assertNull(shadowOf(activity).nextStartedActivity)
        val expected = editor.text.toString().indexOf("## Chapter one")
        assertEquals(expected, editor.selectionStart)
    }

    @Test
    fun `the caret outside any link says so and opens nothing`() {
        editor.setSelection(editor.text.toString().indexOf("the end") + 2)
        idle()
        assertNull(editor.linkAtCaret())
        pressCtrlEnter()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    // ------------------------------------------------------------------ tap

    @Test
    fun `tapping a link follows it`() {
        // Lay the editor out so the text has real coordinates to hit.
        editor.layout(0, 0, 1600, 1200)
        editor.restyleNow()
        idle()

        val l = editor.layout
        assertNotNull("no layout means the tap cannot be aimed", l)

        // The caret is elsewhere, so this line is showing its rendered form.
        editor.setSelection(editor.text.toString().indexOf("the end"))
        idle()

        // Aimed at the first link on the first line. Robolectric measures text
        // crudely, so a test that depended on hitting the second link exactly
        // would be testing the fake layout rather than this code.
        tapAt(firstLinkX(), firstLineY())
        val now = find(activity.window.decorView) { it is MarkdownEditor } as MarkdownEditor
        assertEquals(
            "a tap on link text should follow it to the sibling document",
            "the other document", now.text.toString()
        )
    }

    @Test
    fun `tapping the line the caret is on places the caret rather than following`() {
        editor.layout(0, 0, 1600, 1200)
        editor.restyleNow()
        idle()

        // Caret already on that line: its Markdown is showing and it is being
        // edited, so a tap belongs to the caret.
        editor.setSelection(2)
        idle()

        val before = editor.text.toString()
        tapAt(firstLinkX(), firstLineY())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals("nothing should have been followed", before, editor.text.toString())
    }

    /** Screen x just inside the first link's text, allowing for scroll. */
    private fun firstLinkX(): Float {
        val l = editor.layout!!
        return l.getPrimaryHorizontal(2) + editor.totalPaddingLeft - editor.scrollX
    }

    /** Screen y at the middle of the first line, allowing for scroll. */
    private fun firstLineY(): Float {
        val l = editor.layout!!
        return ((l.getLineTop(0) + l.getLineBottom(0)) / 2f) +
            editor.totalPaddingTop - editor.scrollY
    }

    private fun tapAt(x: Float, y: Float) {
        for (action in intArrayOf(
            android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP
        )) {
            val ev = android.view.MotionEvent.obtain(0L, 0L, action, x, y, 0)
            editor.onTouchEvent(ev)
            ev.recycle()
        }
        idle()
    }
}
