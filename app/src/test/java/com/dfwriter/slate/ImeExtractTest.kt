package com.dfwriter.slate

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What the IME is allowed to see of the document.
 *
 * The device's IME re-extracts the buffer through the InputConnection on
 * every edit, and it asks for all of it: measured on the Manta, that made a
 * keystroke cost ~37 µs per character of document — four seconds in a
 * 25k-word part, twenty-two in an undivided book — while the same keystroke
 * with the IME disconnected was instant. The editor therefore hands the IME
 * a window around the selection rather than the manuscript, and these tests
 * pin the window shut.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ImeExtractTest {

    private fun bigEditor(chars: Int, caretAt: Int): MarkdownEditor {
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = Prefs(ctx)
        Scale.init(ctx, prefs)
        val editor = MarkdownEditor(ctx)
        editor.bind(prefs, MarkdownStyler(prefs))
        val sb = StringBuilder(chars + 64)
        sb.append("# Title\n\n")
        val sentence = "The morning began badly and it went on from there. "
        while (sb.length < chars) sb.append(sentence)
        editor.setText(sb.toString())
        editor.setSelection(caretAt.coerceIn(0, editor.text.length))
        return editor
    }

    @Test
    fun `an extract is a window around the selection, not the manuscript`() {
        val editor = bigEditor(560_000, caretAt = 300_000)
        val out = android.view.inputmethod.ExtractedText()
        val req = ExtractedTextRequest().apply {
            hintMaxChars = 0   // "no hint" — the request an IME makes for everything
            hintMaxLines = 0
            flags = android.view.inputmethod.InputConnection.GET_EXTRACTED_TEXT_MONITOR
        }
        assertTrue("the editor should still report an extract", editor.extractText(req, out))
        assertNotNull(out.text)
        assertTrue(
            "the IME was handed ${out.text.length} characters of a 560k document",
            out.text.length <= 4_096
        )
        // Absolute position must survive the windowing, or the IME edits the
        // wrong part of the document.
        assertEquals(
            "the selection must map back to the caret",
            300_000, out.startOffset + out.selectionStart
        )
    }

    @Test
    fun `text before and after the cursor is bounded too`() {
        val editor = bigEditor(560_000, caretAt = 559_000)
        val ic = editor.onCreateInputConnection(EditorInfo())
        assertNotNull("this test needs a live input connection", ic)
        val before = ic!!.getTextBeforeCursor(Int.MAX_VALUE, 0)
        val after = ic.getTextAfterCursor(Int.MAX_VALUE, 0)
        assertTrue(
            "getTextBeforeCursor handed back ${before?.length} characters",
            (before?.length ?: 0) <= 4_096
        )
        assertTrue(
            "getTextAfterCursor handed back ${after?.length} characters",
            (after?.length ?: 0) <= 4_096
        )
    }

    @Test
    fun `the ime can still commit text through the wrapped connection`() {
        val editor = bigEditor(20_000, caretAt = 9)   // just after "# Title\n\n"
        val ic = editor.onCreateInputConnection(EditorInfo())!!
        ic.beginBatchEdit()
        ic.commitText("typed", 1)
        ic.endBatchEdit()
        assertEquals(
            "a commit must land at the caret exactly as before",
            "# Title\n\ntyped", editor.text.substring(0, 14)
        )
    }

    @Test
    fun `a small document is handed over whole`() {
        val editor = bigEditor(500, caretAt = 100)
        val out = android.view.inputmethod.ExtractedText()
        val req = ExtractedTextRequest().apply { hintMaxChars = 0 }
        assertTrue(editor.extractText(req, out))
        assertEquals(
            "a note-sized document should be extracted in full",
            editor.text.length, out.text.length
        )
        assertEquals(0, out.startOffset)
    }
}
