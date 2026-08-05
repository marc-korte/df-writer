package com.dfwriter.slate

import android.os.Looper
import android.view.KeyEvent
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Drives the real activity with real key events. This is the layer where a
 * shortcut can silently do nothing — a chord mapped to a command name that no
 * longer exists still compiles — and it is the only thing that exercises the
 * panels end to end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ShortcutTest {

    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var editor: MarkdownEditor

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = controller.get()
        editor = findView(activity.window.decorView) { it is MarkdownEditor } as MarkdownEditor
        idle()
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun findView(root: View, match: (View) -> Boolean): View? {
        if (match(root)) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findView(root.getChildAt(i), match)?.let { return it }
            }
        }
        return null
    }

    private fun chord(keyCode: Int, shift: Boolean = false) {
        var meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        activity.dispatchKeyEvent(
            KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, meta)
        )
        idle()
    }

    private fun plainKey(keyCode: Int) {
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        idle()
    }

    private fun setDoc(text: String, selStart: Int, selEnd: Int = selStart) {
        editor.setText(text)
        editor.setSelection(selStart, selEnd)
        editor.clearHistory()
        idle()
    }

    private fun body() = editor.text.toString()

    private fun visiblePanel(): View? = findView(activity.window.decorView) {
        (it is ListSheet || it is SettingsSheet) && it.visibility == View.VISIBLE
    }

    // ------------------------------------------------------------ formatting

    @Test
    fun `ctrl B bolds the selection`() {
        setDoc("make this bold", 5, 9)
        chord(KeyEvent.KEYCODE_B)
        assertEquals("make **this** bold", body())
    }

    @Test
    fun `ctrl I italicises the selection`() {
        setDoc("make this italic", 5, 9)
        chord(KeyEvent.KEYCODE_I)
        assertEquals("make *this* italic", body())
    }

    @Test
    fun `ctrl E marks inline code`() {
        setDoc("call foo now", 5, 8)
        chord(KeyEvent.KEYCODE_E)
        assertEquals("call `foo` now", body())
    }

    @Test
    fun `ctrl K makes a link`() {
        setDoc("see docs", 4, 8)
        chord(KeyEvent.KEYCODE_K)
        assertEquals("see [docs](url)", body())
    }

    @Test
    fun `ctrl digits set heading levels and ctrl 0 clears them`() {
        setDoc("Title", 0)
        chord(KeyEvent.KEYCODE_1)
        assertEquals("# Title", body())
        chord(KeyEvent.KEYCODE_3)
        assertEquals("### Title", body())
        chord(KeyEvent.KEYCODE_5)          // handled by a separate branch
        assertEquals("##### Title", body())
        chord(KeyEvent.KEYCODE_0)
        assertEquals("Title", body())
    }

    @Test
    fun `ctrl shift L and ctrl shift Q toggle list and quote`() {
        setDoc("line", 0)
        chord(KeyEvent.KEYCODE_L, shift = true)
        assertEquals("- line", body())

        setDoc("line", 0)
        chord(KeyEvent.KEYCODE_Q, shift = true)
        assertEquals("> line", body())
    }

    @Test
    fun `ctrl shift D strikes through`() {
        setDoc("wrong word", 0, 5)
        chord(KeyEvent.KEYCODE_D, shift = true)
        assertEquals("~~wrong~~ word", body())
    }

    // ----------------------------------------------------------------- undo

    @Test
    fun `ctrl Z undoes and ctrl shift Z redoes`() {
        setDoc("word", 0, 4)
        chord(KeyEvent.KEYCODE_B)
        assertEquals("**word**", body())

        chord(KeyEvent.KEYCODE_Z)
        assertEquals("word", body())

        chord(KeyEvent.KEYCODE_Z, shift = true)
        assertEquals("**word**", body())
    }

    // --------------------------------------------------------------- panels

    @Test
    fun `ctrl P opens the command palette and escape closes it`() {
        assertTrue("no panel should be open at rest", visiblePanel() == null)
        chord(KeyEvent.KEYCODE_P)
        assertTrue("Ctrl+P should open the palette", visiblePanel() is ListSheet)
        plainKey(KeyEvent.KEYCODE_ESCAPE)
        assertTrue("Escape should close the palette", visiblePanel() == null)
    }

    @Test
    fun `ctrl comma opens settings and escape closes it`() {
        chord(KeyEvent.KEYCODE_COMMA)
        assertTrue("Ctrl+, should open settings", visiblePanel() is SettingsSheet)
        plainKey(KeyEvent.KEYCODE_ESCAPE)
        assertTrue(visiblePanel() == null)
    }

    @Test
    fun `ctrl shift O opens the outline and ctrl O opens the file list`() {
        chord(KeyEvent.KEYCODE_O, shift = true)
        assertTrue(visiblePanel() is ListSheet)
        plainKey(KeyEvent.KEYCODE_ESCAPE)

        chord(KeyEvent.KEYCODE_O)
        assertTrue(visiblePanel() is ListSheet)
        plainKey(KeyEvent.KEYCODE_ESCAPE)
        assertTrue(visiblePanel() == null)
    }

    @Test
    fun `ctrl F opens find and escape closes it`() {
        val bar = findView(activity.window.decorView) { it is FindBar } as FindBar
        assertEquals(View.GONE, bar.visibility)
        chord(KeyEvent.KEYCODE_F)
        assertEquals(View.VISIBLE, bar.visibility)
        plainKey(KeyEvent.KEYCODE_ESCAPE)
        assertEquals(View.GONE, bar.visibility)
    }

    // ---------------------------------------------------------------- modes

    @Test
    fun `F8 and F9 toggle focus and typewriter modes`() {
        val prefs = Prefs(activity)
        val focusBefore = prefs.focusMode
        plainKey(KeyEvent.KEYCODE_F8)
        assertEquals(!focusBefore, Prefs(activity).focusMode)
        plainKey(KeyEvent.KEYCODE_F8)
        assertEquals(focusBefore, Prefs(activity).focusMode)

        val typeBefore = prefs.typewriterMode
        plainKey(KeyEvent.KEYCODE_F9)
        assertEquals(!typeBefore, Prefs(activity).typewriterMode)
        plainKey(KeyEvent.KEYCODE_F9)
    }

    @Test
    fun `ctrl slash toggles raw source mode`() {
        val before = Prefs(activity).sourceMode
        chord(KeyEvent.KEYCODE_SLASH)
        assertEquals(!before, Prefs(activity).sourceMode)
        chord(KeyEvent.KEYCODE_SLASH)
        assertEquals(before, Prefs(activity).sourceMode)
    }

    // ---------------------------------------------------------------- scale

    @Test
    fun `ctrl equals and ctrl minus change the interface scale and persist it`() {
        val start = Scale.ui
        chord(KeyEvent.KEYCODE_EQUALS)
        val bigger = Scale.ui
        assertTrue("Ctrl+= must enlarge the interface", bigger > start)
        assertEquals(bigger, Prefs(activity).uiScale, 0.001f)

        chord(KeyEvent.KEYCODE_MINUS)
        assertTrue("Ctrl+- must shrink it back", Scale.ui < bigger)

        chord(KeyEvent.KEYCODE_0, shift = true)
        assertEquals("Ctrl+Shift+0 should reset to 100%", 1.0f, Scale.ui, 0.001f)
    }

    @Test
    fun `rebuilding after a scale change keeps the document and the caret`() {
        setDoc("# Kept heading\n\nkept body text", 8)
        chord(KeyEvent.KEYCODE_EQUALS)
        // The view tree is replaced wholesale, so re-find the editor.
        val after = findView(activity.window.decorView) { it is MarkdownEditor } as MarkdownEditor
        assertEquals("# Kept heading\n\nkept body text", after.text.toString())
        assertEquals(8, after.selectionStart)
    }

    // ------------------------------------------------------------ typing

    @Test
    fun `an unmodified key is left alone for the editor to handle`() {
        setDoc("", 0)
        val handled = activity.dispatchKeyEvent(
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)
        )
        idle()
        assertTrue("plain letters must not be swallowed as shortcuts", !handled || body() == "a")
    }
}
