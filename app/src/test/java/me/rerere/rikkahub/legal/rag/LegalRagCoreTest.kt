package me.rerere.rikkahub.legal.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegalRagCoreTest {

    private val brief = """Under Strickland v. Washington, 466 U.S. 668, 687-88 (1984), counsel's performance must be deficient. See also Harrington v. Richter, 562 U.S. 86, 105 (2011); Brady v. Maryland, 373 U.S. 83 (1963). United States v. Jones, 403 F.3d 1100, 1105 (8th Cir. 2005). Relief is barred by 28 U.S.C. § 2254(d)(1) and § 2244(d). Id. at 694. Doe v. Roe, 2020 WL 1234567, at *3 (D. Neb. Mar. 2, 2020). See Fed. R. Crim. P. 33(b)(1); 21 C.F.R. § 1308.11; Kimmelman v. Morrison, 477 U.S. 365, 106 S. Ct. 2574, 91 L. Ed. 2d 305 (1986)."""

    @Test
    fun extractsCasesStatutesAndRules() {
        val cites = CitationExtractor.extract(brief)
        val keys = cites.map { it.key }.toSet()
        assertTrue("466 U.S. 668" in keys)
        assertTrue("562 U.S. 86" in keys)
        assertTrue("403 F.3d 1100" in keys)
        assertTrue("477 U.S. 365" in keys)
        assertTrue("106 S. Ct. 2574" in keys)
        assertTrue(keys.any { it.startsWith("28 U.S.C. § 2254") })
        assertTrue(cites.any { it.kind == CitationExtractor.Kind.WESTLAW })
        assertTrue(cites.any { it.kind == CitationExtractor.Kind.RULE })
        assertTrue(cites.any { it.kind == CitationExtractor.Kind.REGULATION })
        val strickland = cites.first { it.key == "466 U.S. 668" }
        assertEquals("Strickland v. Washington", strickland.caseName)
        assertEquals("1984", strickland.year)
        // "See also" is a signal, not part of the case name.
        assertEquals("Harrington v. Richter", cites.first { it.key == "562 U.S. 86" }.caseName)
        // Parallel cites are not mistaken for pin cites.
        assertNull(cites.first { it.key == "477 U.S. 365" }.pin?.takeIf { it.startsWith("106") })
    }

    @Test
    fun keysInQueryFindsCitation() {
        assertEquals(listOf("466 U.S. 668"), CitationExtractor.keysIn("cases citing Strickland, 466 U.S. 668"))
    }

    private fun pages() = listOf(
        LegalChunker.Page(
            1,
            "I. STATEMENT OF THE CASE\nPetitioner was convicted of first-\ndegree murder in 2004. The\nstate relied on a jailhouse informant.\n\nII. ARGUMENT\n" +
                "Trial counsel failed to investigate. ".repeat(30) +
                "See Strickland v. Washington, 466 U.S. 668, 687 (1984). The court said \"a fair assessment of attorney performance requires that every effort be made to eliminate the distorting effects of hindsight.\" Id. at 689.",
        ),
        LegalChunker.Page(2, "A. Prejudice\n" + "There is a reasonable probability of a different result. ".repeat(40)),
    )

    @Test
    fun chunkerKeepsCompoundsHeadingsAndPages() {
        val p = LegalChunker.prepare(pages())
        assertTrue("first-degree" in p.text)
        val chunks = LegalChunker.chunk(p)
        assertTrue(chunks.all { it.text.length <= LegalChunker.MAX_CHARS })
        assertTrue(chunks.any { it.heading?.startsWith("II. ARGUMENT") == true })
        assertTrue(chunks.any { it.heading == "A. Prejudice" && it.pageStart == 2 })
        assertEquals(1, chunks.first().pageStart)
        // A citation is never split across chunk boundaries.
        chunks.forEach { c ->
            CitationExtractor.protectedRanges(p.text).forEach { r ->
                val inside = r.first >= c.start && r.first < c.end
                if (inside) assertTrue(r.last < c.end + 1)
            }
        }
        assertEquals(2, p.pageAt(p.text.indexOf("A. Prejudice")))
    }

    @Test
    fun quoteMatcherHandlesBracketsEllipsesAndFuzzy() {
        val p = LegalChunker.prepare(pages())
        assertNotNull(QuoteMatcher.findVerbatim(p.text, "“[E]very effort be made to eliminate the distorting effects of hindsight”"))
        assertNotNull(QuoteMatcher.findVerbatim(p.text, "a fair assessment . . . requires that every effort be made"))
        assertNull(QuoteMatcher.findVerbatim(p.text, "every effort must be made to remove hindsight"))
        val f = QuoteMatcher.findClosest(p.text, "every effort must be made to eliminate the distorting effect of hindsight")
        assertNotNull(f)
        assertTrue(f!!.similarity > 0.8)
    }

    @Test
    fun rrfAndVectors() {
        val fused = RankFusion.rrf(mapOf("a" to listOf(1, 2, 3), "b" to listOf(3, 1, 4)))
        assertEquals(1, fused.first().first)
        val v = VectorMath.normalize(floatArrayOf(3f, 4f))
        assertEquals(1f, VectorMath.dot(v, v), 1e-5f)
        assertTrue(VectorMath.fromBytes(VectorMath.toBytes(v)).contentEquals(v))
    }

    @Test
    fun nvidiaRerankUrls() {
        assertEquals(
            "https://ai.api.nvidia.com/v1/retrieval/nvidia/llama-3_2-nv-rerankqa-1b-v2/reranking",
            RerankClient.nvidiaRerankUrl("https://integrate.api.nvidia.com/v1", "nvidia/llama-3.2-nv-rerankqa-1b-v2"),
        )
        assertEquals("http://localhost:8000/v1/ranking", RerankClient.nvidiaRerankUrl("http://localhost:8000/v1", "x"))
    }
}
