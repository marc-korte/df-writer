package com.dfwriter.slate

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
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

    /**
     * Held by everything that writes to the card or to the shadow copy, and by
     * nothing else. What this object *remembers* — [current], [dirty], the
     * preferences — stays main-thread only; only the writing is shared, and
     * only [writeThrough] and [writeScratchFor] may be called from elsewhere.
     */
    private val ioLock = Any()

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
        return TEXT_EXT.any { n.endsWith(it) }
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

    /**
     * Writes [body] through to the open document and brings what the app
     * remembers of it up to date. Main thread only, and only where the wait is
     * acceptable — an explicit save, or the pause that must finish before the
     * process can be killed. The autosave path calls [writeThrough] on a
     * thread of its own instead, and does this bookkeeping itself.
     */
    fun save(body: String): File? {
        val f = current ?: return null
        if (!writeThrough(f, body)) return null
        dirty = false
        prefs.lastFile = f.absolutePath
        return f
    }

    /**
     * The disk half of a save: the temp copy, the swap onto [f], and the shadow
     * copy that has to follow it. It touches nothing this object remembers, so
     * it is safe to run off the main thread — and every writer in this class
     * holds [ioLock], so two saves cannot interleave a rename with a temp
     * write, nor one save's scratch body with another's owner.
     *
     * Returns false rather than throwing: this sits on the autosave path.
     */
    fun writeThrough(f: File, body: String): Boolean = synchronized(ioLock) {
        try {
            f.parentFile?.mkdirs()
            // Write beside the target then swap, so a crash mid-write cannot
            // truncate the only copy of a draft.
            val tmp = File(f.parentFile, ".${f.name}.tmp")
            writeSynced(tmp, body)
            if (!tmp.renameTo(f)) {
                // Some cards refuse to rename onto a name that already exists.
                // Move the old copy aside instead of truncating it, so a failure
                // from here on still leaves one complete version on the card.
                val aside = File(f.parentFile, ".${f.name}.bak")
                var moved = false
                if (f.isFile) {
                    aside.delete()
                    moved = f.renameTo(aside)
                }
                if (!tmp.renameTo(f)) {
                    try {
                        writeSynced(f, body)
                    } catch (e: Exception) {
                        // Put the previous text back rather than leaving a hole;
                        // the new text is still whole in tmp.
                        if (moved && !f.exists()) aside.renameTo(f)
                        throw e
                    }
                }
                if (moved) aside.delete()
                tmp.delete()
            }
            // Without this the shadow copy is older than the file from here on,
            // and the next launch would offer it as if it were newer.
            writeScratchFor(f.absolutePath, body)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Writes the whole body and asks the card to flush it before returning. A
     * removable FAT card can otherwise acknowledge a write, take the rename, and
     * still hold nothing but zeroes when the power goes.
     */
    private fun writeSynced(f: File, body: String) {
        FileOutputStream(f).use { os ->
            os.write(body.toByteArray(Charsets.UTF_8))
            os.flush()
            // Best effort: a card that will not sync still gets the write.
            runCatching { os.fd.sync() }
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
            // The shadow copy is matched by path, so a draft written before the
            // rename would be refused after it.
            runCatching {
                if (scratchOwner() == f.absolutePath) {
                    scratchOwnerFile.writeText(target.absolutePath, Charsets.UTF_8)
                }
            }
            target
        } else null
    }

    fun delete(): Boolean {
        val f = current ?: return false
        val ok = f.delete()
        if (ok) {
            // A document made later in the same minute can be handed the same
            // name, so the dead one's draft must not outlive it.
            if (scratchOwner() == f.absolutePath) clearScratch()
            current = null
            prefs.lastFile = ""
        }
        return ok
    }

    // ------------------------------------------------------------- recovery

    private val scratchBody: File get() = File(ctx.filesDir, "scratch.md")
    private val scratchOwnerFile: File get() = File(ctx.filesDir, "scratch.path")

    /**
     * A shadow copy in private storage, written on every pause, after every
     * save, and whenever a save fails. It records which document it belongs to,
     * so a later run can tell recovered text apart from text that simply belongs
     * elsewhere.
     *
     * After a clean exit this matches the file on disk, which is what makes the
     * recovery prompt silent in the normal case: there is nothing to recover
     * when the two agree.
     */
    fun writeScratch(body: String) = writeScratchFor(current?.absolutePath ?: "", body)

    /**
     * As [writeScratch], for a document the caller names. The autosave thread
     * uses this one: [current] belongs to the main thread, and a save carries
     * the document it was taken against with it anyway.
     */
    fun writeScratchFor(owner: String, body: String) {
        // Serialised against every other writer here, so that the half-written
        // window below cannot be widened by a second writer stepping into it.
        synchronized(ioLock) {
            runCatching {
                // Body and owner cannot be written in one step, so the pair is
                // disowned first. Dying between the writes then reads back as
                // nothing to offer, rather than as one document's text filed
                // under another document's name.
                scratchOwnerFile.writeText(WRITING, Charsets.UTF_8)
                scratchBody.writeText(body, Charsets.UTF_8)
                scratchOwnerFile.writeText(owner, Charsets.UTF_8)
            }
        }
    }

    fun readScratch(): String? =
        runCatching { scratchBody.takeIf { it.exists() }?.readText(Charsets.UTF_8) }.getOrNull()

    /** Absolute path of the document the scratch copy came from, if any. */
    fun scratchOwner(): String? =
        runCatching { scratchOwnerFile.takeIf { it.exists() }?.readText(Charsets.UTF_8) }
            .getOrNull()?.trim()?.ifEmpty { null }

    fun clearScratch() {
        runCatching { scratchBody.delete(); scratchOwnerFile.delete() }
    }

    /**
     * The text of an unsaved draft that outlived its process, or null when
     * there is nothing to offer. [against] is what was just loaded from disk.
     */
    fun recoverableText(against: String): String? {
        val scratch = readScratch() ?: return null
        if (scratch.isBlank() || scratch == against) return null
        val owner = scratchOwner()
        // Text kept when there was nowhere to save it has no owner. It belongs
        // to no document, which is exactly why nothing else would ever offer it.
        if (owner == null) return scratch
        // Otherwise only offer text belonging to the document actually open, so
        // switching files does not resurrect a draft from a different one.
        if (owner != current?.absolutePath) return null
        return scratch
    }

    companion object {
        /** What the browser shows, and what a new name is given if it has none. */
        private val TEXT_EXT = listOf(".md", ".markdown", ".txt", ".mdown", ".text")

        /** Marks the scratch pair as half-written; never equal to any path. */
        private const val WRITING = "?"

        fun ensureExt(name: String): String {
            val n = name.trim()
            // Not "contains a dot": "Notes v1.2" would keep a name the browser
            // does not recognise as a document, and drop out of the list.
            val lower = n.lowercase()
            return if (TEXT_EXT.any { lower.endsWith(it) }) n else "$n.md"
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
