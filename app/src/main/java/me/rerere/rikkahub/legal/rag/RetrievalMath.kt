package me.rerere.rikkahub.legal.rag

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Vector helpers. Embeddings are stored L2-normalized so cosine similarity == dot product. */
object VectorMath {
    fun normalize(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = sqrt(s).toFloat()
        if (n == 0f) return v
        return FloatArray(v.size) { v[it] / n }
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var s = 0f
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }

    fun toBytes(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) buf.putFloat(x)
        return buf.array()
    }

    fun fromBytes(b: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { buf.getFloat() }
    }
}

/**
 * Reciprocal Rank Fusion (Cormack et al., 2009). Robust to the incomparable score scales of
 * BM25, cosine similarity and exact-match hits, which is why it is used instead of a weighted
 * score sum. `k = 60` is the standard constant.
 */
object RankFusion {
    fun <T> rrf(rankings: Map<String, List<T>>, k: Int = 60, weights: Map<String, Double> = emptyMap()): List<Pair<T, Double>> {
        val scores = LinkedHashMap<T, Double>()
        for ((name, list) in rankings) {
            val w = weights[name] ?: 1.0
            list.forEachIndexed { rank, item ->
                scores[item] = (scores[item] ?: 0.0) + w / (k + rank + 1)
            }
        }
        return scores.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
}

/**
 * Text normalization + quote verification. Legal quotations must match the source verbatim
 * (modulo typography), so the comparison folds only differences that are not substantive:
 * curly vs straight quotes, dash variants, whitespace / line wraps, soft hyphens, and
 * optionally case. Ellipses and bracketed alterations (`[t]he`) are treated as the standard
 * Bluebook signals they are.
 */
object QuoteMatcher {

    data class Normalized(val text: String, /** index in normalized -> index in original */ val map: IntArray)

    fun normalize(s: String, foldCase: Boolean = true): Normalized {
        val out = StringBuilder(s.length)
        val map = IntArray(s.length + 1)
        var n = 0
        var lastSpace = true
        var i = 0
        while (i < s.length) {
            var c = s[i]
            when (c) {
                '\u00AD' -> { i++; continue } // soft hyphen
                '‘', '’', '‛', '′', '`' -> c = '\''
                '“', '”', '„', '″' -> c = '"'
                '‐', '‑', '‒', '–', '—', '―' -> c = '-'
                '\u00A0', '\t', '\n', '\r', '\u2009', '\u202F' -> c = ' '
            }
            if (c == ' ') {
                if (lastSpace) { i++; continue }
                lastSpace = true
            } else lastSpace = false
            out.append(if (foldCase) c.lowercaseChar() else c)
            map[n++] = i
            i++
        }
        // trim trailing space
        var len = out.length
        if (len > 0 && out[len - 1] == ' ') { out.setLength(len - 1); len-- }
        val m = IntArray(len + 1)
        System.arraycopy(map, 0, m, 0, len)
        m[len] = s.length
        return Normalized(out.toString(), m)
    }

    /**
     * Split a quotation into the segments that must each appear verbatim, in order.
     * `...`, `…`, `. . .` are omissions; `[x]` alterations are reduced to a wildcard boundary.
     */
    fun segments(quote: String): List<String> {
        val q = quote.trim().trim('"', '“', '”', '\'', '‘', '’')
        return q.split(Regex("\\s*(?:\\.\\s?\\.\\s?\\.|…|\\[[^\\]]{0,40}\\])\\s*"))
            .map { it.trim().trim(',', ';') }
            .filter { it.length >= 3 }
    }

    data class Match(val start: Int, val end: Int)

    /** Find all segments in order within [haystack]. Returns original-offset span or null. */
    fun findVerbatim(haystack: String, quote: String, foldCase: Boolean = true): Match? {
        val segs = segments(quote)
        if (segs.isEmpty()) return null
        val h = normalize(haystack, foldCase)
        val normSegs = segs.map { normalize(it, foldCase).text }
        var searchFrom = 0
        var first = -1
        var lastEnd = -1
        // Anchor on the first segment, then require the rest in order within a bounded window.
        while (true) {
            val idx = h.text.indexOf(normSegs[0], searchFrom)
            if (idx < 0) return null
            var cursor = idx + normSegs[0].length
            var ok = true
            for (k in 1 until normSegs.size) {
                val nxt = h.text.indexOf(normSegs[k], cursor)
                if (nxt < 0 || nxt - cursor > 4_000) { ok = false; break }
                cursor = nxt + normSegs[k].length
            }
            if (ok) { first = idx; lastEnd = cursor; break }
            searchFrom = idx + 1
        }
        return Match(h.map[first], h.map[lastEnd.coerceAtMost(h.map.size - 1)])
    }

    data class Fuzzy(val start: Int, val end: Int, val similarity: Double, val sourceText: String)

    /**
     * Best approximate location of [quote] in [haystack] using a word-level sliding window and
     * a normalized Levenshtein similarity. Used to show counsel what the source *actually* says
     * when a quotation does not match verbatim.
     */
    fun findClosest(haystack: String, quote: String): Fuzzy? {
        val q = normalize(segments(quote).joinToString(" ").ifBlank { quote }).text
        if (q.isBlank()) return null
        val h = normalize(haystack)
        val words = Regex("\\S+").findAll(h.text).map { it.range }.toList()
        if (words.isEmpty()) return null
        val qWords = q.split(' ').size
        val qSet = q.split(' ').toHashSet()
        var best: Fuzzy? = null
        val step = if (words.size > 4_000) 3 else 1
        var wi = 0
        while (wi < words.size) {
            // Cheap prefilter: window must share at least 40% of distinct words.
            val endWord = (wi + qWords - 1).coerceAtMost(words.lastIndex)
            var shared = 0
            for (x in wi..endWord) if (h.text.substring(words[x].first, words[x].last + 1) in qSet) shared++
            if (shared * 10 >= qWords * 4) {
                for (delta in intArrayOf(-2, 0, 2)) {
                    val e = (endWord + delta).coerceIn(wi, words.lastIndex)
                    val cand = h.text.substring(words[wi].first, words[e].last + 1)
                    val sim = similarity(q, cand)
                    if (best == null || sim > best.similarity) {
                        val os = h.map[words[wi].first]
                        val oe = h.map[(words[e].last + 1).coerceAtMost(h.map.size - 1)]
                        best = Fuzzy(os, oe, sim, haystack.substring(os, oe))
                    }
                }
            }
            wi += step
        }
        return best
    }

    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val d = levenshtein(a, b, maxOf(a.length, b.length))
        return 1.0 - d.toDouble() / maxOf(a.length, b.length)
    }

    private fun levenshtein(a: String, b: String, cap: Int): Int {
        if (a.length > 3_000 || b.length > 3_000) return cap
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Build an FTS5 query string from free text: quoted phrases kept, other terms OR-ed. */
    fun ftsQuery(query: String, joiner: String = " OR "): String {
        val parts = ArrayList<String>()
        val phraseRe = Regex("[\"“]([^\"”]{2,200})[\"”]")
        var rest = query
        phraseRe.findAll(query).forEach { m ->
            val phrase = m.groupValues[1].replace("\"", " ").trim()
            if (phrase.isNotEmpty()) parts += "\"$phrase\""
        }
        rest = phraseRe.replace(rest, " ")
        Regex("[\\p{L}\\p{N}§][\\p{L}\\p{N}'’.§-]*").findAll(rest).forEach { m ->
            val t = m.value.trim('.', '-', '\'', '’')
            if (t.length >= 2 && t.lowercase() !in STOPWORDS) parts += "\"${t.replace("\"", "")}\""
        }
        return parts.distinct().joinToString(joiner)
    }

    private val STOPWORDS = setOf(
        "the", "a", "an", "of", "to", "in", "and", "or", "for", "on", "at", "by", "is", "was",
        "be", "that", "this", "with", "as", "it", "are", "from", "what", "which", "who", "how",
        "does", "did", "do", "any", "all", "about", "find", "show", "me", "where",
    )
}
