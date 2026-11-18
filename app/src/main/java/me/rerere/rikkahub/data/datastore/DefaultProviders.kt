package me.rerere.rikkahub.data.datastore

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import me.rerere.ai.provider.BalanceOption
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import kotlin.uuid.Uuid

val DEFAULT_AUTO_MODEL_ID = Uuid.parse("b7055fb4-39f9-4042-a88a-0d80ed76cf08")
val DEFAULT_CODEX_PROVIDER_ID = Uuid.parse("7ce7e322-b995-4b0c-9d48-42e08dcfcdda")
val DEFAULT_GROK_PROVIDER_ID = Uuid.parse("8f3e1d20-4b6a-4c9e-a1f2-9d5c7e0b3a44")
val DEFAULT_GEMINI_OAUTH_PROVIDER_ID = Uuid.parse("2b6c1f84-73ad-4e35-b0c7-1a9e4d5f8c21")

val DEFAULT_GROQ_PROVIDER_ID = Uuid.parse("0c9a3f52-6d1e-4b8a-9f27-5e4d3c2b1a90")
val DEFAULT_NVIDIA_PROVIDER_ID = Uuid.parse("4e7b2d19-a8c3-4f56-b012-7d9e8c6a5b31")
val DEFAULT_GEMINI_API_PROVIDER_ID = Uuid.parse("6ab18148-c138-4394-a46f-1cd8c8ceaa6d")
val DEFAULT_OPENROUTER_PROVIDER_ID = Uuid.parse("d5734028-d39b-4d41-9841-fd648d65440e")

/**
 * RikkaLaw-U.S. ships exactly five built-in, bring-your-own-key providers. Every one starts
 * DISABLED with an empty key, so a fresh install makes no model calls until counsel enables a
 * provider and enters a key. Any other OpenAI-compatible endpoint can still be added by hand
 * from Settings -> Providers -> Add.
 */
val DEFAULT_PROVIDERS = listOf(
    ProviderSetting.Google(
        id = DEFAULT_GEMINI_API_PROVIDER_ID,
        name = "Google Gemini",
        apiKey = "",
        enabled = false,
        builtIn = true,
        shortDescription = { Text("Gemini API key (Google AI Studio)") },
    ),
    ProviderSetting.GeminiOAuth(
        id = DEFAULT_GEMINI_OAUTH_PROVIDER_ID,
        name = "Google Gemini (OAuth)",
        enabled = false,
        builtIn = true,
        shortDescription = {
            Text(stringResource(R.string.gemini_provider_short_description))
        },
    ),
    ProviderSetting.OpenAI(
        id = DEFAULT_OPENROUTER_PROVIDER_ID,
        name = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        apiKey = "",
        enabled = false,
        builtIn = true,
        balanceOption = BalanceOption(
            enabled = true,
            apiPath = "/credits",
            resultPath = "data.total_credits - data.total_usage",
        ),
        shortDescription = { Text("Chat + embeddings, hundreds of models") },
    ),
    ProviderSetting.OpenAI(
        id = DEFAULT_GROQ_PROVIDER_ID,
        name = "Groq",
        baseUrl = "https://api.groq.com/openai/v1",
        apiKey = "",
        enabled = false,
        builtIn = true,
        shortDescription = { Text("Fast inference (chat, Whisper)") },
    ),
    ProviderSetting.OpenAI(
        id = DEFAULT_NVIDIA_PROVIDER_ID,
        name = "NVIDIA NIM",
        baseUrl = "https://integrate.api.nvidia.com/v1",
        apiKey = "",
        enabled = false,
        builtIn = true,
        shortDescription = { Text("Chat, embedding and rerank models (build.nvidia.com)") },
    ),
)
