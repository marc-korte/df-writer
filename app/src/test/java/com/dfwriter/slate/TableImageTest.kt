package com.dfwriter.slate

import android.text.SpannableStringBuilder
import android.text.TextPaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Live rendering of tables and images inside the editor. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class TableImageTest {

    private lateinit var prefs: Prefs
    private lateinit var styler: MarkdownStyler
    private lateinit var dir: File

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        prefs = Prefs(ctx)
        Scale.init(ctx, prefs)
        dir = File(ctx.cacheDir, "docs-${System.nanoTime()}").apply { mkdirs() }
        styler = MarkdownStyler(prefs).apply {
            overrideBodyPx = 40f
            contentWidthPx = 1200
            measure = TextPaint().apply { textSize = 40f }
            documentDir = dir
        }
        ImageCache.clear()
    }

    private fun style(src: String, caret: Int = -1): SpannableStringBuilder {
        val sb = SpannableStringBuilder(src)
        styler.restyleAll(sb, caret)
        return sb
    }

    private inline fun <reified T> spans(sb: SpannableStringBuilder, from: Int, to: Int): List<T> =
        sb.getSpans(from, to, T::class.java).toList()

    private val TABLE = """
        Before the table.

        | Name | Count | Note |
        | --- | ---: | :---: |
        | apples | 12 | fresh |
        | pears | 3 |  |

        After the table.
    """.trimIndent()

    // ---------------------------------------------------------------- tables

    @Test
    fun `a table renders as rows with a collapsed delimiter`() {
        val sb = style(TABLE)
        val header = TABLE.indexOf("| Name")
        val delim = TABLE.indexOf("| --- |")
        val body = TABLE.indexOf("| apples")

        assertTrue("header row", spans<TableRowSpan>(sb, header, header + 6).isNotEmpty())
        assertTrue("delimiter collapses", spans<TableDividerSpan>(sb, delim, delim + 6).isNotEmpty())
        assertTrue("body row", spans<TableRowSpan>(sb, body, body + 6).isNotEmpty())
        assertEquals("the file itself is untouched", TABLE, sb.toString())
    }

    @Test
    fun `the caret's own row shows its pipes so it can be edited`() {
        val body = TABLE.indexOf("| apples")
        val sb = style(TABLE, caret = body + 3)

        assertTrue(
            "the row under the caret must not be replaced",
            spans<TableRowSpan>(sb, body, body + 6).isEmpty()
        )
        // Other rows keep their rendering.
        val header = TABLE.indexOf("| Name")
        assertTrue(spans<TableRowSpan>(sb, header, header + 6).isNotEmpty())
    }

    @Test
    fun `alignment markers are read from the delimiter row`() {
        val t = styler.tableAt(TABLE, TABLE.indexOf("| Name"))
        assertNotNull(t)
        assertEquals(
            listOf(
                TableRowSpan.ALIGN_LEFT,
                TableRowSpan.ALIGN_RIGHT,
                TableRowSpan.ALIGN_CENTER
            ),
            t!!.aligns.toList()
        )
    }

    @Test
    fun `columns fill the text column exactly`() {
        val t = styler.tableAt(TABLE, TABLE.indexOf("| Name"))!!
        assertEquals(3, t.widths.size)
        assertEquals("the grid should close on the column edge", 1200, t.widths.sum())
        assertTrue("every column needs a usable width", t.widths.all { it > 0 })
    }

    @Test
    fun `pipes without a delimiter row are just text`() {
        val src = "a | b | c\nnot a table\n"
        val sb = style(src)
        assertNull(styler.tableAt(src, 0))
        assertTrue(spans<TableRowSpan>(sb, 0, src.length).isEmpty())
    }

    @Test
    fun `a table without outer pipes still renders`() {
        val src = "Name | Count\n--- | ---\napples | 12\n"
        assertNotNull(styler.tableAt(src, 0))
        val sb = style(src)
        assertTrue(spans<TableRowSpan>(sb, 0, 12).isNotEmpty())
    }

    @Test
    fun `an empty cell does not break the row`() {
        val src = "| a | b |\n| --- | --- |\n|  |  |\n"
        val sb = style(src)
        val last = src.indexOf("|  |")
        assertTrue(spans<TableRowSpan>(sb, last, last + 4).isNotEmpty())
        assertEquals(src, sb.toString())
    }

    @Test
    fun `styling every caret position in a table never throws`() {
        for (caret in -1..TABLE.length) {
            val sb = SpannableStringBuilder(TABLE)
            styler.restyleAll(sb, caret)
            assertEquals("buffer changed at caret $caret", TABLE, sb.toString())
        }
    }

    @Test
    fun `editing one cell restyles the whole table`() {
        val sb = SpannableStringBuilder(TABLE)
        styler.restyleAll(sb, -1)
        // A keystroke confined to the last row must still restyle the header,
        // because every column width depends on every cell.
        val last = TABLE.indexOf("| pears")
        styler.restyleRange(sb, last + 3, last + 4, -1)
        val header = TABLE.indexOf("| Name")
        assertTrue(
            "the header should have been restyled too",
            spans<TableRowSpan>(sb, header, header + 6).isNotEmpty()
        )
    }

    @Test
    fun `measuring a row twice does not make it taller`() {
        // getSize may be called more than once for the same line. Adjusting the
        // metrics it is handed, rather than deriving them from the paint, made
        // the padding accumulate so rows crept further apart the longer a table
        // stayed on screen.
        val span = TableRowSpan(
            cells = listOf("a", "b"),
            widths = intArrayOf(100, 100),
            aligns = intArrayOf(0, 0),
            header = false,
            pad = 12f,
            rule = 2,
            firstRow = false,
            lastRow = false
        )
        val paint = TextPaint().apply { textSize = 40f }
        val fm = android.graphics.Paint.FontMetricsInt()

        span.getSize(paint, "x", 0, 1, fm)
        val ascent = fm.ascent
        val descent = fm.descent

        repeat(5) { span.getSize(paint, "x", 0, 1, fm) }
        assertEquals("row height must not creep", ascent, fm.ascent)
        assertEquals("row height must not creep", descent, fm.descent)
    }

    @Test
    fun `an image reports the same height however often it is measured`() {
        val span = ImageSpan(null, 600, "alt", broken = false, bodyPx = 40f)
        val paint = TextPaint().apply { textSize = 40f }
        val fm = android.graphics.Paint.FontMetricsInt()

        span.getSize(paint, "x", 0, 1, fm)
        val ascent = fm.ascent
        repeat(5) { span.getSize(paint, "x", 0, 1, fm) }
        assertEquals(ascent, fm.ascent)
    }

    // ---------------------------------------------------------------- images

    @Test
    fun `an image becomes a picture rather than link text`() {
        val src = "text\n\n![a photo](photo.png)\n\nmore\n"
        val sb = style(src)
        val at = src.indexOf("![")
        assertTrue(spans<ImageSpan>(sb, at, at + 5).isNotEmpty())
        assertEquals(src, sb.toString())
    }

    @Test
    fun `the caret on an image line shows its markdown`() {
        val src = "![a photo](photo.png)\n"
        val at = 0
        val sb = style(src, caret = 3)
        assertTrue(
            "the source must come back for editing",
            spans<ImageSpan>(sb, at, at + 5).isEmpty()
        )
        assertTrue(spans<MarkerSpan>(sb, at, at + 2).isNotEmpty())
    }

    @Test
    fun `a remote image is left as link text rather than fetched`() {
        // The app holds no INTERNET permission and a writing tool should never
        // stall on the network.
        val src = "![remote](https://example.com/x.png)\n"
        val sb = style(src)
        assertTrue(spans<ImageSpan>(sb, 0, 10).isEmpty())
        assertTrue(spans<LinkTextSpan>(sb, 2, 8).isNotEmpty())
    }

    /**
     * Waits on the cache rather than on the ready callback. The callback is
     * posted to the main looper, which Robolectric leaves paused, so a test
     * thread blocking on it would wait for a message that cannot be delivered
     * until the test thread itself pumps the queue.
     */
    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    /** A real PNG on disk, of a real size, for the decoder to chew on. */
    private fun writePng(f: File, w: Int, h: Int): File {
        val bmp = android.graphics.Bitmap.createBitmap(
            w, h, android.graphics.Bitmap.Config.ARGB_8888
        )
        f.outputStream().use {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        return f
    }

    @Test
    fun `a real file on disk decodes and is drawn`() {
        val png = writePng(File(dir, "real.png"), 40, 20)

        ImageCache.request(png, 1200) { }
        waitFor("the decode") { ImageCache.peek(png, 1200) != null }

        val bmp = ImageCache.peek(png, 1200)!!
        assertEquals("a picture narrower than the column is left as it is", 40, bmp.width)
        assertFalse(ImageCache.isBroken(png, 1200))
        // peek is called from the styler and from onDraw, so it must never go to
        // disk: a width nobody has asked for stays empty however real the file.
        assertNull("peek must not decode", ImageCache.peek(png, 600))
    }

    @Test
    fun `a picture far wider than the column is decoded smaller`() {
        // The whole point of the cache: a photo off a camera must not be held at
        // full size to be drawn into a column a fraction of its width.
        val png = writePng(File(dir, "wide.png"), 2400, 60)

        ImageCache.request(png, 1200) { }
        waitFor("the downsampled decode") { ImageCache.peek(png, 1200) != null }
        val small = ImageCache.peek(png, 1200)!!

        // Asked for more than the file holds, nothing is thrown away.
        ImageCache.request(png, 4000) { }
        waitFor("the full size decode") { ImageCache.peek(png, 4000) != null }
        val full = ImageCache.peek(png, 4000)!!

        assertEquals("nothing to sample away for a column wider than the file", 2400, full.width)
        assertTrue(
            "2400px in a 1200px column should have been sampled down, got ${small.width}",
            small.width <= 1200
        )
        assertTrue(
            "but never below the width it has to fill, got ${small.width}",
            small.width >= 600
        )
        assertTrue("the smaller decode must cost less memory", small.byteCount < full.byteCount)
    }

    @Test
    fun `a missing image is reported rather than silently blank`() {
        val gone = File(dir, "nope.png")
        ImageCache.request(gone, 800) { }
        waitFor("the failed decode") { ImageCache.isBroken(gone, 800) }
        assertTrue(ImageCache.isBroken(gone, 800))
        assertNull(ImageCache.peek(gone, 800))
    }

    @Test
    fun `a broken image draws a labelled frame and can be retried`() {
        val gone = File(dir, "later.png")
        ImageCache.request(gone, 800) { }
        waitFor("the failed decode") { ImageCache.isBroken(gone, 800) }

        val src = "![a diagram](later.png)\n"
        val sb = style(src)
        assertTrue(
            "a missing image still occupies its place",
            spans<ImageSpan>(sb, 0, 5).isNotEmpty()
        )

        ImageCache.retryBroken()
        assertFalse("opening a document should give it another go",
            ImageCache.isBroken(gone, 800))

        // Forgetting the failure is only half of it: the picture the user has
        // just copied onto the card has to actually appear on the second look.
        writePng(gone, 40, 20)
        ImageCache.request(gone, 800) { }
        waitFor("the retried decode") { ImageCache.peek(gone, 800) != null }
        assertFalse("a file that decoded is not broken", ImageCache.isBroken(gone, 800))
    }

    // --------------------------------------------------------------- paths

    @Test
    fun `image targets resolve against the document folder`() {
        assertEquals(
            File(dir, "pic.png").absolutePath,
            ImageCache.resolve("pic.png", dir)?.absolutePath
        )
        assertEquals(
            File(dir, "sub/pic.png").absolutePath,
            ImageCache.resolve("sub/pic.png", dir)?.absolutePath
        )
        assertEquals("/tmp/abs.png", ImageCache.resolve("/tmp/abs.png", dir)?.absolutePath)
        assertEquals(
            File(dir, "with space.png").absolutePath,
            ImageCache.resolve("with%20space.png", dir)?.absolutePath
        )
    }

    @Test
    fun `a bracketed target may hold spaces and a title`() {
        // ![a](<my photo.png> "caption") is how Markdown writes a name with a
        // space in it, and a name with a space in it is what a scanner produces.
        assertEquals(
            File(dir, "my photo.png").absolutePath,
            ImageCache.resolve("<my photo.png>", dir)?.absolutePath
        )
        assertEquals(
            File(dir, "my photo.png").absolutePath,
            ImageCache.resolve("<my photo.png> \"caption\"", dir)?.absolutePath
        )
        // A bare target ends where its title begins.
        assertEquals(
            File(dir, "photo.png").absolutePath,
            ImageCache.resolve("photo.png \"caption\"", dir)?.absolutePath
        )
    }

    @Test
    fun `a plus in a file name is left alone`() {
        // URL decoding would read "+" as a space and turn a file honestly named
        // "C++.png" into one called "C  .png", which is not on the card.
        assertEquals(
            File(dir, "C++.png").absolutePath,
            ImageCache.resolve("C++.png", dir)?.absolutePath
        )
        // Percent escapes are still decoded, so both spellings find one file.
        assertEquals(
            ImageCache.resolve("C++.png", dir)?.absolutePath,
            ImageCache.resolve("C%2B%2B.png", dir)?.absolutePath
        )
    }

    @Test
    fun `remote and empty targets resolve to nothing`() {
        assertNull(ImageCache.resolve("https://example.com/a.png", dir))
        assertNull(ImageCache.resolve("http://example.com/a.png", dir))
        assertNull(ImageCache.resolve("data:image/png;base64,AAAA", dir))
        assertNull(ImageCache.resolve("", dir))
        assertNull("a relative path needs a folder to resolve against",
            ImageCache.resolve("pic.png", null))
    }
}
