package me.rerere.rikkahub.legal.rag

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.SettingsStore

private const val TAG = "LegalRetriever"

enum class SearchMode { HYBRID, KEYWORD, EXACT, SEMANTIC, CITATION;
    companion object {
        fun parse(s: String?): SearchMode = entries.firstOrNull { it.name.equals(s?.trim(), true) } ?: HYBRID
    }
}

@Serializable
data class SearchHit(
    val chunkId: Long,
    val docId: Long,
    val projectId: String,
    val document: String,
    val docType: String,
    val pageStart: Int,
    val pageEnd: Int,
    val heading: String,
    val snippet: String,
    val text: String,
    val score: Double,
    val matchedBy: List<String>,
)

@Serializable
data class QuoteCheck(
    val status: String, // verbatim | close | not_found
    val document: String? = null,
    val docId: Long? = null,
    val page: Int? = null,
    val pageEnd: Int? = null,
    val similarity: Double? = null,
    val sourceText: String? = null,
    val context: String? = null,
    val note: String,
)

class LegalRetriever(
    private val index: LegalIndex,
    private val settingsStore: SettingsStore,
    private val embeddings: EmbeddingClient,
    private val reranker: RerankClient,
) {
    data class Query(
        val text: String,
        val mode: SearchMode = SearchMode.HYBRID,
        val projectIds: List<String>? = null,
        val docIds: List<Long>? = null,
        val topK: Int = 8,
        val rerank: Boolean = true,
    )

    data class Outcome(val hits: List<SearchHit>, val notes: List<String>)

    suspend fun search(q: Query): Outcome = withContext(Dispatchers.IO) {
        val notes = ArrayList<String>()
        val pool = maxOf(q.topK * 6, 40)
        val rankings = LinkedHashMap<String, List<Long>>()
        val snippets = HashMap<Long, String>()
        val settings = settingsStore.settingsFlow.value

        fun runKeyword() {
            val fts = QuoteMatcher.ftsQuery(q.text)
            val hits = index.keyword(fts, q.projectIds, q.docIds, pool)
            hits.forEach { snippets.putIfAbsent(it.chunkId, it.snippet) }
            rankings["keyword"] = hits.map { it.chunkId }
        }

        fun runExact(phrases: List<String>) {
            val verified = LinkedHashSet<Long>()
            for (phrase in phrases) {
                val cand = index.exactCandidates(phrase, q.projectIds, q.docIds, 400)
                val rows = index.chunks(cand)
                cand.forEach { id ->
                    val row = rows[id] ?: return@forEach
                    val m = QuoteMatcher.findVerbatim(row.text, phrase)
                    if (m != null) {
                        verified += id
                        snippets[id] = window(row.text, m.start, m.end)
                    }
                }
            }
            rankings["exact"] = verified.toList()
        }

        suspend fun runSemantic() {
            val slot = settings.resolveSlot(settings.embeddingModelId)
            if (slot == null) {
                notes += "semantic search skipped: no embedding model selected"
                return
            }
            val qv = runCatching { embeddings.embed(slot, listOf(q.text), EmbedRole.QUERY).first() }
                .onFailure { notes += "semantic search failed: ${it.message}" }
                .getOrNull() ?: return
            val hits = index.semantic(qv, q.projectIds, q.docIds, pool)
            if (hits.isEmpty()) notes += "no embedded chunks in scope (embedding may still be running)"
            rankings["semantic"] = hits.map { it.first }
        }

        fun runCitation(): Boolean {
            val keys = CitationExtractor.keysIn(q.text)
            if (keys.isEmpty()) return false
            val rows = index.citationsByKey(keys, q.projectIds, pool * 2)
                .filter { q.docIds == null || it.docId in q.docIds }
            rankings["citation"] = rows.mapNotNull { it.chunkId }.distinct()
            return true
        }

        val quoted = Regex("[\"“]([^\"”]{3,400})[\"”]").findAll(q.text).map { it.groupValues[1] }.toList()
        when (q.mode) {
            SearchMode.KEYWORD -> runKeyword()
            SearchMode.EXACT -> runExact(quoted.ifEmpty { listOf(q.text) })
            SearchMode.SEMANTIC -> runSemantic()
            SearchMode.CITATION -> if (!runCitation()) notes += "no recognizable citation in query; try 'Strickland, 466 U.S. 668' style"
            SearchMode.HYBRID -> {
                runKeyword()
                if (quoted.isNotEmpty()) runExact(quoted)
                runCitation()
                runSemantic()
            }
        }

        val weights = mapOf("exact" to 1.6, "citation" to 1.4, "keyword" to 1.0, "semantic" to 1.0)
        val fused = RankFusion.rrf(rankings, k = 60, weights = weights)
        if (fused.isEmpty()) return@withContext Outcome(emptyList(), notes)

        val candIds = fused.take(maxOf(q.topK, if (q.rerank) 30 else q.topK)).map { it.first }
        val rows = index.chunks(candIds)
        var ordered: List<Pair<Long, Double>> = fused.filter { it.first in rows }.take(candIds.size)

        if (q.rerank && q.mode != SearchMode.EXACT && q.mode != SearchMode.CITATION && ordered.size > 1) {
            val slot = settings.resolveSlot(settings.rerankModelId)
            if (slot != null) {
                runCatching {
                    val passages = ordered.map { rows.getValue(it.first).let { r -> (r.heading + "\n" + r.text).take(4_000) } }
                    val rr = reranker.rerank(slot, q.text, passages, passages.size)
                    // Keep exact/citation hits pinned at the top: reranking only reorders the rest.
                    val pinned = ordered.filter { id -> listOf("exact", "citation").any { rankings[it]?.contains(id.first) == true } }
                    val rest = rr.map { ordered[it.first].first to it.second }.filter { p -> pinned.none { it.first == p.first } }
                    ordered = pinned + rest
                }.onFailure {
                    Log.w(TAG, "rerank failed", it)
                    notes += "rerank failed: ${it.message}"
                }
            }
        }

        val docs = HashMap<Long, LegalDocument?>()
        val hits = ordered.take(q.topK).mapNotNull { (id, score) ->
            val r = rows[id] ?: return@mapNotNull null
            val doc = docs.getOrPut(r.docId) { index.getDocument(r.docId) } ?: return@mapNotNull null
            SearchHit(
                chunkId = id, docId = r.docId, projectId = r.projectId, document = doc.title, docType = doc.docType,
                pageStart = r.pageStart, pageEnd = r.pageEnd, heading = r.heading,
                snippet = snippets[id] ?: r.text.take(320), text = r.text, score = score,
                matchedBy = rankings.filterValues { id in it }.keys.toList(),
            )
        }
        Outcome(hits, notes)
    }

    /**
     * Verify that [quote] appears in the indexed record. Ellipses and bracketed alterations are
     * treated as gaps. Returns the exact page when verbatim; otherwise the closest passage and
     * its similarity so counsel can see what the source actually says.
     */
    suspend fun checkQuote(quote: String, projectIds: List<String>?, docIds: List<Long>?): QuoteCheck = withContext(Dispatchers.IO) {
        val segments = QuoteMatcher.segments(quote)
        if (segments.isEmpty()) return@withContext QuoteCheck("not_found", note = "Quote too short to verify")
        val longest = segments.maxBy { it.length }

        // 1. Candidate docs from the most distinctive segment.
        val candChunks = index.exactCandidates(longest, projectIds, docIds, 300)
        val candDocs = index.chunks(candChunks).values.map { it.docId }.distinct()
        for (docId in candDocs) {
            val ft = index.fullText(docId) ?: continue
            val m = QuoteMatcher.findVerbatim(ft.text, quote) ?: continue
            val doc = index.getDocument(docId)!!
            return@withContext QuoteCheck(
                status = "verbatim", document = doc.title, docId = docId,
                page = ft.prepared.pageAt(m.start), pageEnd = ft.prepared.pageAt((m.end - 1).coerceAtLeast(m.start)),
                similarity = 1.0, sourceText = ft.text.substring(m.start, m.end), context = window(ft.text, m.start, m.end, 200),
                note = "Found verbatim (whitespace, curly quotes and case normalized; ellipses/brackets treated as gaps).",
            )
        }

        // 2. Closest passage among keyword neighbours.
        val kw = index.keyword(QuoteMatcher.ftsQuery(quote), projectIds, docIds, 12)
        val rows = index.chunks(kw.map { it.chunkId })
        var best: QuoteCheck? = null
        for (h in kw) {
            val r = rows[h.chunkId] ?: continue
            val ft = index.fullText(r.docId) ?: continue
            val neigh = index.neighbors(r, 1)
            val base = neigh.minOf { it.charStart }.coerceIn(0, ft.text.length)
            val end = neigh.maxOf { it.charEnd }.coerceIn(base, ft.text.length)
            val region = ft.text.substring(base, end)
            val f = QuoteMatcher.findClosest(region, quote) ?: continue
            if (best == null || f.similarity > (best.similarity ?: 0.0)) {
                val doc = index.getDocument(r.docId)!!
                best = QuoteCheck(
                    status = if (f.similarity >= 0.85) "close" else "not_found",
                    document = doc.title, docId = r.docId,
                    page = ft.prepared.pageAt(base + f.start), pageEnd = ft.prepared.pageAt(base + (f.end - 1).coerceAtLeast(f.start)),
                    similarity = Math.round(f.similarity * 1000) / 1000.0, sourceText = f.sourceText,
                    context = window(region, f.start, f.end, 200),
                    note = if (f.similarity >= 0.85) "Not verbatim. The closest source text differs — compare before quoting."
                    else "No verbatim or near match in the indexed record. Closest passage shown for reference only.",
                )
            }
        }
        best ?: QuoteCheck("not_found", note = "No matching text in the indexed documents in scope.")
    }

    private fun window(text: String, start: Int, end: Int, pad: Int = 160): String {
        val s = (start - pad).coerceAtLeast(0)
        val e = (end + pad).coerceAtMost(text.length)
        return (if (s > 0) "…" else "") + text.substring(s, start) + "«" + text.substring(start, end) + "»" +
            text.substring(end, e) + (if (e < text.length) "…" else "")
    }
}
