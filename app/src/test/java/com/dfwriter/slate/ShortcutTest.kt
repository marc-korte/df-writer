package com.dfwriter.slate

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
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
    private lateinit var lib: File

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        // A library of its own, so what the file commands wrote can be read back
        // off the disk rather than inferred from the status bar.
        lib = File(ctx.cacheDir, "lib-${System.nanoTime()}").apply { mkdirs() }
        Prefs(ctx).libraryPath = lib.absolutePath

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

    /**
     * Lets the debounced work run. The word count is deliberately taken a
     * moment after the text changes rather than on every keystroke, so a test
     * that types and looks immediately is looking too early.
     */
    private fun settle() =
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))

    /** Runs the delayed work too, for the things that undo themselves. */
    private fun advance(ms: Long) =
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun findView(root: View, match: (View) -> Boolean): View? {
        if (match(root)) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findView(root.getChildAt(i), match)?.let { return it }
            }
        }
        return null
    }

    private fun chord(keyCode: Int, shift: Boolean = false, repeat: Int = 0) {
        var meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        activity.dispatchKeyEvent(
            KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, repeat, meta)
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

    /** The heading the open panel was configured with; a ListSheet's first child. */
    private fun sheetTitle(): String {
        val sheet = visiblePanel() as? ListSheet ?: return ""
        return (sheet.getChildAt(0) as TextView).text.toString()
    }

    private fun textsIn(root: View): List<String> {
        val out = ArrayList<String>()
        fun walk(v: View) {
            if (v is TextView && v.isShown) out.add(v.text.toString())
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun visibleTexts(): List<String> = textsIn(activity.window.decorView)

    /**
     * The full-screen black view an E Ink refresh puts up. Matched on its shape
     * rather than only its colour, since a selected row and a divider are black
     * as well.
     */
    private fun flashOverlay(): View? = findView(activity.window.decorView) { v ->
        v.javaClass == View::class.java &&
                (v.background as? ColorDrawable)?.color == Color.BLACK &&
                v.layoutParams?.height == ViewGroup.LayoutParams.MATCH_PARENT
    }

    private fun documentCount(): Int =
        lib.listFiles()?.count { it.isFile && it.name.endsWith(".md") } ?: 0

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

    @Test
    fun `the remaining ctrl shift chords insert their blocks`() {
        setDoc("line", 0)
        chord(KeyEvent.KEYCODE_N, shift = true)
        assertEquals("1. line", body())

        setDoc("line", 0)
        chord(KeyEvent.KEYCODE_T, shift = true)
        assertEquals("- [ ] line", body())

        setDoc("text", 4)
        chord(KeyEvent.KEYCODE_K, shift = true)
        assertEquals("text\n```\n\n```\n", body())

        setDoc("text", 4)
        chord(KeyEvent.KEYCODE_H, shift = true)
        assertEquals("text\n\n---\n\n", body())

        setDoc("text", 4)
        chord(KeyEvent.KEYCODE_B, shift = true)
        assertEquals("text\n\n| A | B |\n| --- | --- |\n|  |  |\n\n", body())
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

    @Test
    fun `ctrl Y redoes as well`() {
        setDoc("word", 0, 4)
        chord(KeyEvent.KEYCODE_B)
        chord(KeyEvent.KEYCODE_Z)
        assertEquals("word", body())
        chord(KeyEvent.KEYCODE_Y)
        assertEquals("**word**", body())
    }

    // ----------------------------------------------------------------- files

    @Test
    fun `ctrl N starts a new empty document`() {
        setDoc("what the old document said", 0)
        val before = documentCount()
        chord(KeyEvent.KEYCODE_N)
        assertEquals("Ctrl+N should leave an empty page", "", body())
        assertEquals("and a new file to put it in", before + 1, documentCount())
    }

    @Test
    fun `ctrl S writes the document to the card`() {
        setDoc("text that has to reach the card", 0)
        chord(KeyEvent.KEYCODE_S)
        val f = File(lib, "Welcome to Slate.md")
        assertTrue("the document opened at startup should be on disk", f.isFile)
        assertEquals("text that has to reach the card", f.readText())
        assertTrue("a save should say so", visibleTexts().any { it.contains("Saved") })
    }

    @Test
    fun `a held chord does not repeat a command meant to run once`() {
        setDoc("still the same document", 0)
        val before = documentCount()
        // The second and later events of a held Ctrl+N.
        chord(KeyEvent.KEYCODE_N, repeat = 1)
        assertEquals("an auto-repeat must not make another document", before, documentCount())
        assertEquals("still the same document", body())

        // The size nudges are the exception: they are meant to be leant on.
        val scale = Scale.ui
        chord(KeyEvent.KEYCODE_EQUALS, repeat = 1)
        assertTrue("Ctrl+= should still repeat", Scale.ui > scale)
    }

    @Test
    fun `ctrl shift M exports HTML into the library`() {
        setDoc("# Title\n\nbody", 0)
        chord(KeyEvent.KEYCODE_M, shift = true)
        // The page is written on the save thread; wait the way a save is waited for.
        activity.drainSaves()
        shadowOf(Looper.getMainLooper()).idle()
        val out = File(File(lib, "Exports"), "Welcome to Slate.html")
        assertTrue("expected ${out.absolutePath}, saw ${visibleTexts()}", out.isFile)
        assertTrue("the export should be real HTML", out.readText().contains("<h1>"))
    }

    // ------------------------------------------------- outline jump, escape

    private fun book(words: Int): String {
        val sb = StringBuilder("# A Book\n\n")
        val sentence = "The morning began badly and it went on from there, as mornings will. "
        var w = 0
        var ch = 1
        while (w < words) {
            sb.append("## Chapter ").append(ch).append("\n\n")
            repeat(10) { sb.append(sentence.repeat(6)).append("\n\n"); w += 78 }
            ch++
        }
        return sb.toString()
    }

    private fun clickRow(label: String) {
        val row = findView(activity.window.decorView) { v ->
            v is TextView && v.isShown && v.text.toString() == label
        }
        assertTrue("no visible row labelled \"$label\"", row != null)
        var t: View = row!!
        while (!t.isClickable && t.parent is View) t = t.parent as View
        t.performClick()
        idle()
    }

    @Test
    fun `escape closes the outline`() {
        setDoc(book(2_000), 0)
        chord(KeyEvent.KEYCODE_O, shift = true)
        assertTrue("Ctrl+Shift+O should open the outline", visiblePanel() is ListSheet)
        plainKey(KeyEvent.KEYCODE_ESCAPE)
        assertTrue("Escape should close the outline", visiblePanel() == null)
    }

    @Test
    fun `escape with ctrl still held closes the outline too`() {
        setDoc(book(2_000), 0)
        chord(KeyEvent.KEYCODE_O, shift = true)
        assertTrue(visiblePanel() is ListSheet)
        // The chord's modifier is often still on its way up when Esc arrives,
        // and a Bluetooth keyboard can leave a meta bit stuck besides. Neither
        // may lock the panel open.
        chord(KeyEvent.KEYCODE_ESCAPE)
        assertTrue("Escape must close the panel with Ctrl still down", visiblePanel() == null)
    }

    @Test
    fun `a heading picked from the outline lands at the top of the page`() {
        setDoc(book(20_000), 0)
        settle()
        chord(KeyEvent.KEYCODE_O, shift = true)
        assertTrue(visiblePanel() is ListSheet)
        clickRow("Chapter 20")
        settle()   // the placement runs again after the styled window settles

        val at = body().indexOf("## Chapter 20")
        assertEquals("the caret should sit at the heading", at, editor.selectionStart)
        assertTrue(
            "the editor must hold focus again, or the caret is invisible",
            editor.isFocused
        )
        val l = editor.layout!!
        val line = l.getLineForOffset(at)
        val screenY = l.getLineTop(line) + editor.totalPaddingTop - editor.scrollY
        // One line height of air above the heading, measured against settled
        // heights — not wherever the styled-window rebuild happened to drag it.
        val lineH = l.getLineBottom(line) - l.getLineTop(line)
        assertTrue(
            "a picked heading belongs one line height from the top, " +
                    "was y=$screenY with line height $lineH of ${editor.height}",
            kotlin.math.abs(screenY - lineH) <= 2
        )
    }

    // --------------------------------------------------------------- panels

    @Test
    fun `ctrl P opens the command palette and escape closes it`() {
        assertTrue("no panel should be open at rest", visiblePanel() == null)
        chord(KeyEvent.KEYCODE_P)
        assertTrue("Ctrl+P should open the palette", visiblePanel() is ListSheet)
        assertEquals("Commands", sheetTitle())
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
        // Both are the same class of panel, so only what they were configured
        // with can tell them apart — swapping the two chords would otherwise go
        // unnoticed.
        setDoc("# A heading\n\nbody", 0)
        chord(KeyEvent.KEYCODE_O, shift = true)
        assertEquals("Outline", sheetTitle())
        assertTrue("the outline should list the heading", visibleTexts().any { it == "A heading" })
        plainKey(KeyEvent.KEYCODE_ESCAPE)

        chord(KeyEvent.KEYCODE_O)
        assertTrue("expected the file list, got ${sheetTitle()}", sheetTitle().startsWith("Files ·"))
        assertTrue(
            "the library's own documents should be in it",
            visibleTexts().any { it == "Welcome to Slate.md" }
        )
        plainKey(KeyEvent.KEYCODE_ESCAPE)
        assertTrue(visiblePanel() == null)
    }

    @Test
    fun `shift enter in the file list creates the name that was typed`() {
        chord(KeyEvent.KEYCODE_O)
        val sheet = visiblePanel() as ListSheet
        val filter = findView(sheet) { it is EditText } as EditText
        filter.setText("Wel")               // a subsequence of "Welcome to Slate.md"
        idle()

        // Plain Enter would open the highlighted match. Shift+Enter is how you
        // say no, make the one I typed.
        sheet.handleKey(
            KeyEvent.KEYCODE_ENTER,
            KeyEvent(
                0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0,
                KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            )
        )
        idle()

        assertTrue("Shift+Enter should have made Wel.md", File(lib, "Wel.md").isFile)
        assertEquals("and opened it, empty", "", body())
        assertTrue(
            "the file it fuzzy-matched must be left alone",
            File(lib, "Welcome to Slate.md").isFile
        )
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

    @Test
    fun `ctrl H opens find with the replace field explained`() {
        val bar = findView(activity.window.decorView) { it is FindBar } as FindBar
        chord(KeyEvent.KEYCODE_H)
        assertEquals(View.VISIBLE, bar.visibility)
        assertTrue(
            "Ctrl+H is replace, not plain find",
            textsIn(bar).any { it == "Tab to the replace field" }
        )
    }

    @Test
    fun `ctrl G steps through the matches and wraps`() {
        setDoc("alpha beta alpha", 0)
        chord(KeyEvent.KEYCODE_F)
        val bar = findView(activity.window.decorView) { it is FindBar } as FindBar
        bar.setQuery("alpha")
        idle()

        chord(KeyEvent.KEYCODE_G)
        assertEquals(0, editor.selectionStart)
        assertEquals(5, editor.selectionEnd)

        chord(KeyEvent.KEYCODE_G)
        assertEquals("the second match", 11, editor.selectionStart)

        chord(KeyEvent.KEYCODE_G)
        assertEquals("and round to the first again", 0, editor.selectionStart)
    }

    /** The find bar's second field; the first one is the search itself. */
    private fun replaceField(bar: FindBar): EditText =
        (0 until bar.childCount).map { bar.getChildAt(it) }
            .filterIsInstance<EditText>()
            .first { it.hint?.toString() == "Replace with" }

    private fun findBarButton(bar: FindBar, label: String): View =
        findView(bar) { it is TextView && it.text.toString() == label }!!

    /** Opens replace, fills both fields in, and presses All. */
    private fun replaceAll(query: String, with: String): FindBar {
        chord(KeyEvent.KEYCODE_H)
        val bar = findView(activity.window.decorView) { it is FindBar } as FindBar
        bar.setQuery(query)
        replaceField(bar).setText(with)
        idle()
        findBarButton(bar, "All").performClick()
        idle()
        return bar
    }

    @Test
    fun `replace all rewrites every match and undo puts the document back`() {
        setDoc("alpha beta alpha gamma alpha", 6)

        val bar = replaceAll("alpha", "omega")

        assertEquals("omega beta omega gamma omega", body())
        assertTrue(
            "the count is the only feedback there is, saw ${textsIn(bar)}",
            textsIn(bar).any { it == "replaced 3" }
        )

        // This is the one edit that rewrites the whole buffer through setText,
        // so it is the one most likely to leave undo with nothing to work from.
        chord(KeyEvent.KEYCODE_Z)
        assertEquals(
            "undo has to bring the whole document back in one step",
            "alpha beta alpha gamma alpha", body()
        )
    }

    @Test
    fun `replace all matches whatever the case and says when nothing matched`() {
        setDoc("Alpha alpha ALPHA", 0)
        replaceAll("alpha", "beta")
        assertEquals("find is case-insensitive, so replace has to be too", "beta beta beta", body())

        val bar = replaceAll("nothing here", "x")
        assertEquals("beta beta beta", body())
        assertTrue(
            "a replace that matched nothing must say so, saw ${textsIn(bar)}",
            textsIn(bar).any { it == "no match" }
        )
    }

    @Test
    fun `replace all leaves the caret inside the document it just rewrote`() {
        // The replacement is shorter than what it replaces, so a caret left
        // where it was would be past the end of the new text.
        setDoc("longword longword longword", 26)
        replaceAll("longword", "x")
        assertEquals("x x x", body())
        assertTrue(
            "the caret ended up at ${editor.selectionStart} in a ${body().length}-character document",
            editor.selectionStart in 0..body().length
        )
    }

    // ------------------------------------------------------------- contents

    @Test
    fun `ctrl T opens the table of contents and lists every heading`() {
        setDoc("# One\n\ntext\n\n## Two\n\nmore\n\n### Three\n", 0)
        chord(KeyEvent.KEYCODE_T)

        val texts = visibleTexts()
        assertTrue("expected a contents panel, saw $texts", texts.any { it.startsWith("Contents") })
        assertTrue(texts.contains("One"))
        assertTrue(texts.contains("Two"))
        assertTrue(texts.contains("Three"))
    }

    @Test
    fun `the drawer sits down one edge rather than across the page`() {
        setDoc("# One\n\n## Two\n", 0)
        chord(KeyEvent.KEYCODE_T)
        val drawer = findView(activity.window.decorView) {
            it is ListSheet && it.isShown
        } as ListSheet
        val lp = drawer.layoutParams as android.widget.FrameLayout.LayoutParams
        assertTrue("a drawer must not fill the width", lp.width > 0)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, lp.height)
    }

    @Test
    fun `picking a chapter moves the document and leaves the drawer open`() {
        val doc = "# One\n\ntext\n\n## Two\n\nmore\n"
        setDoc(doc, 0)
        chord(KeyEvent.KEYCODE_T)

        val row = findView(activity.window.decorView) { v ->
            v is android.widget.TextView && v.isShown && v.text.toString() == "Two"
        }!!
        var target: View = row
        while (target.parent is View && !(target.parent as View).isClickable) {
            target = target.parent as View
        }
        (target.parent as View).performClick()
        idle()

        assertEquals("the document should have jumped", doc.indexOf("## Two"), editor.selectionStart)
        assertTrue(
            "the drawer must stay open for the next chapter",
            visibleTexts().any { it.startsWith("Contents") }
        )
    }

    @Test
    fun `ctrl T again closes it`() {
        setDoc("# One\n", 0)
        chord(KeyEvent.KEYCODE_T)
        assertTrue(visibleTexts().any { it.startsWith("Contents") })
        chord(KeyEvent.KEYCODE_T)
        assertTrue(
            "the same chord should put it away",
            visibleTexts().none { it.startsWith("Contents") }
        )
    }

    @Test
    fun `escape closes the drawer`() {
        setDoc("# One\n", 0)
        chord(KeyEvent.KEYCODE_T)
        plainKey(KeyEvent.KEYCODE_ESCAPE)
        assertTrue(visibleTexts().none { it.startsWith("Contents") })
    }

    @Test
    fun `a document with no headings says so instead of showing nothing`() {
        setDoc("just prose, no headings at all\n", 0)
        chord(KeyEvent.KEYCODE_T)
        assertTrue(
            visibleTexts().any { it.contains("No headings yet", ignoreCase = true) }
        )
    }

    @Test
    fun `ctrl shift T still makes a task list`() {
        setDoc("buy milk", 0)
        chord(KeyEvent.KEYCODE_T, shift = true)
        assertEquals("- [ ] buy milk", body())
    }

    // ---------------------------------------------------------------- modes

    @Test
    fun `F8 and F9 toggle focus and typewriter modes`() {
        val focusBefore = Prefs(activity).focusMode
        plainKey(KeyEvent.KEYCODE_F8)
        assertEquals(!focusBefore, Prefs(activity).focusMode)
        plainKey(KeyEvent.KEYCODE_F8)
        assertEquals(focusBefore, Prefs(activity).focusMode)

        val typeBefore = Prefs(activity).typewriterMode
        plainKey(KeyEvent.KEYCODE_F9)
        assertEquals(!typeBefore, Prefs(activity).typewriterMode)
        plainKey(KeyEvent.KEYCODE_F9)
        assertEquals(
            "a second F9 has to put typewriter mode back",
            typeBefore, Prefs(activity).typewriterMode
        )
    }

    @Test
    fun `ctrl slash toggles raw source mode`() {
        val before = Prefs(activity).sourceMode
        chord(KeyEvent.KEYCODE_SLASH)
        assertEquals(!before, Prefs(activity).sourceMode)
        chord(KeyEvent.KEYCODE_SLASH)
        assertEquals(before, Prefs(activity).sourceMode)
    }

    @Test
    fun `F11 hides the status bar and brings it back up to date`() {
        setDoc("one two three", 0)
        settle()
        assertTrue(
            "the bar should be showing at rest, saw ${visibleTexts()}",
            visibleTexts().any { it.contains("3 words") }
        )

        plainKey(KeyEvent.KEYCODE_F11)
        assertTrue(
            "F11 should hide the bar",
            visibleTexts().none { it.contains("words") }
        )

        // Nothing is written into a hidden bar, so this is the count it would
        // still be showing if bringing it back did not refresh it.
        setDoc("one two three four five six", 0)
        plainKey(KeyEvent.KEYCODE_F11)
        assertTrue(
            "the bar came back stale, showing ${visibleTexts()}",
            visibleTexts().any { it.contains("6 words") }
        )
    }

    @Test
    fun `ctrl R and F5 flash the panel and clear it again`() {
        assertTrue("nothing should be covering the page at rest", flashOverlay() == null)
        chord(KeyEvent.KEYCODE_R)
        assertTrue("Ctrl+R should black the panel out", flashOverlay() != null)
        advance(400)
        assertTrue("and take it away again", flashOverlay() == null)

        plainKey(KeyEvent.KEYCODE_F5)
        assertTrue("F5 is the same command", flashOverlay() != null)
        advance(400)
        assertTrue(flashOverlay() == null)
    }

    @Test
    fun `ctrl shift R steps the screen orientation`() {
        val before = Prefs(activity).orientation
        chord(KeyEvent.KEYCODE_R, shift = true)
        assertTrue(
            "Ctrl+Shift+R should change the orientation",
            Prefs(activity).orientation != before
        )
        assertEquals(
            "and ask the system for the one it settled on",
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, activity.requestedOrientation
        )
    }

    @Test
    fun `ctrl W reports the word count`() {
        setDoc("one two three", 0)
        chord(KeyEvent.KEYCODE_W)
        assertTrue(
            "expected a word count in the status bar, saw ${visibleTexts()}",
            visibleTexts().any { it.contains("3 words") && it.contains("characters") }
        )
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
    fun `undo still works after a chrome rebuild`() {
        setDoc("", 0)
        for (c in "word") editor.text.insert(editor.selectionStart, c.toString())
        idle()
        editor.setSelection(0, 4)
        chord(KeyEvent.KEYCODE_B)                 // a second, separate history step
        assertEquals("**word**", body())

        chord(KeyEvent.KEYCODE_EQUALS)            // replaces the entire view tree
        val after = findView(activity.window.decorView) { it is MarkdownEditor } as MarkdownEditor
        assertEquals("**word**", after.text.toString())

        // Two steps deep. A rebuild records its own setText, so undoing straight
        // to "" would also happen with no history at all — landing on "word" is
        // what proves the real history came across.
        assertTrue(after.undo())
        assertEquals("word", after.text.toString())
        // However many steps the typing became — that is a wall-clock rule — the
        // rest of the history has to be there too.
        while (after.undo()) { /* down to the bottom of the stack */ }
        assertEquals("", after.text.toString())
    }

    @Test
    fun `a rebuild keeps an open find bar and what was typed into it`() {
        setDoc("find the word here", 0)
        chord(KeyEvent.KEYCODE_F)
        val bar = findView(activity.window.decorView) { it is FindBar } as FindBar
        assertEquals(View.VISIBLE, bar.visibility)
        bar.setQuery("word")
        idle()

        chord(KeyEvent.KEYCODE_EQUALS)   // forces a full chrome rebuild
        val after = findView(activity.window.decorView) { it is FindBar } as FindBar
        assertEquals("the search should survive the rebuild", View.VISIBLE, after.visibility)
        assertEquals("word", after.queryText())
    }

    @Test
    fun `repeated scale changes settle on the right size`() {
        val start = Scale.ui
        repeat(4) { chord(KeyEvent.KEYCODE_EQUALS) }
        idle()
        assertTrue("four steps up should still land above the start", Scale.ui > start)
        assertEquals(Scale.ui, Prefs(activity).uiScale, 0.001f)
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
    fun `an unmodified key is not taken as a shortcut`() {
        setDoc("make this bold", 5, 9)
        // Every one of these is a chord's key. Without Ctrl the shortcut layer
        // has to leave it to whatever has focus, which for a writer typing a
        // sentence is the whole of the app working at all.
        plainKey(KeyEvent.KEYCODE_B)
        plainKey(KeyEvent.KEYCODE_N)
        plainKey(KeyEvent.KEYCODE_O)
        plainKey(KeyEvent.KEYCODE_P)
        plainKey(KeyEvent.KEYCODE_COMMA)
        plainKey(KeyEvent.KEYCODE_1)

        assertTrue("a plain letter ran a formatting command", !body().contains("*"))
        assertTrue("a plain letter ran a heading command", !body().contains("#"))
        assertTrue("a plain letter opened a panel", visiblePanel() == null)
        assertEquals("a plain letter made a document", 1, documentCount())
    }
}
