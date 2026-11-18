package me.rerere.rikkahub.legal.docx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class DocxWriterTest {
    private fun render(md: String, opts: DocxWriter.Options = DocxWriter.Options()): Map<String, String> {
        val out = ByteArrayOutputStream()
        DocxWriter(opts).write(md, out)
        val parts = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            while (true) { val e = z.nextEntry ?: break; parts[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        return parts
    }

    private fun parse(xml: String): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray()))

    @Test fun packageIsWellFormed() {
        val parts = render(
            """
            # INTRODUCTION
            Under *Strickland v. Washington*, 466 U.S. 668 (1984), & "prejudice" <matters>.[^1]
            > Quoted text.
            - bullet
            1. numbered
            | A | B |
            |---|---|
            | 1 | 2 |
            [[pagebreak]]
            [[right]] Respectfully submitted,
            [^1]: A footnote.
            """.trimIndent(),
            DocxWriter.Options(caption = DocxWriter.Caption("COURT", listOf("DOE,", "v.", "STATE."), listOf("No. 1"), "Brief")),
        )
        listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml", "word/footnotes.xml")
            .forEach { assertTrue("missing $it", parts.containsKey(it)) }
        parts.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.values.forEach { parse(it) }
        val doc = parts.getValue("word/document.xml")
        assertTrue(doc.contains("Strickland v. Washington"))
        assertTrue(doc.contains("&amp;"))
        assertTrue(doc.contains("&lt;matters&gt;"))
        assertTrue(doc.contains("w:footnoteReference"))
        assertTrue(doc.contains("w:tbl"))
        assertTrue(doc.contains("pageBreakBefore"))
        assertTrue(parts.getValue("word/footnotes.xml").contains("A footnote."))
        assertFalse(doc.contains("[^1]"))
    }

    @Test fun draftBannerIsDefault() {
        val parts = render("Hello")
        assertTrue(parts.values.any { it.contains(DocxWriter.DRAFT_BANNER) })
    }

    @Test fun doubleSpacingDefault() {
        val doc = render("Body text.").getValue("word/document.xml")
        assertTrue(doc.contains("w:line=\"480\"") || render("x").getValue("word/styles.xml").contains("w:line=\"480\""))
    }

}
