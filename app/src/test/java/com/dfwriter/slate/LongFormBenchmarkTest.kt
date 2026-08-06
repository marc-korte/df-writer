package com.dfwriter.slate

import android.text.SpannableStringBuilder
import android.text.TextPaint
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * How the app behaves on a manuscript rather than a note.
 *
 * These are not assertions about wall-clock speed on the Manta — this runs on a
 * desktop JVM, which is far quicker than an RK3566. What they establish is the
 * *shape* of each cost: anything that grows with the length of the document and
 * runs once per keystroke is disqualifying for long-form writing whatever the
 * constant factor happens to be, and anything flat will stay flat on slower
 * hardware too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LongFormBenchmarkTest {

    private lateinit var styler: MarkdownStyler
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        prefs = Prefs(ctx)
        Scale.init(ctx, prefs)
        styler = MarkdownStyler(prefs).apply {
            overrideBodyPx = 40f
            contentWidthPx = 1900
            measure = TextPaint().apply { textSize = 40f }
        }
    }

    /** A novel-shaped document: chapters, prose, the odd list and emphasis. */
    private fun book(words: Int): String {
        val sb = StringBuilder(words * 7)
        val sentence = "The morning began badly and it went on from there, as mornings will "
        var count = 0
        var chapter = 1
        while (count < words) {
            sb.append("\n## Chapter ").append(chapter).append(" — The Long Road\n\n")
            count += 8
            repeat(24) {
                sb.append("A paragraph of **ordinary** prose with some *emphasis* in it. ")
                repeat(6) { sb.append(sentence) }
                sb.append("\n\n")
                count += 90
            }
            sb.append("- a note to self\n- and another\n\n")
            count += 8
            chapter++
        }
        return sb.toString()
    }

    /**
     * Warmed before it is timed. Without that the first size measured carries
     * the cost of compiling everything the later ones then run for free, which
     * reads as the work getting cheaper as the document grows.
     */
    private fun median(runs: Int, warmup: Int, block: () -> Unit): Double {
        repeat(warmup) { block() }
        val times = ArrayList<Double>(runs)
        repeat(runs) {
            val t0 = System.nanoTime()
            block()
            times.add((System.nanoTime() - t0) / 1_000_000.0)
        }
        times.sort()
        return times[times.size / 2]
    }

    @Test
    fun `where the cost is on a book-sized document`() {
        val sizes = listOf(10_000, 50_000, 100_000)
        println("BENCH ── words | chars | countWords | outline | toString | restyleAll | keystroke")

        val keystrokeCosts = HashMap<Int, Double>()
        val wordCountCosts = HashMap<Int, Double>()

        for (w in sizes) {
            val text = book(w)
            val chars = text.length

            // Runs on every keystroke, from onEdit -> updateStatus.
            val countMs = median(21, warmup = 10) { DocStore.countWords(text) }

            // Runs when the contents drawer is opened.
            val outlineMs = median(11, warmup = 5) { MarkdownStyler.outline(text) }

            // Runs when the document is opened, and on undo, and on every mode
            // toggle, and after the column width first arrives.
            val sb = SpannableStringBuilder(text)

            // Runs on every autosave tick and every pause, on the main thread.
            // Measured on the buffer, not on a String: the app copies out of an
            // Editable, where toString is a real copy rather than a no-op.
            val copyMs = median(21, warmup = 10) { sb.toString().length }

            val allMs = median(5, warmup = 2) { styler.restyleAll(sb, -1) }

            // The realistic edit: one character, in the middle of the book.
            val mid = chars / 2
            val editMs = median(41, warmup = 20) { styler.restyleRange(sb, mid, mid + 1, mid) }

            keystrokeCosts[w] = editMs
            wordCountCosts[w] = countMs
            println(
                "BENCH %,7d | %,8d | %8.2f | %7.2f | %8.2f | %10.2f | %9.3f"
                    .format(w, chars, countMs, outlineMs, copyMs, allMs, editMs)
            )
        }

        // The shape, not the speed. A per-keystroke cost that grows with the
        // document is the thing that makes a book impossible to type into.
        val small = wordCountCosts[10_000]!!
        val large = wordCountCosts[100_000]!!
        println("BENCH word count grew %.1fx for 10x the document".format(large / small))

        val editSmall = keystrokeCosts[10_000]!!
        val editLarge = keystrokeCosts[100_000]!!
        println("BENCH keystroke restyle grew %.1fx for 10x the document".format(editLarge / editSmall))

        assertTrue("the benchmark itself should have run", large > 0.0)
    }

    /**
     * Where the super-linear cost in a full restyle actually sits: putting the
     * spans on, or taking the previous ones off again.
     */
    @Test
    fun `what makes a full restyle grow faster than the document`() {
        println("BENCH ── words | first pass (no spans yet) | repeat pass (must clear) | visible window")
        for (w in listOf(10_000, 50_000, 100_000)) {
            val text = book(w)

            // A buffer that has never been styled: nothing to remove first.
            val firstMs = median(5, warmup = 1) {
                styler.restyleAll(SpannableStringBuilder(text), -1)
            }

            // A buffer already carrying a full set of spans, as it would be on
            // an undo or a mode toggle.
            val styled = SpannableStringBuilder(text)
            styler.restyleAll(styled, -1)
            val repeatMs = median(5, warmup = 2) { styler.restyleAll(styled, -1) }

            // What styling only what is on screen would cost instead.
            val windowMs = median(21, warmup = 10) {
                styler.restyleRange(styled, 0, minOf(4_000, text.length), -1)
            }

            println(
                "BENCH %,7d | %22.2f | %24.2f | %14.3f"
                    .format(w, firstMs, repeatMs, windowMs)
            )
        }
    }

    /**
     * What an undo now costs, against what it used to. Undo replaces a run of
     * text and then has to restyle: over the whole document, or over the range
     * it actually changed.
     */
    @Test
    fun `undo costs the edit rather than the document`() {
        println("BENCH ── words | whole-document restyle | changed-range restyle")
        val costs = HashMap<Int, Double>()
        for (w in listOf(10_000, 50_000, 100_000)) {
            val text = book(w)
            val styled = SpannableStringBuilder(text)
            styler.restyleAll(styled, -1)
            val mid = text.length / 2

            val wholeMs = median(5, warmup = 2) { styler.restyleAll(styled, mid) }
            val rangeMs = median(41, warmup = 20) {
                styler.restyleRange(styled, mid, mid + 12, mid)
            }
            println("BENCH %,7d | %21.2f | %21.3f".format(w, wholeMs, rangeMs))
            costs[w] = rangeMs
        }

        // The guard this file exists for. Editing has to cost the edit, not the
        // manuscript: if someone puts a whole-document restyle back on the undo
        // or keystroke path, this is what says so. Generous, because a desktop
        // JVM under a test runner is noisy — it is the shape being asserted, an
        // order of magnitude, not a millisecond count.
        val small = costs[10_000]!!
        val large = costs[100_000]!!
        assertTrue(
            "an edit in a 100k-word document cost ${"%.2f".format(large)}ms against " +
                "${"%.2f".format(small)}ms in a 10k-word one — editing is scaling " +
                "with the document again",
            large < small * 8 + 5
        )
    }
}
