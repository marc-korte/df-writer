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

    @Test
    fun `a real file on disk decodes and is drawn`() {
        val png = File(dir, "real.png")
        val bmp = android.graphics.Bitmap.createBitmap(
            40, 20, android.graphics.Bitmap.Config.ARGB_8888
        )
        png.outputStream().use {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }

        assertNull("nothing may be decoded on the calling thread", ImageCache.peek(png, 1200))
        ImageCache.request(png, 1200) { }

        waitFor("the decode") { ImageCache.peek(png, 1200) != null }
        assertNotNull(ImageCache.peek(png, 1200))
        assertFalse(ImageCache.isBroken(png, 1200))
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
    fun `remote and empty targets resolve to nothing`() {
        assertNull(ImageCache.resolve("https://example.com/a.png", dir))
        assertNull(ImageCache.resolve("http://example.com/a.png", dir))
        assertNull(ImageCache.resolve("data:image/png;base64,AAAA", dir))
        assertNull(ImageCache.resolve("", dir))
        assertNull("a relative path needs a folder to resolve against",
            ImageCache.resolve("pic.png", null))
    }
}
