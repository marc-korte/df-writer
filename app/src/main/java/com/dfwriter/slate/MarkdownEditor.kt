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
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
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
    var onLinkTapped: ((String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.TEXT }
    private var lastCaretLineStart = -1
    private var lastCaretLineEnd = -1
    private var styling = false
    private var pendingRestyle: Runnable? = null
    private var pendingFrom = -1
    private var pendingTo = -1
    private var imeWanted = true
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var notATap = false

    fun bind(prefs: Prefs, styler: MarkdownStyler) {
        this.prefs = prefs
        this.styler = styler

        // A picture that has finished decoding needs the line it sits on to be
        // measured again, which only a restyle will do. That line and no more:
        // restyling the document would look every other picture up again.
        styler.onImageReady = { at ->
            if (isAttachedToWindow) {
                restyleLine(at)
                requestLayout()
            }
        }

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
                // Reported before the change lands, because the selection moves
                // during it and that alone can ask for a restyle.
                styler.fencesChangedAt(st)
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

    // ------------------------------------------------------------- links

    /**
     * Opens a link when its address is hidden, and places the caret when it is
     * showing. The caret's own line always displays its Markdown, so the line
     * you are working on stays editable by tap while every other link on the
     * page behaves like a link.
     */
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downAt = event.eventTime
                notATap = false
            }
            // Straying past the slop at any point rules the gesture out for
            // good. Comparing only where the finger went down and came up would
            // let a scroll that wandered off and came back count as a tap.
            android.view.MotionEvent.ACTION_MOVE -> if (!notATap && movedTooFar(event)) {
                notATap = true
            }
            // A second finger means a pinch or a two-finger scroll, never a tap
            // on a link, however still the first finger happens to have been.
            android.view.MotionEvent.ACTION_POINTER_DOWN -> notATap = true
            android.view.MotionEvent.ACTION_CANCEL -> notATap = true
        }
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP &&
            ::styler.isInitialized && isTap(event)
        ) {
            // Read before the framework sees the event, while the caret is still
            // where it was: whether a link opens depends on the caret's line.
            val target = linkAt(event.x, event.y)
            if (target != null) {
                // The framework still gets the up. Swallowing it would leave the
                // editor's own touch and selection bookkeeping half way through a
                // gesture it never sees the end of.
                val handled = super.onTouchEvent(event)
                onLinkTapped?.invoke(target)
                return handled
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * A tap, rather than the end of a drag or a long press. Without this, a
     * scroll that happens to lift off over a link would follow it, and so would
     * the release after a long press meant to start a selection.
     */
    private fun isTap(up: android.view.MotionEvent): Boolean {
        if (notATap) return false
        if (movedTooFar(up)) return false
        return up.eventTime - downAt <= android.view.ViewConfiguration.getLongPressTimeout()
    }

    private fun movedTooFar(e: android.view.MotionEvent): Boolean {
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        val dx = e.x - downX
        val dy = e.y - downY
        return dx * dx + dy * dy > (slop * slop).toFloat()
    }

    /** Internal so a test can aim at it without synthesising touch events. */
    internal fun linkAt(x: Float, y: Float): String? {
        val e = text ?: return null
        val l = layout ?: return null

        // getOffsetForPosition, not layout arithmetic of our own: it accounts for
        // the padding and the scroll together, and getting that conversion wrong
        // resolves the touch to the start of the line, which follows whichever
        // link happens to come first rather than the one under the finger.
        val off = getOffsetForPosition(x, y)
        if (off < 0 || off > e.length) return null

        val line = l.getLineForOffset(off)
        val vx = x + scrollX - totalPaddingLeft
        // A tap past the end of a line resolves to its last character, which
        // would follow a link the finger never touched.
        val slack = Scale.pt(3f)
        if (vx < l.getLineLeft(line) - slack || vx > l.getLineRight(line) + slack) return null

        if (MarkdownStyler.lineStartOf(e, off) == lastCaretLineStart) return null
        return linkTargetAt(e, off)
    }

    /**
     * Asks about the character under the offset rather than a range around it.
     *
     * The brackets either side of a link are collapsed to zero width, so a tap
     * on the first letter resolves to the insertion point in front of it — one
     * short of where the span begins. Each neighbouring character is checked in
     * turn, nearest first, which finds the link without widening the hit area
     * enough to catch the prose next door.
     */
    private fun linkTargetAt(e: Editable, off: Int): String? {
        for (probe in intArrayOf(off, off - 1, off + 1)) {
            if (probe < 0 || probe >= e.length) continue
            val hit = e.getSpans(probe, probe + 1, LinkTextSpan::class.java)
                .firstOrNull { it.target.isNotBlank() }
            if (hit != null) return hit.target
        }
        return null
    }

    /** The link the caret is sitting in, for opening from the keyboard. */
    fun linkAtCaret(): String? {
        val e = text ?: return null
        return linkTargetAt(e, selectionStart.coerceIn(0, e.length))
    }

    // ------------------------------------------------------- on-screen keys

    /** True when a physical keyboard is attached and usable right now. */
    fun hasHardwareKeyboard(): Boolean {
        val cfg = resources.configuration
        return cfg.keyboard == android.content.res.Configuration.KEYBOARD_QWERTY &&
                cfg.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO
    }

    /**
     * This device sets `show_ime_with_hard_keyboard`, so Android puts the
     * on-screen keyboard up even while you type on Bluetooth, covering nearly
     * half the panel. That is a system-wide setting and not ours to change, so
     * the IME is suppressed per-view instead.
     */
    fun applySoftInputPolicy() {
        if (!::prefs.isInitialized) return
        val wanted = when (prefs.softKeyboard) {
            SoftKeyboard.ALWAYS -> true
            SoftKeyboard.NEVER -> false
            SoftKeyboard.AUTO -> !hasHardwareKeyboard()
        }
        val changed = wanted != imeWanted
        imeWanted = wanted

        // The decisive part. Whether key events are offered to the IME at all is
        // a property of the *window*, not of the focused view: ViewRootImpl asks
        // WindowManager.LayoutParams.mayUseInputMethod(flags). With
        // FLAG_ALT_FOCUSABLE_IM set and FLAG_NOT_FOCUSABLE clear, the window
        // stays focusable but stops being an input-method target, so hardware
        // keys go straight to the view instead of through PinyinIME, which
        // consumes them without committing anything.
        (context as? android.app.Activity)?.window?.let { w ->
            if (wanted) w.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            else w.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }

        showSoftInputOnFocus = wanted
        // The input connection is created once per focus, so the decision in
        // onCreateInputConnection only takes effect after a restart.
        if (changed) runCatching { imm().restartInput(this) }
        // Setting the flag only affects the *next* focus event. If the keyboard
        // has just been unpaired the editor is already focused, so without this
        // there would be no hardware keyboard and no on-screen one either, and
        // no way left to type.
        // Showing has to wait a frame: clearing FLAG_ALT_FOCUSABLE_IM only makes
        // this window an input-method target again after the next relayout, and
        // showSoftInput fails silently if it runs before that.
        if (!wanted) hideIme() else if (isFocused) post { showIme() }
    }

    /**
     * Refuses an input connection while a hardware keyboard is attached.
     *
     * This device ships exactly one IME, PinyinIME, and it consumes every
     * hardware key it is offered — ordinary letters and bare function keys
     * alike — without committing anything. Android only routes keys through the
     * IME when the focused view is an IME target, and a view with no input
     * connection is not one. Declining the connection therefore puts the keys
     * back on their normal path to the key listener, which is how text entry
     * works on a machine with no IME at all.
     *
     * The connection comes straight back when the keyboard is unpaired, so the
     * on-screen keyboard still works when it is the only thing to type on.
     */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!imeWanted) return null
        return super.onCreateInputConnection(outAttrs)
    }

    private fun imm(): InputMethodManager =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    fun hideIme() {
        windowToken?.let { imm().hideSoftInputFromWindow(it, 0) }
    }

    fun showIme() {
        requestFocus()
        imm().showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    // ---------------------------------------------------------- undo history

    /**
     * A writer reaches for Ctrl+Z constantly and EditText has no undo of its
     * own, so one is kept here. Consecutive typing coalesces into a single step,
     * because undoing one character at a time is not undo, it is punishment.
     */
    class Change internal constructor(
        internal val start: Int,
        internal val before: CharSequence,
        internal var after: CharSequence,
        internal var at: Long
    )

    /** The two stacks, so a view rebuild can carry the history to a new editor. */
    class History internal constructor(
        internal val undo: List<Change>,
        internal val redo: List<Change>
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
        val c = from.last()
        val e = text ?: return false
        val old = if (undoing) c.after else c.before
        val new = if (undoing) c.before else c.after
        if (c.start > e.length || c.start + old.length > e.length) {
            from.clear(); to.clear()
            return false
        }
        // Popped only once the edit is known to be applicable, so a rejected
        // step leaves the history intact rather than quietly dropping it.
        from.removeAt(from.size - 1)
        applyingHistory = true
        try {
            e.replace(c.start, c.start + old.length, new)
            setSelection((c.start + new.length).coerceIn(0, e.length))
        } finally {
            applyingHistory = false
        }
        to.add(c)
        // The range that changed, not the document. A full restyle clears and
        // re-adds every span in the buffer, which costs more the longer the
        // piece is — undoing a character in a novel used to pay for the novel.
        restyleAround(c.start, c.start + new.length)
        return true
    }

    fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
    }

    fun snapshotHistory(): History = History(undoStack.toList(), redoStack.toList())

    /**
     * Reinstates a history captured from a previous editor. Changing the
     * interface scale replaces the whole view tree, and losing every undo step
     * to a cosmetic setting is not something a writer should have to expect.
     */
    fun restoreHistory(h: History) {
        undoStack.clear(); undoStack.addAll(h.undo)
        redoStack.clear(); redoStack.addAll(h.redo)
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
        applySoftInputPolicy()
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

        // Tables and images are laid out against the text column, so the styler
        // needs to know how wide it ended up. The first styling pass happens
        // before this view has ever been measured, so the width arrives late and
        // anything sized against it has to be styled again once it does.
        styler.measure = paint
        val column = (w - side * 2).coerceAtLeast(0)
        if (column != styler.contentWidthPx) {
            styler.contentWidthPx = column
            // Posted: restyling from inside a layout pass would re-enter layout.
            post { if (isAttachedToWindow) restyleNow() }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layoutColumn()
    }

    // ------------------------------------------------------------- styling

    /**
     * A big change is styled a moment late so the paste itself stays quick. The
     * range of a cancelled restyle is carried into the new one rather than
     * dropped: typing a character inside that window used to replace the pasted
     * block's range with the one character's, leaving the paste unstyled.
     */
    private fun scheduleRestyle(from: Int, to: Int, delay: Long) {
        pendingRestyle?.let { handler.removeCallbacks(it) }
        val f = if (pendingFrom < 0) from else min(pendingFrom, from)
        val t = if (pendingTo < 0) to else max(pendingTo, to)
        pendingFrom = f
        pendingTo = t
        val r = Runnable {
            pendingRestyle = null
            pendingFrom = -1
            pendingTo = -1
            runStyle(f, t)
        }
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
        // Not just invalidate. Several of these spans change how tall a line is
        // — an image, a table row, the space above a heading — and only some
        // span types make DynamicLayout reflow on their own. Without an explicit
        // pass the old heights survive while the new spans draw, which puts the
        // text of one line on top of another.
        requestLayout()
        invalidate()
    }

    /**
     * Restyles the lines a change touched, and asks for the layout pass that
     * a height change needs. The whole-document [restyleNow] is for the things
     * that really do change everything: a mode toggle, or a new document.
     */
    fun restyleAround(from: Int, to: Int) {
        val e = text ?: return
        styling = true
        try {
            styler.restyleRange(e, from.coerceIn(0, e.length), to.coerceIn(0, e.length), selectionStart)
            styler.applyFocus(e, selectionStart)
        } finally {
            styling = false
        }
        rememberCaretLine()
        requestLayout()
        invalidate()
    }

    /** Restyles the one line containing [offset] and nothing else. */
    private fun restyleLine(offset: Int) {
        val e = text ?: return
        val at = offset.coerceIn(0, e.length)
        styling = true
        try {
            styler.restyleRange(e, at, at, selectionStart, withCaretLine = false)
        } finally {
            styling = false
        }
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
                // Re-hide the markers on the line the caret just left. Exactly
                // that line: left to widen itself to the caret, this would
                // restyle every line jumped over, and a jump to the far end of a
                // long document happens on the UI thread.
                styler.restyleRange(
                    e, lastCaretLineStart, min(lastCaretLineEnd, e.length), selStart,
                    withCaretLine = false
                )
            }
            if (movedLine) styler.restyleRange(e, ls, le, selStart, withCaretLine = false)
            if (prefs.focusMode) styler.applyFocus(e, selStart)
        } finally {
            styling = false
        }
        lastCaretLineStart = ls
        lastCaretLineEnd = le

        if (prefs.typewriterMode) post { centreCaret() }
        onCaretMoved?.invoke()
        // Moving between lines swaps a rendered table row or image for its
        // source and back, which changes that line's height.
        if (movedLine) requestLayout()
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
        // The layout can lag the buffer for a frame after setText, so the caret
        // offset is clamped to what has actually been laid out.
        val line = l.getLineForOffset(selectionStart.coerceIn(0, l.text.length))
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
        rewriteSelectedLines { "  $it" }
    }

    private fun outdent() {
        rewriteSelectedLines { line ->
            var n = 0
            while (n < 2 && n < line.length && line[n] == ' ') n++
            line.substring(n)
        }
    }

    /**
     * Rewrites every line the selection touches in a single replace. One line at
     * a time would be one undo step and one restyle per line, so undoing a Tab
     * across ten lines would take ten presses of Ctrl+Z.
     */
    private fun rewriteSelectedLines(transform: (String) -> String) {
        val e = text ?: return
        val hadSelection = selectionStart != selectionEnd
        val caret = selectionEnd
        val from = MarkdownStyler.lineStartOf(e, min(selectionStart, selectionEnd))
        val to = MarkdownStyler.lineEndOf(e, max(selectionStart, selectionEnd))
        val block = e.subSequence(from, to).toString()
        val out = block.split('\n').joinToString("\n") { transform(it) }
        if (out == block) return
        e.replace(from, to, out)
        // Selected lines stay selected, so the command can be repeated. With no
        // selection the caret keeps its place in its own line instead, rather
        // than the line ending up selected under it.
        val end = from + out.length
        if (hadSelection) setSelection(from, end.coerceAtMost(e.length))
        else setSelection((caret + out.length - block.length).coerceIn(from, end))
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
        val prefix = if (level <= 0) "" else "#".repeat(level) + " "
        rewriteSelectedLines { line ->
            val m = HEADING_PREFIX.find(line)
            prefix + (if (m != null) line.substring(m.value.length) else line)
        }
    }

    /** Adds a line prefix such as `> ` or `- `, or removes it if already there. */
    fun togglePrefix(prefix: String) {
        val e = text ?: return
        val token = prefix.trim()
        val allHave = selectedLines().all { (ls, le) ->
            e.subSequence(ls, le).toString().trimStart().startsWith(token)
        }
        rewriteSelectedLines { line ->
            if (!allHave) prefix + line
            else {
                val idx = line.indexOf(token)
                if (idx < 0) line else {
                    val cut = idx + token.length +
                            (if (line.length > idx + token.length &&
                                line[idx + token.length] == ' '
                            ) 1 else 0)
                    line.substring(0, idx) + line.substring(cut)
                }
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
        private val HEADING_PREFIX = Regex("^#{1,6}\\s+")

        private const val MAX_HISTORY = 300
        private const val COALESCE_MS = 1200L
    }
}
