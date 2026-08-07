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
import java.io.File

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

    private fun bookEditor(words: Int): MarkdownEditor {
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = Prefs(ctx)
        Scale.init(ctx, prefs)
        val editor = MarkdownEditor(ctx)
        editor.bind(prefs, MarkdownStyler(prefs))
        editor.setText(book(words))
        // Measured as well as laid out: without a measure pass the text Layout
        // is never built, there is no viewport to read, and the window silently
        // falls back to its fixed opening stretch — which would make a scroll
        // test prove nothing at all.
        editor.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1600, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1200, android.view.View.MeasureSpec.EXACTLY)
        )
        editor.layout(0, 0, 1600, 1200)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        editor.restyleNow()
        return editor
    }

    private fun spanCount(e: MarkdownEditor) =
        e.text.getSpans(0, e.text.length, SlateSpan::class.java).size

    /**
     * Hosted in a real activity: a standalone view never gets a text Layout
     * under Robolectric, and without one there is no viewport, so the window
     * falls back to its fixed opening stretch and a scroll test proves nothing.
     */
    private fun activityEditor(words: Int): Pair<MarkdownEditor, MainActivity> {
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = Prefs(ctx)
        val lib = File(ctx.cacheDir, "bench-${System.nanoTime()}").apply { mkdirs() }
        prefs.libraryPath = lib.absolutePath
        val doc = File(lib, "book.md")
        doc.writeText(book(words))
        prefs.lastFile = doc.absolutePath
        prefs.lastCaret = 0
        File(ctx.filesDir, "scratch.md").delete()
        File(ctx.filesDir, "scratch.path").delete()

        val a = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup().get()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        fun find(v: android.view.View): MarkdownEditor? = when {
            v is MarkdownEditor -> v
            v is android.view.ViewGroup ->
                (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return find(a.window.decorView)!! to a
    }

    @Test
    fun `scrolling through a book extends the window without unbounding it`() {
        val (editor, _) = activityEditor(40_000)
        assertTrue(
            "no viewport means this test is not exercising the window at all",
            editor.visibleOffsets() != null
        )
        val start = spanCount(editor)

        // Total work per screen, the deferred widening included: the loop idles
        // the looper inside the timing. On the device that part runs after the
        // scroll rather than inside it, so this is the pessimistic reading.
        var worst = 0.0
        repeat(15) { step ->
            val t0 = System.nanoTime()
            editor.scrollTo(0, (step + 1) * editor.height)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            if (ms > worst) worst = ms
        }
        val after = spanCount(editor)
        println(
            "BENCH scrolling: spans $start -> $after, worst step %.2fms, window ${editor.styledWindow()}"
                .format(worst)
        )

        assertTrue("a scroll step cost %.1fms".format(worst), worst < 250.0)
        assertTrue(
            "styling grew without bound while scrolling: $start -> $after spans",
            after < start * 12 + 500
        )
    }

    /**
     * A fling can outrun the styled window's margin, so the widening that
     * follows overlaps the viewport instead of trailing it. The spans it adds
     * there include ones DynamicLayout will not reflow for on its own, so the
     * extension must ask for a layout pass — without one, stale line heights
     * draw one line over another on the very page being read.
     */
    @Test
    fun `an extension that reaches the page asks for layout`() {
        // The un-paged path — still shipped as the escape hatch, and its
        // span-window extension still owes a layout pass when it touches
        // the screen. The paged path supersedes this with splices.
        Prefs(RuntimeEnvironment.getApplication()).pagedBuffer = false
        val (editor, _) = activityEditor(40_000)
        val (s0, e0) = editor.styledWindow()
        assertTrue("this test needs a bounded window to extend", e0 < editor.text.length)
        // Settle whatever layout work start-up left behind, so the pass this
        // test counts can only be the scroll's own.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        // isLayoutRequested cannot be asserted here: the same idle that runs
        // the deferred widening also runs the traversal that clears it. A
        // global-layout listener only fires when a layout pass actually runs.
        var layoutPasses = 0
        editor.viewTreeObserver.addOnGlobalLayoutListener { layoutPasses++ }

        // Land the end of the styled window mid-screen, as a fling would.
        val l = editor.layout!!
        val y = l.getLineTop(l.getLineForOffset(e0)) - editor.height / 2
        editor.scrollTo(0, y.coerceAtLeast(0))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val (s1, e1) = editor.styledWindow()
        assertTrue("the window should have extended downward, $e0 -> $e1", e1 > e0)
        assertTrue("and not been rebuilt, which would prove nothing: $s0 -> $s1", s1 <= s0)
        assertTrue(
            "an extension overlapping the page must cause a layout pass",
            layoutPasses > 0
        )
    }

    /**
     * The editor's own cost, which is the one the writer feels: opening a
     * document, and toggling a mode. Both go through restyleNow, which styles a
     * window around the page rather than the whole buffer.
     */
    @Test
    fun `opening and toggling cost the page rather than the book`() {
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = Prefs(ctx)
        Scale.init(ctx, prefs)

        println("BENCH ── words | restyleNow (ms) | spans held")
        val costs = HashMap<Int, Double>()
        val spans = HashMap<Int, Int>()

        for (w in listOf(10_000, 50_000, 100_000)) {
            val editor = MarkdownEditor(ctx)
            editor.bind(prefs, MarkdownStyler(prefs))
            editor.layout(0, 0, 1600, 1200)
            editor.setText(book(w))
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

            val ms = median(7, warmup = 3) { editor.restyleNow() }
            val held = editor.text.getSpans(0, editor.text.length, SlateSpan::class.java).size

            costs[w] = ms
            spans[w] = held
            println("BENCH %,7d | %15.2f | %11d".format(w, ms, held))
        }

        // Both have to stay flat. If either climbs with the length of the
        // document then the window is not doing its job and a manuscript is
        // back to paying for itself on every open and every toggle.
        val small = costs[10_000]!!
        val large = costs[100_000]!!
        assertTrue(
            "restyleNow cost ${"%.1f".format(large)}ms at 100k words against " +
                "${"%.1f".format(small)}ms at 10k — it is still styling the whole document",
            large < small * 6 + 5
        )
        assertTrue(
            "the buffer held ${spans[100_000]} spans at 100k words against " +
                "${spans[10_000]} at 10k — the window is not bounding them",
            spans[100_000]!! < spans[10_000]!! * 6 + 500
        )
    }
}
