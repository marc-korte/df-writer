package com.dfwriter.slate

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * View construction helpers. Nothing here takes a `dp`; every dimension is a
 * physical measurement, which is what keeps the interface legible on a panel
 * whose reported density does not match its glass. See [Scale].
 */
object Ui {

    /** Smallest comfortable target for a finger on a slow panel. */
    fun tapSize(): Int = maxOf(Scale.fixedMm(9f), Scale.mmInt(8f))

    fun text(
        ctx: Context,
        pt: Float,
        bold: Boolean = false,
        color: Int = Ink.TEXT,
        mono: Boolean = false
    ): TextView = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Scale.pt(pt))
        setTextColor(color)
        typeface = when {
            mono && bold -> android.graphics.Typeface.create(
                android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD
            )
            mono -> android.graphics.Typeface.MONOSPACE
            bold -> android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD
            )
            else -> android.graphics.Typeface.SANS_SERIF
        }
        includeFontPadding = false
        setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
    }

    fun rowContainer(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = tapSize()
        setPadding(Scale.mmInt(3f), Scale.mmInt(1.6f), Scale.mmInt(3f), Scale.mmInt(1.6f))
    }

    fun column(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
    }

    fun divider(ctx: Context, color: Int = 0xFFD0D0D0.toInt()): View = View(ctx).apply {
        setBackgroundColor(color)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, Scale.ptInt(0.9f))
        )
    }

    fun spacer(ctx: Context, weight: Float = 1f): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, weight)
    }

    /** A hard-edged outline; E Ink cannot show a soft shadow, so it gets a border. */
    fun panelBackground(width: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(Color.WHITE)
        setStroke(if (width > 0) width else maxOf(2, Scale.ptInt(1.4f)), Ink.TEXT)
    }

    fun keyCap(ctx: Context, label: String, pt: Float): TextView =
        text(ctx, pt, bold = false, color = Ink.RULE, mono = true).apply {
            this.text = label
        }

    /** A tappable row that also participates in keyboard selection. */
    fun selectableRow(ctx: Context): LinearLayout = rowContainer(ctx).apply {
        isClickable = true
        isFocusable = false
    }

    fun setSelected(row: LinearLayout, selected: Boolean) {
        row.setBackgroundColor(if (selected) Ink.TEXT else Color.WHITE)
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i)
            // A child that paints its own background — the stepper caps, which
            // are white with a border — keeps its own colours. Inverting its
            // text would leave white on white, and the steppers would disappear
            // from whichever row is selected.
            if (c is TextView && c.background == null) {
                c.setTextColor(
                    if (selected) Color.WHITE
                    else (c.getTag(R_TAG_COLOR) as? Int ?: Ink.TEXT)
                )
            }
        }
    }

    /** Remembers a child's unselected colour so inversion can be undone. */
    fun tagColor(v: TextView) {
        v.setTag(R_TAG_COLOR, v.currentTextColor)
    }

    private const val R_TAG_COLOR = 0x7f9a0001
}
