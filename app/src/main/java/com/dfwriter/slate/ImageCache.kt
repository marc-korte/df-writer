package com.dfwriter.slate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors

/**
 * Decoded images, kept small and kept off the main thread.
 *
 * Styling runs on every keystroke and drawing runs on every frame, so neither
 * can afford to touch the disk. A span asks for a bitmap; it either gets one
 * immediately from the cache or gets null and a callback when the decode
 * finishes, at which point the editor restyles and the picture appears.
 */
object ImageCache {

    /** Downsampled to the column width, so a 12 megapixel photo costs kilobytes. */
    private val cache: LruCache<String, Bitmap> =
        object : LruCache<String, Bitmap>(budgetBytes()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "slate-image").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val ui = Handler(Looper.getMainLooper())

    private val inFlight = HashSet<String>()
    private val failed = HashSet<String>()

    /** Bumped by [retryBroken]; guarded by [failed]. */
    private var generation = 0

    private fun budgetBytes(): Int {
        val max = Runtime.getRuntime().maxMemory()
        return (max / 8).coerceIn(2L * 1024 * 1024, 24L * 1024 * 1024).toInt()
    }

    /**
     * Size and timestamp belong in the key: a picture replaced on the card
     * keeps its path, and nothing here would otherwise notice the new one.
     */
    private fun key(file: File, width: Int) =
        "${file.absolutePath}\u0000$width\u0000${file.lastModified()}\u0000${file.length()}"

    /** A bitmap if one is ready, otherwise null. Never decodes. */
    fun peek(file: File, width: Int): Bitmap? = cache.get(key(file, width))

    /** True once a decode has been tried and failed; the span then draws alt text. */
    fun isBroken(file: File, width: Int): Boolean =
        synchronized(failed) { failed.contains(key(file, width)) }

    /**
     * Queues a decode if one is not already running or done. [onReady] runs on
     * the main thread, once, when something has changed worth redrawing.
     */
    fun request(file: File, width: Int, onReady: () -> Unit) {
        if (width <= 0) return
        val k = key(file, width)
        if (cache.get(k) != null) return
        val gen = synchronized(failed) {
            if (failed.contains(k)) return
            generation
        }
        synchronized(inFlight) {
            if (!inFlight.add(k)) return
        }
        io.execute {
            val bmp = runCatching { decode(file, width) }.getOrNull()
            // Publish before clearing the flag: a request arriving between the
            // two would otherwise decode the same file all over again.
            if (bmp != null) cache.put(k, bmp)
            // A failure decided before a retry says nothing about the file now.
            else synchronized(failed) { if (gen == generation) failed.add(k) }
            synchronized(inFlight) { inFlight.remove(k) }
            ui.post(onReady)
        }
    }

    /**
     * Decodes every image a document refers to, on the calling thread. The PDF
     * exporter lays its pages out once, so a bitmap still on its way would come
     * out as a blank frame in the file rather than as a picture.
     */
    fun warm(source: CharSequence, documentDir: File?, width: Int) {
        if (width <= 0) return
        for (m in IMAGE.findAll(source)) {
            val file = resolve(m.groupValues[1], documentDir) ?: continue
            val k = key(file, width)
            if (cache.get(k) != null) continue
            val bmp = runCatching { decode(file, width) }.getOrNull()
            if (bmp != null) cache.put(k, bmp) else synchronized(failed) { failed.add(k) }
        }
    }

    /** Forgets failures so an image added since can be picked up. */
    fun retryBroken() {
        synchronized(failed) { failed.clear(); generation++ }
    }

    fun clear() {
        cache.evictAll()
        retryBroken()
    }

    private fun decode(file: File, reqWidth: Int): Bitmap? {
        if (!file.isFile || !file.canRead()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqWidth && sample < 64) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            // The panel is greyscale, so 16 bits per pixel loses nothing visible
            // and halves what the cache has to hold.
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    /**
     * Resolves a Markdown image target against the document's own folder.
     * Remote URLs are refused: the app holds no INTERNET permission, and a
     * writing tool should not stall on the network.
     */
    fun resolve(target: String, documentDir: File?): File? {
        val raw = target.trim()
        // A bracketed target may contain spaces — ![a](<my photo.png> "t") — and
        // a bare one ends at the space where its title begins.
        val t = if (raw.startsWith("<")) {
            val end = raw.indexOf('>')
            (if (end > 0) raw.substring(1, end) else raw.substring(1)).trim()
        } else {
            raw.substringBefore(' ').trim()
        }
        if (t.isEmpty()) return null
        if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("data:")) return null
        val path = if (t.startsWith("file://")) t.removePrefix("file://") else t
        val decoded = percentDecode(path)
        val f = File(decoded)
        if (f.isAbsolute) return f
        val dir = documentDir ?: return null
        return File(dir, decoded)
    }

    /**
     * Percent-decoding and nothing else. URLDecoder would also read "+" as a
     * space, which turns a file honestly named "C++.png" into "C  .png".
     */
    internal fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val hi = if (s[i] == '%' && i + 2 < s.length) Character.digit(s[i + 1], 16) else -1
            val lo = if (hi >= 0) Character.digit(s[i + 2], 16) else -1
            if (lo >= 0) {
                out.write((hi shl 4) or lo)
                i += 3
            } else {
                // Copy the whole run at once: a character outside the basic
                // plane is two chars here and must not be split.
                var j = i + 1
                while (j < s.length && s[j] != '%') j++
                out.write(s.substring(i, j).toByteArray(Charsets.UTF_8))
                i = j
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** The image syntax, matched the same way the styler matches it. */
    private val IMAGE = Regex("!\\[[^\\]\\n]*\\]\\(([^)\\n]*)\\)")
}
