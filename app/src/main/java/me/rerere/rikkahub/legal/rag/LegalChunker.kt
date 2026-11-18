package me.rerere.rikkahub.legal.rag

/**
 * Legal-aware chunker. Pure Kotlin so it is JVM-testable.
 *
 * Design goals, in priority order:
 *  1. Every chunk carries the page range it came from, so search hits and quotations can be
 *     pin-cited back to the record (`R. 214`, `Pet. App. 37a`, transcript pages).
 *  2. A citation (case name through parenthetical) is never split across chunks.
 *  3. A quoted passage is not split unless it alone exceeds the hard size cap.
 *  4. Paragraph and heading structure is preserved; each chunk records the nearest heading
 *     (`I. STATEMENT OF THE CASE`, `B. Ineffective assistance ...`).
 *
 * Offsets ([Chunk.start] / [Chunk.end]) are into [Prepared.text], the normalized full text of
 * the document, which is stored alongside the chunks so exact-quote verification can run on
 * the whole document rather than chunk-by-chunk.
 */
object LegalChunker {

    data class Page(val number: Int, val text: String)

    data class Prepared(
        /** Normalized full document text. */
        val text: String,
        /** Start offset of each page in [text], same order as the input pages. */
        val pageStarts: IntArray,
        val pageNumbers: IntArray,
    ) {
        fun pageAt(offset: Int): Int {
            var lo = 0
            var hi = pageStarts.size - 1
            var ans = 0
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (pageStarts[mid] <= offset) {
                    ans = mid; lo = mid + 1
                } else hi = mid - 1
            }
            return if (pageNumbers.isEmpty()) 1 else pageNumbers[ans]
        }
    }

    data class Chunk(
        val ordinal: Int,
        val text: String,
        val start: Int,
        val end: Int,
        val pageStart: Int,
        val pageEnd: Int,
        val heading: String?,
    )

    const val TARGET_CHARS = 1_400
    const val MAX_CHARS = 2_400
    const val OVERLAP_CHARS = 250

    private val HYPHEN_BREAK = Regex("(\\p{L}+)-\\n(\\p{Ll})")
    /** Prefixes whose line-end hyphen is a real compound hyphen (`first-degree`), not a wrap. */
    private val COMPOUND_PREFIXES = setOf(
        "first", "second", "third", "fourth", "self", "non", "pre", "post", "co", "anti", "ex",
        "well", "so", "cross", "pro", "quasi", "semi", "multi", "counter", "sub", "out", "over",
        "under", "high", "low", "long", "short", "full", "part", "half", "court", "state", "law",
    )
    private val TRAILING_WS = Regex("[ \\t]+\\n")
    private val MULTI_BLANK = Regex("\\n{3,}")
    private val INLINE_WS = Regex("[ \\t\\u00A0]{2,}")

    private val HEADING_RE = Regex(
        "^(?:" +
            "(?:[IVXLC]{1,6}|[A-Z]|\\d{1,2})\\.\\s+\\S.{0,110}" + // I. / A. / 1. outline headings
            "|(?:ARGUMENT|STATEMENT OF (?:THE )?(?:CASE|FACTS|ISSUES?)|INTRODUCTION|CONCLUSION|" +
            "SUMMARY OF (?:THE )?ARGUMENT|STANDARD OF REVIEW|PROCEDURAL (?:HISTORY|BACKGROUND)|" +
            "BACKGROUND|DISCUSSION|ANALYSIS|JURISDICTION(?:AL STATEMENT)?|QUESTIONS? PRESENTED|" +
            "GROUNDS? FOR RELIEF|GROUND (?:ONE|TWO|THREE|FOUR|FIVE|SIX|\\d+)|CLAIM (?:ONE|TWO|THREE|\\d+)|" +
            "PRAYER FOR RELIEF|OPINION|ORDER|MEMORANDUM|FINDINGS OF FACT|CONCLUSIONS OF LAW)\\b.{0,100}" +
            ")$",
    )

    private val ABBREVIATIONS = setOf(
        "v", "vs", "no", "nos", "id", "e.g", "i.e", "cf", "see", "u.s", "s. ct", "ct", "cir", "app",
        "supp", "fed", "f", "rev", "stat", "ann", "art", "sec", "ch", "cl", "para", "pp", "p",
        "mr", "mrs", "ms", "dr", "jr", "sr", "inc", "co", "corp", "ltd", "dist", "div", "dept",
        "gov", "st", "ave", "j", "jj", "c.j", "op", "aff", "tr", "r", "ex", "pet", "br", "resp",
        "def", "pl", "crim", "civ", "evid", "proc", "cal", "ill", "tex", "neb", "okla", "mass",
        "wash", "ohio", "pa", "n.y", "n.e", "n.w", "s.e", "s.w", "so", "l. ed", "ed", "et al", "al",
        "approx", "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sept", "sep", "oct", "nov", "dec",
    )

    fun prepare(pages: List<Page>): Prepared {
        val sb = StringBuilder()
        val starts = IntArray(pages.size)
        val numbers = IntArray(pages.size)
        pages.forEachIndexed { i, page ->
            if (sb.isNotEmpty() && !sb.endsWith("\n\n")) sb.append("\n\n")
            starts[i] = sb.length
            numbers[i] = page.number
            sb.append(normalizePage(page.text))
        }
        return Prepared(sb.toString(), starts, numbers)
    }

    internal fun normalizePage(raw: String): String {
        var t = raw.replace("\r\n", "\n").replace('\r', '\n').replace('\u000C', '\n')
        t = HYPHEN_BREAK.replace(t) { m ->
            val left = m.groupValues[1]
            if (left.lowercase() in COMPOUND_PREFIXES) "$left-${m.groupValues[2]}" else left + m.groupValues[2]
        }
        t = TRAILING_WS.replace(t, "\n")
        t = INLINE_WS.replace(t, " ")
        t = reflowLines(t)
        t = MULTI_BLANK.replace(t, "\n\n")
        return t.trim()
    }

    /**
     * PDF text arrives one visual line per `\n`. Join wrapped lines into paragraphs while
     * keeping real paragraph breaks, headings, list items and transcript Q/A lines.
     */
    private fun reflowLines(t: String): String {
        val lines = t.split('\n')
        if (lines.size < 3) return t
        val out = StringBuilder()
        for (i in lines.indices) {
            val line = lines[i].trimEnd()
            out.append(line)
            if (i == lines.lastIndex) break
            val next = lines[i + 1].trimStart()
            val joinable = line.isNotBlank() && next.isNotBlank() &&
                !isHeading(line.trim()) && !isHeading(next) &&
                !line.trimEnd().endsWith(":") &&
                !Regex("^(?:\\d{1,2}\\s+)?(?:Q|A|THE COURT|BY M[RS]S?\\.)[.:]?\\s").containsMatchIn(next) &&
                !Regex("^(?:[-•*]|\\(?[a-z0-9]{1,3}[.)])\\s").containsMatchIn(next) &&
                !(line.trimEnd().last() in ".?!\"”" && next.first().isUpperCase() && line.length < 55)
            out.append(if (joinable) " " else "\n")
        }
        return out.toString()
    }

    fun isHeading(line: String): Boolean {
        val l = line.trim()
        if (l.isEmpty() || l.length > 120) return false
        if (HEADING_RE.matches(l)) {
            // Outline-style headings must look like titles, not ordinary numbered sentences.
            val letters = l.filter { it.isLetter() }
            val upperRatio = if (letters.isEmpty()) 0.0 else letters.count { it.isUpperCase() }.toDouble() / letters.length
            return upperRatio > 0.6 || !l.trimEnd().endsWith(".") || l.length < 60
        }
        val letters = l.filter { it.isLetter() }
        return letters.length >= 6 && letters.all { it.isUpperCase() } && l.length <= 90
    }

    fun chunk(prepared: Prepared): List<Chunk> {
        val text = prepared.text
        if (text.isBlank()) return emptyList()
        val protected = CitationExtractor.protectedRanges(text)
        val paragraphs = splitParagraphs(text)
        val chunks = ArrayList<Chunk>()
        var heading: String? = null
        var curStart = -1
        var curEnd = -1
        var curHeading: String? = null
        var curOnlyHeadings = false

        fun flush() {
            if (curStart < 0) return
            val body = text.substring(curStart, curEnd).trim()
            if (body.isNotEmpty()) {
                val lead = text.indexOf(body.first(), curStart)
                chunks += Chunk(
                    ordinal = chunks.size,
                    text = body,
                    start = lead,
                    end = lead + body.length,
                    pageStart = prepared.pageAt(lead),
                    pageEnd = prepared.pageAt((lead + body.length - 1).coerceAtLeast(lead)),
                    heading = curHeading,
                )
            }
            curStart = -1
            curEnd = -1
        }

        for (p in paragraphs) {
            val pText = text.substring(p.first, p.last + 1)
            val headingPara = isHeading(pText)
            if (headingPara) {
                heading = pText.trim()
                // A new section starts a new chunk (no overlap across section boundaries).
                if (curStart >= 0 && !curOnlyHeadings) flush()
            }
            val pieces = if (pText.length > MAX_CHARS) splitLong(text, p, protected) else listOf(p)
            for (piece in pieces) {
                val len = piece.last + 1 - piece.first
                val limit = if (curOnlyHeadings) MAX_CHARS else TARGET_CHARS
                if (curStart >= 0 && (curEnd - curStart) + len > limit) {
                    val prevEnd = curEnd
                    flush()
                    // Overlap: carry the tail of the previous chunk (whole sentences) forward.
                    val overlapStart = overlapStart(text, prevEnd, protected)
                    if (overlapStart in 0 until prevEnd && prevEnd - overlapStart <= OVERLAP_CHARS * 2 &&
                        (prevEnd - overlapStart) + len <= MAX_CHARS &&
                        !isHeading(text.substring(piece.first, piece.last + 1))
                    ) {
                        curStart = overlapStart
                        curEnd = prevEnd
                        curHeading = heading
                        curOnlyHeadings = false
                    }
                }
                if (curStart < 0) {
                    curStart = piece.first
                    curHeading = heading
                    curOnlyHeadings = headingPara
                } else if (!headingPara) {
                    curOnlyHeadings = false
                }
                curEnd = piece.last + 1
            }
        }
        flush()
        return chunks
    }

    private fun splitParagraphs(text: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < text.length) {
            val brk = text.indexOf("\n\n", i).let { if (it < 0) text.length else it }
            var a = i
            var b = brk
            while (a < b && text[a].isWhitespace()) a++
            while (b > a && text[b - 1].isWhitespace()) b--
            if (b > a) {
                // A single newline inside a block still separates headings / transcript lines.
                var s = a
                while (s < b) {
                    val nl = text.indexOf('\n', s).let { if (it < 0 || it > b) b else it }
                    val line = text.substring(s, nl)
                    if (line.isNotBlank()) out += s until nl
                    s = nl + 1
                }
            }
            i = brk + 2
        }
        return mergeShortLines(text, out)
    }

    /** Merge transcript / list lines that were kept separate back into paragraph-sized groups. */
    private fun mergeShortLines(text: String, ranges: List<IntRange>): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (r in ranges) {
            val prev = out.lastOrNull()
            val line = text.substring(r.first, r.last + 1)
            if (prev != null && !isHeading(line) && !isHeading(text.substring(prev.first, prev.last + 1)) &&
                (prev.last + 1 - prev.first) < 300 && (r.last + 1 - r.first) < 300 &&
                text.substring(prev.last + 1, r.first).count { it == '\n' } <= 1
            ) {
                out[out.lastIndex] = prev.first..r.last
            } else out += r
        }
        return out
    }

    private fun splitLong(text: String, range: IntRange, protected: List<IntRange>): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = range.first
        val end = range.last + 1
        while (end - start > TARGET_CHARS) {
            val target = start + TARGET_CHARS
            val cut = sentenceBoundaryNear(text, start, target, (start + MAX_CHARS).coerceAtMost(end), protected)
            out += start until cut
            start = cut
            while (start < end && text[start].isWhitespace()) start++
        }
        if (start < end) out += start until end
        return out
    }

    /** Find a safe sentence boundary at or after [target] and before [limit]. */
    internal fun sentenceBoundaryNear(text: String, from: Int, target: Int, limit: Int, protected: List<IntRange>): Int {
        var best = -1
        var i = target
        while (i < limit) {
            if (isSentenceEnd(text, i) && protected.none { i in it } && !insideQuote(text, from, i)) {
                best = i + 1; break
            }
            i++
        }
        if (best < 0) {
            // No clean boundary forward; search backward, ignoring quotes but never citations.
            i = target
            while (i > from + TARGET_CHARS / 3) {
                if (isSentenceEnd(text, i) && protected.none { i in it }) { best = i + 1; break }
                i--
            }
        }
        if (best < 0) {
            // Last resort: a whitespace not inside a citation.
            i = target
            while (i < limit && (!text[i].isWhitespace() || protected.any { i in it })) i++
            best = i
        }
        return best.coerceIn(from + 1, limit)
    }

    internal fun isSentenceEnd(text: String, i: Int): Boolean {
        val c = text[i]
        if (c != '.' && c != '?' && c != '!') return false
        var j = i + 1
        while (j < text.length && text[j] in "\"”’')]") j++
        if (j >= text.length) return true
        if (!text[j].isWhitespace()) return false
        var k = j
        while (k < text.length && text[k].isWhitespace()) k++
        if (k >= text.length) return true
        val nextCh = text[k]
        if (!(nextCh.isUpperCase() || nextCh in "\"“(")) return false
        if (c == '.') {
            // Abbreviation check on the token before the period.
            var s = i - 1
            while (s >= 0 && !text[s].isWhitespace() && text[s] != '(') s--
            val token = text.substring(s + 1, i).lowercase().trimStart('(', '"', '“')
            if (token.length == 1 && token[0].isLetter()) return false // initials, "F." reporter
            if (token in ABBREVIATIONS) return false
            if (token.contains('.') && token.length <= 6) return false // "U.S", "N.E.2d"
            if (token.isNotEmpty() && token.all { it.isDigit() } && k < text.length && text[k].isDigit()) return false
        }
        return true
    }

    private fun insideQuote(text: String, from: Int, at: Int): Boolean {
        var open = 0
        var straight = 0
        for (idx in from until at) {
            when (text[idx]) {
                '“' -> open++
                '”' -> if (open > 0) open--
                '"' -> straight++
            }
        }
        return open > 0 || straight % 2 == 1
    }

    private fun overlapStart(text: String, end: Int, protected: List<IntRange>): Int {
        val floor = (end - OVERLAP_CHARS * 2).coerceAtLeast(0)
        var i = (end - OVERLAP_CHARS).coerceAtLeast(floor)
        while (i < end) {
            if (isSentenceEnd(text, i) && protected.none { i in it }) {
                var s = i + 1
                while (s < end && text[s].isWhitespace()) s++
                return s
            }
            i++
        }
        return -1
    }
}
