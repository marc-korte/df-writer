package com.dfwriter.slate

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Every size in this app is a physical measurement converted through one
 * effective density, never an Android `dp`.
 *
 * `dp` is derived from `densityDpi`, a number the ROM reports, and E Ink ROMs
 * routinely report something unrelated to the glass. The Manta is a 300 PPI
 * panel; a ROM claiming 160 draws a 16dp control 16px tall, about half the
 * intended physical size, which is why sideloaded apps come out unreadably
 * small on it.
 *
 * Two independent signals are combined, and the larger wins:
 *
 *  - what the display metrics claim, when the claim is plausible at all;
 *  - what the panel's own pixel count implies, which no ROM can misreport.
 *
 * Taking the larger means a lying density can only ever be corrected upward, so
 * the failure mode is text that is slightly too big rather than unreadable.
 */
object Scale {

    /** Density actually used for every conversion, in dots per inch. */
    var dpi: Float = 300f
        private set

    /** What the system claimed, kept for the diagnostics line in settings. */
    var reportedDpi: Float = 0f
        private set

    /** User multiplier on top of the physical size. Persisted. */
    var ui: Float = 1.0f
        private set

    /**
     * Assumed physical width of the short edge of an E Ink writing tablet, in
     * inches. The Manta's 1920 px short edge over 6.4 in is exactly its stated
     * 300 PPI. Anything in this class of device lands close enough that the
     * floor it produces is sane, and the user scale covers the rest.
     */
    private const val SHORT_EDGE_INCHES = 6.4f

    fun init(ctx: Context, prefs: Prefs) {
        val dm = DisplayMetrics()
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)

        reportedDpi = (dm.xdpi + dm.ydpi) / 2f
        dpi = chooseDpi(reportedDpi, dm.densityDpi, minOf(dm.widthPixels, dm.heightPixels))
        ui = prefs.uiScale
    }

    /**
     * Pure so it can be tested against the cases that matter: a Manta whose ROM
     * tells the truth, a Manta whose ROM claims 160, and an ordinary phone.
     */
    fun chooseDpi(measured: Float, densityDpi: Int, shortEdgePx: Int): Float {
        // A believable panel density. Anything outside is a ROM bug, not a fact.
        val claimed = when {
            measured.isFinite() && measured in 100f..700f -> measured
            densityDpi in 100..700 -> densityDpi.toFloat()
            else -> 0f
        }
        val impliedByPanel =
            if (shortEdgePx > 0) shortEdgePx / SHORT_EDGE_INCHES else 0f
        return maxOf(claimed, impliedByPanel).coerceIn(120f, 700f)
    }

    fun setUiScale(prefs: Prefs, value: Float) {
        ui = value.coerceIn(MIN_UI, MAX_UI)
        prefs.uiScale = ui
    }

    /** Typographic points to pixels. 72 pt is one physical inch. */
    fun pt(points: Float): Float = points / 72f * dpi * ui

    fun ptInt(points: Float): Int = Math.round(pt(points))

    /** Millimetres to pixels, for gaps and touch targets. */
    fun mm(millis: Float): Float = millis / 25.4f * dpi * ui

    fun mmInt(millis: Float): Int = Math.round(mm(millis))

    /** A fixed real-world size that the user multiplier must not shrink. */
    fun fixedMm(millis: Float): Int = Math.round(millis / 25.4f * dpi)

    fun describe(): String =
        "${Math.round(dpi)} dpi effective, ${Math.round(reportedDpi)} reported"

    const val MIN_UI = 0.65f
    const val MAX_UI = 2.20f
    const val UI_STEP = 0.08f
}
