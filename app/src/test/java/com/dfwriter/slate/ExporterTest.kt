package com.dfwriter.slate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HTML side of export. A document is somebody's own writing, but it can
 * also have arrived from a card, a mail attachment or another app, and the
 * page produced here is opened in a browser at file:// origin — where a script
 * can read the rest of the user's files. So the tests that matter most are the
 * ones about what a document must not be able to put on the page.
 */
class ExporterTest {

    private fun html(md: String, title: String = "Doc") = Exporter.toHtml(md, title)

    // ------------------------------------------------------------ the page

    @Test
    fun `the exported page is a whole standalone document`() {
        val page = html("# Title\n\nSome words.\n", "My Notes")
        assertTrue(page, page.startsWith("<!doctype html>"))
        assertTrue(page, page.contains("<meta charset=\"utf-8\">"))
        assertTrue("the page has to stand alone with no stylesheet to fetch",
            page.contains("<style>"))
        assertTrue(page, page.contains("<title>My Notes</title>"))
        assertTrue(page, page.contains("<h1>Title</h1>"))
        assertTrue(page, page.trimEnd().endsWith("</body></html>"))
    }

    @Test
    fun `a document title cannot break out of the title element`() {
        val page = html("hello\n", "Q&A about <script> \"quotes\"")
        assertTrue(page, page.contains("Q&amp;A about &lt;script&gt; &quot;quotes&quot;"))
        assertFalse("a title must not open a tag of its own", page.contains("<script>"))
    }

    // -------------------------------------------------------------- escaping

    @Test
    fun `escaping covers both kinds of quote`() {
        // Alt text and URLs are dropped inside quoted attributes, so a quote
        // that survived would end the attribute and start whatever came next.
        assertEquals(
            "&amp; &lt; &gt; &quot; &#39;",
            Md.escape("& < > \" '")
        )
    }

    @Test
    fun `a quote in image alt text cannot close the attribute`() {
        // Regression: ![x" onload="alert(1)](a.png) once produced a working
        // onload handler on the exported page.
        val page = html("![x\" onload=\"alert(1)](a.png)\n")
        assertTrue(page, page.contains("<img src=\"a.png\""))
        assertTrue(page, page.contains("alt=\"x&quot; onload=&quot;alert(1)\""))
        assertFalse("the alt text closed its own attribute", page.contains("onload=\""))
    }

    @Test
    fun `a single quote in image alt text is no better`() {
        val page = html("![x' onload='alert(1)](a.png)\n")
        assertFalse("the alt text closed its own attribute", page.contains("onload='"))
        assertTrue(page, page.contains("&#39;"))
    }

    // ----------------------------------------------------------------- links

    @Test
    fun `a javascript link is written as words rather than as a link`() {
        val page = html("Press [click me](javascript:alert) now.\n")
        assertTrue("the words must survive", page.contains("click me"))
        assertFalse("javascript must never become an anchor", page.contains("<a href="))
        assertFalse(page, page.contains("javascript:"))
    }

    @Test
    fun `schemes that can execute are refused whatever their spelling`() {
        for (target in listOf("javascript:alert", "JaVaScRiPt:alert", "data:text/html,hi", "vbscript:x")) {
            val page = html("[go]($target)\n")
            assertFalse("$target became a link: $page", page.contains("<a href="))
            assertTrue(page, page.contains("go"))
        }
    }

    @Test
    fun `ordinary links are still links`() {
        assertTrue(html("[site](https://example.com/a)\n")
            .contains("<a href=\"https://example.com/a\">site</a>"))
        assertTrue(html("[mail](mailto:someone@example.com)\n")
            .contains("<a href=\"mailto:someone@example.com\">mail</a>"))
        // A document beside this one, which is the common case on the card.
        assertTrue(html("[next](chapter-two.md)\n")
            .contains("<a href=\"chapter-two.md\">next</a>"))
    }

    @Test
    fun `a colon inside a path is not mistaken for a scheme`() {
        // "sub/a:b.md" has a colon, but it is a file on the card, not a scheme.
        val page = html("[odd](sub/a:b.md)\n")
        assertTrue(page, page.contains("<a href=\"sub/a:b.md\">odd</a>"))
    }
}
