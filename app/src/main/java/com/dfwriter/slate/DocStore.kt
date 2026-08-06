package com.dfwriter.slate

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plain files on the SD card, nothing else. The app targets SDK 29 so it keeps
 * legacy storage on Android 11, which means documents live where the device's
 * own file browser and USB export can already see them.
 */
class DocStore(private val ctx: Context, private val prefs: Prefs) {

    var current: File? = null
        private set

    var dirty: Boolean = false

    val fallbackDir: File
        get() = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "Documents")

    fun libraryRoot(): File {
        val saved = prefs.libraryPath
        if (saved.isNotEmpty()) {
            val f = File(saved)
            // canWrite, not canRead: a readable but unwritable folder would be
            // accepted here and then fail every save from that point on.
            if (f.isDirectory && f.canWrite()) return f
        }
        for (candidate in defaultCandidates()) {
            if (candidate.isDirectory && candidate.canWrite()) return candidate
            if (!candidate.exists() && candidate.parentFile?.canWrite() == true) {
                if (candidate.mkdirs()) return candidate
            }
        }
        fallbackDir.mkdirs()
        return fallbackDir
    }

    private fun defaultCandidates(): List<File> {
        val ext = Environment.getExternalStorageDirectory()
        return listOf(
            File(ext, "Documents/Slate"),
            File(ext, "Note/Slate"),
            File(ext, "Documents"),
            ext
        )
    }

    fun setLibraryRoot(dir: File) {
        prefs.libraryPath = dir.absolutePath
    }

    // ------------------------------------------------------------- browsing

    data class Entry(val file: File, val isDir: Boolean, val words: Int)

    fun list(dir: File): List<Entry> {
        val kids = dir.listFiles() ?: return emptyList()
        val dirs = kids.filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
            .map { Entry(it, true, 0) }
        val files = kids.filter { it.isFile && isText(it) }
            .sortedByDescending { it.lastModified() }
            .map { Entry(it, false, 0) }
        return dirs + files
    }

    fun isText(f: File): Boolean {
        val n = f.name.lowercase()
        return n.endsWith(".md") || n.endsWith(".markdown") ||
                n.endsWith(".txt") || n.endsWith(".mdown") || n.endsWith(".text")
    }

    // ----------------------------------------------------------------- i/o

    fun read(f: File): String = f.readText(Charsets.UTF_8)

    fun open(f: File): String {
        val body = read(f)
        current = f
        dirty = false
        prefs.lastFile = f.absolutePath
        return body
    }

    fun save(body: String): File? {
        val f = current ?: return null
        return try {
            f.parentFile?.mkdirs()
            // Write beside the target then swap, so a crash mid-write cannot
            // truncate the only copy of a draft.
            val tmp = File(f.parentFile, ".${f.name}.tmp")
            tmp.writeText(body, Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.writeText(body, Charsets.UTF_8)
                tmp.delete()
            }
            dirty = false
            prefs.lastFile = f.absolutePath
            f
        } catch (e: Exception) {
            null
        }
    }

    fun saveAs(f: File, body: String): File? {
        current = f
        return save(body)
    }

    fun newFile(dir: File, title: String? = null): File {
        dir.mkdirs()
        val base = (title?.takeIf { it.isNotBlank() }?.let(::slug))
            ?: SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
        var f = File(dir, "$base.md")
        var n = 2
        while (f.exists()) {
            f = File(dir, "$base-$n.md")
            n++
        }
        return f
    }

    /**
     * Returns null rather than throwing when the card is full or unwritable.
     * This sits on the autosave path, so an exception here would take the app
     * down while the user was typing.
     */
    fun createAndOpen(dir: File, title: String? = null): File? = runCatching {
        val f = newFile(dir, title)
        f.parentFile?.mkdirs()
        f.writeText("", Charsets.UTF_8)
        current = f
        dirty = false
        prefs.lastFile = f.absolutePath
        f
    }.getOrNull()

    /** As [createAndOpen], for a name the user typed. Null if it cannot be made. */
    fun createNamed(dir: File, name: String): File? = runCatching {
        val f = File(dir, ensureExt(name))
        f.parentFile?.mkdirs()
        f.writeText("", Charsets.UTF_8)
        f
    }.getOrNull()

    fun rename(to: String): File? {
        val f = current ?: return null
        val target = File(f.parentFile, ensureExt(to))
        if (target.exists()) return null
        return if (f.renameTo(target)) {
            current = target
            prefs.lastFile = target.absolutePath
            target
        } else null
    }

    fun delete(): Boolean {
        val f = current ?: return false
        val ok = f.delete()
        if (ok) {
            current = null
            prefs.lastFile = ""
        }
        return ok
    }

    /** Never lose a draft to a crash or an OOM kill: a shadow copy, always. */
    fun writeScratch(body: String) {
        runCatching { File(ctx.filesDir, "scratch.md").writeText(body, Charsets.UTF_8) }
    }

    fun readScratch(): String? =
        runCatching { File(ctx.filesDir, "scratch.md").takeIf { it.exists() }?.readText() }
            .getOrNull()

    companion object {
        fun ensureExt(name: String): String {
            val n = name.trim()
            return if (n.contains('.')) n else "$n.md"
        }

        fun slug(s: String): String =
            s.trim().replace(Regex("[^A-Za-z0-9 _-]"), "")
                .replace(Regex("\\s+"), "-")
                .take(60)
                .ifEmpty { "untitled" }

        fun countWords(s: CharSequence): Int {
            var n = 0
            var inWord = false
            for (c in s) {
                if (c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-') {
                    if (!inWord) { n++; inWord = true }
                } else inWord = false
            }
            return n
        }
    }
}
