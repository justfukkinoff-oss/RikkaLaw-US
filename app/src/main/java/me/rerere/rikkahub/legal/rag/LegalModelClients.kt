package me.rerere.rikkahub.legal.rag

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.mergeCustomHeaders
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.uuid.Uuid

private val JSON_MEDIA = "application/json".toMediaType()
private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

class LegalModelException(message: String) : Exception(message)

/** A resolved (model, provider) pair for a retrieval slot. */
data class SlotModel(val model: Model, val provider: ProviderSetting) {
    val label: String get() = "${provider.name}/${model.modelId}"
}

fun Settings.resolveSlot(id: Uuid?): SlotModel? {
    val model = findModelById(id) ?: return null
    val provider = model.findProvider(providers) ?: return null
    return SlotModel(model, provider)
}

private fun firstKey(raw: String): String =
    raw.split(',', '\n').map { it.trim() }.firstOrNull { it.isNotEmpty() }
        ?: throw LegalModelException("Provider has no API key configured")

private fun isNvidia(url: String) = url.contains("nvidia.com", ignoreCase = true)

private suspend fun OkHttpClient.postJson(url: String, body: JsonObject, build: Request.Builder.() -> Unit): JsonObject =
    withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).post(body.toString().toRequestBody(JSON_MEDIA)).apply(build).build()
        newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw LegalModelException("HTTP ${resp.code} from ${req.url.host}: ${text.take(400)}")
            }
            lenient.parseToJsonElement(text).jsonObject
        }
    }

enum class EmbedRole { DOCUMENT, QUERY }

/**
 * Embedding client for the user-selected embedding slot. Supports OpenAI-compatible
 * `/embeddings` (OpenAI, OpenRouter, NVIDIA NIM, Groq-compatible hosts, local servers in Termux)
 * and Google Gemini `batchEmbedContents`.
 */
class EmbeddingClient(private val http: OkHttpClient) {

    suspend fun embed(slot: SlotModel, texts: List<String>, role: EmbedRole): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        return when (val p = slot.provider) {
            is ProviderSetting.OpenAI -> openAi(p, slot.model, texts, role)
            is ProviderSetting.Google -> {
                if (p.vertexAI) throw LegalModelException("Vertex AI embeddings are not supported; use an API-key Gemini provider")
                google(p, slot.model, texts, role)
            }
            else -> throw LegalModelException("Provider type ${p::class.simpleName} cannot serve embeddings. Choose an OpenAI-compatible, NVIDIA, or Gemini API-key provider.")
        }
    }

    private suspend fun openAi(p: ProviderSetting.OpenAI, model: Model, texts: List<String>, role: EmbedRole): List<FloatArray> {
        val url = p.baseUrl.trimEnd('/') + "/embeddings"
        val body = buildJsonObject {
            put("model", model.modelId)
            put("input", buildJsonArray { texts.forEach { add(it) } })
            put("encoding_format", "float")
            if (isNvidia(p.baseUrl)) {
                put("input_type", if (role == EmbedRole.QUERY) "query" else "passage")
                put("truncate", "END")
            }
        }
        val json = http.postJson(url, body) {
            headers(p.mergeCustomHeaders(model.customHeaders))
            header("Authorization", "Bearer ${firstKey(p.apiKey)}")
        }
        val data = json["data"]?.jsonArray ?: throw LegalModelException("Embedding response missing `data`")
        val out = arrayOfNulls<FloatArray>(texts.size)
        data.forEachIndexed { i, el ->
            val o = el.jsonObject
            val idx = o["index"]?.jsonPrimitive?.int ?: i
            out[idx] = o["embedding"]!!.jsonArray.let { arr -> FloatArray(arr.size) { arr[it].jsonPrimitive.float } }
        }
        return out.mapIndexed { i, v -> v ?: throw LegalModelException("Embedding missing for input $i") }
    }

    private suspend fun google(p: ProviderSetting.Google, model: Model, texts: List<String>, role: EmbedRole): List<FloatArray> {
        val id = model.modelId.removePrefix("models/")
        val url = p.baseUrl.trimEnd('/') + "/models/$id:batchEmbedContents"
        val body = buildJsonObject {
            put("requests", buildJsonArray {
                texts.forEach { t ->
                    add(buildJsonObject {
                        put("model", "models/$id")
                        put("content", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", t) }) }) })
                        put("taskType", if (role == EmbedRole.QUERY) "RETRIEVAL_QUERY" else "RETRIEVAL_DOCUMENT")
                    })
                }
            })
        }
        val json = http.postJson(url, body) {
            headers(p.mergeCustomHeaders(model.customHeaders))
            header("x-goog-api-key", firstKey(p.apiKey))
        }
        val arr = json["embeddings"]?.jsonArray ?: throw LegalModelException("Gemini response missing `embeddings`")
        return arr.map { e -> e.jsonObject["values"]!!.jsonArray.let { v -> FloatArray(v.size) { v[it].jsonPrimitive.float } } }
    }
}

/**
 * Reranker client. NVIDIA hosted retrieval (`ai.api.nvidia.com/v1/retrieval/.../reranking`),
 * self-hosted NIM (`/v1/ranking`), and the Cohere/Jina/vLLM style `/rerank` shape.
 */
class RerankClient(private val http: OkHttpClient) {

    /** Returns (index into [passages], score) sorted by descending relevance. */
    suspend fun rerank(slot: SlotModel, query: String, passages: List<String>, topN: Int): List<Pair<Int, Double>> {
        if (passages.isEmpty()) return emptyList()
        val p = slot.provider as? ProviderSetting.OpenAI
            ?: throw LegalModelException("Rerank models must come from an OpenAI-compatible or NVIDIA provider")
        val key = firstKey(p.apiKey)
        val headers = p.mergeCustomHeaders(slot.model.customHeaders)
        return if (isNvidia(p.baseUrl) || p.baseUrl.contains("/v1/ranking")) {
            nvidia(p, slot.model.modelId, key, headers, query, passages)
        } else {
            generic(p, slot.model.modelId, key, headers, query, passages, topN)
        }.take(topN)
    }

    private suspend fun nvidia(
        p: ProviderSetting.OpenAI, modelId: String, key: String, headers: okhttp3.Headers,
        query: String, passages: List<String>,
    ): List<Pair<Int, Double>> {
        val url = nvidiaRerankUrl(p.baseUrl, modelId)
        val body = buildJsonObject {
            put("model", modelId)
            put("query", buildJsonObject { put("text", query) })
            put("passages", buildJsonArray { passages.forEach { t -> add(buildJsonObject { put("text", t) }) } })
            put("truncate", "END")
        }
        val json = http.postJson(url, body) { headers(headers); header("Authorization", "Bearer $key") }
        val rankings = json["rankings"]?.jsonArray ?: throw LegalModelException("Rerank response missing `rankings`")
        return rankings.map { it.jsonObject["index"]!!.jsonPrimitive.int to it.jsonObject["logit"]!!.jsonPrimitive.double }
            .sortedByDescending { it.second }
    }

    private suspend fun generic(
        p: ProviderSetting.OpenAI, modelId: String, key: String, headers: okhttp3.Headers,
        query: String, passages: List<String>, topN: Int,
    ): List<Pair<Int, Double>> {
        val url = p.baseUrl.trimEnd('/') + "/rerank"
        val body = buildJsonObject {
            put("model", modelId)
            put("query", query)
            put("documents", buildJsonArray { passages.forEach { add(it) } })
            put("top_n", topN)
        }
        val json = http.postJson(url, body) { headers(headers); header("Authorization", "Bearer $key") }
        val results: JsonArray = (json["results"] ?: json["data"])?.jsonArray
            ?: throw LegalModelException("Rerank response missing `results`")
        return results.map {
            val o = it.jsonObject
            o["index"]!!.jsonPrimitive.int to (o["relevance_score"] ?: o["score"])!!.jsonPrimitive.double
        }.sortedByDescending { it.second }
    }

    companion object {
        /**
         * Hosted NVIDIA reranking lives on `ai.api.nvidia.com`, not the `integrate` chat host:
         * `nvidia/llama-3.2-nv-rerankqa-1b-v2` -> `/v1/retrieval/nvidia/llama-3_2-nv-rerankqa-1b-v2/reranking`.
         * The legacy mistral-4b-v3 endpoint is `/v1/retrieval/nvidia/reranking`. Self-hosted NIMs
         * expose `/v1/ranking`.
         */
        fun nvidiaRerankUrl(baseUrl: String, modelId: String): String {
            val base = baseUrl.trimEnd('/')
            if (!base.contains("api.nvidia.com")) {
                return if (base.endsWith("/ranking")) base else "$base/ranking"
            }
            if (modelId == "nvidia/nv-rerankqa-mistral-4b-v3") return "https://ai.api.nvidia.com/v1/retrieval/nvidia/reranking"
            return "https://ai.api.nvidia.com/v1/retrieval/${modelId.replace('.', '_')}/reranking"
        }
    }
}
