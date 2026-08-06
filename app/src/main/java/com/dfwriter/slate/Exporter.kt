package com.dfwriter.slate

import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File

/** HTML and PDF output, both produced without any third-party renderer. */
object Exporter {

    // ------------------------------------------------------------------ pdf

    private const val PAGE_W = 595   // A4 at 72 points per inch
    private const val PAGE_H = 842
    private const val MARGIN = 64

    fun toPdf(
        source: String, prefs: Prefs, out: File, title: String, documentDir: File? = null
    ): File {
        val width = PAGE_W - MARGIN * 2
        // Pictures are resolved against the document's own folder, as they are in
        // the editor; without the folder and the column width the styler leaves
        // the raw ![alt](path) on the page.
        val dir = documentDir
            ?: prefs.lastFile.takeIf { it.isNotEmpty() }?.let { File(it).parentFile }
        // This runs on the export thread. The layout is built once, so every
        // bitmap has to be in hand before it is.
        ImageCache.warm(source, dir, width)

        val styler = MarkdownStyler(prefs).apply {
            overrideBodyPx = 11f
            forceHideMarkers = true
            contentWidthPx = width
            this.documentDir = dir
        }
        val sb = SpannableStringBuilder(source)
        styler.restyleAll(sb, -1)

        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = when (prefs.typeface) {
                SerifChoice.SERIF -> Typeface.SERIF
                SerifChoice.SANS -> Typeface.SANS_SERIF
                SerifChoice.MONO -> Typeface.MONOSPACE
            }
            textSize = 11f
            color = Ink.TEXT
        }

        val usable = PAGE_H - MARGIN * 2
        val layout = StaticLayout.Builder
            .obtain(sb, 0, sb.length, paint, width)
            .setLineSpacing(0f, 1.42f)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()

        val doc = PdfDocument()
        var line = 0
        var page = 1
        while (line < layout.lineCount) {
            // Fill the page with whole lines; never split one across a break.
            val topY = layout.getLineTop(line)
            var last = line
            while (last + 1 < layout.lineCount &&
                layout.getLineBottom(last + 1) - topY <= usable
            ) last++

            val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, page).create()
            val pdfPage = doc.startPage(info)
            val c = pdfPage.canvas
            c.save()
            c.translate(MARGIN.toFloat(), (MARGIN - topY).toFloat())
            c.clipRect(
                0, topY, width, topY + usable
            )
            layout.draw(c)
            c.restore()

            val footer = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.SANS_SERIF
                textSize = 8f
                color = Ink.RULE
            }
            c.drawText(title, MARGIN.toFloat(), (PAGE_H - MARGIN / 2).toFloat(), footer)
            footer.textAlign = android.graphics.Paint.Align.RIGHT
            c.drawText(
                page.toString(), (PAGE_W - MARGIN).toFloat(),
                (PAGE_H - MARGIN / 2).toFloat(), footer
            )

            doc.finishPage(pdfPage)

            line = last + 1
            page++
            if (page > 2000) break
        }

        try {
            out.parentFile?.mkdirs()
            out.outputStream().use { doc.writeTo(it) }
        } finally {
            // close() must run even if the write fails, or the pages stay held.
            doc.close()
        }
        return out
    }

    // ----------------------------------------------------------------- html

    fun toHtml(source: String, title: String): String = buildString {
        append("<!doctype html>\n<html><head><meta charset=\"utf-8\">\n")
        append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n")
        append("<title>").append(esc(title)).append("</title>\n<style>\n")
        append(CSS)
        append("</style></head>\n<body>\n")
        append(Md.render(source))
        append("\n</body></html>\n")
    }

    private fun esc(s: String) = Md.escape(s)

    private val CSS = """
        :root { color-scheme: light dark; }
        body { max-width: 40em; margin: 3rem auto; padding: 0 1.5rem;
               font-family: Georgia, 'Times New Roman', serif; font-size: 18px;
               line-height: 1.62; color: #111; background: #fff; }
        @media (prefers-color-scheme: dark) {
            body { color: #e8e8e8; background: #111; }
            code, pre { background: #1e1e1e; }
            blockquote { border-color: #555; color: #bbb; }
        }
        h1,h2,h3,h4,h5,h6 { line-height: 1.25; margin: 2.2rem 0 .8rem; }
        h1 { font-size: 1.9em; } h2 { font-size: 1.5em; } h3 { font-size: 1.25em; }
        p { margin: 0 0 1.1rem; }
        code { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: .9em;
               background: #f0f0f0; padding: .1em .32em; border-radius: 3px; }
        pre { background: #f0f0f0; padding: 1rem 1.1rem; overflow-x: auto;
              border-left: 3px solid #111; border-radius: 3px; }
        pre code { background: none; padding: 0; }
        blockquote { margin: 0 0 1.1rem; padding-left: 1.1rem;
                     border-left: 3px solid #999; color: #555; }
        hr { border: none; border-top: 1px solid #999; margin: 2rem 0; }
        table { border-collapse: collapse; margin: 0 0 1.2rem; width: 100%; }
        th, td { border: 1px solid #bbb; padding: .45em .7em; text-align: left; }
        img { max-width: 100%; }
        ul, ol { padding-left: 1.6rem; margin: 0 0 1.1rem; }
        li { margin: .25rem 0; }
        li.task { list-style: none; margin-left: -1.2rem; }
    """.trimIndent()
}

/** A small, dependency-free Markdown to HTML renderer. */
object Md {

    fun render(src: String): String {
        val out = StringBuilder()
        val lines = src.replace("\r\n", "\n").split("\n")
        var i = 0
        val listStack = ArrayList<String>()   // "ul" / "ol"
        val listIndent = ArrayList<Int>()

        fun closeLists(toIndent: Int = -1) {
            while (listStack.isNotEmpty() && (toIndent < 0 || listIndent.last() > toIndent)) {
                out.append("</").append(listStack.removeAt(listStack.size - 1)).append(">\n")
                listIndent.removeAt(listIndent.size - 1)
            }
        }

        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trimEnd()

            // fenced code
            val fence = Regex("^\\s{0,3}(`{3,}|~{3,})\\s*([A-Za-z0-9+#._-]*)$").find(line)
            if (fence != null) {
                closeLists()
                val fenceChar = fence.groupValues[1].first()
                val lang = fence.groupValues[2]
                val body = StringBuilder()
                i++
                while (i < lines.size && !closesFence(lines[i], fenceChar)) {
                    body.append(escape(lines[i])).append('\n')
                    i++
                }
                i++
                val cls = if (lang.isNotEmpty()) " class=\"language-$lang\"" else ""
                out.append("<pre><code$cls>").append(body).append("</code></pre>\n")
                continue
            }

            if (line.isBlank()) { closeLists(); i++; continue }

            // table
            if (line.contains('|') && i + 1 < lines.size &&
                Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
                    .matches(lines[i + 1].trimEnd())
            ) {
                closeLists()
                out.append("<table>\n<thead><tr>")
                for (c in cells(line)) out.append("<th>").append(inline(c)).append("</th>")
                out.append("</tr></thead>\n<tbody>\n")
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    out.append("<tr>")
                    for (c in cells(lines[i])) out.append("<td>").append(inline(c)).append("</td>")
                    out.append("</tr>\n")
                    i++
                }
                out.append("</tbody></table>\n")
                continue
            }

            // hr
            if (Regex("^\\s{0,3}([-*_])\\s*(\\1\\s*){2,}$").matches(line)) {
                closeLists(); out.append("<hr>\n"); i++; continue
            }

            // heading
            val h = Regex("^(#{1,6})\\s+(.*)$").find(line)
            if (h != null) {
                closeLists()
                val lvl = h.groupValues[1].length
                out.append("<h$lvl>").append(inline(h.groupValues[2].trim()))
                    .append("</h$lvl>\n")
                i++; continue
            }

            // blockquote
            if (Regex("^\\s{0,3}>").containsMatchIn(line)) {
                closeLists()
                val body = StringBuilder()
                while (i < lines.size && Regex("^\\s{0,3}>").containsMatchIn(lines[i])) {
                    body.append(lines[i].replaceFirst(Regex("^\\s{0,3}>\\s?"), "")).append('\n')
                    i++
                }
                out.append("<blockquote>\n").append(render(body.toString()))
                    .append("</blockquote>\n")
                continue
            }

            // list item
            val li = Regex("^([ \\t]*)([-*+]|\\d{1,9}[.)])[ \\t]+(.*)$").find(raw)
            if (li != null) {
                val indent = li.groupValues[1].replace("\t", "  ").length
                val ordered = li.groupValues[2].first().isDigit()
                val kind = if (ordered) "ol" else "ul"
                closeLists(indent)
                if (listStack.isEmpty() || listIndent.last() < indent) {
                    out.append("<$kind>\n"); listStack.add(kind); listIndent.add(indent)
                } else if (listStack.last() != kind) {
                    out.append("</").append(listStack.removeAt(listStack.size - 1)).append(">\n")
                    listIndent.removeAt(listIndent.size - 1)
                    out.append("<$kind>\n"); listStack.add(kind); listIndent.add(indent)
                }
                var body = li.groupValues[3]
                val task = Regex("^\\[([ xX])\\]\\s+(.*)$").find(body)
                if (task != null) {
                    val done = task.groupValues[1].lowercase() == "x"
                    out.append("<li class=\"task\">")
                        .append(if (done) "☑ " else "☐ ")
                        .append(inline(task.groupValues[2]))
                        .append("</li>\n")
                } else {
                    out.append("<li>").append(inline(body)).append("</li>\n")
                }
                i++; continue
            }

            // paragraph
            closeLists()
            val para = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank() &&
                Regex("^(#{1,6}\\s|\\s{0,3}>|\\s{0,3}(`{3,}|~{3,}))").find(lines[i]) == null &&
                Regex("^([ \\t]*)([-*+]|\\d{1,9}[.)])[ \\t]+").find(lines[i]) == null
            ) {
                if (para.isNotEmpty()) para.append('\n')
                para.append(lines[i].trimEnd())
                i++
            }
            if (para.isNotEmpty()) {
                out.append("<p>").append(inline(para.toString()).replace("\n", "<br>\n"))
                    .append("</p>\n")
            }
        }
        closeLists()
        return out.toString()
    }

    private fun closesFence(line: String, fenceChar: Char): Boolean {
        val t = line.trim()
        return t.length >= 3 && t.all { it == fenceChar }
    }

    private fun cells(row: String): List<String> =
        row.trim().trim('|').split('|').map { it.trim() }

    /**
     * Everything that reaches the page goes through here, attribute values
     * included. The quotes matter: alt text and URLs are dropped inside quoted
     * attributes below, so a bare one would let a document close the attribute
     * and write its own onload= handler, which then runs at file:// origin.
     */
    fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    /**
     * Only schemes that cannot execute. Anything relative is a document beside
     * this one and is left alone; javascript: is not written as a link at all.
     */
    private fun safeHref(url: String): Boolean {
        val colon = url.indexOf(':')
        if (colon < 0) return true
        // A colon after the first slash, query or fragment is part of a path,
        // not a scheme.
        val mark = url.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (mark in 0 until colon) return true
        return when (url.substring(0, colon).lowercase()) {
            "http", "https", "mailto" -> true
            else -> false
        }
    }

    private fun inline(sIn: String): String {
        // Protect code spans from every other rule, exactly as Markdown requires.
        val codes = ArrayList<String>()
        // The content may itself contain backticks, as in ``a ` b``, so it must
        // not be [^`]: that stops at the inner backtick and mis-pairs the fence.
        var s = Regex("(`+)(.+?)\\1(?!`)").replace(sIn) { m ->
            codes.add("<code>" + escape(m.groupValues[2]) + "</code>")
            "\u0000${codes.size - 1}\u0000"
        }
        s = escape(s)
        s = Regex("!\\[([^\\]]*)]\\(([^)\\s]+)(?:\\s+&quot;([^&]*)&quot;)?\\)")
            .replace(s) { m -> "<img src=\"${m.groupValues[2]}\" alt=\"${m.groupValues[1]}\">" }
        s = Regex("\\[([^\\]]*)]\\(([^)\\s]+)\\)")
            .replace(s) { m ->
                // An unusable scheme leaves the words behind and the link out.
                if (safeHref(m.groupValues[2])) {
                    "<a href=\"${m.groupValues[2]}\">${m.groupValues[1]}</a>"
                } else m.groupValues[1]
            }
        s = Regex("\\*{3}(?!\\s)(.+?)(?<!\\s)\\*{3}").replace(s) { "<strong><em>${it.groupValues[1]}</em></strong>" }
        s = Regex("\\*{2}(?!\\s)(.+?)(?<!\\s)\\*{2}").replace(s) { "<strong>${it.groupValues[1]}</strong>" }
        s = Regex("(?<![A-Za-z0-9_])_{2}(?!\\s)(.+?)(?<!\\s)_{2}(?![A-Za-z0-9_])").replace(s) { "<strong>${it.groupValues[1]}</strong>" }
        s = Regex("~{2}(?!\\s)(.+?)(?<!\\s)~{2}").replace(s) { "<del>${it.groupValues[1]}</del>" }
        s = Regex("={2}(?!\\s)(.+?)(?<!\\s)={2}").replace(s) { "<mark>${it.groupValues[1]}</mark>" }
        s = Regex("\\*(?!\\s)([^*]+?)(?<!\\s)\\*").replace(s) { "<em>${it.groupValues[1]}</em>" }
        s = Regex("(?<![A-Za-z0-9_])_(?!\\s)([^_]+?)(?<!\\s)_(?![A-Za-z0-9_])").replace(s) { "<em>${it.groupValues[1]}</em>" }
        for ((idx, code) in codes.withIndex()) s = s.replace("\u0000$idx\u0000", code)
        return s
    }
}
