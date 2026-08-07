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
    private var winStart = 0
    private var winEnd = 0

    // ------------------------------------------------------ document model

    /**
     * The whole document. The Editable is a window onto it — [pageStart,
     * pageEnd) — held in sync by mirroring every change the Editable sees,
     * in onTextChanged, before any other code can react. For now the window
     * is always the whole document; the splice engine that moves it lands
     * behind a setting. The point of the split is the device: its framework
     * re-lays-out everything below an edit, so what the Editable holds is
     * what a keystroke costs, and one day soon it will hold only a page.
     *
     * Everything outside this class speaks GLOBAL offsets. The Editable's
     * own coordinates stop at this file's walls.
     */
    private val docText = StringBuilder()
    private var pageStart = 0
    private var pageEnd = 0
    private var splicing = false
    private var inSetText = false

    /**
     * Whether the page is genuinely a slice of a larger document. Tracked as
     * intent — set by the splice, cleared by a whole-document setText — so
     * the mirror's repair logic never keys on the PREFERENCE: the setting
     * can be flipped off while the page is still a slice, and treating that
     * page as the whole document would truncate the book on the next save.
     */
    private var pageIsSlice = false

    /** Called when a save found the mirror and the page disagreeing. */
    var onMirrorRepair: (() -> Unit)? = null

    fun docLength(): Int = docText.length

    fun globalSelectionStart(): Int = pageStart + selectionStart

    fun globalSelectionEnd(): Int = pageStart + selectionEnd

    fun setSelectionGlobal(start: Int, end: Int = start) {
        // A target outside the page — or a page still holding a whole
        // document it shouldn't — moves the page first.
        if (pagedEnabled() &&
            (start < pageStart || start > pageEnd || pageOverBudget())
        ) {
            splice(start)
        }
        val e = text ?: return
        setSelection(
            (start - pageStart).coerceIn(0, e.length),
            (end - pageStart).coerceIn(0, e.length)
        )
    }

    /**
     * The document as the file should hold it. Before handing it over, the
     * page is compared against its slice of the mirror: a desynced mirror
     * must never reach the card. On disagreement the Editable wins — it is
     * what the writer saw — the mirror is repaired, and the owner is told.
     */
    fun documentText(): String {
        val e = text
        if (e != null) {
            val len = e.length
            // The page vouches for its slice. When it IS the document the
            // invariant is total; when it is a slice — by its own record,
            // never by the preference — text beyond it answers to the mirror
            // alone, whatever the setting says today.
            var same = (pageEnd - pageStart) == len && pageEnd <= docText.length &&
                    (pageIsSlice || (pageStart == 0 && pageEnd == docText.length))
            if (same) {
                for (i in 0 until len) {
                    if (e[i] != docText[pageStart + i]) { same = false; break }
                }
            }
            if (!same) {
                if (pageIsSlice && pageStart >= 0 && pageStart <= docText.length) {
                    docText.replace(pageStart, pageEnd.coerceIn(pageStart, docText.length), e.toString())
                    pageEnd = pageStart + len
                } else {
                    docText.setLength(0)
                    docText.append(e)
                    pageStart = 0
                    pageEnd = len
                }
                onMirrorRepair?.invoke()
            }
        }
        return docText.toString()
    }

    /** For tests: the mirror as-is, no verification, no repair. */
    internal fun documentTextRaw(): String = docText.toString()

    /** For tests: the page's document bounds. */
    internal fun pageBounds(): Pair<Int, Int> = pageStart to pageEnd

    /**
     * The paged-buffer setting changed. Turning it off while the page is a
     * slice must put the whole document back into the Editable — silently
     * keeping the slice would leave the writer editing one page of a book
     * the setting claims is whole. Turning it on just lets the next check
     * narrow the page in its own time.
     */
    fun onPagedPreferenceChanged() {
        if (::prefs.isInitialized && prefs.pagedBuffer) {
            schedulePageCheck()
            return
        }
        if (!pageIsSlice) return
        val caretG = globalSelectionStart()
        splicing = true
        styling = true
        try {
            pageStart = 0
            pageEnd = docText.length
            pageIsSlice = false
            styler.baseFenceParity = false
            styler.fencesChangedAt(0)
            setText(docText.toString())
            winStart = 0
            winEnd = 0
            val e = text ?: return
            setSelection(caretG.coerceIn(0, e.length))
            rememberCaretLine()
        } finally {
            styling = false
            splicing = false
        }
        restyleNow()
    }

    // -------------------------------------------------------------- splice

    /**
     * Whether the page may be narrower than the document. Off by default;
     * pointless for anything a single page can hold anyway.
     */
    private fun pagedEnabled(): Boolean =
        ::prefs.isInitialized && prefs.pagedBuffer &&
                docText.length > PAGE_BEFORE + PAGE_AFTER + 2 * EDGE_SLACK

    private fun pageOverBudget(): Boolean =
        (pageEnd - pageStart) > PAGE_BEFORE + PAGE_AFTER + 2 * EDGE_SLACK

    /**
     * Moves the page to hold [globalCaret]. The heart of the paged buffer:
     * the Editable is discarded and refilled with a slice of the document,
     * cut at blank-line boundaries outside fences, and everything that spoke
     * page coordinates is remapped or rebuilt. No text changes — the mirror
     * is suppressed for the swap — so the document, the history, and the
     * dirty flag are untouched.
     */
    private fun splice(globalCaret: Int) {
        val doc = docText
        val want = globalCaret.coerceIn(0, doc.length)
        val (from, to) = choosePage(want)
        if (from == pageStart && to == pageEnd) return

        val selG = pageStart + selectionStart.coerceIn(0, text?.length ?: 0)
        val selEndG = pageStart + selectionEnd.coerceIn(0, text?.length ?: 0)

        // Anything holding page offsets dies or is remapped here.
        pendingRestyle?.let { handler.removeCallbacks(it) }
        pendingRestyle = null
        pendingFrom = -1
        pendingTo = -1
        handler.removeCallbacks(extendWindow)
        arrival = null   // its span dies with the old Editable

        splicing = true
        styling = true
        try {
            pageStart = from
            pageEnd = to
            pageIsSlice = from > 0 || to < doc.length
            styler.baseFenceParity = fenceParityAt(doc, from)
            styler.fencesChangedAt(0)
            setText(doc.substring(from, to))   // splicing=true: no reset, no mirror
            winStart = 0
            winEnd = 0
            val e = text ?: return
            setSelection(
                (selG - from).coerceIn(0, e.length),
                (selEndG - from).coerceIn(0, e.length)
            )
            rememberCaretLine()
        } finally {
            styling = false
            splicing = false
        }
        restyleNow()
    }

    /**
     * Splices when the caret has drifted too near a page edge — after typing
     * has eroded the tail, or arrows have walked toward the top. Posted, and
     * never run from inside a text watcher: a splice must see the edit whole.
     */
    private val ensurePage = Runnable {
        if (!pagedEnabled() || splicing || styling) return@Runnable
        val e = text ?: return@Runnable
        val vis = visibleOffsets()
        if (vis != null) {
            val topG = pageStart + vis.first
            val botG = pageStart + vis.second
            val scrolledOffTop = pageStart > 0 && topG - pageStart < EDGE_SLACK
            val scrolledOffEnd = pageEnd < docText.length && pageEnd - botG < EDGE_SLACK
            if (scrolledOffTop || scrolledOffEnd) {
                // The page follows the view; the caret clamps into it. The
                // reader who scrolled this far away chose the view.
                splice((topG + botG) / 2)
                return@Runnable
            }
        }
        val caretG = pageStart + selectionStart.coerceIn(0, e.length)
        val nearTop = pageStart > 0 && (caretG - pageStart) < EDGE_SLACK
        val nearEnd = pageEnd < docText.length && (pageEnd - caretG) < EDGE_SLACK
        if (nearTop || nearEnd || pageOverBudget()) splice(caretG)
    }

    private fun schedulePageCheck() {
        handler.removeCallbacks(ensurePage)
        handler.post(ensurePage)
    }

    /**
     * Page bounds around [caretG]: generous before the caret, a short tail
     * after it — the tail is what a keystroke costs on this device — cut on
     * blank-line starts whose fence parity is closed. One forward walk
     * carries the parity, so the scan is linear in the prefix, not squared.
     */
    private fun choosePage(caretG: Int): Pair<Int, Int> {
        val doc = docText
        if (!pagedEnabled()) return 0 to doc.length
        val wantFrom = (caretG - PAGE_BEFORE).coerceAtLeast(0)
        val wantTo = (caretG + PAGE_AFTER).coerceAtMost(doc.length)

        val searchFrom = MarkdownStyler.lineStartOf(doc, (wantFrom - CUT_SLACK).coerceAtLeast(0))
        var parity = fenceParityAt(doc, searchFrom)
        var from = if (wantFrom == 0) 0 else -1
        var to = if (wantTo >= doc.length) doc.length else -1

        var i = searchFrom
        var prevBlank = searchFrom == 0
        while (i < doc.length && (to < 0 || i <= wantTo + CUT_SLACK)) {
            val le = MarkdownStyler.lineEndOf(doc, i)
            // A blank-line start outside any fence is a safe cut.
            if (prevBlank && !parity) {
                if (i <= wantFrom) from = i
                if (to < 0 && i >= wantTo) to = i
            }
            if (MarkdownStyler.isFenceLine(doc, i, le)) parity = !parity
            prevBlank = le == i || doc.subSequence(i, le).isBlank()
            if (le >= doc.length) break
            i = le + 1
        }
        // Nowhere safe found: fall back to plain line starts, and finally to
        // the document's edges. A rough cut styles one edge line oddly at
        // worst; an unbounded page costs every keystroke.
        if (from < 0) from = MarkdownStyler.lineStartOf(doc, wantFrom)
        if (to < 0) to = MarkdownStyler.lineEndOf(doc, wantTo).coerceAtMost(doc.length)
        if (to <= from) return 0 to doc.length
        return from to to
    }

    /** Fence parity of everything above [offset], walked line by line. */
    private fun fenceParityAt(doc: CharSequence, offset: Int): Boolean {
        var parity = false
        var i = 0
        while (i < offset) {
            var e2 = i
            while (e2 < doc.length && doc[e2] != '\n') e2++
            if (MarkdownStyler.isFenceLine(doc, i, e2)) parity = !parity
            i = e2 + 1
        }
        return parity
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        // Any setText that is not a splice means "this is now the whole
        // document" — the app's own loads and rebuilds, and every framework
        // path this app never calls. The reset happens before super so the
        // watcher's mirror pass can be skipped rather than double-applied.
        @Suppress("SENSELESS_COMPARISON")
        if (docText != null && !splicing) {
            docText.setLength(0)
            docText.append(text ?: "")
            pageStart = 0
            pageEnd = docText.length
            pageIsSlice = false
            // A whole document starts outside any fence; a stale parity from
            // a previous page would style everything inverted.
            if (::styler.isInitialized) styler.baseFenceParity = false
            inSetText = true
            try {
                super.setText(text, type)
            } finally {
                inSetText = false
            }
            return
        }
        super.setText(text, type)
    }

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
        // Dark enough to survive 1-bit dithering: at 20% the selection
        // disappears on the panel, and with the caret hidden during selection
        // that made shift-arrow editing completely blind.
        highlightColor = 0x55000000
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
                // The mirror first, before any other code can nest another
                // edit: what the Editable now holds is what the document
                // holds. Skipped for setText (the override already reset the
                // whole mirror) and for splices (a page swap is not a change
                // to the document).
                if (s != null && !inSetText && !splicing) {
                    docText.replace(
                        pageStart + st, pageStart + st + before,
                        s.subSequence(st, st + count).toString()
                    )
                    pageEnd += count - before
                }
                changeStart = st
                changeEnd = st + count
                inserted = s?.subSequence(st, st + count)?.toString() ?: ""
            }

            override fun afterTextChanged(e: Editable?) {
                if (styling || e == null) return
                // Typing is its own signal of where you are — and the page
                // must follow the caret now, not keep re-placing the jump.
                if (arrival != null) clearArrival.run()
                settleTarget = -1
                // History speaks document offsets, like everything else that
                // must survive the page moving out from under it.
                record(pageStart + changeStart, removed, inserted)
                onEdit?.invoke(e.length)
                val big = (changeEnd - changeStart) > 240
                scheduleRestyle(changeStart, changeEnd, if (big) 110L else 0L)
                // Typing erodes the page's tail; top it back up between
                // keystrokes, never from inside the watcher.
                if (pagedEnabled()) schedulePageCheck()
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
                // The reader has taken the page back; stop re-placing it.
                settleTarget = -1
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
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        // The IME asks for text around the cursor with no particular limit —
        // the device's IME passes what amounts to "all of it". At the end of a
        // manuscript that is a whole-book copy per request, and an unbounded n
        // can even fail the allocation outright. Clamped here, in one place,
        // rather than trusting any IME to be reasonable.
        return object : android.view.inputmethod.InputConnectionWrapper(ic, true) {
            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
                super.getTextBeforeCursor(n.coerceIn(0, EXTRACT_WINDOW), flags)

            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? =
                super.getTextAfterCursor(n.coerceIn(0, EXTRACT_WINDOW), flags)

            override fun getExtractedText(
                request: android.view.inputmethod.ExtractedTextRequest?, flags: Int
            ): android.view.inputmethod.ExtractedText? =
                // The monitor bit signs the IME up for a fresh extract on every
                // edit — and those updates run through the framework's own
                // extract path, which copies the whole buffer and cannot be
                // overridden. Measured on the panel, that alone kept a
                // keystroke linear in the length of the manuscript. Stripped
                // here, the IME still gets its windowed extract on request and
                // its selection updates; it simply is not fed the document
                // again after every character.
                super.getExtractedText(
                    request,
                    flags and android.view.inputmethod.InputConnection
                        .GET_EXTRACTED_TEXT_MONITOR.inv()
                )
        }
    }

    /**
     * What the IME sees when it extracts "the text": a window around the
     * selection, not the manuscript.
     *
     * Both the connection's getExtractedText and the per-edit monitor updates
     * funnel through this method, and the device's IME re-extracts on every
     * edit. Handing it the whole buffer made a keystroke cost time linear in
     * the length of the document — four seconds in a 25k-word part, measured
     * on the panel — while the hardware-keyboard path, which has no input
     * connection at all, stayed instant. The IME only ever needs enough
     * context to compose with; it gets that, positioned absolutely via
     * [ExtractedText.startOffset] so its edits still land where they should.
     *
     * Plain text on purpose: the extract UI is disabled, so styles in the
     * extract serve nobody, and parcelling a window of spans across the
     * binder on every keystroke is exactly the bill this method exists to
     * cancel.
     */
    override fun extractText(
        request: android.view.inputmethod.ExtractedTextRequest,
        outText: android.view.inputmethod.ExtractedText
    ): Boolean {
        val e = text ?: return false
        val selLo = min(selectionStart, selectionEnd).coerceIn(0, e.length)
        val selHi = max(selectionStart, selectionEnd).coerceIn(0, e.length)
        val from = (selLo - EXTRACT_WINDOW).coerceAtLeast(0)
        val to = (selHi + EXTRACT_WINDOW).coerceAtMost(e.length)
        outText.text = e.subSequence(from, to).toString()
        outText.startOffset = from
        outText.selectionStart = selLo - from
        outText.selectionEnd = selHi - from
        outText.partialStartOffset = -1
        outText.partialEndOffset = -1
        outText.flags =
            if (selLo != selHi) android.view.inputmethod.ExtractedText.FLAG_SELECTING else 0
        return true
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
        val old = if (undoing) c.after else c.before
        val new = if (undoing) c.before else c.after
        // Bounds against the DOCUMENT, not the page: a change outside the
        // page is one the page will move to, not an invalid one — testing
        // against the Editable here would wipe both stacks the first time
        // the page had moved away from an old edit.
        if (c.start > docLength() || c.start + old.length > docLength()) {
            from.clear(); to.clear()
            return false
        }
        // A change bigger than the page's TAIL — the room a splice leaves
        // after the caret — applies to the document directly, and the page
        // resets around it. Judged against the tail, not the whole page: a
        // mid-sized change would pass the whole-page test, fail the local
        // bounds after the splice, and wipe both stacks.
        if (old.length > PAGE_AFTER) {
            from.removeAt(from.size - 1)
            applyingHistory = true
            try {
                docText.replace(c.start, c.start + old.length, new.toString())
                setText(docText.toString())
                setSelectionGlobal(c.start + new.length)
            } finally {
                applyingHistory = false
            }
            to.add(c)
            restyleNow()
            return true
        }
        // The page must hold the change before it can be applied; move it
        // there when it does not. The Editable is fetched only AFTER any
        // page move — a splice replaces it, and a replace aimed at the old
        // one lands in a buffer the writer no longer sees.
        if (pagedEnabled() &&
            (c.start < pageStart || c.start + old.length > pageEnd)
        ) {
            splice(c.start)
        }
        val e = text ?: return false
        val local = c.start - pageStart
        if (local < 0 || local + old.length > e.length) {
            from.clear(); to.clear()
            return false
        }
        // Popped only once the edit is known to be applicable, so a rejected
        // step leaves the history intact rather than quietly dropping it.
        from.removeAt(from.size - 1)
        applyingHistory = true
        try {
            e.replace(local, local + old.length, new)
            setSelection((local + new.length).coerceIn(0, e.length))
        } finally {
            applyingHistory = false
        }
        to.add(c)
        // The range that changed, not the document. A full restyle clears and
        // re-adds every span in the buffer, which costs more the longer the
        // piece is — undoing a character in a novel used to pay for the novel.
        restyleAround(local, local + new.length)
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
        // Coalesced: a rotation lands here through onConfigurationChanged AND
        // through onSizeChanged -> layoutColumn, and each full restyle drags a
        // full relayout of the buffer behind it. One is enough.
        scheduleFullRestyle()
    }

    private val fullRestyle = Runnable { if (isAttachedToWindow) restyleNow() }

    private fun scheduleFullRestyle() {
        handler.removeCallbacks(fullRestyle)
        handler.post(fullRestyle)
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
            // Posted: restyling from inside a layout pass would re-enter
            // layout — and coalesced with applyMetrics' own pass, since a
            // rotation arrives through both doors.
            scheduleFullRestyle()
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

    /**
     * Styles the page around what is on screen, and forgets the rest.
     *
     * Every span operation on a buffer gets slower as the buffer's total span
     * count grows, so styling a whole manuscript makes the whole manuscript
     * slower — to open, and to change a mode on. Holding spans only for a
     * window around the viewport keeps that count bounded however long the
     * piece is. The window then grows as the page is scrolled, and is thrown
     * away and rebuilt only on a jump, where there is no continuity to keep.
     */
    fun restyleNow() {
        val e = text ?: return
        winStart = 0
        winEnd = 0
        ensureWindow()
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

    /** The stretch of the document currently carrying spans. For tests. */
    internal fun styledWindow(): Pair<Int, Int> = winStart to winEnd

    /** The first and last character offsets currently on screen, if known. */
    internal fun visibleOffsets(): Pair<Int, Int>? {
        val l = layout ?: return null
        if (height <= 0 || l.lineCount <= 0) return null
        val top = scrollY.coerceAtLeast(0)
        val bottom = (scrollY + height).coerceIn(0, l.height)
        val first = l.getLineForVertical(top)
        val last = l.getLineForVertical(bottom)
        return l.getLineStart(first) to l.getLineEnd(last)
    }

    /**
     * Widens the styled window to cover the page, extending it where the new
     * text adjoins what is already styled and rebuilding it where it does not.
     *
     * Extending never touches what is above: unstyling a passed region would
     * change its height and shift the line being read out from under the eye.
     */
    private fun ensureWindow() {
        val e = text ?: return
        val visible = visibleOffsets()
        val from: Int
        val to: Int
        if (visible == null) {
            // Before the first measure there is no viewport to work from, so a
            // fixed opening stretch stands in. Anything shorter than it — every
            // note, and every test — is simply styled whole.
            from = 0
            to = min(e.length, MIN_WINDOW)
        } else {
            from = (visible.first - MARGIN_CHARS).coerceAtLeast(0)
            to = (visible.second + MARGIN_CHARS).coerceAtMost(e.length)
        }

        val fresh = winEnd == 0 && winStart == 0
        if (!fresh && from >= winStart && to <= winEnd) return

        val jumped = !fresh && (to < winStart || from > winEnd)
        val rebuilt = fresh || jumped
        var extendedOnScreen = false
        styling = true
        try {
            if (rebuilt) {
                // A jump has no continuity to preserve, so the old window goes
                // and a new one is built where the page has landed.
                if (jumped) styler.clearAll(e)
                styler.restyleRange(e, from, to, selectionStart)
                winStart = from
                winEnd = to
            } else {
                if (from < winStart) {
                    styler.restyleRange(e, from, winStart, selectionStart)
                    winStart = from
                    extendedOnScreen = true
                }
                if (to > winEnd) {
                    // Normally this range lies wholly below the page, but a
                    // fling can outrun the margin and leave part of it on
                    // screen — those lines then need the same layout pass an
                    // upward extension gets, for the same reason.
                    if (visible != null && winEnd < visible.second) extendedOnScreen = true
                    styler.restyleRange(e, winEnd, to, selectionStart)
                    winEnd = to
                }
            }
            styler.applyFocus(e, selectionStart)
        } finally {
            styling = false
        }
        // A rebuilt window has to be measured again; an extension wholly below
        // the page does not — asking for a layout pass there re-measures every
        // line in the document, which on a manuscript costs far more than the
        // styling it was meant to settle. One that touches lines on or above
        // the page does: it adds height-changing spans — the space over a
        // heading is a LineHeightSpan, which DynamicLayout does not reflow for
        // on its own — and stale heights there draw one line over another.
        if (rebuilt || extendedOnScreen) requestLayout()
        invalidate()
    }

    /**
     * Widening happens just after the scroll rather than during it. The window
     * reaches thousands of characters beyond the page, so there is nothing to
     * see at its edge, and doing the work inline would put the cost of styling
     * and reflowing into the frame the finger is dragging.
     */
    private val extendWindow = Runnable {
        if (::styler.isInitialized && !styling) ensureWindow()
    }

    override fun onScrollChanged(l: Int, t: Int, ol: Int, ot: Int) {
        super.onScrollChanged(l, t, ol, ot)
        if (!::styler.isInitialized) return
        handler.removeCallbacks(extendWindow)
        handler.post(extendWindow)
        if (pagedEnabled()) schedulePageCheck()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(extendWindow)
        handler.removeCallbacks(ensurePage)
        handler.removeCallbacks(settleDone)
        handler.removeCallbacks(clearArrival)
        handler.removeCallbacks(fullRestyle)
        pendingRestyle?.let { handler.removeCallbacks(it) }
        viewTreeObserver.removeOnGlobalLayoutListener(settleOnLayout)
        super.onDetachedFromWindow()
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
        // Arrow keys can walk the caret toward a page edge with no text
        // change and no scroll; keep the page ahead of them.
        if (movedLine && pagedEnabled()) schedulePageCheck()
        // Moving between lines swaps a rendered table row or image for its
        // source and back, which changes that line's height.
        if (movedLine) requestLayout()
        invalidate()
    }

    // ---------------------------------------------------------------- caret

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // View focus on this device is flaky around window-flag changes, and a
        // caret that vanishes with it reads as being lost. Drawn whenever the
        // window is in front: a panel that takes focus covers the editor
        // anyway, so an always-on caret behind it costs nothing.
        if (!isFocused && !hasWindowFocus()) return
        val l = layout ?: return
        // During a selection the caret sits at the ACTIVE end — the one the
        // arrows are moving — not hidden. Hiding it made shift-arrow editing
        // a blind operation on a panel whose highlight barely dithers through.
        val sel = selectionEnd
        if (sel < 0) return

        val line = l.getLineForOffset(sel)
        // Content coordinates: the framework has already translated the canvas
        // by the scroll before onDraw. Subtracting the scroll again — as this
        // code did from the first commit — drew the caret twice the scroll
        // height above the page: visible at the top of a note, never once in
        // a scrolled manuscript. Every "there is no caret" report was this.
        val x = l.getPrimaryHorizontal(sel) + totalPaddingLeft
        val top = l.getLineTop(line) + totalPaddingTop
        val bottom = l.getLineBottom(line) + totalPaddingTop

        // A second, unmissable channel: a tick in the left margin beside the
        // caret's line. The gutter is otherwise empty, so whatever the panel's
        // update mode does to a moving in-text bar, the line you are on is
        // always flagged at the edge of the page.
        canvas.drawRect(
            (scrollX).toFloat(), top + (bottom - top) * 0.25f,
            scrollX + Scale.mm(1.2f), bottom - (bottom - top) * 0.25f,
            caretPaint
        )
        // A point and a half — settled by eye on the panel: three read as a
        // fence post, two still a shade heavy, and the original hairline was
        // invisible. Thicker for a few seconds after a jump.
        val pt = if (System.currentTimeMillis() < caretBoostUntil) 3f else 1.5f
        val w = max(3f, Scale.pt(pt))
        val inset = (bottom - top) * 0.10f
        canvas.drawRect(x, top + inset, x + w, bottom - inset, caretPaint)
    }

    /**
     * Places [offset] the way a reader expects after picking it from a list:
     * caret in the line, the line one line-height down from the top of the
     * page, visible. Outline, contents and link jumps come through here;
     * find keeps its own centring, where the match wants context both ways.
     *
     * Placement runs twice. The first scroll is what makes the styled window
     * rebuild around the target, and the rebuild changes line heights — the
     * space over a heading, a table's rows — so a single placement measured
     * against the old heights drifts, and can drift the caret clean off the
     * screen. The second pass runs after that work has settled and places
     * the line against the heights that will actually be drawn. Typewriter
     * mode gets the final word and centres instead, where it holds the caret
     * anyway.
     */
    fun jumpTo(offset: Int) {
        val at = offset.coerceIn(0, docLength())
        // Focus first: the caret is drawn only in a focused editor, and a
        // jump whose caret cannot be seen reads as being lost.
        requestFocus()
        if (pagedEnabled() && (at < pageStart || at > pageEnd || pageOverBudget())) {
            splice(at)
        }
        val e = text ?: return
        val local = (at - pageStart).coerceIn(0, e.length)
        setSelection(local)
        placeAtTop(local)
        markArrival(local)
        // The second placement runs off the layout pass the first one causes,
        // not off a guessed pair of posts: the styled window rebuilds around
        // the target and its heights land only when the tree lays out again —
        // on the device that can be well after two message-loop turns. The
        // posted fallback covers the jump that needed no rebuild at all.
        settleTarget = at
        handler.removeCallbacks(settleDone)
        handler.postDelayed(settleDone, SETTLE_WINDOW_MS)
    }

    /**
     * While a jump is settling, every layout pass re-places the target: the
     * rebuild's heights land whenever the device's traversal gets to them —
     * behind stray passes from the closing panel, and well over a second on
     * a long part — so no single moment can be trusted to be "after". A
     * touch takes the page back for the reader and ends the window early.
     */
    /** Document offset, like everything that must outlive a page move. */
    private var settleTarget = -1

    private val settleDone = Runnable {
        val at = settleTarget
        settleTarget = -1
        if (at >= 0) placeSettled(at)
    }

    private fun placeSettled(at: Int) {
        if (::prefs.isInitialized && prefs.typewriterMode) centreCaret()
        else placeAtTop((at - pageStart).coerceIn(0, text?.length ?: 0))
    }

    private val settleOnLayout = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        if (settleTarget >= 0) placeSettled(settleTarget)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(settleOnLayout)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // The caret's visibility gate just changed either way; repaint it.
        invalidate()
    }

    // ------------------------------------------------------------- arrival

    /**
     * A steady two-pixel caret at 300 PPI is not an arrival signal anyone can
     * see, and on E Ink there is no blink to catch the eye. A jump therefore
     * paints a band across its landing line for a moment, and thickens the
     * caret for a few seconds — one partial refresh in, one out.
     */
    private var arrival: ArrivalSpan? = null
    private var caretBoostUntil = 0L

    private val clearArrival = Runnable {
        arrival?.let { text?.removeSpan(it) }
        arrival = null
        invalidate()
    }

    private fun markArrival(at: Int) {
        val e = text ?: return
        arrival?.let { e.removeSpan(it) }
        val ls = MarkdownStyler.lineStartOf(e, at)
        val le = MarkdownStyler.lineEndOf(e, at)
        val span = ArrivalSpan()
        e.setSpan(span, ls, (le + 1).coerceAtMost(e.length), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        arrival = span
        caretBoostUntil = System.currentTimeMillis() + CARET_BOOST_MS
        handler.removeCallbacks(clearArrival)
        handler.postDelayed(clearArrival, ARRIVAL_MS)
        invalidate()
    }

    /** For tests: whether the you-are-here signals are currently showing. */
    internal fun arrivalShowing(): Boolean = arrival != null

    /** For tests: whether a jump is still re-placing its target. */
    internal fun settling(): Boolean = settleTarget >= 0

    private fun placeAtTop(offset: Int) {
        val l = layout ?: return
        val line = l.getLineForOffset(offset.coerceIn(0, l.text.length))
        val air = l.getLineBottom(line) - l.getLineTop(line)
        val target = l.getLineTop(line) + totalPaddingTop - air
        val maxScroll = max(0, l.height + totalPaddingTop + totalPaddingBottom - height)
        scrollTo(0, target.coerceIn(0, maxScroll))
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
        // After a selection change the framework brings the caret into view on
        // the next draw, by its own rules — which would quietly override a
        // jump's placement moments after it was made. While a jump is
        // settling, its placement is the one that stands.
        if (settleTarget >= 0) {
            placeSettled(settleTarget)
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

        /**
         * How much is styled before the view has been measured, and the least
         * that is ever styled. Comfortably more than a note or a scene, so
         * short documents behave exactly as they always did.
         */
        private const val MIN_WINDOW = 12_000

        /** Styled either side of the page, so scrolling stays ahead of the eye. */
        private const val MARGIN_CHARS = 6_000

        private const val MAX_HISTORY = 300
        private const val COALESCE_MS = 1200L

        /**
         * How much of the document the IME may see either side of the
         * selection. Far more context than any composition needs, far less
         * than a manuscript.
         */
        private const val EXTRACT_WINDOW = 1024

        /** How long the landing-line band stays up after a jump. */
        private const val ARRIVAL_MS = 1600L

        /** How long the caret stays thickened after a jump. */
        private const val CARET_BOOST_MS = 4000L

        /** How long a jump keeps re-placing its target as layouts land. */
        private const val SETTLE_WINDOW_MS = 3000L

        /**
         * Page geometry. Generous before the caret — scrollback context —
         * and a short tail after it, because on this device the tail is what
         * every keystroke costs: the framework re-lays-out everything below
         * an edit at roughly 37 µs per character. Three thousand keeps the
         * worst insert near a tenth of a second.
         */
        private const val PAGE_BEFORE = 8_000
        private const val PAGE_AFTER = 3_000

        /** How close the caret or view may come to a page edge. */
        private const val EDGE_SLACK = 800

        /** How far past the budget the cut-point search may roam. */
        private const val CUT_SLACK = 1_200
    }
}
