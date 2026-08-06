package com.dfwriter.slate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the parts that need no Android runtime at all. */
class PureLogicTest {

    // ------------------------------------------------------------ density

    @Test
    fun `manta reporting its true density is left alone`() {
        // 10.7 inch panel, 1920x2560 at a correctly reported 300 PPI.
        assertEquals(300f, Scale.chooseDpi(300f, 300, 1920), 1f)
    }

    @Test
    fun `manta reporting a false low density is corrected upward`() {
        // This is the failure this app exists to avoid: the ROM claims 160,
        // which would draw everything at roughly half its intended size.
        val dpi = Scale.chooseDpi(160f, 160, 1920)
        assertEquals(300f, dpi, 1f)
        assertTrue("density must not be taken at face value", dpi > 160f)
    }

    @Test
    fun `nomad reporting a false low density is also corrected upward`() {
        // The Nomad is the smaller 7.8 inch sibling: 1404x1872, also 300 PPI.
        // A floor derived from an assumed screen width would under-size it.
        assertEquals(300f, Scale.chooseDpi(160f, 160, 1404), 1f)
        assertEquals(300f, Scale.chooseDpi(300f, 300, 1404), 1f)
    }

    @Test
    fun `body text lands at a readable physical size on a lying nomad`() {
        val px = 13.5f / 72f * Scale.chooseDpi(160f, 160, 1404)
        assertTrue("expected readable body height, got $px px", px in 45f..70f)
    }

    @Test
    fun `nonsense metrics still yield a usable density`() {
        assertEquals(300f, Scale.chooseDpi(Float.NaN, 0, 1920), 1f)
        assertTrue(Scale.chooseDpi(0f, 0, 0) >= 120f)
    }

    @Test
    fun `a high density phone is not inflated by the panel floor`() {
        // 1080x1920 at 440 dpi: the resolution floor is only 169, so the real
        // reported density must win or a phone would render absurdly large.
        assertEquals(440f, Scale.chooseDpi(440f, 440, 1080), 1f)
    }

    @Test
    fun `body text lands at a readable physical size on a lying manta`() {
        val dpi = Scale.chooseDpi(160f, 160, 1920)
        // 13.5 pt at 300 dpi is about 56 px tall on a 1920 px short edge,
        // which is roughly 34 lines of text down the short side of the panel.
        val px = 13.5f / 72f * dpi
        assertTrue("expected readable body height, got $px px", px in 45f..70f)
    }

    // ------------------------------------------------------------- outline

    @Test
    fun `outline lists headings and ignores those inside code fences`() {
        val doc = """
            # Title

            Some text.

            ## Section one

            ```
            # not a heading, this is shell
            ```

            ### Deep
        """.trimIndent()

        val heads = MarkdownStyler.outline(doc)
        assertEquals(listOf("Title", "Section one", "Deep"), heads.map { it.title })
        assertEquals(listOf(1, 2, 3), heads.map { it.level })
        assertEquals('#', doc[heads[1].offset])
    }

    // ------------------------------------------------------- line geometry

    @Test
    fun `line and paragraph boundaries`() {
        val t = "alpha\nbeta\n\ngamma\ndelta"
        assertEquals(0, MarkdownStyler.lineStartOf(t, 3))
        assertEquals(5, MarkdownStyler.lineEndOf(t, 3))
        assertEquals(6, MarkdownStyler.lineStartOf(t, 8))

        // The caret sits in "gamma"; its paragraph is "gamma\ndelta".
        val at = t.indexOf("gamma") + 1
        assertEquals(t.indexOf("gamma"), MarkdownStyler.paragraphStart(t, at))
        assertEquals(t.length, MarkdownStyler.paragraphEnd(t, at))
    }

    // --------------------------------------------------------- file naming

    @Test
    fun `file names get an extension and a safe slug`() {
        assertEquals("notes.md", DocStore.ensureExt("notes"))
        assertEquals("notes.txt", DocStore.ensureExt("notes.txt"))
        assertEquals("My-Great-Idea", DocStore.slug("My Great Idea!"))
        assertEquals("untitled", DocStore.slug("!!!"))
    }

    @Test
    fun `word count treats hyphenated and apostrophed words as one`() {
        assertEquals(0, DocStore.countWords(""))
        assertEquals(3, DocStore.countWords("one two three"))
        assertEquals(1, DocStore.countWords("well-known"))
        assertEquals(2, DocStore.countWords("it's fine."))
    }

    // ------------------------------------------------------- html renderer

    @Test
    fun `headings paragraphs and inline emphasis render`() {
        val html = Md.render("# Title\n\nSome **bold** and *italic* text.\n")
        assertTrue(html, html.contains("<h1>Title</h1>"))
        assertTrue(html, html.contains("<strong>bold</strong>"))
        assertTrue(html, html.contains("<em>italic</em>"))
    }

    @Test
    fun `code spans are protected from emphasis rules`() {
        val html = Md.render("Use `a * b * c` carefully.\n")
        assertTrue(html, html.contains("<code>a * b * c</code>"))
        assertTrue("emphasis must not leak into code", !html.contains("<em>"))
    }

    @Test
    fun `a code span may itself contain backticks`() {
        // Doubled fences exist precisely so code can contain a backtick. Content
        // matched as [^`] stops at the inner one and mis-pairs the fence.
        val html = Md.render("Use ``a ` b`` in code.\n")
        assertTrue(html, html.contains("<code>a ` b</code>"))
    }

    @Test
    fun `adjacent code spans on one line stay separate`() {
        val html = Md.render("`one` and `two`\n")
        assertTrue(html, html.contains("<code>one</code>"))
        assertTrue(html, html.contains("<code>two</code>"))
        assertTrue("the gap must not be swallowed", html.contains("and"))
    }

    @Test
    fun `prose containing a placeholder shaped string is not corrupted`() {
        // The renderer parks code spans behind sentinels while it works; text
        // that happens to look like one must survive untouched.
        val html = Md.render("A count of 0 and `real code` here.\n")
        assertTrue(html, html.contains("A count of 0 and"))
        assertTrue(html, html.contains("<code>real code</code>"))
    }

    @Test
    fun `fenced code is escaped and not treated as markdown`() {
        val html = Md.render("```kotlin\nval x = a < b && c > d\n# nope\n```\n")
        assertTrue(html, html.contains("<pre><code class=\"language-kotlin\">"))
        assertTrue(html, html.contains("a &lt; b &amp;&amp; c &gt; d"))
        assertTrue("a hash inside a fence is not a heading", !html.contains("<h1>"))
    }

    @Test
    fun `lists blockquotes rules and tables render`() {
        assertTrue(Md.render("- one\n- two\n").contains("<ul>"))
        assertTrue(Md.render("1. one\n2. two\n").contains("<ol>"))
        assertTrue(Md.render("- [x] done\n").contains("☑"))
        assertTrue(Md.render("> quoted\n").contains("<blockquote>"))
        assertTrue(Md.render("---\n").contains("<hr>"))

        val table = Md.render("| A | B |\n| --- | --- |\n| 1 | 2 |\n")
        assertTrue(table, table.contains("<th>A</th>"))
        assertTrue(table, table.contains("<td>2</td>"))
    }

    @Test
    fun `html special characters in prose are escaped`() {
        val html = Md.render("5 < 6 & 7 > 2\n")
        assertTrue(html, html.contains("5 &lt; 6 &amp; 7 &gt; 2"))
    }

    @Test
    fun `a long document renders without stalling`() {
        val doc = buildString {
            repeat(400) {
                append("## Section $it\n\nBody **text** with `code` and a [link](http://x).\n\n")
                append("- bullet one\n- bullet two\n\n")
            }
        }
        val started = System.nanoTime()
        val html = Md.render(doc)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(html.contains("<h2>Section 399</h2>"))
        assertTrue("render took ${ms}ms", ms < 4000)
    }
}
