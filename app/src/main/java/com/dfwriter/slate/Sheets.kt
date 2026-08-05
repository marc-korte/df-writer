package com.dfwriter.slate

import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Settings, laid out as a keyboard-navigable list: up and down to choose a row,
 * left and right to change it. No sliders — a slider is the single worst control
 * to operate on a panel that redraws in a quarter of a second.
 */
class SettingsSheet(ctx: Context, private val prefs: Prefs) : LinearLayout(ctx) {

    class Row(
        val name: String,
        val read: () -> String,
        val adjust: (Int) -> Unit,
        val note: String = ""
    )

    var onChanged: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    private val list = Ui.column(ctx)
    private val scroll: ScrollView
    private var rows: List<Row> = emptyList()
    private var selected = 0

    init {
        orientation = VERTICAL
        background = Ui.panelBackground()
        val pad = Scale.mmInt(3f)
        setPadding(pad, pad, pad, pad)
        isClickable = true
        isFocusableInTouchMode = true

        val title = Ui.text(ctx, prefs.bodyPt * 0.86f, bold = true, color = Ink.RULE)
        title.text = "Settings  ·  ← →  change  ·  Esc  close"
        addView(title)
        addView(Ui.divider(ctx, Ink.TEXT))

        scroll = ScrollView(ctx).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) false else handleKey(code, ev)
        }
    }

    fun setRows(rows: List<Row>) {
        this.rows = rows
        rebuild()
    }

    fun show() {
        visibility = View.VISIBLE
        requestFocus()
        rebuild()
    }

    fun hide() {
        visibility = View.GONE
    }

    private fun rebuild() {
        list.removeAllViews()
        rows.forEachIndexed { i, r ->
            val row = Ui.selectableRow(context)

            val name = Ui.text(context, prefs.bodyPt * 0.98f)
            name.text = r.name
            Ui.tagColor(name)
            row.addView(name, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            if (r.note.isNotEmpty()) {
                val note = Ui.text(context, prefs.bodyPt * 0.78f, color = Ink.MARKER)
                note.text = r.note
                Ui.tagColor(note)
                note.setPadding(Scale.mmInt(2f), 0, Scale.mmInt(2f), 0)
                row.addView(note)
            }

            val minus = stepper("−") { r.adjust(-1); onChanged?.invoke(); rebuild() }
            val value = Ui.text(context, prefs.bodyPt * 0.98f, bold = true, mono = true)
            value.text = r.read()
            value.gravity = android.view.Gravity.CENTER
            value.minWidth = Scale.mmInt(26f)
            Ui.tagColor(value)
            val plus = stepper("+") { r.adjust(1); onChanged?.invoke(); rebuild() }

            row.addView(minus)
            row.addView(value)
            row.addView(plus)

            row.setOnClickListener { selected = i; applySelection() }
            list.addView(row)
            list.addView(Ui.divider(context, 0xFFE6E6E6.toInt()))
        }
        applySelection()
    }

    private fun stepper(label: String, action: () -> Unit): TextView {
        val t = Ui.text(context, prefs.bodyPt * 1.05f, bold = true)
        t.text = label
        t.gravity = android.view.Gravity.CENTER
        t.minWidth = Ui.tapSize()
        t.minHeight = Ui.tapSize()
        t.background = Ui.panelBackground(maxOf(1, Scale.ptInt(1f)))
        Ui.tagColor(t)
        t.setOnClickListener { action() }
        return t
    }

    private fun applySelection() {
        var idx = 0
        for (i in 0 until list.childCount) {
            val v = list.getChildAt(i)
            if (v is LinearLayout) {
                Ui.setSelected(v, idx == selected)
                idx++
            }
        }
    }

    fun handleKey(code: Int, ev: KeyEvent): Boolean = when (code) {
        KeyEvent.KEYCODE_DPAD_DOWN -> { selected = (selected + 1).coerceAtMost(rows.size - 1); applySelection(); true }
        KeyEvent.KEYCODE_DPAD_UP -> { selected = (selected - 1).coerceAtLeast(0); applySelection(); true }
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MINUS -> { adjustSelected(-1); true }
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS -> { adjustSelected(1); true }
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> { adjustSelected(1); true }
        KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> { onDismiss?.invoke(); true }
        else -> false
    }

    private fun adjustSelected(dir: Int) {
        rows.getOrNull(selected)?.let {
            it.adjust(dir)
            onChanged?.invoke()
            rebuild()
        }
    }
}

/** Find and replace, pinned to the top of the window. */
class FindBar(ctx: Context, private val prefs: Prefs) : LinearLayout(ctx) {

    var onFind: ((String, Boolean) -> Unit)? = null
    var onReplaceOne: ((String, String) -> Unit)? = null
    var onReplaceAll: ((String, String) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    private val findField: EditText
    private val replaceField: EditText
    private val status: TextView

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        background = Ui.panelBackground()
        val pad = Scale.mmInt(2.5f)
        setPadding(pad, pad, pad, pad)

        findField = field("Find")
        replaceField = field("Replace with")

        addView(findField, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
        addView(button("↑") { fire(false) })
        addView(button("↓") { fire(true) })
        addView(replaceField, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
        addView(button("Replace") {
            onReplaceOne?.invoke(findField.text.toString(), replaceField.text.toString())
        })
        addView(button("All") {
            onReplaceAll?.invoke(findField.text.toString(), replaceField.text.toString())
        })

        status = Ui.text(ctx, prefs.bodyPt * 0.8f, color = Ink.MARKER)
        status.setPadding(Scale.mmInt(2f), 0, 0, 0)
        addView(status)
    }

    private fun field(hint: String) = EditText(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Scale.pt(prefs.bodyPt * 0.95f))
        setTextColor(Ink.TEXT)
        setHintTextColor(Ink.MARKER)
        this.hint = hint
        background = null
        setSingleLine(true)
        minHeight = Ui.tapSize()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setPadding(Scale.mmInt(2f), 0, Scale.mmInt(2f), 0)
        setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    fire(!ev.isShiftPressed); true
                }
                KeyEvent.KEYCODE_ESCAPE -> { onDismiss?.invoke(); true }
                else -> false
            }
        }
    }

    private fun button(label: String, action: () -> Unit): TextView {
        val t = Ui.text(context, prefs.bodyPt * 0.9f, bold = true)
        t.text = label
        t.gravity = android.view.Gravity.CENTER
        t.minWidth = Ui.tapSize()
        t.minHeight = Ui.tapSize()
        t.setPadding(Scale.mmInt(2f), 0, Scale.mmInt(2f), 0)
        t.background = Ui.panelBackground(maxOf(1, Scale.ptInt(1f)))
        t.setOnClickListener { action() }
        return t
    }

    private fun fire(forward: Boolean) {
        val q = findField.text.toString()
        if (q.isNotEmpty()) onFind?.invoke(q, forward)
    }

    fun show() {
        visibility = View.VISIBLE
        findField.requestFocus()
        findField.selectAll()
    }

    fun hide() {
        visibility = View.GONE
    }

    fun setStatus(s: String) {
        status.text = s
    }

    fun queryText(): String = findField.text.toString()

    fun setQuery(s: String) {
        findField.setText(s)
        findField.setSelection(s.length)
    }
}
