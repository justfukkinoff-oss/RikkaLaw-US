package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProvidersTest {
    @Test
    fun `default providers are the legal-build set`() {
        assertEquals(
            listOf("Google Gemini", "Google Gemini (OAuth)", "OpenRouter", "Groq", "NVIDIA NIM"),
            DEFAULT_PROVIDERS.map { it.name },
        )
        val nvidia = DEFAULT_PROVIDERS.filterIsInstance<ProviderSetting.OpenAI>().single { it.name == "NVIDIA NIM" }
        assertEquals("https://integrate.api.nvidia.com/v1", nvidia.baseUrl)
        assertTrue(nvidia.builtIn)
        assertFalse(DEFAULT_PROVIDERS.any { it.name == "Vercel AI Gateway" })
    }

    @Test
    fun `every default provider is assigned an explicit category`() {
        // Guard for the grouped provider page: a built-in that nobody mapped would silently
        // fall into the "其他" bucket, which is reserved for user-added providers.
        val uncategorized = DEFAULT_PROVIDERS.filter { it.category() == ProviderCategory.OTHER }
        assertTrue(
            "uncategorized built-ins: ${uncategorized.map { it.name }}",
            uncategorized.isEmpty(),
        )
    }
}
