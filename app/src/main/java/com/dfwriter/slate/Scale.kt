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
     * Every Supernote writing tablet is a 300 PPI panel — the Manta at
     * 1920x2560 and the Nomad at 1404x1872 both are — as is essentially every
     * E Ink device of this kind. So when the panel is big enough to be one of
     * them, 300 is the floor, regardless of screen size. Deriving the floor
     * from an assumed diagonal instead would need a different constant per
     * model and would quietly under-size the smaller ones.
     */
    private const val CLASS_PPI = 300f

    /** Below this the device is a phone, where the reported density is trusted. */
    private const val TABLET_SHORT_EDGE_PX = 1200

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
        val impliedByPanel = if (shortEdgePx >= TABLET_SHORT_EDGE_PX) CLASS_PPI else 0f
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
