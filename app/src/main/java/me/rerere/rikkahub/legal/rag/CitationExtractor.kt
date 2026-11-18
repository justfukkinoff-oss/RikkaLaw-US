package me.rerere.rikkahub.legal.rag

/**
 * Eyecite-style legal citation extractor for U.S. sources, pure Kotlin (no network, no
 * Android dependency) so it is unit-testable on the JVM.
 *
 * It recognizes:
 *  - full case citations: `<volume> <reporter> <page>[, <pin>] [(<court> <year>)]`
 *    for Supreme Court, federal, regional and the common state reporters;
 *  - Westlaw / Lexis cites: `2020 WL 1234567`, `2019 U.S. Dist. LEXIS 12345`;
 *  - public-domain (neutral) cites: `2019 OK CR 12`, `2021 IL App (1st) 190123`, `2020 ND 45`;
 *  - federal statutes and regulations: `28 U.S.C. § 2254(d)(1)`, `21 C.F.R. § 1308.11`;
 *  - bare habeas sections (`§ 2254(d)`), Federal Rules (`Fed. R. Crim. P. 33`), and
 *    common state statute shapes (`Neb. Rev. Stat. § 29-3001`, `Okla. Stat. tit. 22, § 1080`);
 *  - short forms: `Id.`, `Id. at 694`, `supra`.
 *
 * Extraction is deliberately conservative: a citation must have a recognized reporter, so a
 * made-up reporter does not match. Matching never implies the authority exists — that is the
 * job of `citation_verify` against CourtListener.
 */
object CitationExtractor {

    enum class Kind { CASE, WESTLAW, LEXIS, NEUTRAL, STATUTE, REGULATION, RULE, SHORT_FORM }

    data class Citation(
        val raw: String,
        val kind: Kind,
        /** Canonical key used for grouping / lookup, e.g. `466 U.S. 668`, `28 U.S.C. § 2254`. */
        val key: String,
        val volume: String? = null,
        val reporter: String? = null,
        val page: String? = null,
        val pin: String? = null,
        val court: String? = null,
        val year: String? = null,
        val caseName: String? = null,
        val start: Int,
        val end: Int,
    )

    /** Canonical reporter spellings. Spaces are optional between tokens when matching. */
    val REPORTERS: List<String> = listOf(
        // U.S. Supreme Court
        "U.S.", "S. Ct.", "L. Ed.", "L. Ed. 2d", "U.S.L.W.",
        // Federal
        "F.", "F.2d", "F.3d", "F.4th", "F. Supp.", "F. Supp. 2d", "F. Supp. 3d",
        "F. App'x", "F. Appx.", "Fed. App'x", "Fed. Appx.", "B.R.", "F.R.D.", "Fed. Cl.",
        "Ct. Cl.", "M.J.", "C.M.A.", "C.M.R.", "Vet. App.", "T.C.", "Fed. R. Serv.",
        // Regional
        "A.", "A.2d", "A.3d", "N.E.", "N.E.2d", "N.E.3d", "N.W.", "N.W.2d", "P.", "P.2d",
        "P.3d", "S.E.", "S.E.2d", "S.W.", "S.W.2d", "S.W.3d", "So.", "So. 2d", "So. 3d",
        // State-specific (high volume in criminal / post-conviction work)
        "Cal.", "Cal. 2d", "Cal. 3d", "Cal. 4th", "Cal. 5th", "Cal. App.", "Cal. App. 2d",
        "Cal. App. 3d", "Cal. App. 4th", "Cal. App. 5th", "Cal. Rptr.", "Cal. Rptr. 2d",
        "Cal. Rptr. 3d", "N.Y.", "N.Y.2d", "N.Y.3d", "A.D.", "A.D.2d", "A.D.3d", "N.Y.S.",
        "N.Y.S.2d", "N.Y.S.3d", "Misc.", "Misc. 2d", "Misc. 3d", "Ill.", "Ill. 2d",
        "Ill. App.", "Ill. App. 2d", "Ill. App. 3d", "Ill. Dec.", "Ohio St.", "Ohio St. 2d",
        "Ohio St. 3d", "Ohio App.", "Ohio App. 2d", "Ohio App. 3d", "Pa.", "Pa. Super.",
        "Mass.", "Mass. App. Ct.", "Mich.", "Mich. App.", "Wash.", "Wash. 2d", "Wash. App.",
        "Tex. Crim.", "Neb.", "Neb. App.", "Okla. Crim.", "Iowa", "Wis. 2d", "Minn.",
        "Kan.", "Kan. App. 2d", "Mo.", "Ga.", "Ga. App.", "N.J.", "N.J. Super.", "Conn.",
        "Md.", "Md. App.", "Va.", "Va. App.", "N.C.", "N.C. App.", "S.C.", "Ariz.", "Colo.",
        "Or.", "Or. App.", "Haw.", "Utah", "Nev.", "N.M.", "Mont.", "Idaho", "Wyo.", "Alaska",
        "La.", "Miss.", "Ala.", "Ark.", "Tenn.", "Ky.", "W. Va.", "Vt.", "N.H.", "Me.", "R.I.",
        "Del.", "D.C.",
    )

    /** Neutral-citation court codes (`<year> <code> <number>`). */
    private val NEUTRAL_COURTS = listOf(
        "OK CR", "OK CIV APP", "OK", "ND", "SD", "WI App", "WI", "WY", "VT", "UT App", "UT",
        "PA Super", "OH", "NM", "NMCA", "NMSC", "MT", "ME", "MS", "LA", "IL App", "IL",
        "CO", "COA", "AR", "Ark. App.", "Ark.",
    )

    private fun reporterPattern(canonical: String): String {
        val compact = canonical.replace(" ", "")
        val sb = StringBuilder()
        compact.forEachIndexed { i, c ->
            when (c) {
                '.' -> sb.append("\\.\\s*")
                '\'' -> sb.append("['’]")
                else -> {
                    // Allow a space between a letter run and an ordinal like "2d"/"4th".
                    if (i > 0 && c.isDigit() && !compact[i - 1].isDigit() && compact[i - 1] != '.') {
                        sb.append("\\s*")
                    }
                    if (c.isLetterOrDigit()) sb.append(c) else sb.append("\\").append(c)
                }
            }
        }
        return sb.toString().removeSuffix("\\s*")
    }

    private val reporterAlternation: String = REPORTERS
        .sortedByDescending { it.replace(" ", "").length }
        .joinToString("|") { "(?:${reporterPattern(it)})" }

    private val reporterCanonicalByCompact: Map<String, String> =
        REPORTERS.associateBy { normalizeCompact(it) }

    private fun normalizeCompact(s: String): String =
        s.replace(Regex("\\s+"), "").replace('’', '\'').lowercase()

    // <vol> <reporter> <page>[, <pin>[-<pin>]] [(<court> <year>)]
    private val CASE_RE = Regex(
        "(?<![\\w.])(\\d{1,4})\\s+($reporterAlternation)\\s+(\\d{1,5}|_{2,})" +
            "(?:,\\s*(\\d{1,5}(?:\\s*[-–]\\s*\\d{1,5})?(?:\\s*(?:&|and)\\s*n\\.\\s*\\d+)?|n\\.\\s*\\d+)(?!\\d)(?!\\s+[A-Z]))?" +
            "(?:\\s*\\(([^()]{0,60}?)\\s*(\\d{4})\\))?"
    )

    private val WESTLAW_RE = Regex("(?<!\\w)((?:19|20)\\d{2})\\s+WL\\s+(\\d{3,9})(?:,\\s*at\\s*\\*\\s*(\\d+))?")
    private val LEXIS_RE = Regex(
        "(?<!\\w)((?:19|20)\\d{2})\\s+((?:U\\.S\\.\\s*(?:Dist\\.|App\\.)\\s*)?(?:[A-Z][A-Za-z.]*\\s+){0,3}LEXIS)\\s+(\\d{1,9})"
    )
    private val NEUTRAL_RE = Regex(
        "(?<!\\w)((?:19|20)\\d{2})\\s+(" +
            NEUTRAL_COURTS.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } +
            ")(?:\\s*\\((\\d(?:st|nd|rd|th))\\))?\\s+(\\d{1,7})(?:-[A-Z])?(?:,\\s*¶\\s*(\\d+))?(?!\\w)"
    )
    private val USC_RE = Regex(
        "(?<!\\w)(\\d{1,2})\\s+U\\.\\s*S\\.\\s*C\\.\\s*(?:A\\.\\s*)?(?:§§?|[Ss]ec(?:tion|s)?\\.?)\\s*" +
            "(\\d+[a-z]?(?:-\\d+)?)((?:\\([a-zA-Z0-9]{1,4}\\))*)"
    )
    private val CFR_RE = Regex(
        "(?<!\\w)(\\d{1,2})\\s+C\\.\\s*F\\.\\s*R\\.\\s*(?:§§?|[Pp]art)?\\s*(\\d+(?:\\.\\d+)?)((?:\\([a-zA-Z0-9]{1,4}\\))*)"
    )
    // Bare "§ 2254(d)(1)" style references to the core federal habeas / post-conviction sections.
    private val HABEAS_SECTION_RE = Regex(
        "(?<![\\w.])(?:§§?|[Ss]ection)\\s*(2241|2242|2243|2244|2253|2254|2255)((?:\\([a-zA-Z0-9]{1,4}\\))*)"
    )
    private val FED_RULE_RE = Regex(
        "(?<!\\w)Fed\\.\\s*R\\.\\s*(Crim|Civ|Evid|App|Bankr)\\.\\s*(?:P\\.\\s*)?(\\d{1,4}(?:\\.\\d)?)((?:\\([a-zA-Z0-9]{1,4}\\))*)"
    )
    private val HABEAS_RULE_RE = Regex(
        "(?<!\\w)Rules?\\s+Governing\\s+(?:Section\\s+)?(2254|2255)\\s+(?:Cases|Proceedings)"
    )
    private val STATE_STATUTE_RE = Regex(
        "(?<!\\w)((?:[A-Z][a-z]{1,10}\\.\\s*){1,3}(?:Rev\\.\\s*)?(?:Stat|Code|Gen\\.\\s*Laws|Comp\\.\\s*Laws)\\.?(?:\\s*Ann\\.)?" +
            "(?:\\s*tit\\.\\s*\\d+,?)?)\\s*§§?\\s*([\\d][\\d\\-.:]*[\\da-z])((?:\\([a-zA-Z0-9]{1,4}\\))*)"
    )
    private val ID_RE = Regex("(?<![\\w.])(?:I|i)d\\.(?:\\s+at\\s+(\\d{1,5}(?:\\s*[-–]\\s*\\d{1,5})?))?")
    private val SUPRA_RE = Regex("(?<!\\w)([A-Z][A-Za-z'’\\-]{1,40}),\\s+supra(?:,\\s*at\\s+(\\d{1,5}))?")

    private val CASE_NAME_RE = Regex(
        "((?:In\\s+re|Ex\\s+parte|Matter\\s+of)\\s+[A-Z][\\w.'’&\\-]*(?:\\s+[A-Z&][\\w.'’&\\-]*){0,5}" +
            "|[A-Z][\\w.'’&\\-]*(?:\\s+(?:of|the|and|&|[A-Z][\\w.'’&\\-]*)){0,6}\\s+v\\.\\s+" +
            "[A-Z][\\w.'’&\\-]*(?:\\s+(?:of|the|and|&|[A-Z][\\w.'’&\\-]*)){0,6}),?\\s*$"
    )

    fun extract(text: String, includeShortForms: Boolean = true): List<Citation> {
        val out = ArrayList<Citation>()
        val taken = ArrayList<IntRange>()
        fun free(r: IntRange) = taken.none { it.first <= r.last && r.first <= it.last }
        fun add(c: Citation) {
            val r = c.start until c.end
            if (free(r)) {
                taken += r
                out += c
            }
        }

        CASE_RE.findAll(text).forEach { m ->
            val volume = m.groupValues[1]
            val reporterRaw = m.groupValues[2]
            val reporter = reporterCanonicalByCompact[normalizeCompact(reporterRaw)] ?: reporterRaw.trim()
            val page = m.groupValues[3]
            val pin = m.groupValues[4].ifBlank { null }
            val courtRaw = m.groupValues[5].trim().ifBlank { null }
            val year = m.groupValues[6].ifBlank { null }
            // Reject years in the volume slot of things like "2004 Cal. ..." only when followed
            // by nothing that looks like a page; the regex already requires a page.
            add(
                Citation(
                    raw = m.value.trim(),
                    kind = Kind.CASE,
                    key = "$volume $reporter $page",
                    volume = volume,
                    reporter = reporter,
                    page = page,
                    pin = pin,
                    court = courtRaw,
                    year = year,
                    caseName = caseNameBefore(text, m.range.first),
                    start = m.range.first,
                    end = m.range.last + 1,
                ),
            )
        }
        WESTLAW_RE.findAll(text).forEach { m ->
            add(
                Citation(
                    raw = m.value, kind = Kind.WESTLAW, key = "${m.groupValues[1]} WL ${m.groupValues[2]}",
                    year = m.groupValues[1], page = m.groupValues[2], pin = m.groupValues[3].ifBlank { null },
                    caseName = caseNameBefore(text, m.range.first),
                    start = m.range.first, end = m.range.last + 1,
                ),
            )
        }
        LEXIS_RE.findAll(text).forEach { m ->
            val db = m.groupValues[2].replace(Regex("\\s+"), " ").trim()
            add(
                Citation(
                    raw = m.value, kind = Kind.LEXIS, key = "${m.groupValues[1]} $db ${m.groupValues[3]}",
                    year = m.groupValues[1], reporter = db, page = m.groupValues[3],
                    caseName = caseNameBefore(text, m.range.first),
                    start = m.range.first, end = m.range.last + 1,
                ),
            )
        }
        NEUTRAL_RE.findAll(text).forEach { m ->
            val district = m.groupValues[3].ifBlank { null }
            val court = m.groupValues[2] + (district?.let { " ($it)" } ?: "")
            add(
                Citation(
                    raw = m.value, kind = Kind.NEUTRAL, key = "${m.groupValues[1]} $court ${m.groupValues[4]}",
                    year = m.groupValues[1], court = court, page = m.groupValues[4],
                    pin = m.groupValues[5].ifBlank { null }?.let { "¶ $it" },
                    caseName = caseNameBefore(text, m.range.first),
                    start = m.range.first, end = m.range.last + 1,
                ),
            )
        }
        USC_RE.findAll(text).forEach { m ->
            add(
                Citation(
                    raw = m.value, kind = Kind.STATUTE, key = "${m.groupValues[1]} U.S.C. § ${m.groupValues[2]}",
                    volume = m.groupValues[1], reporter = "U.S.C.", page = m.groupValues[2],
                    pin = m.groupValues[3].ifBlank { null },
                    start = m.range.first, end = m.range.last + 1,
                ),
            )
        }
        CFR_RE.findAll(text).forEach { m ->
            add(
                Citation(
                    raw = m.value, kind = Kind.REGULATION, key = "${m.groupValues[1]} C.F.R. § ${m.groupValues[2]}",
                    volume = m.groupValues[1], reporter = "C.F.R.", page = m.groupValues[2],
                    pin = m.groupValues[3].ifBlank { null },
                    start = m.range.first, end = m.range.last + 1,
                ),
            )
        }
        FED_RULE_RE.findAll(text).forEach { m ->
            val body = m.groupValues[1]
            val label = if (body == "Evid") "Fed. R. Evid." else "Fed. R. $body. P."
            add(
                Citation(
                    raw = m.value, kind = Kind.RULE, key = "$label ${m.groupValues[2]}",
                    reporter = label, page = m.groupValues[2], pin = m.groupValues[3].ifBlank { null },
                    start = m.range.first, end = m.range.last + 1,
                ),
            )
        }
        HABEAS_RULE_RE.findAll(text).forEach { m ->
            add(
                Citation(
                    raw = m.value, kind = Kind.RULE, key = "Rules Governing § ${m.groupValues[1]} Cases",
                    start = m.range.first, end = m.range.last + 1,
                ),
            )
        }
        STATE_STATUTE_RE.findAll(text).forEach { m ->
            val code = m.groupValues[1].replace(Regex("\\s+"), " ").trim().trimEnd(',')
            if (code.startsWith("U.S.") || code.contains("C.F.R")) return@forEach
            add(
                Citation(
                    raw = m.value, kind = Kind.STATUTE, key = "$code § ${m.groupValues[2]}",
                    reporter = code, page = m.groupValues[2], pin = m.groupValues[3].ifBlank { null },
                    start = m.range.first, end = m.range.last + 1,
                ),
            )
        }
        HABEAS_SECTION_RE.findAll(text).forEach { m ->
            add(
                Citation(
                    raw = m.value, kind = Kind.STATUTE, key = "28 U.S.C. § ${m.groupValues[1]}",
                    volume = "28", reporter = "U.S.C.", page = m.groupValues[1],
                    pin = m.groupValues[2].ifBlank { null },
                    start = m.range.first, end = m.range.last + 1,
                ),
            )
        }
        if (includeShortForms) {
            ID_RE.findAll(text).forEach { m ->
                add(
                    Citation(
                        raw = m.value, kind = Kind.SHORT_FORM, key = "Id.",
                        pin = m.groupValues[1].ifBlank { null },
                        start = m.range.first, end = m.range.last + 1,
                    ),
                )
            }
            SUPRA_RE.findAll(text).forEach { m ->
                add(
                    Citation(
                        raw = m.value, kind = Kind.SHORT_FORM, key = "${m.groupValues[1]}, supra",
                        caseName = m.groupValues[1], pin = m.groupValues[2].ifBlank { null },
                        start = m.range.first, end = m.range.last + 1,
                    ),
                )
            }
        }
        return out.sortedBy { it.start }
    }

    /** Character ranges covered by citations; used by the chunker to avoid splitting them. */
    fun protectedRanges(text: String): List<IntRange> =
        extract(text, includeShortForms = false).map { c ->
            val nameStart = c.caseName?.let { name ->
                val idx = text.lastIndexOf(name, c.start)
                if (idx >= 0 && c.start - idx < 200) idx else c.start
            } ?: c.start
            nameStart until c.end
        }

    private fun caseNameBefore(text: String, start: Int): String? {
        val from = (start - 160).coerceAtLeast(0)
        val window = text.substring(from, start)
        // Only the current sentence / clause.
        val cut = maxOf(window.lastIndexOf(';'), window.lastIndexOf('\n'), window.lastIndexOf(" See "), window.lastIndexOf("See, e.g.,"))
        val clause = if (cut >= 0) window.substring(cut + 1) else window
        val m = CASE_NAME_RE.find(clause) ?: return null
        var name = m.groupValues[1].trim()
        while (true) {
            val first = name.substringBefore(' ')
            if (first.trimEnd(',', '.').lowercase() in SIGNAL_WORDS && name.contains(' ')) {
                name = name.substringAfter(' ').trim()
            } else break
        }
        return name.trimEnd(',')
            .takeIf { it.length in 4..140 }
    }

    private val SIGNAL_WORDS = setOf(
        "see", "cf", "accord", "under", "in", "but", "and", "also", "compare", "contra", "e.g",
        "citing", "quoting", "following", "applying", "per", "as", "with", "by", "under", "unlike",
        "like", "following", "after", "before", "because", "since", "while", "although", "thus",
        "here", "there", "then", "when", "where", "only", "even", "that", "this", "the",
    )

    /** Detect citations in a free-text query, e.g. to route `legal_search` to the citation index. */
    fun keysIn(query: String): List<String> = extract(query, includeShortForms = false).map { it.key }.distinct()
}
