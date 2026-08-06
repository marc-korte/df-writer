package com.dfwriter.slate

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The one overlay used for the command palette, the file list and the outline.
 * It is driven from the keyboard first — type to filter, arrows to move, Enter
 * to choose, Escape to leave — because tapping a slow panel accurately is worse
 * than typing on the keyboard that is already in your hands.
 */
class ListSheet(ctx: Context, private val prefs: Prefs) : LinearLayout(ctx) {

    data class Item(
        val title: String,
        val hint: String = "",
        val keys: String = "",
        val indent: Int = 0,
        val payload: Any? = null
    )

    var onPick: ((Item) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null
    /** Invoked when Enter is pressed with no match; used by "new file" flows. */
    var onFreeText: ((String) -> Unit)? = null

    private val titleView: TextView
    private val filter: EditText
    private val scroll: ScrollView
    private val list: LinearLayout
    private val emptyView: TextView

    private var all: List<Item> = emptyList()
    private var shown: List<Item> = emptyList()
    private var selected = 0

    init {
        orientation = VERTICAL
        background = Ui.panelBackground()
        val pad = Scale.mmInt(3f)
        setPadding(pad, pad, pad, pad)
        isClickable = true

        titleView = Ui.text(ctx, prefs.bodyPt * 0.86f, bold = true, color = Ink.RULE)
        addView(titleView)

        filter = EditText(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Scale.pt(prefs.bodyPt * 1.02f))
            setTextColor(Ink.TEXT)
            setHintTextColor(Ink.MARKER)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            minHeight = Ui.tapSize()
            setPadding(0, Scale.mmInt(2f), 0, Scale.mmInt(2f))
        }
        addView(filter)
        addView(Ui.divider(ctx, Ink.TEXT))

        list = Ui.column(ctx)
        scroll = ScrollView(ctx).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(
                list,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            )
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        emptyView = Ui.text(ctx, prefs.bodyPt * 0.9f, color = Ink.MARKER).apply {
            text = "Nothing here"
            visibility = View.GONE
            setPadding(0, Scale.mmInt(4f), 0, 0)
        }
        addView(emptyView)

        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refilter()
        })

        filter.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            handleKey(code, ev)
        }
    }

    fun configure(
        title: String,
        hint: String,
        items: List<Item>,
        emptyLabel: String = "Nothing here"
    ) {
        titleView.text = title
        filter.hint = hint
        emptyView.text = emptyLabel
        all = items
        filter.setText("")
        selected = 0
        refilter()
    }

    fun show() {
        visibility = View.VISIBLE
        filter.requestFocus()
        filter.setSelection(filter.text.length)
    }

    fun hide() {
        visibility = View.GONE
    }

    fun query(): String = filter.text.toString().trim()

    // -------------------------------------------------------------- filtering

    private fun refilter() {
        val q = filter.text.toString().trim()
        shown = if (q.isEmpty()) all else all.filter { fuzzy(it.title + " " + it.hint, q) }
            .sortedBy { rank(it.title, q) }
        if (selected >= shown.size) selected = maxOf(0, shown.size - 1)
        rebuild()
    }

    /** Subsequence match, so "sfp" finds "Save file as PDF". */
    private fun fuzzy(hay: String, needle: String): Boolean {
        val h = hay.lowercase()
        val n = needle.lowercase()
        if (h.contains(n)) return true
        var i = 0
        for (c in n) {
            if (c == ' ') continue
            i = h.indexOf(c, i)
            if (i < 0) return false
            i++
        }
        return true
    }

    private fun rank(title: String, q: String): Int {
        val t = title.lowercase()
        val n = q.lowercase()
        return when {
            t.startsWith(n) -> 0
            t.contains(n) -> 1
            else -> 2
        }
    }

    private fun rebuild() {
        list.removeAllViews()
        emptyView.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        val cap = minOf(shown.size, MAX_ROWS)
        for (i in 0 until cap) {
            list.addView(buildRow(shown[i], i))
        }
        if (shown.size > cap) {
            val more = Ui.text(context, prefs.bodyPt * 0.8f, color = Ink.MARKER)
            more.text = "…and ${shown.size - cap} more — keep typing to narrow"
            more.setPadding(Scale.mmInt(3f), Scale.mmInt(2f), 0, 0)
            list.addView(more)
        }
        applySelection()
    }

    private fun buildRow(item: Item, index: Int): View {
        val row = Ui.selectableRow(context)
        val label = Ui.text(context, prefs.bodyPt * 0.98f)
        label.text = item.title
        label.setPadding(Scale.mmInt(2.4f) * item.indent, 0, 0, 0)
        Ui.tagColor(label)
        row.addView(
            label,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        if (item.hint.isNotEmpty()) {
            val hint = Ui.text(context, prefs.bodyPt * 0.8f, color = Ink.MARKER)
            hint.text = item.hint
            Ui.tagColor(hint)
            hint.setPadding(Scale.mmInt(3f), 0, 0, 0)
            row.addView(hint)
        }
        if (item.keys.isNotEmpty()) {
            val keys = Ui.keyCap(context, item.keys, prefs.bodyPt * 0.78f)
            Ui.tagColor(keys)
            keys.setPadding(Scale.mmInt(3f), 0, 0, 0)
            row.addView(keys)
        }
        row.setOnClickListener {
            selected = index
            applySelection()
            pick()
        }
        return row
    }

    private fun applySelection() {
        for (i in 0 until list.childCount) {
            val v = list.getChildAt(i)
            if (v is LinearLayout) Ui.setSelected(v, i == selected)
        }
        val v = list.getChildAt(selected) ?: return
        v.post {
            val top = v.top
            val bottom = v.bottom
            if (top < scroll.scrollY) scroll.scrollTo(0, top)
            else if (bottom > scroll.scrollY + scroll.height) {
                scroll.scrollTo(0, bottom - scroll.height)
            }
        }
    }

    private fun move(delta: Int) {
        if (shown.isEmpty()) return
        selected = (selected + delta).coerceIn(0, minOf(shown.size, MAX_ROWS) - 1)
        applySelection()
    }

    private fun pick() {
        val item = shown.getOrNull(selected)
        if (item == null) {
            val q = query()
            if (q.isNotEmpty()) onFreeText?.invoke(q)
            return
        }
        onPick?.invoke(item)
    }

    /**
     * Takes what was typed as it stands. The filter matches subsequences, so a
     * name being typed for a new file nearly always leaves something
     * highlighted — "sate" finds "Welcome to Slate.md" — and plain Enter would
     * open that instead. This is the way to say no, make the one I typed.
     */
    private fun pickTyped() {
        val q = query()
        val free = onFreeText
        if (q.isEmpty() || free == null) pick() else free(q)
    }

    // ---------------------------------------------------------------- keys

    fun handleKey(code: Int, ev: KeyEvent): Boolean = when (code) {
        KeyEvent.KEYCODE_DPAD_DOWN -> { move(1); true }
        KeyEvent.KEYCODE_DPAD_UP -> { move(-1); true }
        KeyEvent.KEYCODE_PAGE_DOWN -> { move(8); true }
        KeyEvent.KEYCODE_PAGE_UP -> { move(-8); true }
        KeyEvent.KEYCODE_MOVE_HOME -> { move(-9999); true }
        KeyEvent.KEYCODE_MOVE_END -> { move(9999); true }
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
            // Shift or Ctrl with Enter forces the typed text; Enter alone opens
            // whatever the filter has highlighted.
            if (ev.isShiftPressed || ev.isCtrlPressed) pickTyped() else pick()
            true
        }
        KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> { onDismiss?.invoke(); true }
        KeyEvent.KEYCODE_TAB -> { move(if (ev.isShiftPressed) -1 else 1); true }
        else -> false
    }

    companion object {
        private const val MAX_ROWS = 300
    }
}
