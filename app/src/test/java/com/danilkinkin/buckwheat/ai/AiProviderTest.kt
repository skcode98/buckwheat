package com.danilkinkin.buckwheat.ai

import androidx.datastore.preferences.core.preferencesOf
import com.danilkinkin.buckwheat.ai.aiProviderOrderStoreKey
import com.danilkinkin.buckwheat.di.voiceAiApiKeyStoreKey
import com.danilkinkin.buckwheat.di.voiceAiModelStoreKey
import com.danilkinkin.buckwheat.di.voiceAiProviderUrlStoreKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderTest {

    @Test
    fun `gemini accepts long base62-like keys and rejects short or spaced ones`() {
        assertTrue(AiProvider.GEMINI.isValidKey("AIzaSy${"A".repeat(33)}"))
        assertTrue(AiProvider.GEMINI.isValidKey("AIzaSy_ABC-def-0123-45678901234567890123"))
        assertFalse(AiProvider.GEMINI.isValidKey("short"))
        assertFalse(AiProvider.GEMINI.isValidKey("AIzaSy with spaces and padding chars!".repeat(2)))
        assertFalse(AiProvider.GEMINI.isValidKey(""))
    }

    @Test
    fun `groq requires the gsk prefix and enough length`() {
        assertTrue(AiProvider.GROQ.isValidKey("gsk_${"a".repeat(48)}"))
        assertTrue(AiProvider.GROQ.isValidKey("gsk_${"Aa-Zz_09".repeat(6)}"))
        assertFalse(AiProvider.GROQ.isValidKey("sk_${"a".repeat(48)}"))
        assertFalse(AiProvider.GROQ.isValidKey("gsk_${"a".repeat(20)}"))
    }

    @Test
    fun `openrouter requires the sk-or-v1 prefix and enough length`() {
        assertTrue(AiProvider.OPENROUTER.isValidKey("sk-or-v1-${"a".repeat(40)}"))
        assertTrue(AiProvider.OPENROUTER.isValidKey("sk-or-v1-${"A1-b2_c3".repeat(7)}"))
        assertFalse(AiProvider.OPENROUTER.isValidKey("or-v1-${"a".repeat(40)}"))
        assertFalse(AiProvider.OPENROUTER.isValidKey("sk-or-v1-${"a".repeat(10)}"))
    }

    @Test
    fun `cerebras accepts prefixed or very long keys`() {
        assertTrue(AiProvider.CEREBRAS.isValidKey("cerebras_${"x".repeat(20)}"))
        assertTrue(AiProvider.CEREBRAS.isValidKey("x".repeat(40)))
        assertFalse(AiProvider.CEREBRAS.isValidKey("cerebras_${"x".repeat(10)}"))
        assertFalse(AiProvider.CEREBRAS.isValidKey("x".repeat(21)))
        assertFalse(AiProvider.CEREBRAS.isValidKey("x".repeat(5)))
    }

    @Test
    fun `github accepts pat, classic or long keys`() {
        assertTrue(AiProvider.GITHUB.isValidKey("github_pat_${"x".repeat(22)}"))
        assertTrue(AiProvider.GITHUB.isValidKey("ghp_${"x".repeat(20)}"))
        assertTrue(AiProvider.GITHUB.isValidKey("x".repeat(25)))
        assertFalse(AiProvider.GITHUB.isValidKey("x".repeat(15)))
        assertFalse(AiProvider.GITHUB.isValidKey("x".repeat(8)))
    }

    @Test
    fun `nvidia nim requires the nvapi prefix and enough length`() {
        assertTrue(AiProvider.NIM.isValidKey("nvapi-${"a".repeat(30)}"))
        assertTrue(AiProvider.NIM.isValidKey("nvapi-${"A1-b2_c3".repeat(6)}"))
        assertFalse(AiProvider.NIM.isValidKey("api-${"a".repeat(30)}"))
        assertFalse(AiProvider.NIM.isValidKey("nvapi-${"a".repeat(10)}"))
        assertFalse(AiProvider.NIM.isValidKey(""))
    }

    @Test
    fun `resolve skips missing and invalid keys and keeps fallback order`() {
        val prefs = preferencesOf(
            aiApiKeyStoreKey(AiProvider.GEMINI) to "AIzaSy${"A".repeat(33)}",
            aiApiKeyStoreKey(AiProvider.GROQ) to "gsk_${"a".repeat(50)}",
            aiApiKeyStoreKey(AiProvider.GITHUB) to "github_pat_${"x".repeat(30)}",
            aiApiKeyStoreKey(AiProvider.CEREBRAS) to "too-short",
        )

        val configs = resolveProviderConfigs(prefs)

        assertEquals(
            listOf(AiProvider.GEMINI, AiProvider.GROQ, AiProvider.GITHUB),
            configs.map { it.provider },
        )
    }

    @Test
    fun `resolve falls back to the legacy single provider settings for openrouter`() {
        val prefs = preferencesOf(
            voiceAiApiKeyStoreKey to "sk-or-v1-${"a".repeat(40)}",
            voiceAiProviderUrlStoreKey to "https://openrouter.ai/api/v1/chat/completions",
            voiceAiModelStoreKey to "openai/gpt-oss-20b:free",
        )

        val configs = resolveProviderConfigs(prefs)

        assertEquals(listOf(AiProvider.OPENROUTER), configs.map { it.provider })
        assertEquals("openai/gpt-oss-20b:free", configs.single().model)
        assertEquals(AiProvider.OPENROUTER.defaultUrl, configs.single().url)
    }

    @Test
    fun `resolve uses provider defaults for blank url and model`() {
        val prefs = preferencesOf(
            aiApiKeyStoreKey(AiProvider.CEREBRAS) to "cerebras_${"x".repeat(40)}",
        )

        val configs = resolveProviderConfigs(prefs)

        assertEquals(1, configs.size)
        assertEquals(AiProvider.CEREBRAS.defaultUrl, configs.single().url)
        assertEquals(AiProvider.CEREBRAS.defaultModel, configs.single().model)
    }

    @Test
    fun `resolve places configured nim last in the fallback chain`() {
        val prefs = preferencesOf(
            aiApiKeyStoreKey(AiProvider.GROQ) to "gsk_${"a".repeat(50)}",
            aiApiKeyStoreKey(AiProvider.NIM) to "nvapi-${"a".repeat(32)}",
        )

        val configs = resolveProviderConfigs(prefs)

        assertEquals(listOf(AiProvider.GROQ, AiProvider.NIM), configs.map { it.provider })
        assertEquals(AiProvider.NIM.defaultUrl, configs[1].url)
        assertEquals(AiProvider.NIM.defaultModel, configs[1].model)
    }

    @Test
    fun `resolve order honors a stored order and appends missing providers at the end`() {
        val prefs = preferencesOf(
            aiProviderOrderStoreKey() to "github,nim,groq",
        )

        val order = resolveProviderOrder(prefs)

        assertEquals(
            listOf(
                AiProvider.GITHUB,
                AiProvider.NIM,
                AiProvider.GROQ,
                AiProvider.GEMINI,
                AiProvider.OPENROUTER,
                AiProvider.CEREBRAS,
            ),
            order,
        )
    }

    @Test
    fun `resolve order drops unknown ids and duplicates`() {
        val prefs = preferencesOf(
            aiProviderOrderStoreKey() to "gemini,bogus,gemini,openrouter",
        )

        val order = resolveProviderOrder(prefs)

        assertEquals(
            listOf(
                AiProvider.GEMINI,
                AiProvider.OPENROUTER,
                AiProvider.GROQ,
                AiProvider.CEREBRAS,
                AiProvider.GITHUB,
                AiProvider.NIM,
            ),
            order,
        )
    }

    @Test
    fun `resolve configs follows the stored fallback order`() {
        val prefs = preferencesOf(
            aiProviderOrderStoreKey() to "nim,groq",
            aiApiKeyStoreKey(AiProvider.GEMINI) to "AIzaSy${"A".repeat(33)}",
            aiApiKeyStoreKey(AiProvider.NIM) to "nvapi-${"a".repeat(32)}",
        )

        val configs = resolveProviderConfigs(prefs)

        assertEquals(listOf(AiProvider.NIM, AiProvider.GEMINI), configs.map { it.provider })
    }
}
