package com.dfwriter.slate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import kotlin.math.max
import kotlin.math.min

class MarkdownEditor @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : EditText(context, attrs) {

    lateinit var prefs: Prefs
    lateinit var styler: MarkdownStyler

    var onEdit: ((Int) -> Unit)? = null
    var onCaretMoved: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.TEXT }
    private var lastCaretLineStart = -1
    private var lastCaretLineEnd = -1
    private var styling = false
    private var pendingRestyle: Runnable? = null

    fun bind(prefs: Prefs, styler: MarkdownStyler) {
        this.prefs = prefs
        this.styler = styler

        background = null
        setPadding(0, 0, 0, 0)
        gravity = android.view.Gravity.TOP or android.view.Gravity.START
        setTextColor(Ink.TEXT)
        highlightColor = 0x33000000
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false

        // The framework caret blinks twice a second. On an E Ink panel that is a
        // partial refresh twice a second, forever. We paint our own instead.
        isCursorVisible = false

        inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN or
                android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setHorizontallyScrolling(false)
        isSingleLine = false
        maxLines = Int.MAX_VALUE

        addTextChangedListener(object : TextWatcher {
            var changeStart = 0
            var changeEnd = 0
            var removed: CharSequence = ""
            var inserted: CharSequence = ""

            override fun beforeTextChanged(s: CharSequence?, st: Int, count: Int, after: Int) {
                removed = s?.subSequence(st, st + count)?.toString() ?: ""
            }

            override fun onTextChanged(s: CharSequence?, st: Int, before: Int, count: Int) {
                changeStart = st
                changeEnd = st + count
                inserted = s?.subSequence(st, st + count)?.toString() ?: ""
            }

            override fun afterTextChanged(e: Editable?) {
                if (styling || e == null) return
                record(changeStart, removed, inserted)
                onEdit?.invoke(e.length)
                val big = (changeEnd - changeStart) > 240
                scheduleRestyle(changeStart, changeEnd, if (big) 110L else 0L)
            }
        })

        applyMetrics()
    }

    // ---------------------------------------------------------- undo history

    /**
     * A writer reaches for Ctrl+Z constantly and EditText has no undo of its
     * own, so one is kept here. Consecutive typing coalesces into a single step,
     * because undoing one character at a time is not undo, it is punishment.
     */
    private class Change(
        val start: Int,
        val before: CharSequence,
        var after: CharSequence,
        var at: Long
    )

    private val undoStack = ArrayList<Change>()
    private val redoStack = ArrayList<Change>()
    private var applyingHistory = false

    private fun record(start: Int, removed: CharSequence, inserted: CharSequence) {
        if (applyingHistory) return
        if (removed.isEmpty() && inserted.isEmpty()) return
        redoStack.clear()

        val now = System.currentTimeMillis()
        val last = undoStack.lastOrNull()
        val isPlainTyping = removed.isEmpty() && inserted.length == 1 &&
                inserted[0] != '\n' && !inserted[0].isWhitespace()

        if (last != null && isPlainTyping && last.before.isEmpty() &&
            start == last.start + last.after.length &&
            now - last.at < COALESCE_MS &&
            !last.after.contains('\n')
        ) {
            last.after = last.after.toString() + inserted
            last.at = now
            return
        }

        undoStack.add(Change(start, removed.toString(), inserted.toString(), now))
        if (undoStack.size > MAX_HISTORY) undoStack.removeAt(0)
    }

    fun undo(): Boolean = step(undoStack, redoStack, undoing = true)

    fun redo(): Boolean = step(redoStack, undoStack, undoing = false)

    private fun step(from: ArrayList<Change>, to: ArrayList<Change>, undoing: Boolean): Boolean {
        if (from.isEmpty()) return false
        val c = from.removeAt(from.size - 1)
        val e = text ?: return false
        val old = if (undoing) c.after else c.before
        val new = if (undoing) c.before else c.after
        if (c.start > e.length || c.start + old.length > e.length) {
            from.clear(); to.clear()
            return false
        }
        applyingHistory = true
        try {
            e.replace(c.start, c.start + old.length, new)
            setSelection((c.start + new.length).coerceIn(0, e.length))
        } finally {
            applyingHistory = false
        }
        to.add(c)
        restyleNow()
        return true
    }

    fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
    }

    // ------------------------------------------------------------- metrics

    fun applyMetrics() {
        styler.refreshMetrics()
        typeface = when (prefs.typeface) {
            SerifChoice.SERIF -> Typeface.SERIF
            SerifChoice.SANS -> Typeface.SANS_SERIF
            SerifChoice.MONO -> Typeface.MONOSPACE
        }
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Scale.pt(prefs.bodyPt))
        setLineSpacing(0f, prefs.lineSpacing)
        caretPaint.color = Ink.TEXT
        requestLayout()
        layoutColumn()
        restyleNow()
    }

    /**
     * Holds the text to a fixed measure and centres it. A 2560px-wide panel with
     * edge-to-edge text is unreadable; roughly 70 characters is the comfortable
     * line length that Typora's themes also target.
     */
    private fun layoutColumn() {
        val w = width
        // The framework can measure this view before bind() has run.
        if (w <= 0 || !::prefs.isInitialized) return
        val minSide = Scale.mmInt(6f)
        val chars = prefs.measureChars
        val side: Int = if (chars <= 0) {
            minSide
        } else {
            val em = paint.measureText("abcdefghijklmnopqrstuvwxyz ") / 27f
            val column = min((w - minSide * 2).toFloat(), em * chars)
            max(minSide.toFloat(), (w - column) / 2f).toInt()
        }
        val top = Scale.ptInt(26f)
        val bottom = if (prefs.typewriterMode) (height / 2) else Scale.ptInt(48f)
        setPadding(side, top, side, max(bottom, Scale.ptInt(48f)))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layoutColumn()
    }

    // ------------------------------------------------------------- styling

    private fun scheduleRestyle(from: Int, to: Int, delay: Long) {
        pendingRestyle?.let { handler.removeCallbacks(it) }
        val r = Runnable { runStyle(from, to) }
        pendingRestyle = r
        if (delay <= 0L) r.run() else handler.postDelayed(r, delay)
    }

    private fun runStyle(from: Int, to: Int) {
        val e = text ?: return
        styling = true
        try {
            styler.restyleRange(e, from, to, selectionStart)
            styler.applyFocus(e, selectionStart)
        } finally {
            styling = false
        }
        rememberCaretLine()
    }

    fun restyleNow() {
        val e = text ?: return
        styling = true
        try {
            styler.restyleAll(e, selectionStart)
            styler.applyFocus(e, selectionStart)
        } finally {
            styling = false
        }
        rememberCaretLine()
        invalidate()
    }

    private fun rememberCaretLine() {
        val e = text ?: return
        lastCaretLineStart = MarkdownStyler.lineStartOf(e, selectionStart)
        lastCaretLineEnd = MarkdownStyler.lineEndOf(e, selectionStart)
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (styling || !::styler.isInitialized) return
        val e = text ?: return
        val ls = MarkdownStyler.lineStartOf(e, selStart)
        val le = MarkdownStyler.lineEndOf(e, selStart)
        val movedLine = ls != lastCaretLineStart

        styling = true
        try {
            if (movedLine && lastCaretLineStart >= 0 && lastCaretLineStart <= e.length) {
                // Re-hide the markers on the line the caret just left.
                styler.restyleRange(
                    e, lastCaretLineStart, min(lastCaretLineEnd, e.length), selStart
                )
            }
            if (movedLine) styler.restyleRange(e, ls, le, selStart)
            if (prefs.focusMode) styler.applyFocus(e, selStart)
        } finally {
            styling = false
        }
        lastCaretLineStart = ls
        lastCaretLineEnd = le

        if (prefs.typewriterMode) post { centreCaret() }
        onCaretMoved?.invoke()
        invalidate()
    }

    // ---------------------------------------------------------------- caret

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isFocused) return
        val l = layout ?: return
        val sel = selectionStart
        if (sel < 0 || sel != selectionEnd) return

        val line = l.getLineForOffset(sel)
        val x = l.getPrimaryHorizontal(sel) + totalPaddingLeft - scrollX
        val top = l.getLineTop(line) + totalPaddingTop - scrollY
        val bottom = l.getLineBottom(line) + totalPaddingTop - scrollY
        val w = max(2f, Scale.pt(1.5f))
        val inset = (bottom - top) * 0.10f
        canvas.drawRect(x, top + inset, x + w, bottom - inset, caretPaint)
    }

    fun centreCaret() {
        val l = layout ?: return
        val line = l.getLineForOffset(selectionStart)
        val target = l.getLineTop(line) - (height / 2) + totalPaddingTop
        val maxScroll = max(0, l.height + totalPaddingTop + totalPaddingBottom - height)
        scrollTo(0, target.coerceIn(0, maxScroll))
    }

    override fun bringPointIntoView(offset: Int): Boolean {
        if (::prefs.isInitialized && prefs.typewriterMode) {
            centreCaret()
            return true
        }
        return super.bringPointIntoView(offset)
    }

    // ------------------------------------------------------------- editing

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed) return false // the activity owns Ctrl chords
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (!event.isShiftPressed && continueList()) return true
            }
            KeyEvent.KEYCODE_TAB -> {
                if (event.isShiftPressed) outdent() else indent()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Enter inside a list makes the next bullet; Enter on an empty one ends it. */
    private fun continueList(): Boolean {
        val e = text ?: return false
        if (selectionStart != selectionEnd) return false
        val caret = selectionStart
        val ls = MarkdownStyler.lineStartOf(e, caret)
        val le = MarkdownStyler.lineEndOf(e, caret)
        if (caret != le) return false
        val line = e.subSequence(ls, le).toString()

        val m = LIST_ITEM.find(line) ?: return false
        val indent = m.groupValues[1]
        val marker = m.groupValues[2]
        val task = m.groupValues[3]
        val bodyStart = m.value.length

        if (line.length <= bodyStart) {
            // Empty item: clear it rather than starting another.
            e.replace(ls, le, indent.dropLast(min(2, indent.length)))
            return true
        }

        val nextMarker = if (marker.first().isDigit()) {
            val n = marker.dropLast(1).toIntOrNull() ?: 1
            "${n + 1}${marker.last()}"
        } else marker

        val nextTask = if (task.isNotEmpty()) "[ ] " else ""
        e.insert(caret, "\n$indent$nextMarker $nextTask")
        return true
    }

    private fun indent() {
        val e = text ?: return
        if (selectionStart == selectionEnd) {
            e.insert(selectionStart, "  ")
            return
        }
        eachSelectedLine { ls, _ -> e.insert(ls, "  ") }
    }

    private fun outdent() {
        val e = text ?: return
        eachSelectedLine { ls, _ ->
            var n = 0
            while (n < 2 && ls + n < e.length && e[ls + n] == ' ') n++
            if (n > 0) e.delete(ls, ls + n)
        }
    }

    private inline fun eachSelectedLine(body: (start: Int, end: Int) -> Unit) {
        val e = text ?: return
        val from = MarkdownStyler.lineStartOf(e, min(selectionStart, selectionEnd))
        val to = MarkdownStyler.lineEndOf(e, max(selectionStart, selectionEnd))
        val starts = ArrayList<Int>()
        var i = from
        while (i <= to && i <= e.length) {
            starts.add(i)
            val le = MarkdownStyler.lineEndOf(e, i)
            if (le >= e.length) break
            i = le + 1
        }
        for (s in starts.asReversed()) body(s, MarkdownStyler.lineEndOf(e, s))
    }

    // -------------------------------------------------------- format verbs

    /** Wraps the selection, or unwraps it if it is already wrapped. */
    fun toggleWrap(open: String, close: String = open) {
        val e = text ?: return
        var s = min(selectionStart, selectionEnd)
        var en = max(selectionStart, selectionEnd)
        if (s == en) {
            // No selection: take the word under the caret.
            while (s > 0 && !e[s - 1].isWhitespace()) s--
            while (en < e.length && !e[en].isWhitespace()) en++
        }
        val inner = e.subSequence(s, en).toString()
        if (inner.startsWith(open) && inner.endsWith(close) &&
            inner.length >= open.length + close.length
        ) {
            e.replace(s, en, inner.substring(open.length, inner.length - close.length))
            setSelection(s, en - open.length - close.length)
            return
        }
        val outerOk = s >= open.length && en + close.length <= e.length &&
                e.subSequence(s - open.length, s).toString() == open &&
                e.subSequence(en, en + close.length).toString() == close
        if (outerOk) {
            e.delete(en, en + close.length)
            e.delete(s - open.length, s)
            setSelection(s - open.length, en - open.length)
            return
        }
        e.replace(s, en, open + inner + close)
        setSelection(s + open.length, s + open.length + inner.length)
    }

    /** Sets, or clears, the ATX heading level on every selected line. */
    fun setHeading(level: Int) {
        val e = text ?: return
        eachSelectedLine { ls, le ->
            val line = e.subSequence(ls, le).toString()
            val m = Regex("^#{1,6}\\s+").find(line)
            val stripped = if (m != null) line.substring(m.value.length) else line
            val prefix = if (level <= 0) "" else "#".repeat(level) + " "
            e.replace(ls, le, prefix + stripped)
        }
    }

    /** Adds a line prefix such as `> ` or `- `, or removes it if already there. */
    fun togglePrefix(prefix: String) {
        val e = text ?: return
        val allHave = selectedLines().all { (ls, le) ->
            e.subSequence(ls, le).toString().trimStart().startsWith(prefix.trim())
        }
        eachSelectedLine { ls, le ->
            val line = e.subSequence(ls, le).toString()
            if (allHave) {
                val idx = line.indexOf(prefix.trim())
                if (idx >= 0) {
                    val cut = idx + prefix.trim().length +
                            (if (line.length > idx + prefix.trim().length &&
                                line[idx + prefix.trim().length] == ' '
                            ) 1 else 0)
                    e.replace(ls, le, line.substring(0, idx) + line.substring(cut))
                }
            } else {
                e.insert(ls, prefix)
            }
        }
    }

    private fun selectedLines(): List<Pair<Int, Int>> {
        val e = text ?: return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        val from = MarkdownStyler.lineStartOf(e, min(selectionStart, selectionEnd))
        val to = MarkdownStyler.lineEndOf(e, max(selectionStart, selectionEnd))
        var i = from
        while (i <= to && i <= e.length) {
            val le = MarkdownStyler.lineEndOf(e, i)
            out.add(i to le)
            if (le >= e.length) break
            i = le + 1
        }
        return out
    }

    fun insertBlock(block: String, caretOffsetFromEnd: Int = 0) {
        val e = text ?: return
        val at = max(selectionStart, selectionEnd)
        val ls = MarkdownStyler.lineStartOf(e, at)
        val needsBreak = at > ls
        val payload = (if (needsBreak) "\n" else "") + block
        e.insert(at, payload)
        setSelection((at + payload.length - caretOffsetFromEnd).coerceIn(0, e.length))
    }

    companion object {
        private val LIST_ITEM =
            Regex("^([ \\t]*)([-*+]|\\d{1,9}[.)])[ \\t]+(\\[[ xX]\\][ \\t]+)?")

        private const val MAX_HISTORY = 300
        private const val COALESCE_MS = 1200L
    }
}
