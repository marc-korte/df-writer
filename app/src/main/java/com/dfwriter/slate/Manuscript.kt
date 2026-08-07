package com.dfwriter.slate

import java.io.File

/**
 * A long piece kept as a folder of chapter files rather than one large one.
 *
 * The editor holds the whole open document in a single text view, and Android
 * re-measures that whole buffer on every change, so a manuscript in one file
 * gets slower to open and to type in the longer it grows — measured on the
 * device at six seconds to open a hundred thousand words. Chapters keep every
 * file small enough that none of that is felt.
 *
 * The writer is not asked to manage this. A document that grows past the point
 * where it starts to drag is divided at its own chapter headings, and the
 * chapter being worked on stays open with the caret where it was.
 */
object Manuscript {

    /**
     * Where a document is divided, and into what. Produced without touching the
     * disk so it can be examined — and tested — before anything is written.
     */
    class Plan(val parts: List<Part>)

    class Part(val title: String, val body: String)

    /** Files are named so that a plain alphabetical listing is reading order. */
    fun fileNameFor(index: Int, title: String): String {
        val stem = DocStore.slug(title).take(48).ifEmpty { "chapter" }
        return "%02d %s.md".format(index, stem)
    }

    /**
     * How a document would be divided, or null if it should be left alone.
     *
     * The size of a part is what is being chosen — a file only exists to keep
     * the buffer small enough to stay quick — so parts are filled to
     * [targetWords] and then closed. Where they are closed is a separate
     * question with a firm answer: never inside a paragraph. The cut falls on a
     * blank line, and on a chapter heading where one is near enough, so a part
     * begins at a chapter whenever the book allows it.
     *
     * A piece written as one unbroken block has no such boundary, and is left
     * whole rather than cut mid-sentence to satisfy a number.
     */
    fun plan(source: String, targetWords: Int = TARGET_WORDS): Plan? {
        if (targetWords <= 0) return null
        if (DocStore.countWords(source) <= targetWords) return null

        val blocks = blocksOf(source)
        if (blocks.size < 2) return null

        val parts = ArrayList<Part>()
        val current = StringBuilder()
        var words = 0
        var title: String? = null

        for ((i, block) in blocks.withIndex()) {
            val blockWords = DocStore.countWords(block.text)
            val startsChapter = block.heading != null

            // Close the part here when it is full enough and this block opens a
            // chapter, so a part starts at a heading rather than mid-scene.
            val fullEnough = words >= targetWords * 4 / 5
            val overfull = words >= targetWords
            if (current.isNotEmpty() && ((fullEnough && startsChapter) || overfull)) {
                parts.add(Part(title ?: "Part ${parts.size + 1}", current.toString()))
                current.setLength(0)
                words = 0
                title = null
            }

            if (title == null && block.heading != null) title = block.heading
            current.append(block.text)
            words += blockWords
            if (i == blocks.size - 1 && current.isNotEmpty()) {
                parts.add(Part(title ?: "Part ${parts.size + 1}", current.toString()))
            }
        }

        if (parts.size < 2) return null
        return Plan(parts)
    }

    private class Block(val text: String, val heading: String?)

    /**
     * The document as paragraphs, each carrying the text that precedes it, so
     * that concatenating every block reproduces the source exactly — including
     * its blank lines, which is what makes a division lossless.
     */
    private fun blocksOf(source: String): List<Block> {
        val out = ArrayList<Block>()
        var i = 0
        var blockStart = 0
        val len = source.length
        var sawText = false
        var inFence = false

        while (i < len) {
            var lineEnd = i
            while (lineEnd < len && source[lineEnd] != '\n') lineEnd++
            val line = source.substring(i, lineEnd)
            // Same fence rules as the renderer, or a cut could fall where the
            // renderer sees code. Inside a fence nothing is a heading and a
            // blank line is code, not a boundary: a part boundary there would
            // split the fence across two files and leave both halves — and
            // everything after them — rendering wrong.
            val fenceLine = MarkdownStyler.isFenceLine(source, i, lineEnd)
            val heading = if (inFence || fenceLine) null else HEADING.find(line)

            // A heading always opens a block; a blank line closes one.
            if (heading != null && sawText && i > blockStart) {
                out.add(Block(source.substring(blockStart, i), headingOf(source, blockStart)))
                blockStart = i
                sawText = false
            }
            if (fenceLine) inFence = !inFence
            if (line.isNotBlank()) sawText = true
            i = if (lineEnd < len) lineEnd + 1 else len

            if (!inFence && line.isBlank() && sawText && i < len) {
                out.add(Block(source.substring(blockStart, i), headingOf(source, blockStart)))
                blockStart = i
                sawText = false
            }
        }
        if (blockStart < len) {
            out.add(Block(source.substring(blockStart, len), headingOf(source, blockStart)))
        }
        return out
    }

    /** The heading a block opens with, if it opens with one. */
    private fun headingOf(source: String, start: Int): String? {
        var i = start
        while (i < source.length) {
            var e = i
            while (e < source.length && source[e] != '\n') e++
            val line = source.substring(i, e)
            if (line.isNotBlank()) {
                val m = HEADING.find(line) ?: return null
                return line.substring(m.value.length).trim().ifEmpty { null }
            }
            i = e + 1
        }
        return null
    }

    /**
     * Everything the plan describes, reassembled. Used to prove that dividing a
     * document loses nothing: the parts put back together are the original,
     * character for character.
     */
    fun join(plan: Plan): String = plan.parts.joinToString("") { it.body }

    /**
     * Writes [plan] into a folder beside [source], and returns the folder.
     *
     * The original is kept, renamed so that it no longer looks like a document:
    * nothing here deletes a manuscript, and a writer who dislikes the result
     * has the whole thing back in one piece.
     *
     * Returns null without having written anything if any part of it fails, so
     * a half-divided book cannot be left on the card.
     */
    fun write(source: File, plan: Plan): File? {
        val folder = File(source.parentFile, source.nameWithoutExtension)
        if (folder.exists() && !folder.isDirectory) return null
        // A folder that already holds something belongs to someone else, and
        // whatever is in it would be read back as chapters of this book. An
        // unreadable folder counts as occupied: better to leave the document
        // whole than to divide it into a place that cannot be checked.
        if (folder.isDirectory && folder.list()?.isEmpty() != true) return null

        val written = ArrayList<File>()
        val ok = runCatching {
            if (!folder.isDirectory && !folder.mkdirs()) return null
            for ((i, part) in plan.parts.withIndex()) {
                val f = File(folder, fileNameFor(i + 1, part.title))
                if (f.exists()) return@runCatching false
                f.writeText(part.body, Charsets.UTF_8)
                written.add(f)
            }
            true
        }.getOrDefault(false)

        if (!ok) {
            // Back out completely rather than leave some of the chapters behind.
            written.forEach { runCatching { it.delete() } }
            if (written.isNotEmpty()) runCatching { folder.delete() }
            return null
        }

        val kept = File(source.parentFile, source.name + ".bak")
        runCatching { kept.delete() }
        if (!source.renameTo(kept)) {
            written.forEach { runCatching { it.delete() } }
            runCatching { folder.delete() }
            return null
        }
        return folder
    }

    /** The chapter files of a manuscript folder, in reading order. */
    fun chapters(folder: File): List<File> =
        (folder.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.lowercase().endsWith(".md") }
            .sortedWith(READING_ORDER)

    /**
     * Reading order. The names are written so that a plain alphabetical sort
     * gives this same answer — that is what makes the Supernote's own file
     * browser show a book in order — but the numbers are compared as numbers
     * here so that a name written by an older version, or by hand, still lands
     * where it belongs rather than putting part 10 ahead of part 2.
     */
    private val READING_ORDER = Comparator<File> { a, b ->
        val x = numbersOf(a.name)
        val y = numbersOf(b.name)
        val byName = a.name.lowercase().compareTo(b.name.lowercase())
        when {
            // Anything unnumbered is not part of the sequence; it keeps to the
            // end rather than cutting into the middle of the book.
            x.isEmpty() || y.isEmpty() ->
                if (x.isEmpty() == y.isEmpty()) byName else if (x.isEmpty()) 1 else -1
            else -> {
                var c = 0
                for (i in 0 until minOf(x.size, y.size)) {
                    c = x[i].compareTo(y[i])
                    if (c != 0) break
                }
                // A part sorts before the pieces it was divided into: 04, 04-2.
                if (c == 0) c = x.size.compareTo(y.size)
                if (c == 0) byName else c
            }
        }
    }

    /** A part's number read as numbers: "04-2" is fourth, then second within it. */
    private fun numbersOf(name: String): List<Int> =
        numberOf(name)?.split('-')?.mapNotNull { it.toIntOrNull() } ?: emptyList()

    /**
     * True when [folder] holds a divided manuscript rather than a plain folder
     * of notes: at least two files, all of them numbered by [fileNameFor].
     */
    fun isManuscript(folder: File): Boolean {
        val md = chapters(folder)
        return md.size >= 2 && md.all { NUMBERED.containsMatchIn(it.name) }
    }

    /** The manuscript [f] belongs to, or null when it stands on its own. */
    fun folderOf(f: File): File? =
        f.parentFile?.takeIf { NUMBERED.containsMatchIn(f.name) && isManuscript(it) }

    /** The leading number of a part's file name, sub-numbers included. */
    private fun numberOf(name: String): String? =
        Regex("^(\\d{2,}(?:-\\d+)*) ").find(name)?.groupValues?.get(1)

    /**
     * Divides one part of an already-divided piece, in place.
     *
     * Nothing is renamed. The first piece stays in the file it came from — the
     * one that may be open at this moment — and the rest are added beside it
     * with sub-numbers, which sort into the right place on their own. Renaming
     * a shelf full of files to make room would mean renaming the file being
     * written in, and a rename that fails halfway would leave the book in an
     * order nobody chose.
     *
     * The new pieces are written before the original is shortened, so an
     * interruption leaves the tail of the part twice over rather than not at
     * all. Duplicated text can be seen and deleted; lost text cannot.
     *
     * Nothing is written into a file that already holds text. Every write goes
     * to a temporary file first and is renamed into place, so a card that fills
     * up or a battery that goes cannot catch a chapter half-written — least of
     * all [part] itself, which still holds the whole of what it held if the
     * shortening does not go through.
     */
    fun divideInPlace(part: File, plan: Plan): List<File>? {
        val folder = part.parentFile ?: return null
        val number = numberOf(part.name) ?: return null
        if (plan.parts.size < 2) return null

        val added = ArrayList<File>()
        fun backOut(): List<File>? {
            added.forEach { runCatching { it.delete() } }
            return null
        }

        for (i in 1 until plan.parts.size) {
            val piece = plan.parts[i]
            val stem = DocStore.slug(piece.title).take(48).ifEmpty { "part" }
            // Padded, so that the tenth piece still sorts after the second.
            val f = File(folder, "%s-%02d %s.md".format(number, i + 1, stem))
            if (f.exists()) return backOut()
            // Counted as ours before the write, so that a write which fails
            // partway cannot leave a piece of a chapter behind under a name
            // the book would read back as one.
            added.add(f)
            if (!replace(f, piece.body)) return backOut()
        }

        if (!replace(part, plan.parts[0].body)) return backOut()
        return listOf(part) + added
    }

    /**
     * Puts [text] in [target], or leaves [target] exactly as it was.
     *
     * Writing straight to a file empties it first, and a write that fails after
     * that point has destroyed what was there. The bytes go to a temporary file
     * beside the target instead, and only a completed one is renamed over it.
     */
    private fun replace(target: File, text: String): Boolean {
        // Hidden, and not a .md file, so a temporary left by a failure is never
        // read back as a chapter.
        val tmp = File(target.parentFile, ".${target.name}.part")
        runCatching { tmp.delete() }
        val wrote = runCatching { tmp.writeText(text, Charsets.UTF_8); true }.getOrDefault(false)
        if (!wrote) {
            runCatching { tmp.delete() }
            return false
        }
        if (runCatching { tmp.renameTo(target) }.getOrDefault(false)) return true
        // The rename is what makes this safe; if it will not go through, the
        // target is left alone rather than deleted to make room for a second
        // attempt that might fail just as well.
        runCatching { tmp.delete() }
        return false
    }

    /** Every chapter joined back into one piece, for export. */
    fun compile(folder: File): String =
        chapters(folder).joinToString("\n\n") {
            runCatching { it.readText(Charsets.UTF_8).trim('\n') }.getOrDefault("")
        } + "\n"

    /**
     * A part's number, which may carry sub-numbers from having been divided
     * again: 04, then 04-2, then 04-2-2. Ordering still falls out of a plain
     * alphabetical sort, because a space sorts before a hyphen — "04 " comes
     * before "04-2 ", which comes before "05 ".
     */
    private val NUMBERED = Regex("^\\d{2,}(-\\d+)* ")
    private val HEADING = Regex("^#{1,6}\\s+")

    /**
     * How many words a part is filled to. Measured on the device: a file this
     * size opens in well under a second and types within a dozen milliseconds
     * of a short note, where a hundred thousand words takes six seconds to open
     * and types with a noticeable lag.
     */
    const val TARGET_WORDS = 25_000
}
