package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.legal.rag.LegalIndex
import me.rerere.rikkahub.legal.rag.LegalIngestor
import me.rerere.rikkahub.legal.rag.LegalRetriever
import me.rerere.rikkahub.legal.rag.SearchMode
import java.io.File

/*
 * Legal record tools: the agent's window into the on-device case-record index (Projects).
 * Everything here runs locally except embedding / rerank calls to the user's chosen models.
 * Results always carry document title + page so counsel can check every proposition.
 */

private val legalJson = Json { encodeDefaults = false; prettyPrint = false }
private const val READ_MAX_CHARS = 40_000
private const val HIT_TEXT_CHARS = 1_800

private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull
private fun JsonObject.long(k: String) = this[k]?.jsonPrimitive?.longOrNull

private fun prop(type: String, description: String) = buildJsonObject {
    put("type", type); put("description", description)
}

/** Resolve the `project` argument (id or name). Null/blank = every project. */
private fun scope(index: LegalIndex, raw: String?): Result<List<String>?> {
    if (raw == null || raw.equals("all", true)) return Result.success(null)
    val p = index.resolveProject(raw) ?: return Result.failure(IllegalArgumentException(
        "No project matches '$raw'. Call legal_project_list to see project names."
    ))
    return Result.success(listOf(p.id))
}

fun legalProjectListTool(index: LegalIndex): Tool = Tool(
    name = "legal_project_list",
    description = "List the user's legal Projects (case files) and the documents indexed in each: " +
        "document id, title, page count, detected type (transcript, opinion, habeas_petition, police_report …), " +
        "index status (needs_ocr = scanned pages without text) and embedding status. Call this first to learn " +
        "what record is available before searching.",
    // Gives the model a live inventory of the case files so it scopes searches to the right
    // project without an extra round-trip. Kept short: names, ids and counts only.
    systemPrompt = { _, _ ->
        runCatching {
            val projects = index.listProjects()
            if (projects.isEmpty()) {
                "Case record: no Projects exist yet. If the user refers to case documents, ask them to upload " +
                    "the record in Projects (drawer → Projects) or use legal_project_create + legal_document_ingest."
            } else buildString {
                appendLine("Case record available on this device (pass the project name or id as `project` to scope legal_* tools):")
                projects.take(25).forEach { p ->
                    val docs = index.listDocuments(p.id)
                    append("- ").append(p.name).append(" [id ").append(p.id).append("]: ")
                    append(docs.size).append(" document(s), ").append(docs.sumOf { it.pageCount }).append(" pages")
                    val ocr = docs.count { it.status == "needs_ocr" }
                    if (ocr > 0) append(", ").append(ocr).append(" need OCR")
                    appendLine()
                }
                if (projects.size > 25) appendLine("- … ${projects.size - 25} more (legal_project_list)")
            }.trim()
        }.getOrDefault("")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("project", prop("string", "Optional project id or name to list only that project's documents"))
            },
        )
    },
    execute = { input ->
        val only = input.jsonObject.str("project")
        val projects = index.listProjects().filter { only == null || it.id == only || it.name.contains(only, true) }
        fmTextPart(buildJsonObject {
            putJsonArray("projects") {
                projects.forEach { p ->
                    add(buildJsonObject {
                        put("id", p.id); put("name", p.name)
                        if (p.description.isNotBlank()) put("description", p.description)
                        if (p.jurisdiction.isNotBlank()) put("jurisdiction", p.jurisdiction)
                        put("auto_embed", p.autoEmbed)
                        put("chunks", p.chunkCount); put("embedded_chunks", p.embeddedCount)
                        putJsonArray("documents") {
                            index.listDocuments(p.id).forEach { d ->
                                add(buildJsonObject {
                                    put("doc_id", d.id); put("title", d.title); put("pages", d.pageCount)
                                    if (d.docType.isNotBlank()) put("type", d.docType)
                                    put("status", d.status); put("embedding", d.embedStatus)
                                    if (d.emptyPages > 0) put("pages_without_text", d.emptyPages)
                                    d.error?.let { put("note", it) }
                                })
                            }
                        }
                    })
                }
            }
            if (projects.isEmpty()) put("hint", "No projects yet. The user can create one in Projects, or call legal_project_create.")
        }.toString())
    },
)

fun legalProjectCreateTool(ingestor: LegalIngestor): Tool = Tool(
    name = "legal_project_create",
    description = "Create a new legal Project (case file) to hold record documents, e.g. 'State v. Doe — 2254'.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", prop("string", "Short case name"))
                put("description", prop("string", "Optional: court, case number, posture"))
                put("jurisdiction", prop("string", "Optional: e.g. 'Okla. / 10th Cir.'"))
                put("auto_embed", prop("boolean", "Embed uploads automatically (default true)"))
            },
            required = listOf("name"),
        )
    },
    execute = { input ->
        val o = input.jsonObject
        val name = o.str("name") ?: return@Tool fmTextPart(fmErrEnvelope("missing_name", "name is required"))
        val p = ingestor.createProject(
            name, o.str("description").orEmpty(), o.str("jurisdiction").orEmpty(),
            o["auto_embed"]?.jsonPrimitive?.booleanOrNull ?: true,
        )
        fmTextPart(buildJsonObject { put("id", p.id); put("name", p.name); put("created", true) }.toString())
    },
)

fun legalDocumentIngestTool(index: LegalIndex, ingestor: LegalIngestor): Tool = Tool(
    name = "legal_document_ingest",
    description = "Add a file already on the device (PDF, DOCX, TXT, MD, HTML, EPUB) to a Project's index — e.g. an " +
        "opinion downloaded into the workspace. The file is copied, parsed page-by-page, chunked, citation-indexed, " +
        "and embedded in the background if the project has auto-embed on.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("project", prop("string", "Project id or name"))
                put("path", prop("string", "Absolute path of the file on the device"))
                put("title", prop("string", "Optional display title (defaults to file name)"))
                put("doc_type", prop("string", "Optional type: transcript, opinion, brief, motion, habeas_petition, police_report, forensic_report, affidavit, docket"))
            },
            required = listOf("project", "path"),
        )
    },
    execute = { input ->
        val o = input.jsonObject
        val project = o.str("project")?.let { index.resolveProject(it) }
            ?: return@Tool fmTextPart(fmErrEnvelope("unknown_project", "Project not found; call legal_project_list"))
        val file = File(o.str("path") ?: return@Tool fmTextPart(fmErrEnvelope("missing_path", "path is required")))
        if (!file.isFile) return@Tool fmTextPart(fmErrEnvelope("not_found", "No file at ${file.path}"))
        runCatching { ingestor.ingestFile(project.id, file, o.str("title"), o.str("doc_type").orEmpty()) }
            .fold(
                onSuccess = { r ->
                    fmTextPart(buildJsonObject {
                        put("doc_id", r.document.id); put("title", r.document.title)
                        put("duplicate", r.duplicate); put("pages", r.document.pageCount)
                        put("chunks", r.chunks); put("citations_found", r.citations)
                        put("status", r.document.status)
                        if (r.document.emptyPages > 0) put("pages_without_text", r.document.emptyPages)
                    }.toString())
                },
                onFailure = { fmTextPart(fmErrEnvelope("ingest_failed", it.message ?: it.toString())) },
            )
    },
)

fun legalSearchTool(index: LegalIndex, retriever: LegalRetriever): Tool = Tool(
    name = "legal_search",
    description = "Search the indexed case record (Projects). Modes: hybrid (default: BM25 keyword + exact phrase + " +
        "citation + semantic, fused and reranked), keyword, exact (verbatim substring — use for quotes, names, " +
        "exhibit numbers, '§ 2254(d)'), semantic (meaning), citation (find every place a case/statute is cited). " +
        "Put phrases that must match exactly in double quotes inside the query. Every hit returns document, " +
        "page range and section heading — cite them as (Doc title at p. N). Results are retrieval only, not findings.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", prop("string", "What to find. Quote exact phrases: \"did not object\" ineffective counsel"))
                put("project", prop("string", "Project id or name; omit to search every project"))
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { SearchMode.entries.forEach { add(it.name.lowercase()) } })
                    put("description", "Retrieval mode (default hybrid)")
                })
                put("doc_ids", buildJsonObject {
                    put("type", "array"); put("items", buildJsonObject { put("type", "integer") })
                    put("description", "Optional: restrict to these document ids")
                })
                put("top_k", prop("integer", "Number of passages (default 8, max 25)"))
            },
            required = listOf("query"),
        )
    },
    execute = { input ->
        val o = input.jsonObject
        val q = o.str("query") ?: return@Tool fmTextPart(fmErrEnvelope("missing_query", "query is required"))
        val projectIds = scope(index, o.str("project")).getOrElse {
            return@Tool fmTextPart(fmErrEnvelope("unknown_project", it.message!!))
        }
        val docIds = o["doc_ids"]?.let { el -> runCatching { el.jsonArray.mapNotNull { it.jsonPrimitive.longOrNull } }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
        val topK = (o.int("top_k") ?: 8).coerceIn(1, 25)
        val out = retriever.search(LegalRetriever.Query(q, SearchMode.parse(o.str("mode")), projectIds, docIds, topK))
        fmTextPart(buildJsonObject {
            put("query", q)
            putJsonArray("results") {
                out.hits.forEachIndexed { i, h ->
                    add(buildJsonObject {
                        put("rank", i + 1)
                        put("document", h.document); put("doc_id", h.docId)
                        put("pages", if (h.pageStart == h.pageEnd) "${h.pageStart}" else "${h.pageStart}-${h.pageEnd}")
                        if (h.heading.isNotBlank()) put("section", h.heading)
                        if (h.docType.isNotBlank()) put("type", h.docType)
                        put("matched_by", buildJsonArray { h.matchedBy.forEach { add(it) } })
                        put("snippet", h.snippet)
                        put("text", h.text.take(HIT_TEXT_CHARS))
                    })
                }
            }
            if (out.hits.isEmpty()) put("hint", "No passages found. Try keyword mode with fewer terms, or check legal_project_list.")
            if (out.notes.isNotEmpty()) putJsonArray("notes") { out.notes.forEach { add(it) } }
        }.toString())
    },
)

fun legalQuoteCheckTool(index: LegalIndex, retriever: LegalRetriever): Tool = Tool(
    name = "legal_quote_check",
    description = "Verify a quotation against the indexed record BEFORE using it in any draft. Returns verbatim " +
        "(with exact page), close (shows what the source actually says and similarity), or not_found. " +
        "Ellipses and [bracketed alterations] are treated as gaps. Never present a quote as record text unless " +
        "this returns verbatim.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("quote", prop("string", "The quotation exactly as you intend to use it"))
                put("project", prop("string", "Project id or name; omit for all"))
                put("doc_id", prop("integer", "Optional: the document the quote is attributed to"))
            },
            required = listOf("quote"),
        )
    },
    execute = { input ->
        val o = input.jsonObject
        val quote = o.str("quote") ?: return@Tool fmTextPart(fmErrEnvelope("missing_quote", "quote is required"))
        val projectIds = scope(index, o.str("project")).getOrElse {
            return@Tool fmTextPart(fmErrEnvelope("unknown_project", it.message!!))
        }
        val r = retriever.checkQuote(quote, projectIds, o.long("doc_id")?.let { listOf(it) })
        fmTextPart(legalJson.encodeToString(me.rerere.rikkahub.legal.rag.QuoteCheck.serializer(), r))
    },
)

fun legalDocumentReadTool(index: LegalIndex): Tool = Tool(
    name = "legal_document_read",
    description = "Read the full text of an indexed document by page range, with [Page N] markers. Use after " +
        "legal_search to read surrounding context before characterizing testimony or holdings. " +
        "Up to 40,000 characters per call; page through long transcripts.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("doc_id", prop("integer", "Document id from legal_project_list or legal_search"))
                put("page_from", prop("integer", "First page (default 1)"))
                put("page_to", prop("integer", "Last page (default page_from + 9)"))
            },
            required = listOf("doc_id"),
        )
    },
    execute = { input ->
        val o = input.jsonObject
        val docId = o.long("doc_id") ?: return@Tool fmTextPart(fmErrEnvelope("missing_doc_id", "doc_id is required"))
        val doc = index.getDocument(docId) ?: return@Tool fmTextPart(fmErrEnvelope("not_found", "No document $docId"))
        val ft = index.fullText(docId) ?: return@Tool fmTextPart(fmErrEnvelope("not_found", "No text for $docId"))
        val nums = ft.prepared.pageNumbers
        val from = (o.int("page_from") ?: 1).coerceAtLeast(1)
        val to = (o.int("page_to") ?: (from + 9)).coerceAtLeast(from)
        val sb = StringBuilder()
        var lastPage = from
        var truncated = false
        for (i in nums.indices) {
            val n = nums[i]
            if (n < from || n > to) continue
            val s = ft.prepared.pageStarts[i]
            val e = if (i + 1 < nums.size) ft.prepared.pageStarts[i + 1] else ft.text.length
            val pageText = ft.text.substring(s.coerceIn(0, ft.text.length), e.coerceIn(s, ft.text.length)).trim()
            val block = "[Page $n]\n${pageText.ifEmpty { "(no text layer — scanned page; OCR needed)" }}\n\n"
            if (sb.length + block.length > READ_MAX_CHARS) {
                if (sb.isEmpty()) sb.append(block.take(READ_MAX_CHARS))
                truncated = true; break
            }
            sb.append(block); lastPage = n
        }
        fmTextPart(buildJsonObject {
            put("document", doc.title); put("doc_id", docId); put("page_count", doc.pageCount)
            put("pages_returned", "$from-$lastPage")
            if (truncated) put("next_page_from", lastPage + 1)
            put("text", sb.toString().ifEmpty { "(no pages in range $from-$to; document has ${doc.pageCount} pages)" })
        }.toString())
    },
)

fun legalAuthoritiesTool(index: LegalIndex): Tool = Tool(
    name = "legal_authorities",
    description = "Table of authorities for a Project or document: every case, statute, rule and regulation " +
        "citation found in the indexed record, grouped and counted, with the pages where each appears. " +
        "These are citations AS WRITTEN in the record — verify each with citation_verify before relying on it.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("project", prop("string", "Project id or name; omit for all"))
                put("doc_id", prop("integer", "Optional: one document only"))
                put("kind", prop("string", "Optional filter: case, statute, regulation, rule"))
            },
        )
    },
    execute = { input ->
        val o = input.jsonObject
        val projectIds = scope(index, o.str("project")).getOrElse {
            return@Tool fmTextPart(fmErrEnvelope("unknown_project", it.message!!))
        }
        val kind = o.str("kind")?.uppercase()
        val rows = index.citationsForScope(projectIds, o.long("doc_id")?.let { listOf(it) })
            .filter { it.kind != "SHORT_FORM" }
            .filter { kind == null || it.kind == kind || (kind == "CASE" && it.kind in setOf("WESTLAW", "LEXIS", "NEUTRAL")) }
        val chunkPages = index.chunks(rows.mapNotNull { it.chunkId }.toSet())
        val titles = HashMap<Long, String>()
        val grouped = rows.groupBy { it.key }.entries.sortedWith(compareBy({ it.value.first().kind }, { it.key }))
        fmTextPart(buildJsonObject {
            put("count", grouped.size)
            putJsonArray("authorities") {
                grouped.forEach { (key, list) ->
                    val first = list.first()
                    add(buildJsonObject {
                        put("kind", first.kind.lowercase())
                        put("citation", key)
                        list.firstNotNullOfOrNull { it.caseName }?.let { put("case_name", it) }
                        list.firstNotNullOfOrNull { it.year }?.let { put("year", it) }
                        list.firstNotNullOfOrNull { it.court }?.let { put("court", it) }
                        put("occurrences", list.size)
                        putJsonObject("appears_in") {
                            list.groupBy { it.docId }.forEach { (docId, l) ->
                                val title = titles.getOrPut(docId) { index.getDocument(docId)?.title ?: "doc $docId" }
                                val pages = l.mapNotNull { r -> r.chunkId?.let { chunkPages[it]?.pageStart } }.distinct().sorted()
                                put(title, pages.joinToString(", "))
                            }
                        }
                    })
                }
            }
        }.toString())
    },
)

fun legalDocumentOcrTool(index: LegalIndex, ocr: me.rerere.rikkahub.legal.rag.LegalOcr): Tool = Tool(
    name = "legal_document_ocr",
    description = "Run OCR on the scanned pages (pages with no text layer) of an indexed PDF or image using the " +
        "user's OCR vision model, then re-index the document. Use when legal_project_list shows needs_ocr or " +
        "pages_without_text. Page numbers are preserved. OCR text may contain recognition errors — quote it " +
        "only after legal_quote_check and tell counsel the passage came from OCR.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { put("doc_id", prop("integer", "Document id from legal_project_list")) },
            required = listOf("doc_id"),
        )
    },
    execute = { input ->
        val id = input.jsonObject.long("doc_id") ?: return@Tool fmTextPart(fmErrEnvelope("missing_doc_id", "doc_id is required"))
        val doc = index.getDocument(id) ?: return@Tool fmTextPart(fmErrEnvelope("not_found", "No document $id"))
        if (!ocr.isConfigured()) return@Tool fmTextPart(fmErrEnvelope("no_ocr_model", "No OCR model selected; ask the user to pick one in Settings → Models → OCR"))
        runCatching { ocr.ocrDocument(id) }.fold(
            onSuccess = { n ->
                val after = index.getDocument(id)
                fmTextPart(buildJsonObject {
                    put("doc_id", id); put("title", doc.title); put("pages_ocred", n)
                    after?.let { put("status", it.status); put("pages_still_without_text", it.emptyPages) }
                }.toString())
            },
            onFailure = { fmTextPart(fmErrEnvelope("ocr_failed", it.message ?: it.toString())) },
        )
    },
)
