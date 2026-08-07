package com.dfwriter.slate

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * The document mirror. The Editable is a window onto [MarkdownEditor]'s own
 * document text, kept in sync by mirroring every change the watcher sees —
 * and a mirror that drifts is a save that silently loses words. So it is
 * fuzzed: hundreds of random edits through every door the app has, with the
 * mirror compared against the page after each one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DocMirrorTest {

    private fun editor(initial: String): MarkdownEditor {
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = Prefs(ctx)
        Scale.init(ctx, prefs)
        val e = MarkdownEditor(ctx)
        e.bind(prefs, MarkdownStyler(prefs))
        e.setText(initial)
        return e
    }

    private fun check(e: MarkdownEditor, step: String) {
        assertEquals("mirror != page after $step", e.text.toString(), e.documentTextRaw())
        assertEquals("length invariant after $step", e.text.length, e.docLength())
        assertEquals(
            "selection mapping after $step",
            e.selectionStart, e.globalSelectionStart()
        )
    }

    @Test
    fun `the mirror survives a storm of edits through every door`() {
        val e = editor("# Title\n\nSome opening prose to edit around.\n\n## Chapter\n\nMore words here.\n")
        val rnd = Random(42)
        val ic = e.onCreateInputConnection(EditorInfo())!!

        repeat(600) { step ->
            val len = e.text.length
            when (rnd.nextInt(9)) {
                0 -> { // type a character at a random spot
                    e.setSelection(rnd.nextInt(len + 1))
                    e.text.insert(e.selectionStart, "x")
                }
                1 -> { // delete a small range
                    if (len > 2) {
                        val a = rnd.nextInt(len - 1)
                        val b = (a + 1 + rnd.nextInt(3)).coerceAtMost(len)
                        e.text.delete(a, b)
                    }
                }
                2 -> { // replace a range with a word
                    if (len > 2) {
                        val a = rnd.nextInt(len - 1)
                        val b = (a + rnd.nextInt(4)).coerceAtMost(len)
                        e.text.replace(a, b, "word ")
                    }
                }
                3 -> { // the IME commits
                    e.setSelection(rnd.nextInt(len + 1))
                    ic.beginBatchEdit()
                    ic.commitText("ime", 1)
                    ic.endBatchEdit()
                }
                4 -> { // the IME deletes around the cursor
                    e.setSelection(rnd.nextInt(len + 1))
                    ic.deleteSurroundingText(rnd.nextInt(2), rnd.nextInt(2))
                }
                5 -> { // a formatting verb
                    val a = rnd.nextInt(len + 1)
                    e.setSelection(a, (a + rnd.nextInt(6)).coerceAtMost(len))
                    e.toggleWrap("**")
                }
                6 -> { // undo, redo — both are document changes
                    if (rnd.nextBoolean()) e.undo() else e.redo()
                }
                7 -> { // a whole-document replace, as replaceAll and loads do
                    e.setText(e.documentTextRaw().replace("word", "term") + "\n")
                }
                8 -> { // enter continuing a list via the raw editable
                    e.setSelection(rnd.nextInt(len + 1))
                    e.text.insert(e.selectionStart, "\n- item")
                }
            }
            check(e, "step $step (op class above)")
        }
    }

    @Test
    fun `a save snapshot repairs a broken mirror rather than trusting it`() {
        val e = editor("the page is the truth")
        // Sabotage the mirror directly — no app path does this; the check is
        // the belt for the day one appears.
        val raw = e.documentTextRaw()
        var repaired = false
        e.onMirrorRepair = { repaired = true }
        // Reach in via reflection to corrupt the private mirror.
        val f = MarkdownEditor::class.java.getDeclaredField("docText")
        f.isAccessible = true
        (f.get(e) as StringBuilder).append(" plus rot")
        assertEquals("the snapshot must return what the writer saw", raw, e.documentText())
        assertEquals("and leave the mirror repaired", raw, e.documentTextRaw())
        org.junit.Assert.assertTrue("and say that it did", repaired)
    }
}
