package com.dfwriter.slate

import android.app.Activity
import android.content.res.Configuration
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
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

    @Test
    fun `the link under the caret is found, and only there`() {
        set("go [home](https://example.com) now", 0)
        editor.restyleNow()

        val inside = body().indexOf("home") + 2   // inside the visible link text
        editor.setSelection(inside)
        assertEquals("https://example.com", editor.linkAtCaret())

        editor.setSelection(editor.text.length)   // out in the plain prose
        assertNull(editor.linkAtCaret())
    }

    @Test
    fun `plain text reports no link`() {
        set("nothing to follow here", 4)
        editor.restyleNow()
        assertNull(editor.linkAtCaret())
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

    @Test
    fun `rewriting every selected line is a single undo step`() {
        // One replace per line would be one press of Ctrl+Z per line, which after
        // a Tab across a paragraph is not undo so much as a chore.
        set("one\ntwo\nthree", 0, 13)
        editor.clearHistory()
        pressTab()
        assertEquals("  one\n  two\n  three", body())
        assertEquals("indenting three lines must take one undo", 1, undoAll())
        assertEquals("one\ntwo\nthree", body())

        set("one\ntwo\nthree", 0, 13)
        editor.clearHistory()
        editor.setHeading(2)
        assertEquals("## one\n## two\n## three", body())
        assertEquals("so must heading three lines", 1, undoAll())
        assertEquals("one\ntwo\nthree", body())
    }

    // ----------------------------------------------------------------- undo

    private fun type(s: String) {
        for (c in s) editor.text.insert(editor.selectionStart, c.toString())
    }

    /**
     * Types [s] and reports whether it fitted inside the coalescing window.
     *
     * Whether a run of typing collapses into one undo step is decided against the
     * wall clock, and the editor reads `System.currentTimeMillis()`, which
     * Robolectric leaves alone for application code. A stall longer than the
     * window really does end the run — that is the rule working — so a test that
     * insists on a single step has to know its own typing was quick enough
     * rather than fail on a pause it never controlled.
     */
    private fun typeWithinWindow(s: String): Boolean {
        val started = System.currentTimeMillis()
        type(s)
        return System.currentTimeMillis() - started < COALESCE_MS
    }

    /** Undoes to the bottom of the stack; returns how many steps that took. */
    private fun undoAll(): Int {
        var steps = 0
        while (editor.undo()) steps++
        return steps
    }

    @Test
    fun `undo reverses a run of typing in one step and redo restores it`() {
        set("", 0)
        editor.clearHistory()
        val quickEnough = typeWithinWindow("hello")
        assertEquals("hello", body())
        assumeTrue("typing itself outlasted the coalescing window", quickEnough)

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
        // However many steps the typing itself became, the bold was not folded
        // into the first of them, which is the whole claim here.
        undoAll()
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
        undoAll()
        assertEquals("", body())
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

    // ----------------------------------------------------------- restyling

    @Test
    fun `a caret jump restyles only the lines it left and landed on`() {
        // Widening the restyle to reach the caret would style every line jumped
        // over, on the UI thread, which is what a jump to the far end of a long
        // document would cost. An untouched line keeps the very span objects it
        // already had; a restyle would have thrown them away and made new ones.
        val src = "# One\n# Two\n# Three\n# Four"
        set(src, src.length)
        editor.restyleNow()

        val middle = src.indexOf("# Three")
        val before = editor.text.getSpans(middle, middle + 2, HiddenSpan::class.java).first()

        editor.setSelection(0)
        val after = editor.text.getSpans(middle, middle + 2, HiddenSpan::class.java).firstOrNull()
        assertSame("a line the caret only flew over was restyled", before, after)

        // The line the caret landed on does get its markers back.
        assertTrue(editor.text.getSpans(0, 2, MarkerSpan::class.java).isNotEmpty())
    }

    // ------------------------------------------------------------ metrics

    @Test
    fun `the text column is centred and bounded by the measure`() {
        editor.layout(0, 0, 2560, 1920)
        editor.applyMetrics()
        assertTrue("expected side margins", editor.paddingLeft > 0)
        val column = 2560 - editor.paddingLeft - editor.paddingRight
        assertTrue(
            "the text column should not fill a 2560px panel edge to edge",
            column < 2560 - 200
        )
        // What bounds the column is the measure in characters, not the panel.
        val em = editor.paint.measureText("abcdefghijklmnopqrstuvwxyz ") / 27f
        assertEquals(
            "the column should hold about ${prefs.measureChars} characters",
            em * prefs.measureChars, column.toFloat(), em * 2f
        )
    }

    /**
     * Every other test here runs on Robolectric's default 320x470 mdpi screen,
     * where the panel floor in [Scale.chooseDpi] never comes into play — so
     * nothing was exercising the path this whole app exists for. This one runs
     * against a Manta-shaped display, and goes through Scale.init and pt() the
     * way the device does rather than calling chooseDpi directly.
     */
    @Test
    @Config(qualifiers = "w1920dp-h2560dp-mdpi")
    fun `on a 300 PPI panel the sizes come from the glass, not from the ROM`() {
        // setUp ran Scale.init against this display.
        assertEquals("the 300 PPI floor should have been applied", 300f, Scale.dpi, 0.5f)
        assertTrue(
            "the point of the floor is that the ROM claims less than the glass",
            Scale.reportedDpi < Scale.dpi
        )

        editor.layout(0, 0, 1920, 2560)
        editor.applyMetrics()
        assertEquals(Scale.pt(prefs.bodyPt), editor.textSize, 0.5f)
        // 13.5pt is about 3/16 of an inch, so about 56px at 300 PPI, where the
        // density this ROM reports would have drawn it half that size.
        assertTrue("body text came out at ${editor.textSize}px", editor.textSize > 50f)
        assertTrue(
            "the side margins keep their 6mm minimum",
            editor.paddingLeft >= Scale.mmInt(6f)
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

    private companion object {
        /** Mirrors COALESCE_MS in [MarkdownEditor], which is private to it. */
        const val COALESCE_MS = 1200L
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
    fun `a lifecycle round trip keeps the document`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val text = "text that has to survive being put away"
        editorOf(activity).setText(text)

        controller.pause().resume()
        assertEquals(
            "the document should still be there after a pause and a resume",
            text, editorOf(activity).text.toString()
        )
        assertTrue("activity finished during the round trip", !activity.isFinishing)
        controller.pause().stop().destroy()
    }

    private fun editorOf(a: Activity): MarkdownEditor {
        fun walk(v: View): MarkdownEditor? {
            if (v is MarkdownEditor) return v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))?.let { return it }
            }
            return null
        }
        return walk(a.window.decorView)!!
    }
}

/**
 * The one workaround this app cannot do without. The Manta ships exactly one
 * IME, PinyinIME, and it swallows every hardware key it is offered without
 * committing anything, so whenever a keyboard is attached the app takes its
 * window out of the IME's path and the editor declines an input connection.
 * Both halves are checked here: the day this stops working is the day nothing
 * can be typed on the device at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SoftInputTest {

    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var editor: MarkdownEditor
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        prefs = Prefs(RuntimeEnvironment.getApplication())
        // Decided when the editor is built, so it has to be set before that.
        prefs.softKeyboard = SoftKeyboard.AUTO
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        adopt()
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
    }

    /** Picks the activity and editor up again, in case a change replaced them. */
    private fun adopt() {
        activity = controller.get()
        editor = walk(activity.window.decorView)!!
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun walk(v: View): MarkdownEditor? {
        if (v is MarkdownEditor) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))?.let { return it }
        return null
    }

    /**
     * Pairs or unpairs a keyboard, which reaches the app as a configuration
     * change and nothing else.
     *
     * Set field by field rather than through a `qwerty` resource qualifier,
     * because a qualifier can only say that a keyboard exists — it leaves
     * `hardKeyboardHidden` undefined, and being *usable right now* is the half
     * of the question that decides this.
     */
    private fun keyboard(attached: Boolean) {
        controller.configurationChange(
            Configuration(activity.resources.configuration).apply {
                keyboard =
                    if (attached) Configuration.KEYBOARD_QWERTY
                    else Configuration.KEYBOARD_NOKEYS
                hardKeyboardHidden =
                    if (attached) Configuration.HARDKEYBOARDHIDDEN_NO
                    else Configuration.HARDKEYBOARDHIDDEN_YES
            }
        )
        adopt()
    }

    /**
     * Whether the window has stopped being an input-method target. This is the
     * decisive half: ViewRootImpl asks the *window's* flags, not the view's,
     * before it offers a key to the IME at all.
     */
    private fun outOfTheImePath(): Boolean =
        (activity.window.attributes.flags and
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM) != 0

    private fun connection(): InputConnection? = editor.onCreateInputConnection(EditorInfo())

    @Test
    fun `a hardware keyboard takes the window out of the IME's path`() {
        keyboard(attached = true)

        assertTrue(
            "this test means nothing unless the device reports a keyboard",
            editor.hasHardwareKeyboard()
        )
        assertTrue("the window must stop being an input-method target", outOfTheImePath())
        assertNull("PinyinIME must not be handed a connection to eat keys through", connection())
        assertFalse(
            "and the on-screen keyboard must not come up over the page",
            editor.showSoftInputOnFocus
        )
    }

    @Test
    fun `with no hardware keyboard the IME is left alone`() {
        // Robolectric's device has no keyboard, which is this one with the
        // Bluetooth keyboard unpaired.
        assertFalse(editor.hasHardwareKeyboard())
        assertFalse("suppressing the IME here leaves nothing to type on", outOfTheImePath())
        assertNotNull("the on-screen keyboard needs the connection", connection())
        assertTrue(editor.showSoftInputOnFocus)
    }

    @Test
    fun `asking for the on-screen keyboard beats the hardware one`() {
        keyboard(attached = true)
        assertNull("suppressed to begin with", connection())

        prefs.softKeyboard = SoftKeyboard.ALWAYS
        editor.applySoftInputPolicy()

        assertFalse("the setting has to win, or the command is a lie", outOfTheImePath())
        assertNotNull(connection())
    }

    @Test
    fun `turning the on-screen keyboard off suppresses it with no keyboard attached`() {
        assertNotNull("nothing is suppressed to begin with", connection())

        prefs.softKeyboard = SoftKeyboard.NEVER
        editor.applySoftInputPolicy()

        assertTrue(outOfTheImePath())
        assertNull(connection())
    }

    @Test
    fun `dropping the keyboard mid-session gives the on-screen one back`() {
        keyboard(attached = true)
        assertTrue(outOfTheImePath())

        // Unpairing arrives the same way pairing did. Without this the editor
        // would be left with no hardware keyboard and no on-screen one either,
        // and no way at all to type.
        keyboard(attached = false)

        assertFalse(editor.hasHardwareKeyboard())
        assertFalse("the on-screen keyboard is all there is now", outOfTheImePath())
        assertNotNull(connection())
    }
}
