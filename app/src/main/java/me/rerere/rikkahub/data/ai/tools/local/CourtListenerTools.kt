package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.net.withEgressGuard
import me.rerere.rikkahub.data.secrets.AgentSecretsStore
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/*
 * Primary-law lookup: CourtListener (case law, opinions, RECAP dockets) and the official
 * U.S. Code (uscode.house.gov). Results always carry a source URL so counsel can check them.
 */

private const val CL_BASE = "https://www.courtlistener.com"
private const val OPINION_MAX_CHARS = 60_000
private val clJson = Json { ignoreUnknownKeys = true }

private fun JsonObject.sv(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
private fun JsonElement?.strOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun prm(type: String, d: String) = buildJsonObject { put("type", type); put("description", d) }

/** Token from the vault (default name COURTLISTENER_TOKEN). Search works without one, at lower limits. */
private fun clToken(secrets: AgentSecretsStore, ref: String?): String? =
    runCatching { secrets.get(ref?.takeIf { it.isNotBlank() } ?: COURTLISTENER_TOKEN_HINT_NAME) }.getOrNull()?.takeIf { it.isNotBlank() }

private suspend fun clGet(client: OkHttpClient, url: String, token: String?): Pair<Int, String> = withContext(Dispatchers.IO) {
    val req = Request.Builder().url(url).header("Accept", "application/json").apply {
        if (token != null) header("Authorization", "Token $token")
    }.build()
    client.withEgressGuard().newCall(req).execute().use { it.code to it.body.string() }
}

private fun httpError(code: Int, body: String): String = fmErrEnvelope(
    when (code) { 401, 403 -> "auth_required"; 429 -> "rate_limited"; 404 -> "not_found"; else -> "http_$code" },
    when (code) {
        401, 403 -> "CourtListener needs an API token for this call. Ask the user to store one with request_secret " +
            "(name=$COURTLISTENER_TOKEN_HINT_NAME); free at https://www.courtlistener.com/profile/signups/"
        429 -> "Rate limited by CourtListener; wait and retry with fewer calls."
        else -> body.take(300)
    },
)

private fun tokenSecretProp() = prm("string", "Optional vault name of the CourtListener token (default $COURTLISTENER_TOKEN_HINT_NAME)")

fun caseLawSearchTool(client: OkHttpClient, secrets: AgentSecretsStore): Tool = Tool(
    name = "case_law_search",
    description = "Search U.S. case law (CourtListener, ~all published and many unpublished opinions) or federal " +
        "dockets (RECAP). Supports CourtListener query syntax: \"exact phrase\", AND/OR/NOT, proximity \"a b\"~10, " +
        "fields like caseName:(Strickland) or citation:(466 U.S. 668). Filter by court ids (e.g. 'scotus', 'ca8', " +
        "'ca10', 'ned', 'okcrimapp', 'neb') and dates. Returns case name, citations, court, date filed, cluster_id, " +
        "snippet and URL. Treat results as leads: read the opinion with case_law_opinion before relying on it.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", prm("string", "Search query"))
                put("type", prm("string", "o = opinions (default), r = federal dockets with filings"))
                put("court", prm("string", "Space-separated CourtListener court ids, e.g. 'scotus ca8'"))
                put("filed_after", prm("string", "YYYY-MM-DD"))
                put("filed_before", prm("string", "YYYY-MM-DD"))
                put("order_by", prm("string", "'score desc' (default), 'dateFiled desc', 'citeCount desc'"))
                put("limit", prm("integer", "Max results to return (default 10, max 20)"))
                put("token_secret", tokenSecretProp())
            },
            required = listOf("query"),
        )
    },
    execute = { input ->
        val o = input.jsonObject
        val q = o.sv("query") ?: return@Tool fmTextPart(fmErrEnvelope("missing_query", "query is required"))
        val type = o.sv("type")?.takeIf { it in setOf("o", "r", "d", "rd") } ?: "o"
        val limit = (o["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 20)
        val url = "$CL_BASE/api/rest/v4/search/".toHttpUrl().newBuilder().apply {
            addQueryParameter("q", q); addQueryParameter("type", type); addQueryParameter("highlight", "on")
            o.sv("court")?.let { addQueryParameter("court", it) }
            o.sv("filed_after")?.let { addQueryParameter("filed_after", it) }
            o.sv("filed_before")?.let { addQueryParameter("filed_before", it) }
            addQueryParameter("order_by", o.sv("order_by") ?: "score desc")
        }.build().toString()
        val (code, body) = runCatching { clGet(client, url, clToken(secrets, o.sv("token_secret"))) }
            .getOrElse { return@Tool fmTextPart(fmErrEnvelope("network_error", it.message ?: it.toString())) }
        if (code !in 200..299) return@Tool fmTextPart(httpError(code, body))
        val root = runCatching { clJson.parseToJsonElement(body).jsonObject }
            .getOrElse { return@Tool fmTextPart(fmErrEnvelope("bad_response", body.take(200))) }
        val strip = { s: String? -> s?.let { Jsoup.parse(it).text() }.orEmpty() }
        fmTextPart(buildJsonObject {
            put("count", root["count"].strOrNull() ?: "")
            putJsonArray("results") {
                root["results"]?.jsonArray?.take(limit)?.forEach { el ->
                    val r = el.jsonObject
                    add(buildJsonObject {
                        put("case_name", r.sv("caseName") ?: r.sv("case_name") ?: "")
                        r["citation"]?.let { c -> runCatching { putJsonArray("citations") { c.jsonArray.forEach { add(it.strOrNull()) } } } }
                        r.sv("court")?.let { put("court", it) }
                        r.sv("court_id")?.let { put("court_id", it) }
                        (r.sv("dateFiled") ?: r.sv("dateArgued"))?.let { put("date_filed", it) }
                        r.sv("docketNumber")?.let { put("docket_number", it) }
                        r["cluster_id"]?.jsonPrimitive?.longOrNull?.let { put("cluster_id", it) }
                        r["docket_id"]?.jsonPrimitive?.longOrNull?.let { put("docket_id", it) }
                        r.sv("status")?.let { put("status", it) }
                        r["citeCount"]?.jsonPrimitive?.intOrNull?.let { put("cited_by", it) }
                        val snippet = r["opinions"]?.jsonArray?.firstOrNull()?.jsonObject?.sv("snippet")
                            ?: r["recap_documents"]?.jsonArray?.firstOrNull()?.jsonObject?.sv("snippet")
                            ?: r.sv("snippet")
                        if (snippet != null) put("snippet", strip(snippet).take(600))
                        r.sv("absolute_url")?.let { put("url", CL_BASE + it) }
                    })
                }
            }
            put("note", "Search hits are leads, not verified authority. Read the opinion and run citation_verify before citing.")
        }.toString())
    },
)

fun caseLawOpinionTool(client: OkHttpClient, secrets: AgentSecretsStore): Tool = Tool(
    name = "case_law_opinion",
    description = "Fetch an opinion's full text from CourtListener by cluster_id (from case_law_search or " +
        "citation_verify). Returns case name, citations, court, date, judges, precedential status and the " +
        "opinion text (majority first; concurrences/dissents labeled). Use offset to page through long opinions. " +
        "Quote only from this text and give pinpoint pages when the text shows star pagination (*123).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("cluster_id", prm("integer", "CourtListener cluster id"))
                put("offset", prm("integer", "Character offset into the combined opinion text (default 0)"))
                put("token_secret", tokenSecretProp())
            },
            required = listOf("cluster_id"),
        )
    },
    execute = { input ->
        val o = input.jsonObject
        val id = o["cluster_id"]?.jsonPrimitive?.longOrNull
            ?: return@Tool fmTextPart(fmErrEnvelope("missing_cluster_id", "cluster_id is required"))
        val offset = (o["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
        val token = clToken(secrets, o.sv("token_secret"))
        val (code, body) = runCatching { clGet(client, "$CL_BASE/api/rest/v4/clusters/$id/", token) }
            .getOrElse { return@Tool fmTextPart(fmErrEnvelope("network_error", it.message ?: it.toString())) }
        if (code !in 200..299) return@Tool fmTextPart(httpError(code, body))
        val cluster = clJson.parseToJsonElement(body).jsonObject
        val text = StringBuilder()
        cluster["sub_opinions"]?.jsonArray?.forEach { u ->
            val url = u.strOrNull() ?: return@forEach
            val (c2, b2) = runCatching { clGet(client, url, token) }.getOrNull() ?: return@forEach
            if (c2 !in 200..299) return@forEach
            val op = clJson.parseToJsonElement(b2).jsonObject
            val kind = op.sv("type").orEmpty()
            val label = when {
                kind.contains("dissent") -> "DISSENT"
                kind.contains("concur") -> "CONCURRENCE"
                kind.contains("combined") || kind.contains("lead") || kind.contains("majority") -> "OPINION"
                else -> kind.uppercase().ifBlank { "OPINION" }
            }
            val author = op.sv("author_str")
            val opText = op.sv("plain_text")
                ?: listOf("html_with_citations", "html", "html_lawbox", "html_columbia", "xml_harvard")
                    .firstNotNullOfOrNull { op.sv(it) }?.let { Jsoup.parse(it).wholeText() }
                ?: ""
            if (text.isNotEmpty()) text.append("\n\n")
            text.append("===== ").append(label).append(author?.let { " ($it)" } ?: "").append(" =====\n").append(opText.trim())
        }
        val full = text.toString()
        val slice = if (offset < full.length) full.substring(offset, minOf(full.length, offset + OPINION_MAX_CHARS)) else ""
        fmTextPart(buildJsonObject {
            put("cluster_id", id)
            put("case_name", cluster.sv("case_name_full") ?: cluster.sv("case_name") ?: "")
            cluster["citations"]?.let { cs ->
                runCatching {
                    putJsonArray("citations") {
                        cs.jsonArray.forEach { c ->
                            val co = c.jsonObject
                            add("${co.sv("volume")} ${co.sv("reporter")} ${co.sv("page")}")
                        }
                    }
                }
            }
            cluster.sv("date_filed")?.let { put("date_filed", it) }
            cluster.sv("judges")?.let { put("judges", it) }
            cluster.sv("precedential_status")?.let { put("precedential_status", it) }
            cluster.sv("absolute_url")?.let { put("url", CL_BASE + it) }
            put("total_chars", full.length); put("offset", offset)
            if (offset + OPINION_MAX_CHARS < full.length) put("next_offset", offset + OPINION_MAX_CHARS)
            put("text", slice)
            if (full.isBlank()) put("note", "No opinion text available from CourtListener for this cluster.")
        }.toString())
    },
)

/** Text of the `<!-- field-start:NAME -->` … `<!-- field-end:NAME -->` blocks in a GovInfo USC page. */
internal fun govInfoFields(html: String, names: Set<String>): String {
    val out = StringBuilder()
    Regex("<!-- field-start:(\\w+) -->(.*?)<!-- field-end:\\1 -->", RegexOption.DOT_MATCHES_ALL).findAll(html).forEach { m ->
        if (m.groupValues[1] !in names) return@forEach
        val d = Jsoup.parse(m.groupValues[2])
        val lines = d.select("h3, h4, p").map { it.text().trim() }.filter { it.isNotEmpty() }
        if (lines.isNotEmpty()) { if (out.isNotEmpty()) out.append("\n\n"); out.append(lines.joinToString("\n")) }
    }
    return out.toString()
}

fun usCodeSectionTool(client: OkHttpClient): Tool = Tool(
    name = "us_code_section",
    description = "Fetch the official text of a U.S. Code section from GovInfo (U.S. Government Publishing Office, " +
        "most recent edition), e.g. title 28 section 2254 (habeas, state custody), 2255 (federal prisoners), 2244 " +
        "(limitations / successive petitions), 18 U.S.C. 3600 (DNA testing), 42 U.S.C. 1983. Returns the statutory " +
        "text and source credit; set include_notes for amendments and historical notes. Editions are annual: tell " +
        "counsel to confirm the version in force on the relevant date.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("title", prm("integer", "U.S. Code title number, e.g. 28"))
                put("section", prm("string", "Section number, e.g. '2254' or '3600A'"))
                put("include_notes", prm("boolean", "Include amendments / historical notes (default false)"))
            },
            required = listOf("title", "section"),
        )
    },
    execute = { input ->
        val o = input.jsonObject
        val title = o["title"]?.jsonPrimitive?.intOrNull ?: return@Tool fmTextPart(fmErrEnvelope("missing_title", "title is required"))
        val section = o.sv("section")?.removePrefix("§")?.trim()?.takeIf { it.matches(Regex("[0-9A-Za-z\\-–]+")) }
            ?: return@Tool fmTextPart(fmErrEnvelope("missing_section", "section is required, e.g. 2254"))
        val notes = o["include_notes"]?.jsonPrimitive?.contentOrNull == "true"
        val link = "https://www.govinfo.gov/link/uscode/$title/$section?type=usc&year=mostrecent&link-type=html"
        val (finalUrl, html) = runCatching {
            withContext(Dispatchers.IO) {
                client.withEgressGuard().newCall(Request.Builder().url(link).build()).execute().use {
                    if (!it.isSuccessful) throw IllegalStateException("HTTP ${it.code}")
                    it.request.url.toString() to it.body.string()
                }
            }
        }.getOrElse { return@Tool fmTextPart(fmErrEnvelope("fetch_failed", "${it.message} ($link)")) }
        val heading = Jsoup.parse(html).selectFirst("h3.section-head")?.text().orEmpty()
        val statute = govInfoFields(html, setOf("statute"))
        if (statute.isBlank()) return@Tool fmTextPart(fmErrEnvelope("not_found", "No text for $title U.S.C. § $section ($finalUrl)"))
        val edition = Regex("USCODE-(\\d{4})-").find(finalUrl)?.groupValues?.get(1)
        val credit = govInfoFields(html, setOf("sourcecredit"))
        val extra = if (notes) govInfoFields(html, setOf("amendment", "notes", "historicalandrevision", "effectivedate", "miscellaneous")) else ""
        fmTextPart(buildJsonObject {
            put("citation", "$title U.S.C. § $section")
            if (heading.isNotBlank()) put("heading", heading)
            edition?.let { put("edition", "United States Code, $it Edition") }
            put("url", finalUrl)
            put("text", statute.take(OPINION_MAX_CHARS))
            if (credit.isNotBlank()) put("source_credit", credit.take(4000))
            if (extra.isNotBlank()) put("notes", extra.take(OPINION_MAX_CHARS))
            put("note", "Official GPO text of the stated edition. Confirm the version in force on the relevant date and any later amendments.")
        }.toString())
    },
)
