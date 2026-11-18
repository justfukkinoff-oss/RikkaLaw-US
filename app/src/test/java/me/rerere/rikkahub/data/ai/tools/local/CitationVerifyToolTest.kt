package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.secrets.AgentSecretsStore
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

/**
 * Offline tests for citation_verify: verdict classification and the response envelope are pure,
 * and the execute paths asserted here all early-return BEFORE any network call or vault read —
 * the [AgentSecretsStore] is an uninitialised ghost instance (never dereferenced on these paths)
 * and the [OkHttpClient] is never used. Live HTTP behaviour is exercised by instrumented runs.
 */
class CitationVerifyToolTest {

    private val tool: Tool = citationVerifyTool(OkHttpClient(), ghostSecretsStore())

    private fun ghostSecretsStore(): AgentSecretsStore {
        val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as Unsafe
        return unsafe.allocateInstance(AgentSecretsStore::class.java) as AgentSecretsStore
    }

    private fun invoke(args: String): String {
        val parts = runBlocking {
            tool.execute(Json.parseToJsonElement(args))
        }
        return (parts.first() as UIMessagePart.Text).text
    }

    private fun obj(result: String) = Json.parseToJsonElement(result).jsonObject

    private fun error(result: String) = obj(result)["error"]?.jsonPrimitive?.content

    // ---------- verdict classification ----------

    @Test fun `status 200 with clusters is verified`() {
        assertEquals(VERDICT_VERIFIED, classifyCitationStatus(200, 1))
    }

    @Test fun `status 200 without clusters is unverified`() {
        assertEquals(VERDICT_UNVERIFIED, classifyCitationStatus(200, 0))
    }

    @Test fun `status 300 is ambiguous`() {
        assertEquals(VERDICT_AMBIGUOUS, classifyCitationStatus(300, 2))
    }

    @Test fun `status 404 is unverified`() {
        assertEquals(VERDICT_UNVERIFIED, classifyCitationStatus(404, 0))
    }

    @Test fun `status 400 is unverified`() {
        assertEquals(VERDICT_UNVERIFIED, classifyCitationStatus(400, 0))
    }

    @Test fun `status 429 is throttled`() {
        assertEquals(VERDICT_THROTTLED, classifyCitationStatus(429, 0))
    }

    @Test fun `unknown status falls to unverified`() {
        assertEquals(VERDICT_UNVERIFIED, classifyCitationStatus(500, 0))
    }

    // ---------- envelope: item-level parsing ----------

    @Test fun `found citation renders verified with cluster match fields`() {
        val body = """
            [{
              "citation": "576 U.S. 644",
              "normalized_citations": ["576 U.S. 644"],
              "start_index": 22,
              "end_index": 34,
              "status": 200,
              "error_message": "",
              "clusters": [{
                "case_name": "Obergefell v. Hodges",
                "absolute_url": "/opinion/2814088/obergefell-v-hodges/",
                "court": "scotus",
                "date_filed": "2015-06-26",
                "docket_number": "14-556"
              }]
            }]
        """.trimIndent()
        val envelope = obj(buildCitationVerifyEnvelope(200, body))
        assertEquals(1, envelope["verified_count"]?.jsonPrimitive?.content?.toInt())
        assertEquals(0, envelope["unverified_count"]?.jsonPrimitive?.content?.toInt())
        val item = envelope["items"]!!.jsonArray.first().jsonObject
        assertEquals("verified", item["verdict"]?.jsonPrimitive?.content)
        assertEquals("576 U.S. 644", item["citation"]?.jsonPrimitive?.content)
        val match = item["matches"]!!.jsonArray.first().jsonObject
        assertEquals("Obergefell v. Hodges", match["case_name"]?.jsonPrimitive?.content)
        assertEquals(
            "/opinion/2814088/obergefell-v-hodges/",
            match["url"]?.jsonPrimitive?.content,
        )
        assertTrue(envelope["note"]!!.jsonPrimitive.content.contains("manual check"))
    }

    @Test fun `not-found citation renders unverified with error message`() {
        val body = """
            [{
              "citation": "1 U.S. 200",
              "normalized_citations": ["1 U.S. 200"],
              "start_index": 0,
              "end_index": 10,
              "status": 404,
              "error_message": "Citation not found: '1 U.S. 200'",
              "clusters": []
            }]
        """.trimIndent()
        val envelope = obj(buildCitationVerifyEnvelope(200, body))
        assertEquals(0, envelope["verified_count"]?.jsonPrimitive?.content?.toInt())
        assertEquals(1, envelope["unverified_count"]?.jsonPrimitive?.content?.toInt())
        val item = envelope["items"]!!.jsonArray.first().jsonObject
        assertEquals("unverified", item["verdict"]?.jsonPrimitive?.content)
        assertEquals(
            "Citation not found: '1 U.S. 200'",
            item["error_message"]?.jsonPrimitive?.content,
        )
    }

    @Test fun `ambiguous citation carries multiple matches`() {
        val body = """
            [{
              "citation": "1 H. 150",
              "normalized_citations": ["1 Handy 150", "1 Haw. 150", "1 Hill 150"],
              "start_index": 0,
              "end_index": 8,
              "status": 300,
              "error_message": "",
              "clusters": [
                {"case_name": "Louis v. Steamboat Buckeye", "absolute_url": "/opinion/a/"},
                {"case_name": "Fell v. Parke", "absolute_url": "/opinion/b/"}
              ]
            }]
        """.trimIndent()
        val item = obj(buildCitationVerifyEnvelope(200, body))["items"]!!.jsonArray.first().jsonObject
        assertEquals("ambiguous", item["verdict"]?.jsonPrimitive?.content)
        assertEquals(2, item["matches"]!!.jsonArray.size)
    }

    @Test fun `over-cap citations carry throttled verdict and stay uncounted`() {
        val body = """
            [
              {"citation": "576 U.S. 644", "status": 200, "error_message": "",
               "clusters": [{"case_name": "Obergefell v. Hodges", "absolute_url": "/o/"}]},
              {"citation": "577 U.S. 1", "status": 429, "error_message": "Too many citations requested.", "clusters": []}
            ]
        """.trimIndent()
        val envelope = obj(buildCitationVerifyEnvelope(200, body))
        assertEquals(1, envelope["verified_count"]?.jsonPrimitive?.content?.toInt())
        assertEquals(0, envelope["unverified_count"]?.jsonPrimitive?.content?.toInt())
        val second = envelope["items"]!!.jsonArray[1].jsonObject
        assertEquals("throttled", second["verdict"]?.jsonPrimitive?.content)
    }

    @Test fun `empty text body yields an empty result set`() {
        val envelope = obj(buildCitationVerifyEnvelope(200, "[]"))
        assertEquals(0, envelope["items"]!!.jsonArray.size)
        assertEquals(0, envelope["verified_count"]?.jsonPrimitive?.content?.toInt())
    }

    // ---------- envelope: HTTP-level errors ----------

    @Test fun `http 401 is an auth failure with recovery`() {
        val envelope = obj(buildCitationVerifyEnvelope(401, ""))
        assertEquals("auth_failed", envelope["error"]?.jsonPrimitive?.content)
        assertTrue(envelope["recovery"]!!.jsonPrimitive.content.contains("request_secret"))
    }

    @Test fun `http 403 is an auth failure`() {
        assertEquals(
            "auth_failed",
            obj(buildCitationVerifyEnvelope(403, ""))["error"]?.jsonPrimitive?.content,
        )
    }

    @Test fun `http 429 surfaces the wait_util timestamp`() {
        val envelope = obj(
            buildCitationVerifyEnvelope(429, """{"wait_util": "2026-11-17T12:00:00Z"}"""),
        )
        assertEquals("throttled", envelope["error"]?.jsonPrimitive?.content)
        assertEquals("2026-11-17T12:00:00Z", envelope["wait_until"]?.jsonPrimitive?.content)
    }

    @Test fun `http 429 without wait_util still reports throttled`() {
        assertEquals("throttled", error(buildCitationVerifyEnvelope(429, "")))
    }

    @Test fun `non-json body is a bad_response`() {
        assertEquals(
            "bad_response",
            error(buildCitationVerifyEnvelope(200, "<html>error</html>")),
        )
    }

    // ---------- execute: input validation (no network, no vault) ----------

    @Test fun `missing text is rejected`() {
        assertEquals("missing_text", error(invoke("""{"token_secret": "t"}""")))
    }

    @Test fun `blank text is rejected`() {
        assertEquals("missing_text", error(invoke("""{"text": "   "}""")))
    }

    @Test fun `over-limit text is rejected`() {
        val long = "\"" + "a".repeat(CITATION_LOOKUP_MAX_TEXT_CHARS + 1) + "\""
        assertEquals("text_too_long", error(invoke("""{"text": $long}""")))
    }

    @Test fun `missing token returns setup guidance`() {
        val envelope = obj(invoke("""{"text": "576 U.S. 644"}"""))
        assertEquals("missing_token", envelope["error"]?.jsonPrimitive?.content)
        assertTrue(
            envelope["recovery"]!!.jsonPrimitive.content.contains(
                COURTLISTENER_TOKEN_HINT_NAME,
            ),
        )
    }
}
