package com.dfwriter.slate

import android.text.Editable
import android.text.Spannable
import android.text.Spanned

/**
 * Turns the Markdown in the buffer into the thing you see, without ever changing
 * the buffer. Syntax markers are collapsed to zero width when the caret is
 * somewhere else and revealed in grey when the caret enters their line, which is
 * the whole of Typora's "what you see is what you mean" trick.
 *
 * Everything here is line-oriented on purpose. A keystroke restyles the lines it
 * touched and the two lines the caret moved between, so cost does not grow with
 * document length on a 1.8 GHz RK3566.
 */
class MarkdownStyler(private val prefs: Prefs) {

    data class Heading(val level: Int, val title: String, val offset: Int)

    /** Body size in pixels; recomputed whenever scale or point size changes. */
    var bodyPx: Float = 40f

    /** Set by the PDF exporter, whose canvas is in points rather than panel pixels. */
    var overrideBodyPx: Float? = null

    /** Set by the exporter to conceal markers regardless of the live preference. */
    var forceHideMarkers: Boolean? = null

    /** Width of the text column in pixels, used to lay out tables and images. */
    var contentWidthPx: Int = 0

    /** Folder of the open document, for resolving relative image paths. */
    var documentDir: java.io.File? = null

    /** Measures cell text so table columns can be sized. */
    var measure: android.text.TextPaint? = null

    /**
     * Invoked with the offset of an image that has finished decoding, so the
     * view can restyle that one line rather than the whole document.
     */
    var onImageReady: ((Int) -> Unit)? = null

    private val headingRatio = floatArrayOf(1.80f, 1.50f, 1.28f, 1.14f, 1.02f, 0.96f)

    private var hideEnabled = true

    fun refreshMetrics() {
        bodyPx = overrideBodyPx ?: Scale.pt(prefs.bodyPt)
        hideEnabled = forceHideMarkers ?: (prefs.hideMarkers && !prefs.sourceMode)
    }

    // ---------------------------------------------------------------- entry

    /**
     * Restyle every line intersecting [from]..[to], plus the caret's line.
     *
     * The caret's line is included because an edit reveals the markers there,
     * but a caller that already knows which lines it wants — a caret jump, an
     * image that has just decoded — passes [withCaretLine] false. Otherwise the
     * range is stretched from the line asked for all the way to the caret, and
     * jumping from the first line of a long document to the last would restyle
     * everything in between on the UI thread.
     */
    fun restyleRange(text: Editable, from: Int, to: Int, caret: Int, withCaretLine: Boolean = true) {
        refreshMetrics()
        val len = text.length
        if (len == 0) return

        var s = lineStartOf(text, from.coerceIn(0, len))
        var e = lineEndOf(text, to.coerceIn(0, len))
        if (withCaretLine && caret in 0..len) {
            s = minOf(s, lineStartOf(text, caret))
            e = maxOf(e, lineEndOf(text, caret))
        }
        // A fence delimiter anywhere in the touched range flips the meaning of
        // every following line, so widen to the end of the document.
        if (rangeTouchesFence(text, s, e)) e = len

        // Editing one cell changes every column width, so a table is restyled
        // whole or not at all.
        tableAt(text, s)?.let { s = minOf(s, it.start) }
        tableAt(text, e)?.let { e = maxOf(e, it.end) }

        clearSlateSpans(text, s, e)
        var inFence = fenceStateAt(text, s)
        var i = s
        while (i <= e && i <= len) {
            val ls = i
            val le = lineEndOf(text, ls)
            inFence = styleLine(text, ls, le, caret, inFence)
            if (le >= len) break
            i = le + 1
        }
    }

    fun restyleAll(text: Editable, caret: Int) = restyleRange(text, 0, text.length, caret)

    /**
     * Focus mode dims everything outside the caret's paragraph. Kept separate
     * from [restyleRange] because it changes on caret movement alone, and
     * because its colour must be applied last to win over marker colours.
     */
    fun applyFocus(text: Editable, caret: Int) {
        for (sp in text.getSpans(0, text.length, FocusSpan::class.java)) text.removeSpan(sp)
        if (!prefs.focusMode) return
        val len = text.length
        if (len == 0) return
        val c = caret.coerceIn(0, len)
        val ps = paragraphStart(text, c)
        val pe = paragraphEnd(text, c)
        if (ps > 0) text.setSpan(DimTextSpan(), 0, ps, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (pe < len) text.setSpan(DimTextSpan(), pe, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun clearAll(text: Spannable) = clearSlateSpans(text, 0, text.length)

    // ---------------------------------------------------------------- lines

    /** Styles one line; returns the fenced-code state for the following line. */
    private fun styleLine(
        text: Editable, ls: Int, le: Int, caret: Int, inFence: Boolean
    ): Boolean {
        val line = text.subSequence(ls, le).toString()
        val caretHere = caret in ls..le
        val reveal = !hideEnabled || caretHere

        // --- fenced code ------------------------------------------------
        if (isFenceLine(text, ls, le)) {
            paint(text, ls, le, CodeBlockSpan(0, ruleWidth()))
            markerRun(text, ls, le, reveal)
            return !inFence
        }
        if (inFence) {
            paint(text, ls, le, CodeBlockSpan(0, ruleWidth()))
            if (le > ls) text.setSpan(MonoSpan(), ls, le, EX)
            return true
        }

        // --- tables -------------------------------------------------------
        // Rendered only when the caret is elsewhere. On the caret's own line the
        // raw pipes come back, which is what makes the table editable at all.
        if (!reveal && line.contains('|')) {
            val table = tableAt(text, ls)
            if (table != null && styleTableLine(text, ls, le, table)) return false
        }

        // --- horizontal rule --------------------------------------------
        if (HR.matches(line) && line.isNotEmpty()) {
            paint(text, ls, le, RuleSpan(ruleWidth()))
            markerRun(text, ls, le, reveal)
            return false
        }

        var content = ls

        // --- blockquote --------------------------------------------------
        val quote = QUOTE.find(line)
        if (quote != null) {
            val depth = quote.value.count { it == '>' }
            val step = Math.round(bodyPx * 1.1f)
            paragraphSpan(
                text, ls, le,
                QuoteSpan(step * depth, ruleWidth(), Math.round(bodyPx * 0.25f))
            )
            content = ls + quote.value.length
            markerRun(text, ls, content, reveal)
            if (content < le) text.setSpan(MarkerSpan(0xFF3C3C3C.toInt()), content, le, EX)
        }

        val head = text.subSequence(content, le).toString()

        // --- heading -------------------------------------------------------
        val heading = HEADING.find(head)
        if (heading != null) {
            val level = heading.groupValues[1].length
            val px = Math.round(bodyPx * headingRatio[level - 1])
            val markEnd = content + heading.value.length
            text.setSpan(SizeSpan(px), content, le, EX)
            text.setSpan(WeightSpan(android.graphics.Typeface.BOLD), content, le, EX)
            paragraphSpan(text, ls, le, SpaceAboveSpan(Math.round(bodyPx * 0.55f)))
            markerRun(text, content, markEnd, reveal)
            styleInline(text, markEnd, le, reveal)
            return false
        }

        // --- lists ---------------------------------------------------------
        val list = LIST.find(head)
        if (list != null) {
            val indentChars = list.groupValues[1].length
            val markerStart = content + indentChars
            var markEnd = content + list.value.length
            val step = Math.round(bodyPx * 1.6f)
            val depth = indentChars / 2
            paragraphSpan(
                text, ls, le,
                HangingIndentSpan(step * depth, step * (depth + 1))
            )

            val task = TASK.find(text.subSequence(markEnd, le).toString())
            val ordered = list.groupValues[2].firstOrNull()?.isDigit() == true

            // Leading whitespace is collapsed in both states: the hanging
            // indent already positions the line, and showing the literal spaces
            // as well would indent it twice and shift it as the caret arrives.
            if (markerStart > content) text.setSpan(HiddenSpan(), content, markerStart, EX)

            if (ordered) {
                // Keep the number, quieten the delimiter and the trailing space.
                text.setSpan(MarkerSpan(), markerStart + list.groupValues[2].length - 1, markEnd, EX)
            } else {
                // Revealed or not, the marker occupies one bullet's width, so
                // the text after it never moves.
                val marker = text.subSequence(markerStart, markEnd).toString()
                text.setSpan(
                    if (reveal) GlyphSpan(marker.trimEnd(), 1.6f, Ink.MARKER)
                    else GlyphSpan(bulletFor(depth)),
                    markerStart, markEnd, EX
                )
            }

            if (task != null) {
                val ts = markEnd
                val te = markEnd + task.value.length
                val done = task.groupValues[1].lowercase() == "x"
                if (reveal) {
                    text.setSpan(MarkerSpan(), ts, te, EX)
                } else {
                    text.setSpan(GlyphSpan(if (done) "☑" else "☐", 1.7f), ts, te, EX)
                }
                if (done && te < le) text.setSpan(StrikeSpan(), te, le, EX)
                markEnd = te
            }
            styleInline(text, markEnd, le, reveal)
            return false
        }

        styleInline(text, content, le, reveal)
        return false
    }

    // --------------------------------------------------------------- inline

    private fun styleInline(text: Editable, from: Int, to: Int, reveal: Boolean) {
        if (to <= from) return
        val s = text.subSequence(from, to).toString()
        val claimed = BooleanArray(s.length)

        // Code first: nothing inside a code span is Markdown.
        apply(from, s, claimed, CODE) { st, en, m ->
            val ticks = m.groupValues[1].length
            text.setSpan(InlineCodeSpan(), st, en, EX)
            markerRun(text, st, st + ticks, reveal)
            markerRun(text, en - ticks, en, reveal)
        }
        apply(from, s, claimed, IMAGE) { st, en, m ->
            val alt = m.groupValues[1]
            if (!reveal && contentWidthPx > 0) {
                val file = ImageCache.resolve(m.groupValues[2], documentDir)
                if (file != null) {
                    val bmp = ImageCache.peek(file, contentWidthPx)
                    val broken = ImageCache.isBroken(file, contentWidthPx)
                    val span = ImageSpan(bmp, contentWidthPx, alt, broken, bodyPx)
                    text.setSpan(span, st, en, EX)
                    if (bmp == null && !broken) {
                        // Only the line this picture is on is restyled when it
                        // arrives. Restyling the document would look every other
                        // picture up again, and in a document with more images
                        // than the cache holds each lookup evicts and re-requests
                        // another one, for as long as the file stays open. The
                        // offset is read back off the span so it is still right
                        // if the text moved while the decode was running.
                        ImageCache.request(file, contentWidthPx) {
                            val at = text.getSpanStart(span)
                            onImageReady?.invoke(if (at >= 0) at else st)
                        }
                    }
                    return@apply
                }
            }
            text.setSpan(LinkTextSpan(), st + 2, st + 2 + alt.length, EX)
            markerRun(text, st, st + 2, reveal)
            markerRun(text, st + 2 + alt.length, en, reveal)
        }
        apply(from, s, claimed, LINK) { st, en, m ->
            val textLen = m.groupValues[1].length
            text.setSpan(LinkTextSpan(m.groupValues[2]), st + 1, st + 1 + textLen, EX)
            markerRun(text, st, st + 1, reveal)
            markerRun(text, st + 1 + textLen, en, reveal)
        }
        apply(from, s, claimed, BOLD_ITALIC) { st, en, _ ->
            text.setSpan(WeightSpan(android.graphics.Typeface.BOLD_ITALIC), st, en, EX)
            markerRun(text, st, st + 3, reveal); markerRun(text, en - 3, en, reveal)
        }
        apply(from, s, claimed, BOLD) { st, en, _ ->
            text.setSpan(WeightSpan(android.graphics.Typeface.BOLD), st, en, EX)
            markerRun(text, st, st + 2, reveal); markerRun(text, en - 2, en, reveal)
        }
        apply(from, s, claimed, STRIKE) { st, en, _ ->
            text.setSpan(StrikeSpan(), st, en, EX)
            markerRun(text, st, st + 2, reveal); markerRun(text, en - 2, en, reveal)
        }
        apply(from, s, claimed, ITALIC) { st, en, _ ->
            text.setSpan(WeightSpan(android.graphics.Typeface.ITALIC), st, en, EX)
            markerRun(text, st, st + 1, reveal); markerRun(text, en - 1, en, reveal)
        }
        apply(from, s, claimed, HIGHLIGHT) { st, en, _ ->
            text.setSpan(InlineCodeSpan(), st, en, EX)
            markerRun(text, st, st + 2, reveal); markerRun(text, en - 2, en, reveal)
        }
    }

    /**
     * Runs one inline rule over a line, skipping any character an earlier rule
     * has already claimed. Rule order therefore encodes precedence: code spans
     * run first, so nothing inside them is ever read as Markdown.
     */
    private inline fun apply(
        base: Int, line: String, claimed: BooleanArray, re: Regex,
        body: (start: Int, end: Int, m: MatchResult) -> Unit
    ) {
        for (m in re.findAll(line)) {
            val r = m.range
            if ((r.first..r.last).any { claimed[it] }) continue
            for (i in r) claimed[i] = true
            body(base + r.first, base + r.last + 1, m)
        }
    }

    /** Either collapse a marker run or paint it grey, depending on the caret. */
    private fun markerRun(text: Editable, start: Int, end: Int, reveal: Boolean) {
        if (end <= start) return
        text.setSpan(if (reveal) MarkerSpan() else HiddenSpan(), start, end, EX)
    }

    // --------------------------------------------------------------- outline

    // --------------------------------------------------------------- tables

    /** A contiguous run of pipe rows whose second line is the delimiter row. */
    class Table(
        val start: Int,
        val end: Int,
        val lineStarts: List<Int>,
        val widths: IntArray,
        val aligns: IntArray
    )

    /**
     * The table containing [offset], or null. Scans out from the line in both
     * directions, so it works whether styling starts at the top of the block or
     * in the middle of it after a single keystroke.
     */
    fun tableAt(text: CharSequence, offset: Int): Table? {
        val len = text.length
        if (len == 0 || offset > len) return null
        var ls = lineStartOf(text, offset.coerceIn(0, len))
        if (!looksLikeRow(text, ls)) return null

        // Walk back to the first row of the run.
        while (ls > 0) {
            val prev = lineStartOf(text, ls - 1)
            if (!looksLikeRow(text, prev)) break
            ls = prev
        }
        val starts = ArrayList<Int>()
        var i = ls
        while (i <= len && looksLikeRow(text, i)) {
            starts.add(i)
            val le = lineEndOf(text, i)
            if (le >= len) break
            i = le + 1
        }
        if (starts.size < 2) return null

        val delim = lineText(text, starts[1])
        if (!DELIMITER_ROW.matches(delim.trim())) return null

        val aligns = alignmentsOf(delim)
        val columns = maxOf(aligns.size, cellsOf(lineText(text, starts[0])).size)
        if (columns == 0) return null

        val widths = columnWidths(text, starts, columns)
        val end = lineEndOf(text, starts.last())
        return Table(starts.first(), end, starts, widths, padAligns(aligns, columns))
    }

    private fun looksLikeRow(text: CharSequence, lineStart: Int): Boolean {
        if (lineStart >= text.length) return false
        val le = lineEndOf(text, lineStart)
        if (le <= lineStart) return false
        var i = lineStart
        var pipe = false
        while (i < le) {
            if (text[i] == '|') pipe = true
            i++
        }
        return pipe
    }

    private fun lineText(text: CharSequence, lineStart: Int): String =
        text.subSequence(lineStart, lineEndOf(text, lineStart)).toString()

    /** Splits a row into cell texts, ignoring the outer pipes if present. */
    private fun cellsOf(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && s.isNotEmpty()) s = s.substring(0, s.length - 1)
        if (s.isEmpty()) return listOf("")
        return s.split('|').map { stripInline(it.trim()) }
    }

    /** Cells show their text, not their markup; the styling itself is not nested. */
    private fun stripInline(s: String): String = s
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("(?<![A-Za-z0-9_])__(.+?)__(?![A-Za-z0-9_])"), "$1")
        .replace(Regex("\\*(.+?)\\*"), "$1")
        .replace(Regex("`(.+?)`"), "$1")
        .replace(Regex("~~(.+?)~~"), "$1")
        .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")

    private fun alignmentsOf(delim: String): IntArray =
        cellsOf(delim).map {
            val t = it.trim()
            when {
                t.startsWith(":") && t.endsWith(":") -> TableRowSpan.ALIGN_CENTER
                t.endsWith(":") -> TableRowSpan.ALIGN_RIGHT
                else -> TableRowSpan.ALIGN_LEFT
            }
        }.toIntArray()

    private fun padAligns(a: IntArray, columns: Int): IntArray =
        IntArray(columns) { a.getOrElse(it) { TableRowSpan.ALIGN_LEFT } }

    /**
     * Columns take their natural width where they fit, then the whole table is
     * scaled to the text column so it lines up with the prose around it.
     */
    private fun columnWidths(
        text: CharSequence, lineStarts: List<Int>, columns: Int
    ): IntArray {
        val paint = measure
        val pad = tableCellPad()
        val natural = FloatArray(columns) { 0f }

        for ((row, ls) in lineStarts.withIndex()) {
            if (row == 1) continue                       // the delimiter row
            val cells = cellsOf(lineText(text, ls))
            for (c in 0 until columns) {
                val s = cells.getOrElse(c) { "" }
                val w = if (paint != null) paint.measureText(s) else s.length * bodyPx * 0.5f
                // The header is drawn bold, which is a little wider.
                val weighted = if (row == 0) w * 1.08f else w
                if (weighted > natural[c]) natural[c] = weighted
            }
        }

        val avail = (contentWidthPx.takeIf { it > 0 } ?: Math.round(bodyPx * 30)).toFloat()
        val minCell = bodyPx * 1.6f + pad * 2
        var total = 0f
        for (c in 0 until columns) {
            natural[c] = maxOf(natural[c] + pad * 2, minCell)
            total += natural[c]
        }
        if (total <= 0f) return IntArray(columns) { Math.round(avail / columns) }

        val scale = avail / total
        val out = IntArray(columns)
        var used = 0
        for (c in 0 until columns) {
            out[c] = Math.max(1, Math.round(natural[c] * scale))
            used += out[c]
        }
        // Absorb the rounding drift into the last column so the grid closes.
        out[columns - 1] += Math.round(avail) - used
        if (out[columns - 1] < 1) out[columns - 1] = 1
        return out
    }

    private fun tableCellPad(): Float = bodyPx * 0.45f

    /** Renders one line of a table. Returns false if it is not part of one. */
    private fun styleTableLine(text: Editable, ls: Int, le: Int, table: Table): Boolean {
        val index = table.lineStarts.indexOf(ls)
        if (index < 0 || le <= ls) return false
        val rule = maxOf(1, Math.round(bodyPx * 0.05f))

        if (index == 1) {
            text.setSpan(TableDividerSpan(table.widths, rule), ls, le, EX)
            return true
        }
        val cells = cellsOf(text.subSequence(ls, le).toString())
        text.setSpan(
            TableRowSpan(
                cells, table.widths, table.aligns,
                header = index == 0,
                pad = tableCellPad(),
                rule = rule,
                firstRow = index == 0,
                lastRow = ls == table.lineStarts.last()
            ),
            ls, le, EX
        )
        return true
    }

    // ----------------------------------------------------------------- util

    // Derived from the body size, not from the panel, so the PDF exporter gets
    // proportional rules on its own points-based canvas.
    private fun ruleWidth(): Int = maxOf(1, Math.round(bodyPx * 0.085f))

    private fun bulletFor(depth: Int): String = when (depth % 3) {
        0 -> "•"
        1 -> "◦"
        else -> "▪"
    }

    private fun paint(text: Editable, ls: Int, le: Int, span: Any) {
        text.setSpan(span, ls, maxOf(le, ls + 1).coerceAtMost(text.length), EX)
    }

    private fun paragraphSpan(text: Editable, ls: Int, le: Int, span: Any) {
        val end = (le + 1).coerceAtMost(text.length)
        if (end > ls) text.setSpan(span, ls, end, EX)
    }

    private fun clearSlateSpans(text: Spannable, from: Int, to: Int) {
        for (sp in text.getSpans(from, to, SlateSpan::class.java)) {
            if (sp is FocusSpan) continue
            text.removeSpan(sp)
        }
    }

    /**
     * Tilde fences count too. Missing them here left every line after a `~~~`
     * styled with a stale in-fence state, because the restyle was never widened
     * to the end of the document.
     */
    private fun rangeTouchesFence(text: CharSequence, from: Int, to: Int): Boolean {
        var i = from
        while (i < to && i < text.length) {
            val c = text[i]
            if ((c == '`' || c == '~') && i + 2 < text.length &&
                text[i + 1] == c && text[i + 2] == c
            ) return true
            i++
        }
        return false
    }

    // Whether an offset is inside a fence depends on every delimiter before it,
    // so the obvious implementation walks the whole buffer on every keystroke.
    // The delimiter positions are kept instead, and only the part of the
    // document at or after the last edit is ever scanned again.
    private var fenceOwner: CharSequence? = null
    private var fenceLength = -1
    private var fenceScanned = 0
    private var fenceDirty = Int.MAX_VALUE
    private val fenceStarts = ArrayList<Int>()

    /**
     * Told by the editor where the buffer is about to change, so the delimiters
     * found before that point can be kept. Callers that do not report an edit —
     * the exporter, the tests — are covered by the length check below; only a
     * silent change that leaves the length alone could fool it.
     */
    fun fencesChangedAt(offset: Int) {
        if (offset < fenceDirty) fenceDirty = offset
    }

    /** True when [offset], always a line start, sits inside a fenced block. */
    private fun fenceStateAt(text: CharSequence, offset: Int): Boolean {
        val dirty = fenceDirty
        fenceDirty = Int.MAX_VALUE
        if (text !== fenceOwner || (dirty == Int.MAX_VALUE && text.length != fenceLength)) {
            fenceOwner = text
            fenceStarts.clear()
            fenceScanned = 0
        } else if (dirty < fenceScanned) {
            // The edited line itself may have just become, or stopped being, a
            // delimiter, so the rescan starts at the top of it.
            val from = lineStartOf(text, dirty.coerceIn(0, text.length))
            while (fenceStarts.isNotEmpty() && fenceStarts.last() >= from) {
                fenceStarts.removeAt(fenceStarts.size - 1)
            }
            fenceScanned = from
        }
        fenceLength = text.length

        // Extend the table to cover [offset], walking the characters directly
        // rather than allocating a string per line.
        var i = fenceScanned
        while (i < offset) {
            var e = i
            while (e < text.length && text[e] != '\n') e++
            if (isFenceLine(text, i, e)) fenceStarts.add(i)
            i = e + 1
        }
        if (i > fenceScanned) fenceScanned = i

        var count = 0
        for (s in fenceStarts) {
            if (s >= offset) break
            count++
        }
        return count % 2 == 1
    }

    companion object {
        private const val EX = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

        private val DELIMITER_ROW =
            Regex("^\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?$")

        private val HR = Regex("^\\s{0,3}([-*_])\\s*(\\1\\s*){2,}$")
        private val QUOTE = Regex("^\\s{0,3}(>\\s?)+")
        private val HEADING = Regex("^(#{1,6})\\s+")
        private val LIST = Regex("^([ \\t]*)([-*+]|\\d{1,9}[.)])[ \\t]+")
        private val TASK = Regex("^\\[([ xX])\\][ \\t]+")

        private val CODE = Regex("(`+)(?!`)([^`]|[^`][\\s\\S]*?[^`])\\1(?!`)")
        private val IMAGE = Regex("!\\[([^\\]\\n]*)\\]\\(([^)\\n]*)\\)")
        private val LINK = Regex("\\[([^\\]\\n]*)\\]\\(([^)\\n]*)\\)")
        private val BOLD_ITALIC = Regex("\\*{3}(?!\\s)([^*\\n]+?)(?<!\\s)\\*{3}")
        private val BOLD = Regex("(?:\\*{2}(?!\\s)([^*\\n]+?)(?<!\\s)\\*{2})|(?:_{2}(?!\\s)([^_\\n]+?)(?<!\\s)_{2})")
        private val ITALIC = Regex("(?:\\*(?!\\s)([^*\\n]+?)(?<!\\s)\\*)|(?:(?<![A-Za-z0-9_])_(?!\\s)([^_\\n]+?)(?<!\\s)_(?![A-Za-z0-9_]))")
        private val STRIKE = Regex("~{2}(?!\\s)([^~\\n]+?)(?<!\\s)~{2}")
        private val HIGHLIGHT = Regex("={2}(?!\\s)([^=\\n]+?)(?<!\\s)={2}")

        /**
         * The one thing that decides what a fence is. Two answers to that
         * question — one for the line being styled and another for the state it
         * inherits — made a document render differently depending on where it
         * was edited, and a fence such as ```{r} flip everything after it.
         *
         * Internal because [Manuscript] must agree with the renderer about
         * where a fence is, or a division could cut inside one.
         */
        internal fun isFenceLine(text: CharSequence, start: Int, end: Int): Boolean {
            var i = start
            var lead = 0
            while (i < end && text[i] == ' ' && lead < 4) { i++; lead++ }
            if (lead > 3 || i >= end) return false
            val ch = text[i]
            if (ch != '`' && ch != '~') return false
            var run = 0
            while (i < end && text[i] == ch) { i++; run++ }
            if (run < 3) return false
            // Only an info string may follow, and it may not contain the fence char.
            while (i < end) {
                val c = text[i]
                if (c == ch) return false
                i++
            }
            return true
        }

        /** Headings in document order, skipping anything inside a code fence. */
        fun outline(text: CharSequence): List<Heading> {
            val out = ArrayList<Heading>()
            var i = 0
            var inFence = false
            val len = text.length
            while (i < len) {
                var e = i
                while (e < len && text[e] != '\n') e++
                val line = text.subSequence(i, e).toString()
                if (isFenceLine(text, i, e)) {
                    inFence = !inFence
                } else if (!inFence) {
                    val m = HEADING.find(line)
                    if (m != null) {
                        out.add(
                            Heading(
                                m.groupValues[1].length,
                                line.substring(m.value.length).trim().ifEmpty { "—" },
                                i
                            )
                        )
                    }
                }
                i = e + 1
            }
            return out
        }

        fun lineStartOf(t: CharSequence, at: Int): Int {
            var i = at.coerceIn(0, t.length)
            while (i > 0 && t[i - 1] != '\n') i--
            return i
        }

        fun lineEndOf(t: CharSequence, at: Int): Int {
            var i = at.coerceIn(0, t.length)
            while (i < t.length && t[i] != '\n') i++
            return i
        }

        fun paragraphStart(t: CharSequence, at: Int): Int {
            var i = lineStartOf(t, at)
            while (i > 0) {
                val prevStart = lineStartOf(t, i - 1)
                if (t.subSequence(prevStart, i - 1).isBlank()) break
                i = prevStart
            }
            return i
        }

        fun paragraphEnd(t: CharSequence, at: Int): Int {
            var i = lineEndOf(t, at)
            while (i < t.length) {
                val nextEnd = lineEndOf(t, i + 1)
                if (t.subSequence(i + 1, nextEnd).isBlank()) break
                i = nextEnd
            }
            return i
        }
    }
}
