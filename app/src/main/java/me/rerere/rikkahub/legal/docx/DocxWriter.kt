package me.rerere.rikkahub.legal.docx

import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal, dependency-free OOXML (.docx) writer for court-style drafts.
 *
 * Input is "legal markdown":
 *  - `# H1` centered bold (section titles: ARGUMENT, PRAYER FOR RELIEF), `## H2` bold, `### H3` bold italic
 *  - `> quote` → indented single-spaced block quotation
 *  - `- item` / `* item` bullets, `1. item` numbered (hanging indent)
 *  - pipe tables `| a | b |` (header row bold; separator row `|---|` ignored)
 *  - inline `**bold**`, `*italic*` or `_italic_` (case names), `<u>underline</u>`
 *  - footnotes: `text[^1]` with a later line `[^1]: footnote text` → real Word footnotes
 *  - `[[pagebreak]]` on its own line; `[[center]] text` centers a line; `[[right]] text` right-aligns
 *  - blank line = paragraph break
 */
class DocxWriter(private val options: Options = Options()) {

    data class Caption(
        val court: String = "",
        val leftLines: List<String> = emptyList(),
        val rightLines: List<String> = emptyList(),
        val title: String = "",
    )

    data class Options(
        val font: String = "Times New Roman",
        val sizePt: Int = 12,
        /** 1.0 single, 1.5, 2.0 double. Block quotes, footnotes, tables are always single. */
        val lineSpacing: Double = 2.0,
        val firstLineIndent: Boolean = true,
        val headerText: String? = DRAFT_BANNER,
        val pageNumbers: Boolean = true,
        val title: String = "Draft",
        val author: String = "RikkaLaw",
        val caption: Caption? = null,
    )

    companion object {
        const val DRAFT_BANNER = "DRAFT — PREPARED FOR COUNSEL REVIEW — NOT LEGAL ADVICE"
        private const val TWIP_INCH = 1440
        fun esc(s: String): String = buildString(s.length) {
            for (c in s) when (c) {
                '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;")
                else -> if (c.code < 0x20 && c != '\t' && c != '\n' && c != '\r') Unit else append(c)
            }
        }
    }

    // ---------------------------------------------------------------- model

    private sealed class Block {
        data class Para(val text: String, val style: PStyle, val align: String? = null) : Block()
        data class Table(val rows: List<List<String>>, val header: Boolean) : Block()
        data object PageBreak : Block()
    }

    private enum class PStyle { BODY, H1, H2, H3, QUOTE, BULLET, NUMBER, PLAIN }

    private val footnotes = LinkedHashMap<String, Int>()
    private val footnoteText = HashMap<String, String>()

    fun write(markdown: String, out: OutputStream) {
        val blocks = parse(markdown)
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            val document = documentXml(blocks) // populates footnotes
            put("[Content_Types].xml", contentTypes())
            put("_rels/.rels", rootRels())
            put("docProps/core.xml", coreProps())
            put("word/_rels/document.xml.rels", docRels())
            put("word/document.xml", document)
            put("word/styles.xml", stylesXml())
            put("word/settings.xml", settingsXml())
            put("word/footnotes.xml", footnotesXml())
            options.headerText?.let { put("word/header1.xml", headerXml(it)) }
            if (options.pageNumbers) put("word/footer1.xml", footerXml())
        }
    }

    private fun parse(md: String): List<Block> {
        val lines = md.replace("\r\n", "\n").split('\n')
        // Pull footnote definitions out first.
        val body = ArrayList<String>()
        val fnDef = Regex("^\\[\\^([^\\]]+)]:\\s*(.*)$")
        for (l in lines) {
            val m = fnDef.find(l.trim())
            if (m != null) footnoteText[m.groupValues[1]] = m.groupValues[2] else body += l
        }
        val blocks = ArrayList<Block>()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) blocks += Block.Para(para.toString().trim(), PStyle.BODY)
            para.setLength(0)
        }
        var i = 0
        while (i < body.size) {
            val raw = body[i]
            val t = raw.trim()
            when {
                t.isEmpty() -> flush()
                t == "[[pagebreak]]" -> { flush(); blocks += Block.PageBreak }
                t.startsWith("### ") -> { flush(); blocks += Block.Para(t.removePrefix("### "), PStyle.H3) }
                t.startsWith("## ") -> { flush(); blocks += Block.Para(t.removePrefix("## "), PStyle.H2) }
                t.startsWith("# ") -> { flush(); blocks += Block.Para(t.removePrefix("# "), PStyle.H1, "center") }
                t.startsWith("[[center]]") -> { flush(); blocks += Block.Para(t.removePrefix("[[center]]").trim(), PStyle.PLAIN, "center") }
                t.startsWith("[[right]]") -> { flush(); blocks += Block.Para(t.removePrefix("[[right]]").trim(), PStyle.PLAIN, "right") }
                t.startsWith(">") -> {
                    flush()
                    val q = StringBuilder()
                    while (i < body.size && body[i].trim().startsWith(">")) {
                        q.append(body[i].trim().removePrefix(">").trim()).append(' '); i++
                    }
                    blocks += Block.Para(q.toString().trim(), PStyle.QUOTE)
                    continue
                }
                Regex("^[-*•]\\s+").containsMatchIn(t) && !t.startsWith("**") -> {
                    flush(); blocks += Block.Para("•\t" + t.replaceFirst(Regex("^[-*•]\\s+"), ""), PStyle.BULLET)
                }
                Regex("^(\\d+|[a-z]|[A-H]|[ivx]+|[IVX]+)[.)]\\s+").containsMatchIn(t) && t.length < 2000 && para.isEmpty() -> {
                    val m = Regex("^((\\d+|[a-z]|[A-H]|[ivx]+|[IVX]+)[.)])\\s+").find(t)!!
                    flush(); blocks += Block.Para(m.groupValues[1] + "\t" + t.substring(m.range.last + 1), PStyle.NUMBER)
                }
                t.startsWith("|") -> {
                    flush()
                    val rows = ArrayList<List<String>>()
                    var header = false
                    while (i < body.size && body[i].trim().startsWith("|")) {
                        val row = body[i].trim().trim('|').split('|').map { it.trim() }
                        if (row.all { it.matches(Regex(":?-{2,}:?")) }) header = rows.size == 1 else rows += row
                        i++
                    }
                    blocks += Block.Table(rows, header)
                    continue
                }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
            }
            i++
        }
        flush()
        return blocks
    }

    // ---------------------------------------------------------------- runs

    private data class Fmt(val b: Boolean = false, val i: Boolean = false, val u: Boolean = false)

    /** Inline markdown → `<w:r>` runs (with footnote references). */
    private fun runs(text: String, base: Fmt = Fmt(), size: Int? = null): String {
        val sb = StringBuilder()
        var fmt = base
        val buf = StringBuilder()
        fun emit() {
            if (buf.isEmpty()) return
            sb.append(run(buf.toString(), fmt, size)); buf.setLength(0)
        }
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> { emit(); fmt = fmt.copy(b = !fmt.b); i += 2 }
                text.startsWith("<u>", i) -> { emit(); fmt = fmt.copy(u = true); i += 3 }
                text.startsWith("</u>", i) -> { emit(); fmt = fmt.copy(u = false); i += 4 }
                text.startsWith("[^", i) && text.indexOf(']', i) > i -> {
                    val end = text.indexOf(']', i)
                    val key = text.substring(i + 2, end)
                    if (footnoteText.containsKey(key)) {
                        emit()
                        val id = footnotes.getOrPut(key) { footnotes.size + 1 }
                        sb.append("<w:r><w:rPr><w:rStyle w:val=\"FootnoteReference\"/></w:rPr><w:footnoteReference w:id=\"$id\"/></w:r>")
                        i = end + 1
                    } else { buf.append(text[i]); i++ }
                }
                (text[i] == '*' || text[i] == '_') && isEmphasisToggle(text, i) -> { emit(); fmt = fmt.copy(i = !fmt.i); i++ }
                text[i] == '\t' -> { emit(); sb.append("<w:r><w:tab/></w:r>"); i++ }
                else -> { buf.append(text[i]); i++ }
            }
        }
        emit()
        return sb.toString()
    }

    /** `*` / `_` toggles italics only at word edges, so `5*3`, `snake_case`, `§ 2254(d)(1)` stay literal. */
    private fun isEmphasisToggle(t: String, i: Int): Boolean {
        val prev = if (i > 0) t[i - 1] else ' '
        val next = if (i + 1 < t.length) t[i + 1] else ' '
        val opening = !prev.isLetterOrDigit() && !next.isWhitespace() && next != t[i]
        val closing = !prev.isWhitespace() && !next.isLetterOrDigit() && prev != t[i]
        if (!opening && !closing) return false
        // require a matching partner later in the string for an opener
        return if (opening) t.indexOf(t[i], i + 1) > i + 1 else true
    }

    private fun run(text: String, f: Fmt, size: Int?): String {
        val pr = buildString {
            if (f.b) append("<w:b/>")
            if (f.i) append("<w:i/>")
            if (f.u) append("<w:u w:val=\"single\"/>")
            if (size != null) append("<w:sz w:val=\"${size * 2}\"/><w:szCs w:val=\"${size * 2}\"/>")
        }
        return "<w:r>${if (pr.isNotEmpty()) "<w:rPr>$pr</w:rPr>" else ""}<w:t xml:space=\"preserve\">${esc(text)}</w:t></w:r>"
    }

    // ---------------------------------------------------------------- document

    private val line: Int get() = (240 * options.lineSpacing).toInt()

    private fun pPr(style: PStyle, align: String?): String {
        val spacing = when (style) {
            PStyle.QUOTE, PStyle.PLAIN -> "<w:spacing w:before=\"0\" w:after=\"240\" w:line=\"240\" w:lineRule=\"auto\"/>"
            PStyle.H1, PStyle.H2, PStyle.H3 -> "<w:spacing w:before=\"240\" w:after=\"240\" w:line=\"240\" w:lineRule=\"auto\"/><w:keepNext/>"
            else -> "<w:spacing w:before=\"0\" w:after=\"${if (options.lineSpacing >= 2.0) 0 else 200}\" w:line=\"$line\" w:lineRule=\"auto\"/>"
        }
        val ind = when (style) {
            PStyle.QUOTE -> "<w:ind w:left=\"$TWIP_INCH\" w:right=\"$TWIP_INCH\"/>"
            PStyle.BULLET, PStyle.NUMBER -> "<w:ind w:left=\"${TWIP_INCH}\" w:hanging=\"360\"/>"
            PStyle.BODY -> if (options.firstLineIndent) "<w:ind w:firstLine=\"${TWIP_INCH / 2}\"/>" else ""
            else -> ""
        }
        val jc = (align ?: if (style == PStyle.QUOTE || style == PStyle.BODY) "both" else null)?.let { "<w:jc w:val=\"$it\"/>" }.orEmpty()
        val tabs = if (style == PStyle.BULLET || style == PStyle.NUMBER) "<w:tabs><w:tab w:val=\"left\" w:pos=\"$TWIP_INCH\"/></w:tabs>" else ""
        return "<w:pPr>$tabs$spacing$ind$jc</w:pPr>"
    }

    private fun para(b: Block.Para): String {
        val base = when (b.style) {
            PStyle.H1, PStyle.H2 -> Fmt(b = true)
            PStyle.H3 -> Fmt(b = true, i = true)
            else -> Fmt()
        }
        return "<w:p>${pPr(b.style, b.align)}${runs(b.text, base)}</w:p>"
    }

    private fun table(t: Block.Table): String {
        if (t.rows.isEmpty()) return ""
        val cols = t.rows.maxOf { it.size }
        val w = (6.5 * TWIP_INCH / cols).toInt()
        val sb = StringBuilder("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblBorders>")
        listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach { sb.append("<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/>") }
        sb.append("</w:tblBorders><w:tblCellMar><w:left w:w=\"80\" w:type=\"dxa\"/><w:right w:w=\"80\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid>")
        repeat(cols) { sb.append("<w:gridCol w:w=\"$w\"/>") }
        sb.append("</w:tblGrid>")
        t.rows.forEachIndexed { r, row ->
            sb.append("<w:tr>")
            if (r == 0 && t.header) sb.append("<w:trPr><w:tblHeader/></w:trPr>")
            for (c in 0 until cols) {
                val cell = row.getOrElse(c) { "" }
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"$w\" w:type=\"dxa\"/></w:tcPr><w:p><w:pPr><w:spacing w:before=\"40\" w:after=\"40\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr>")
                sb.append(runs(cell, Fmt(b = r == 0 && t.header), size = (options.sizePt - 1).coerceAtLeast(9)))
                sb.append("</w:p></w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        return sb.toString()
    }

    private fun captionXml(c: Caption): String {
        val sb = StringBuilder()
        if (c.court.isNotBlank()) {
            c.court.split('\n').forEach { l ->
                sb.append("<w:p><w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/><w:jc w:val=\"center\"/></w:pPr>${runs(l.trim().uppercase(), Fmt(b = true))}</w:p>")
            }
            sb.append("<w:p/>")
        }
        if (c.leftLines.isNotEmpty() || c.rightLines.isNotEmpty()) {
            val half = (3.25 * TWIP_INCH).toInt()
            sb.append("<w:tbl><w:tblPr><w:tblW w:w=\"${half * 2}\" w:type=\"dxa\"/><w:tblBorders>")
            listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach { sb.append("<w:$it w:val=\"nil\"/>") }
            sb.append("</w:tblBorders></w:tblPr><w:tblGrid><w:gridCol w:w=\"$half\"/><w:gridCol w:w=\"$half\"/></w:tblGrid><w:tr>")
            fun cell(lines: List<String>, rightBorder: Boolean) {
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"$half\" w:type=\"dxa\"/>")
                if (rightBorder) sb.append("<w:tcBorders><w:right w:val=\"single\" w:sz=\"8\" w:space=\"0\" w:color=\"000000\"/><w:bottom w:val=\"single\" w:sz=\"8\" w:space=\"0\" w:color=\"000000\"/></w:tcBorders>")
                sb.append("<w:vAlign w:val=\"center\"/></w:tcPr>")
                (lines.ifEmpty { listOf("") }).forEach { l ->
                    sb.append("<w:p><w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/>${if (!rightBorder) "<w:ind w:left=\"360\"/>" else ""}</w:pPr>${runs(l)}</w:p>")
                }
                sb.append("</w:tc>")
            }
            cell(c.leftLines, true)
            cell(c.rightLines, false)
            sb.append("</w:tr></w:tbl><w:p/>")
        }
        if (c.title.isNotBlank()) {
            c.title.split('\n').forEach { l ->
                sb.append("<w:p><w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/><w:jc w:val=\"center\"/></w:pPr>${runs(l.trim().uppercase(), Fmt(b = true))}</w:p>")
            }
            sb.append("<w:p/>")
        }
        return sb.toString()
    }

    private fun documentXml(blocks: List<Block>): String {
        val body = StringBuilder()
        options.caption?.let { body.append(captionXml(it)) }
        var breakBefore = false
        blocks.forEachIndexed { idx, b ->
            when (b) {
                is Block.Para -> {
                    var xml = para(b)
                    if (breakBefore) { xml = xml.replaceFirst("<w:pPr>", "<w:pPr><w:pageBreakBefore/>"); breakBefore = false }
                    body.append(xml)
                }
                is Block.Table -> {
                    if (breakBefore) { body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>"); breakBefore = false }
                    body.append(table(b))
                    // Word merges adjacent tables and requires a paragraph before </w:body>.
                    val next = blocks.getOrNull(idx + 1)
                    if (next == null || next is Block.Table) body.append("<w:p/>")
                    else if (next is Block.Para) body.append("<w:p><w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:p>")
                }
                Block.PageBreak -> breakBefore = true
            }
        }
        if (breakBefore) body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
        val sect = buildString {
            append("<w:sectPr>")
            if (options.headerText != null) append("<w:headerReference w:type=\"default\" r:id=\"rIdHeader1\"/>")
            if (options.pageNumbers) append("<w:footerReference w:type=\"default\" r:id=\"rIdFooter1\"/>")
            append("<w:footnotePr><w:numFmt w:val=\"decimal\"/></w:footnotePr>")
            append("<w:pgSz w:w=\"12240\" w:h=\"15840\"/>")
            append("<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/>")
            append("</w:sectPr>")
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
            "<w:body>$body$sect</w:body></w:document>"
    }

    private fun footnotesXml(): String {
        val sb = StringBuilder(
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:footnotes xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
                """<w:footnote w:type="separator" w:id="-1"><w:p><w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr><w:r><w:separator/></w:r></w:p></w:footnote>""" +
                """<w:footnote w:type="continuationSeparator" w:id="0"><w:p><w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr><w:r><w:continuationSeparator/></w:r></w:p></w:footnote>"""
        )
        footnotes.forEach { (key, id) ->
            // Footnote text may itself contain inline formatting but not nested footnotes.
            val text = footnoteText[key].orEmpty().replace(Regex("\\[\\^[^\\]]+]"), "")
            sb.append("<w:footnote w:id=\"$id\"><w:p><w:pPr><w:pStyle w:val=\"FootnoteText\"/></w:pPr>")
            sb.append("<w:r><w:rPr><w:rStyle w:val=\"FootnoteReference\"/></w:rPr><w:footnoteRef/></w:r><w:r><w:t xml:space=\"preserve\"> </w:t></w:r>")
            sb.append(runs(text, size = (options.sizePt - 2).coerceAtLeast(9)))
            sb.append("</w:p></w:footnote>")
        }
        sb.append("</w:footnotes>")
        return sb.toString()
    }

    private fun headerXml(text: String) =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
            """<w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val="18"/></w:rPr><w:t xml:space="preserve">${esc(text)}</w:t></w:r></w:p></w:hdr>"""

    private fun footerXml() =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
            """<w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> PAGE </w:instrText></w:r>""" +
            """<w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>1</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r></w:p></w:ftr>"""

    private fun stylesXml(): String {
        val sz = options.sizePt * 2
        val f = esc(options.font)
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
            """<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="$f" w:hAnsi="$f" w:eastAsia="$f" w:cs="$f"/><w:sz w:val="$sz"/><w:szCs w:val="$sz"/><w:lang w:val="en-US"/></w:rPr></w:rPrDefault>""" +
            """<w:pPrDefault><w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>""" +
            """<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="FootnoteText"><w:name w:val="footnote text"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr><w:rPr><w:sz w:val="${sz - 4}"/><w:szCs w:val="${sz - 4}"/></w:rPr></w:style>""" +
            """<w:style w:type="character" w:styleId="FootnoteReference"><w:name w:val="footnote reference"/><w:rPr><w:vertAlign w:val="superscript"/></w:rPr></w:style>""" +
            "</w:styles>"
    }

    private fun settingsXml() =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
            """<w:defaultTabStop w:val="720"/><w:footnotePr><w:footnote w:id="-1"/><w:footnote w:id="0"/></w:footnotePr><w:compat><w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/></w:compat></w:settings>"""

    private fun contentTypes() = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""")
        append("""<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>""")
        append("""<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>""")
        append("""<Override PartName="/word/footnotes.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footnotes+xml"/>""")
        if (options.headerText != null) append("""<Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>""")
        if (options.pageNumbers) append("""<Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>""")
        append("""<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""")
        append("</Types>")
    }

    private fun rootRels() =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>""" +
            """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>"""

    private fun docRels() = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        append("""<Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        append("""<Relationship Id="rIdSettings" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/>""")
        append("""<Relationship Id="rIdFootnotes" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footnotes" Target="footnotes.xml"/>""")
        if (options.headerText != null) append("""<Relationship Id="rIdHeader1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/>""")
        if (options.pageNumbers) append("""<Relationship Id="rIdFooter1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>""")
        append("</Relationships>")
    }

    private fun coreProps(): String {
        val now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">""" +
            "<dc:title>${esc(options.title)}</dc:title><dc:creator>${esc(options.author)}</dc:creator>" +
            """<dcterms:created xsi:type="dcterms:W3CDTF">$now</dcterms:created><dcterms:modified xsi:type="dcterms:W3CDTF">$now</dcterms:modified></cp:coreProperties>"""
    }
}
