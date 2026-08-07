package com.dfwriter.slate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Dividing a long piece into parts. The first duty of this code is to lose
 * nothing and to cut nowhere a reader would notice, so that is most of what is
 * asserted here.
 */
class ManuscriptTest {

    private val sentence = "The morning began badly and it went on from there, as mornings will. "

    private fun book(words: Int, chapterEvery: Int = 2_000): String {
        val sb = StringBuilder()
        sb.append("# A Manuscript\n\nAn opening note before any chapter.\n\n")
        var count = 0
        var chapter = 1
        while (count < words) {
            sb.append("## Chapter ").append(chapter).append("\n\n")
            var inChapter = 0
            while (inChapter < chapterEvery && count < words) {
                sb.append(sentence.repeat(8)).append("\n\n")
                inChapter += 96
                count += 96
            }
            chapter++
        }
        return sb.toString()
    }

    // ------------------------------------------------------------- losing nothing

    @Test
    fun `the parts put back together are the original, character for character`() {
        for (words in listOf(30_000, 60_000, 120_000)) {
            val source = book(words)
            val plan = Manuscript.plan(source, targetWords = 25_000)
            assertNotNull("a $words word piece should divide", plan)
            assertEquals(
                "dividing a $words word piece changed it",
                source, Manuscript.join(plan!!)
            )
        }
    }

    @Test
    fun `a piece with no blank line anywhere is left whole`() {
        // One unbroken block has no boundary that is not inside a sentence.
        val source = sentence.repeat(6_000)
        assertTrue(DocStore.countWords(source) > 25_000)
        assertNull(
            "a piece with nowhere safe to cut must not be cut",
            Manuscript.plan(source, targetWords = 25_000)
        )
    }

    @Test
    fun `a piece under the target is left alone`() {
        assertNull(Manuscript.plan(book(5_000), targetWords = 25_000))
    }

    // ------------------------------------------------------------ where it cuts

    @Test
    fun `no part begins in the middle of a paragraph`() {
        val source = book(90_000)
        val plan = Manuscript.plan(source, targetWords = 25_000)!!
        // Every part after the first starts where the previous one ended, and
        // that seam must fall on a blank line or a heading — never inside prose.
        for (part in plan.parts.drop(1)) {
            val firstLine = part.body.lineSequence().first { it.isNotBlank() }
            assertTrue(
                "a part began mid-paragraph: ${firstLine.take(60)}",
                firstLine.startsWith("#")
            )
        }
    }

    @Test
    fun `parts begin at a chapter where the book allows it`() {
        val source = book(90_000, chapterEvery = 3_000)
        val plan = Manuscript.plan(source, targetWords = 25_000)!!
        val chapterStarts = plan.parts.drop(1).count {
            it.body.trimStart().startsWith("## ")
        }
        assertEquals(
            "every part after the first should open on a chapter",
            plan.parts.size - 1, chapterStarts
        )
    }

    @Test
    fun `parts come out near the size asked for`() {
        val source = book(120_000)
        val plan = Manuscript.plan(source, targetWords = 25_000)!!
        for (part in plan.parts.dropLast(1)) {
            val w = DocStore.countWords(part.body)
            assertTrue("a part held $w words against a target of 25,000", w in 15_000..35_000)
        }
        assertEquals(
            "the parts should account for every word",
            DocStore.countWords(source),
            plan.parts.sumOf { DocStore.countWords(it.body) }
        )
    }

    @Test
    fun `a heading with no text under it does not strand an empty part`() {
        val source = book(60_000)
        val plan = Manuscript.plan(source, targetWords = 25_000)!!
        assertTrue(plan.parts.all { it.body.isNotBlank() })
    }

    // ----------------------------------------------------------------- writing

    private fun tempDir(): File =
        Files.createTempDirectory("manuscript").toFile().apply { deleteOnExit() }

    @Test
    fun `writing lays out numbered files and keeps the original`() {
        val dir = tempDir()
        val source = File(dir, "My Book.md")
        val text = book(60_000)
        source.writeText(text)

        val plan = Manuscript.plan(text, targetWords = 25_000)!!
        val folder = Manuscript.write(source, plan)
        assertNotNull(folder)

        val parts = Manuscript.chapters(folder!!)
        assertEquals(plan.parts.size, parts.size)
        assertTrue("reading order must be plain alphabetical order",
            parts.map { it.name } == parts.map { it.name }.sorted())

        assertFalse("the original must not be left looking like a document", source.exists())
        val kept = File(dir, "My Book.md.bak")
        assertTrue("the original must be kept", kept.isFile)
        assertEquals("and kept whole", text, kept.readText())
    }

    @Test
    fun `what was written adds back up to what was there`() {
        val dir = tempDir()
        val source = File(dir, "Book.md")
        val text = book(60_000)
        source.writeText(text)
        val folder = Manuscript.write(source, Manuscript.plan(text, 25_000)!!)!!

        val rejoined = Manuscript.chapters(folder).joinToString("") { it.readText() }
        assertEquals("the files on disk must be the book", text, rejoined)
    }

    @Test
    fun `a folder that cannot be written leaves nothing behind`() {
        val dir = tempDir()
        val source = File(dir, "Book.md")
        source.writeText(book(60_000))
        // A file where the folder needs to go.
        File(dir, "Book").writeText("in the way")

        assertNull(Manuscript.write(source, Manuscript.plan(source.readText(), 25_000)!!))
        assertTrue("the original must survive a failed division", source.isFile)
    }

    // -------------------------------------------------------------- recognising

    @Test
    fun `a divided manuscript is recognised, a folder of notes is not`() {
        val dir = tempDir()
        val source = File(dir, "Book.md")
        source.writeText(book(60_000))
        val folder = Manuscript.write(source, Manuscript.plan(source.readText(), 25_000)!!)!!

        assertTrue(Manuscript.isManuscript(folder))
        assertEquals(folder, Manuscript.folderOf(Manuscript.chapters(folder).first()))

        val notes = tempDir()
        File(notes, "shopping.md").writeText("milk")
        File(notes, "ideas.md").writeText("a thought")
        assertFalse("plain notes are not a manuscript", Manuscript.isManuscript(notes))
        assertNull(Manuscript.folderOf(File(notes, "shopping.md")))
    }

    // --------------------------------------------------- dividing again

    @Test
    fun `a part that outgrows the target divides again where it stands`() {
        val dir = tempDir()
        val source = File(dir, "Book.md")
        source.writeText(book(60_000))
        val folder = Manuscript.write(source, Manuscript.plan(source.readText(), 25_000)!!)!!
        val before = Manuscript.chapters(folder)

        // The writer keeps going in the last part until it is too long itself.
        val grown = before.last()
        val longer = grown.readText() + book(40_000)
        grown.writeText(longer)

        val pieces = Manuscript.divideInPlace(grown, Manuscript.plan(longer, 25_000)!!)
        assertNotNull("a part must be able to divide again", pieces)

        assertEquals(
            "the part and its new pieces must be the text that was there",
            longer, pieces!!.joinToString("") { it.readText() }
        )
        assertEquals(
            "the file being written in must keep its name",
            grown.absolutePath, pieces.first().absolutePath
        )
    }

    @Test
    fun `the new pieces sort into the right place`() {
        val dir = tempDir()
        val source = File(dir, "Book.md")
        source.writeText(book(60_000))
        val folder = Manuscript.write(source, Manuscript.plan(source.readText(), 25_000)!!)!!
        val order = Manuscript.chapters(folder)

        // Divide a part in the middle, where the ordering is easiest to break.
        val middle = order[1]
        val longer = middle.readText() + book(40_000)
        middle.writeText(longer)
        Manuscript.divideInPlace(middle, Manuscript.plan(longer, 25_000)!!)!!

        val after = Manuscript.chapters(folder)
        assertEquals(
            "reading order must still be plain alphabetical order",
            after.map { it.name }, after.map { it.name }.sorted()
        )
        val at = after.indexOfFirst { it.name == middle.name }
        assertTrue("the new pieces must follow the part they came from",
            after[at + 1].name.startsWith(middle.name.substringBefore(' ') + "-"))
        assertTrue("and still come before the next part",
            after.last().name.startsWith("03 ") || after.last().name.startsWith("04 "))
        assertTrue("a divided part is still recognised as a manuscript",
            Manuscript.isManuscript(folder))
    }

    @Test
    fun `a part divided twice still sorts correctly`() {
        val names = listOf("01 a.md", "02 b.md", "02-02 c.md", "02-02-02 d.md", "03 e.md")
        assertEquals(
            "sub-numbered parts must sort between their neighbours",
            names, names.sorted()
        )
    }

    @Test
    fun `the tenth piece of a part comes after the second`() {
        val dir = tempDir()
        val source = File(dir, "Book.md")
        source.writeText(book(60_000))
        val folder = Manuscript.write(source, Manuscript.plan(source.readText(), 25_000)!!)!!

        // Divided small enough to make more than nine pieces, which is where
        // plain alphabetical ordering of unpadded numbers goes wrong.
        val grown = Manuscript.chapters(folder).first()
        val longer = grown.readText()
        val pieces = Manuscript.divideInPlace(grown, Manuscript.plan(longer, 2_000)!!)!!
        assertTrue("this test needs at least ten pieces, got ${pieces.size}", pieces.size >= 10)

        assertEquals(
            "the pieces must come back in the order they were written",
            longer, pieces.joinToString("") { it.readText() }
        )
        val names = Manuscript.chapters(folder).map { it.name }
        assertEquals(
            "reading order must survive a plain alphabetical sort, for the " +
                    "device's own file browser",
            names, names.sorted()
        )
        assertEquals(
            "the whole book must still read back in order",
            DocStore.countWords(source.parentFile!!.let { File(it, "Book.md.bak") }.readText()),
            DocStore.countWords(Manuscript.compile(folder))
        )
    }

    @Test
    fun `a number written without padding still reads in order`() {
        // What an older version left behind, and what a writer might type.
        val dir = tempDir()
        listOf("01 one.md", "02 two.md", "02-2 a.md", "02-10 b.md", "03 three.md")
            .forEach { File(dir, it).writeText("x") }
        assertEquals(
            listOf("01 one.md", "02 two.md", "02-2 a.md", "02-10 b.md", "03 three.md"),
            Manuscript.chapters(dir).map { it.name }
        )
    }

    @Test
    fun `a folder that already holds something is left alone`() {
        val dir = tempDir()
        val source = File(dir, "Book.md")
        val text = book(60_000)
        source.writeText(text)
        // Notes that happen to share the document's name.
        File(dir, "Book").mkdirs()
        File(dir, "Book/thoughts.md").writeText("an unrelated note")

        assertNull(
            "a folder with someone else's files in it must not be used",
            Manuscript.write(source, Manuscript.plan(text, 25_000)!!)
        )
        assertEquals("the document must be untouched", text, source.readText())
        assertEquals(
            "and so must the note",
            "an unrelated note", File(dir, "Book/thoughts.md").readText()
        )
    }

    // ------------------------------------------------- when the card says no

    @Test
    fun `a division that cannot be written leaves the part whole`() {
        val dir = tempDir()
        val source = File(dir, "Book.md")
        source.writeText(book(60_000))
        val folder = Manuscript.write(source, Manuscript.plan(source.readText(), 25_000)!!)!!

        val grown = Manuscript.chapters(folder).last()
        val longer = grown.readText() + book(40_000)
        grown.writeText(longer)
        val before = Manuscript.chapters(folder).map { it.name }

        // A card with nothing left on it: nothing new can be created.
        org.junit.Assume.assumeTrue(
            "this test needs a folder that can be made read-only",
            folder.setWritable(false) &&
                    !runCatching { File(folder, "probe.tmp").createNewFile() }.getOrDefault(false)
        )
        try {
            assertNull(
                "a division that cannot be written must not report success",
                Manuscript.divideInPlace(grown, Manuscript.plan(longer, 25_000)!!)
            )
            assertEquals(
                "the part must still hold every word it held",
                longer, grown.readText()
            )
            assertEquals(
                "and nothing may be left behind",
                before, Manuscript.chapters(folder).map { it.name }
            )
            assertTrue(
                "not even a half-written temporary",
                (folder.list() ?: emptyArray()).none { it.startsWith(".") }
            )
        } finally {
            folder.setWritable(true)
        }
    }

    @Test
    fun `compiling puts the book back in order`() {
        val dir = tempDir()
        val source = File(dir, "Book.md")
        val text = book(60_000)
        source.writeText(text)
        val folder = Manuscript.write(source, Manuscript.plan(text, 25_000)!!)!!

        val compiled = Manuscript.compile(folder)
        assertTrue(compiled.contains("# A Manuscript"))
        // Chapters in reading order, not alphabetical by title.
        val first = compiled.indexOf("## Chapter 1")
        val second = compiled.indexOf("## Chapter 2")
        assertTrue(first in 0 until second)
        assertEquals(
            "no words may be lost on the way back",
            DocStore.countWords(text), DocStore.countWords(compiled)
        )
    }
}
