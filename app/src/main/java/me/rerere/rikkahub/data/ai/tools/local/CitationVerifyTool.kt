package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.net.withEgressGuard
import me.rerere.rikkahub.data.secrets.AgentSecretsStore
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

private const val CITATION_LOOKUP_URL = "https://www.courtlistener.com/api/rest/v4/citation-lookup/"
const val CITATION_LOOKUP_MAX_TEXT_CHARS = 64_000
private const val CITATION_VERIFY_TIMEOUT_MS = 60_000L

/** CourtListener token to store via `request_secret` so the plaintext never enters chat. */
const val COURTLISTENER_TOKEN_HINT_NAME = "COURTLISTENER_TOKEN"

const val CITATION_VERIFY_NOTE = "Only case-law reporter citations are verified. Statutes, law " +
    "journals, 'id.' and 'supra' are never looked up by this API — they appear as unverified or " +
    "are absent entirely: mark those 'statute — manual check'. A lookup failure is never a " +
    "verification success."

internal const val VERDICT_VERIFIED = "verified"
internal const val VERDICT_AMBIGUOUS = "ambiguous"
internal const val VERDICT_UNVERIFIED = "unverified"
internal const val VERDICT_THROTTLED = "throttled"

/**
 * Map a citation-lookup item's `status` to a verdict the model can act on.
 *
 * Per the CourtListener v4 contract: 200 = found and matched, 300 = valid but ambiguous
 * (multiple clusters — a human must pick), 404 = valid format, no match in the database,
 * 400 = Eyecite parsed a citation but the reporter is unrecognized (also where made-up
 * reporters land), 429 = identified but not looked up (over the per-request cap).
 */
internal fun classifyCitationStatus(status: Int, clusterCount: Int): String = when (status) {
    200 -> if (clusterCount > 0) VERDICT_VERIFIED else VERDICT_UNVERIFIED
    300 -> VERDICT_AMBIGUOUS
    429 -> VERDICT_THROTTLED
    else -> VERDICT_UNVERIFIED
}

/**
 * Turn a citation-lookup HTTP response into the tool-result envelope.
 *
 * The API answers item-level failures inside a 200 HTTP response, so this is also where the
 * HTTP-level errors land: 401/403 mean the token is bad, and a real HTTP 429 carries a
 * `wait_util` ISO-8601 timestamp instead of the item array.
 */
internal fun buildCitationVerifyEnvelope(httpStatus: Int, responseBody: String): String {
    if (httpStatus == 401 || httpStatus == 403) {
        return fmEnvelope(
            "auth_failed",
            "CourtListener rejected the API token.",
            "Check the token at https://www.courtlistener.com/profile/signups/, then update it " +
                "via request_secret (store under a vault name) and retry with token_secret.",
        )
    }
    if (httpStatus == 429) {
        val waitUntil = runCatching {
            Json.parseToJsonElement(responseBody).jsonObject["wait_util"]
                ?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        if (waitUntil != null) {
            return buildJsonObject {
                put("error", "throttled")
                put("detail", "CourtListener rate limit reached.")
                put("wait_until", waitUntil)
                put(
                    "recovery",
                    "Retry after the wait_until timestamp; do not retry before it.",
                )
            }.toString()
        }
    }
    val items = runCatching { Json.parseToJsonElement(responseBody).jsonArray }.getOrNull()
        ?: return fmEnvelope(
            "bad_response",
            "CourtListener returned a non-JSON body (HTTP $httpStatus).",
            "Check https://www.courtlistener.com status, then retry.",
        )

    var verified = 0
    var unverified = 0
    val out = buildJsonArray {
        items.forEach { el ->
            val obj = el as? JsonObject
            val citation = obj?.get("citation")?.jsonPrimitive?.contentOrNull ?: "?"
            val status = obj?.get("status")?.jsonPrimitive?.intOrNull ?: -1
            val clusters = obj?.get("clusters") as? JsonArray
            val verdict = classifyCitationStatus(status, clusters?.size ?: 0)
            if (verdict == VERDICT_VERIFIED) verified++ else if (verdict != VERDICT_THROTTLED) {
                unverified++
            }
            add(buildJsonObject {
                put("citation", citation)
                obj?.get("normalized_citations")?.let { put("normalized_citations", it) }
                if (status != -1) put("status", status)
                put("verdict", verdict)
                obj?.get("error_message")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { put("error_message", it) }
                clusters?.let { cs ->
                    put("matches", buildJsonArray {
                        cs.forEach { clusterEl ->
                            (clusterEl as? JsonObject)?.let { c ->
                                add(buildJsonObject {
                                    c["case_name"]?.jsonPrimitive?.contentOrNull
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { put("case_name", it) }
                                    c["absolute_url"]?.let { put("url", it) }
                                    c["court"]?.let { put("court", it) }
                                    c["date_filed"]?.let { put("date_filed", it) }
                                    c["docket_number"]?.let { put("docket_number", it) }
                                })
                            }
                        }
                    })
                }
            })
        }
    }
    return buildJsonObject {
        put("items", out)
        put("verified_count", verified)
        put("unverified_count", unverified)
        put("note", CITATION_VERIFY_NOTE)
    }.toString()
}

private fun fmEnvelope(code: String, detail: String, recovery: String): String {
    val obj = buildJsonObject {
        put("error", code)
        put("detail", detail)
        put("recovery", recovery)
    }
    return obj.toString()
}

/**
 * Native citation verification against the CourtListener citation-lookup API (Eyecite-backed),
 * replacing the workspace `curl` scripting the legal-assistant skill previously relied on.
 *
 * Read-only network lookup, like web_fetch. The token lives in the on-device vault: the model
 * stores it once via `request_secret` and from then on only ever passes the vault NAME as
 * `token_secret`, so the plaintext never reaches the conversation.
 */
fun citationVerifyTool(
    client: OkHttpClient,
    secrets: AgentSecretsStore,
): Tool = Tool(
    name = "citation_verify",
    description = (
        "Verify U.S. case-law citations against the CourtListener database (Eyecite-backed). " +
        "Parse every citation in a block of text and look each one up, returning per-citation " +
        "verdicts: verified (matched cluster(s) with case name and URL), ambiguous (multiple " +
        "candidates), unverified (not found / unrecognized reporter), or throttled. Only " +
        "case-law reporter citations are verified — statutes, law journals, id., and supra are " +
        "NOT supported and must be marked 'statute — manual check'. A lookup failure is never a " +
        "verification success; do not treat unverified or absent results as verified. Requires a " +
        "free CourtListener API token: call request_secret once (name=$COURTLISTENER_TOKEN_HINT_NAME), " +
        "then pass token_secret=$COURTLISTENER_TOKEN_HINT_NAME here; sign up at " +
        "https://www.courtlistener.com/profile/signups/. text is capped at 64,000 characters."
    ),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "The draft excerpt / text to parse and verify citations in")
                })
                put("token_secret", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Vault name of the CourtListener API token stored via request_secret " +
                            "(do NOT send the raw token)",
                    )
                })
                put("token", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "DEPRECATED plaintext token — use token_secret + request_secret",
                    )
                })
            },
            required = listOf("text"),
        )
    },
    execute = { input ->
        val p = input.jsonObject
        val text = p["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (text.isEmpty()) {
            return@Tool fmTextPart(fmErrEnvelope("missing_text", "text is required"))
        }
        if (text.length > CITATION_LOOKUP_MAX_TEXT_CHARS) {
            return@Tool fmTextPart(
                fmErrEnvelope(
                    "text_too_long",
                    "text is ${text.length} characters; the API accepts at most " +
                        "$CITATION_LOOKUP_MAX_TEXT_CHARS per request",
                ),
            )
        }
        val token = resolveSecretOrPlain(
            secrets,
            refName = p["token_secret"]?.jsonPrimitive?.contentOrNull,
            plain = p["token"]?.jsonPrimitive?.contentOrNull,
            field = "token",
        ).getOrElse { return@Tool secretError(it) }
        if (token.isNullOrBlank()) {
            return@Tool fmTextPart(fmEnvelope(
                "missing_token",
                "A CourtListener API token is required.",
                "Call request_secret (name=$COURTLISTENER_TOKEN_HINT_NAME, kind=password) to store " +
                    "the token on-device, then retry with token_secret=$COURTLISTENER_TOKEN_HINT_NAME. " +
                    "Ask the user to get a free token at https://www.courtlistener.com/profile/signups/; " +
                    "never collect it in chat.",
            ))
        }

        val request = Request.Builder()
            .url(CITATION_LOOKUP_URL)
            .header("Authorization", "Token $token")
            .header("Accept", "application/json")
            .post(FormBody.Builder().add("text", text).build())
            .build()

        val guarded = client.withEgressGuard()
        val result = withTimeoutOrNull(CITATION_VERIFY_TIMEOUT_MS) {
            try {
                guarded.newCall(request).execute().use { resp ->
                    buildCitationVerifyEnvelope(
                        httpStatus = resp.code,
                        responseBody = resp.body.string(),
                    )
                }
            } catch (e: IOException) {
                buildJsonObject {
                    put("error", "network_error")
                    put("detail", e.message ?: e::class.java.simpleName)
                    put("recovery", "Check connectivity, then retry; do not treat this as unverified.")
                }.toString()
            }
        } ?: buildJsonObject {
            put("error", "timeout")
            put("detail", "Citation lookup exceeded the 60s limit.")
            put("recovery", "Retry with a shorter text.")
        }.toString()

        fmTextPart(result)
    },
)
