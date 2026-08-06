package com.dfwriter.slate

import android.content.Context
import android.content.SharedPreferences

enum class Orientation { LANDSCAPE, PORTRAIT, AUTO }

enum class SerifChoice { SERIF, SANS, MONO }

/**
 * AUTO hides the on-screen keyboard whenever a physical one is attached. It is
 * the default because this device forces `show_ime_with_hard_keyboard`, so
 * without it the IME covers half the panel even while typing over Bluetooth.
 */
enum class SoftKeyboard { AUTO, NEVER, ALWAYS }

class Prefs(ctx: Context) {

    private val sp: SharedPreferences =
        ctx.getSharedPreferences("slate", Context.MODE_PRIVATE)

    var uiScale: Float
        get() = sp.getFloat("uiScale", 1.0f)
        set(v) = sp.edit().putFloat("uiScale", v).apply()

    /** Body text size in typographic points. */
    var bodyPt: Float
        get() = sp.getFloat("bodyPt", 13.5f)
        set(v) = sp.edit().putFloat("bodyPt", v).apply()

    /** Measure of the text column in characters; 0 means fill the window. */
    var measureChars: Int
        get() = sp.getInt("measureChars", 76)
        set(v) = sp.edit().putInt("measureChars", v).apply()

    var lineSpacing: Float
        get() = sp.getFloat("lineSpacing", 1.5f)
        set(v) = sp.edit().putFloat("lineSpacing", v).apply()

    var typeface: SerifChoice
        get() = runCatching { SerifChoice.valueOf(sp.getString("typeface", "SERIF")!!) }
            .getOrDefault(SerifChoice.SERIF)
        set(v) = sp.edit().putString("typeface", v.name).apply()

    var focusMode: Boolean
        get() = sp.getBoolean("focusMode", false)
        set(v) = sp.edit().putBoolean("focusMode", v).apply()

    var typewriterMode: Boolean
        get() = sp.getBoolean("typewriterMode", false)
        set(v) = sp.edit().putBoolean("typewriterMode", v).apply()

    /** Typora's defining trick: conceal syntax markers off the caret's line. */
    var hideMarkers: Boolean
        get() = sp.getBoolean("hideMarkers", true)
        set(v) = sp.edit().putBoolean("hideMarkers", v).apply()

    var sourceMode: Boolean
        get() = sp.getBoolean("sourceMode", false)
        set(v) = sp.edit().putBoolean("sourceMode", v).apply()

    /**
     * Defaults to AUTO so that physically turning the device works. Pinning the
     * app to landscape makes it the sole source of the display's orientation,
     * which silently disables the Manta's own rotation — it does have a working
     * accelerometer, contrary to the assumption this app was first built on.
     */
    var orientation: Orientation
        get() = runCatching { Orientation.valueOf(sp.getString("orientation", "AUTO")!!) }
            .getOrDefault(Orientation.AUTO)
        set(v) = sp.edit().putString("orientation", v.name).apply()

    var softKeyboard: SoftKeyboard
        get() = runCatching { SoftKeyboard.valueOf(sp.getString("softKeyboard", "AUTO")!!) }
            .getOrDefault(SoftKeyboard.AUTO)
        set(v) = sp.edit().putString("softKeyboard", v.name).apply()

    /**
     * Mirrors the controls to the side the writing hand is not covering. The
     * device's own handedness setting is not readable by third-party apps — it
     * is not in system, secure or global settings, nor in any property — so
     * Slate keeps its own.
     */
    var leftHanded: Boolean
        get() = sp.getBoolean("leftHanded", true)
        set(v) = sp.edit().putBoolean("leftHanded", v).apply()

    var showStatusBar: Boolean
        get() = sp.getBoolean("showStatusBar", true)
        set(v) = sp.edit().putBoolean("showStatusBar", v).apply()

    /** Full-panel flash after this many edits, to clear E Ink ghosting. 0 = off. */
    var autoRefreshEdits: Int
        get() = sp.getInt("autoRefreshEdits", 400)
        set(v) = sp.edit().putInt("autoRefreshEdits", v).apply()

    var libraryPath: String
        get() = sp.getString("libraryPath", "")!!
        set(v) = sp.edit().putString("libraryPath", v).apply()

    var lastFile: String
        get() = sp.getString("lastFile", "")!!
        set(v) = sp.edit().putString("lastFile", v).apply()

    var lastCaret: Int
        get() = sp.getInt("lastCaret", 0)
        set(v) = sp.edit().putInt("lastCaret", v).apply()
}
