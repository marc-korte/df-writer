package com.dfwriter.slate

import android.app.Activity
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The editing verbs a writer hits dozens of times an hour. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class EditorTest {

    private lateinit var editor: MarkdownEditor
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        prefs = Prefs(ctx)
        Scale.init(ctx, prefs)
        val styler = MarkdownStyler(prefs)
        editor = MarkdownEditor(ctx)
        editor.bind(prefs, styler)
    }

    private fun set(text: String, selStart: Int, selEnd: Int = selStart) {
        editor.setText(text)
        editor.setSelection(selStart, selEnd)
    }

    private fun body() = editor.text.toString()

    // ------------------------------------------------------------- wrapping

    @Test
    fun `bold wraps the selection`() {
        set("make this bold", 5, 9)
        editor.toggleWrap("**")
        assertEquals("make **this** bold", body())
        assertEquals("this", body().substring(editor.selectionStart, editor.selectionEnd))
    }

    @Test
    fun `bold with no selection wraps the word under the caret`() {
        set("make this bold", 6)
        editor.toggleWrap("**")
        assertEquals("make **this** bold", body())
    }

    @Test
    fun `bold again unwraps rather than nesting`() {
        set("make **this** bold", 5, 13)   // selection includes the markers
        editor.toggleWrap("**")
        assertEquals("make this bold", body())
    }

    @Test
    fun `unwrapping works when the markers sit outside the selection`() {
        set("make **this** bold", 7, 11)   // selection is the inner word only
        editor.toggleWrap("**")
        assertEquals("make this bold", body())
    }

    @Test
    fun `a link wraps with a url placeholder`() {
        set("see docs here", 4, 8)
        editor.toggleWrap("[", "](url)")
        assertEquals("see [docs](url) here", body())
    }

    // ------------------------------------------------------------- headings

    @Test
    fun `heading level is set and replaced rather than stacked`() {
        set("Title", 0)
        editor.setHeading(1)
        assertEquals("# Title", body())
        editor.setHeading(3)
        assertEquals("### Title", body())
        editor.setHeading(0)
        assertEquals("Title", body())
    }

    @Test
    fun `heading applies to every selected line`() {
        set("one\ntwo", 0, 7)
        editor.setHeading(2)
        assertEquals("## one\n## two", body())
    }

    // ---------------------------------------------------------------- lists

    @Test
    fun `a list prefix toggles on and off across a selection`() {
        set("one\ntwo", 0, 7)
        editor.togglePrefix("- ")
        assertEquals("- one\n- two", body())
        editor.setSelection(0, editor.text.length)
        editor.togglePrefix("- ")
        assertEquals("one\ntwo", body())
    }

    @Test
    fun `blockquote toggles`() {
        set("quoted", 0)
        editor.togglePrefix("> ")
        assertEquals("> quoted", body())
        editor.setSelection(0, editor.text.length)
        editor.togglePrefix("> ")
        assertEquals("quoted", body())
    }

    // --------------------------------------------------- enter continuation

    private fun pressEnter() {
        editor.onKeyDown(
            KeyEvent.KEYCODE_ENTER,
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
        )
    }

    private fun pressTab(shift: Boolean = false) {
        val meta = if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        editor.onKeyDown(
            KeyEvent.KEYCODE_TAB,
            KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0, meta)
        )
    }

    @Test
    fun `enter continues a bullet list`() {
        set("- first", 7)
        pressEnter()
        assertEquals("- first\n- ", body())
    }

    @Test
    fun `enter continues and increments a numbered list`() {
        set("3. third", 8)
        pressEnter()
        assertEquals("3. third\n4. ", body())
    }

    @Test
    fun `enter continues a task list with an unchecked box`() {
        set("- [x] done", 10)
        pressEnter()
        assertEquals("- [x] done\n- [ ] ", body())
    }

    @Test
    fun `enter on an empty item ends the list instead of continuing it`() {
        set("- first\n- ", 10)
        pressEnter()
        assertEquals("- first\n", body())
    }

    @Test
    fun `enter mid line splits the line without starting a new item`() {
        set("- first", 4)          // caret is not at the end of the line
        pressEnter()
        assertEquals("- fi\nrst", body())
    }

    @Test
    fun `nested items keep their indentation`() {
        set("  - nested", 10)
        pressEnter()
        assertEquals("  - nested\n  - ", body())
    }

    // --------------------------------------------------------------- indent

    @Test
    fun `tab indents and shift tab outdents every selected line`() {
        set("one\ntwo", 0, 7)
        pressTab()
        assertEquals("  one\n  two", body())
        editor.setSelection(0, editor.text.length)
        pressTab(shift = true)
        assertEquals("one\ntwo", body())
    }

    @Test
    fun `tab with no selection inserts spaces rather than moving focus`() {
        set("word", 4)
        pressTab()
        assertEquals("word  ", body())
    }

    // ----------------------------------------------------------------- undo

    private fun type(s: String) {
        for (c in s) editor.text.insert(editor.selectionStart, c.toString())
    }

    @Test
    fun `undo reverses a run of typing in one step and redo restores it`() {
        set("", 0)
        editor.clearHistory()
        type("hello")
        assertEquals("hello", body())

        assertTrue(editor.undo())
        assertEquals("", body())

        assertTrue(editor.redo())
        assertEquals("hello", body())
    }

    @Test
    fun `undo treats a formatting command as its own step`() {
        set("", 0)
        editor.clearHistory()
        type("word")
        editor.setSelection(0, 4)
        editor.toggleWrap("**")
        assertEquals("**word**", body())

        assertTrue(editor.undo())
        assertEquals("word", body())     // the bold went, the typing stayed
        assertTrue(editor.undo())
        assertEquals("", body())
    }

    @Test
    fun `undo restores deleted text`() {
        set("keep this", 9)
        editor.clearHistory()
        editor.text.delete(4, 9)
        assertEquals("keep", body())
        assertTrue(editor.undo())
        assertEquals("keep this", body())
    }

    @Test
    fun `undo reports when there is nothing left to undo`() {
        set("text", 4)
        editor.clearHistory()
        assertTrue(!editor.undo())
        assertTrue(!editor.redo())
    }

    @Test
    fun `a new edit discards the redo branch`() {
        set("", 0)
        editor.clearHistory()
        type("abc")
        editor.undo()
        type("xyz")
        assertTrue("redo must not resurrect an abandoned branch", !editor.redo())
        assertEquals("xyz", body())
    }

    // -------------------------------------------------------------- inserts

    @Test
    fun `a code block is inserted with the caret inside it`() {
        set("text", 4)
        editor.insertBlock("```\n\n```\n", 5)
        assertEquals("text\n```\n\n```\n", body())
        // The caret should sit on the blank line between the fences.
        assertEquals(9, editor.selectionStart)
    }

    // ------------------------------------------------------------ metrics

    @Test
    fun `the text column is centred and bounded by the measure`() {
        editor.layout(0, 0, 2560, 1920)          // Manta held sideways
        editor.applyMetrics()
        assertTrue("expected side margins", editor.paddingLeft > 0)
        assertEquals(editor.paddingLeft, editor.paddingRight)
        assertTrue(
            "the text column should not fill a 2560px panel edge to edge",
            editor.paddingLeft * 2 > 200
        )
    }

    @Test
    fun `raising the interface scale makes the text bigger`() {
        editor.layout(0, 0, 2560, 1920)
        Scale.setUiScale(prefs, 1.0f)
        editor.applyMetrics()
        val small = editor.textSize

        Scale.setUiScale(prefs, 1.6f)
        editor.applyMetrics()
        val large = editor.textSize

        assertTrue("scale must actually change the text size", large > small * 1.4f)
    }
}

/** The one failure that would make the app useless: not starting. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ActivityStartupTest {

    @Test
    fun `the activity reaches the resumed state`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity: Activity = controller.get()
        assertTrue("activity finished during startup", !activity.isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    fun `a full lifecycle round trip does not throw`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller.pause().resume().pause().stop().destroy()
    }
}
