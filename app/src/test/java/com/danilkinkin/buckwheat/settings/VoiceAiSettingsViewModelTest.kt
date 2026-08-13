package com.danilkinkin.buckwheat.settings

import com.danilkinkin.buckwheat.ai.AiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceAiSettingsViewModelTest {

    private val key = "sk-test-key".repeat(4)

    @Test
    fun `blank provider url falls back to the provider default`() {
        assertEquals(
            "https://api.groq.com/openai/v1/models",
            modelsEndpoint(AiProvider.GROQ, key, "")
        )
        assertEquals(
            "https://openrouter.ai/api/v1/models",
            modelsEndpoint(AiProvider.OPENROUTER, key, "  ")
        )
        assertEquals(
            "https://api.cerebras.ai/v1/models",
            modelsEndpoint(AiProvider.CEREBRAS, key, "   ")
        )
        assertEquals(
            "https://models.github.ai/inference/models",
            modelsEndpoint(AiProvider.GITHUB, key, "")
        )
        assertEquals(
            "https://integrate.api.nvidia.com/v1/models",
            modelsEndpoint(AiProvider.NIM, key, "")
        )
    }

    @Test
    fun `custom chat-completions url maps to base slash models`() {
        assertEquals(
            "https://api.mycustom.com/v1/models",
            modelsEndpoint(AiProvider.GROQ, key, "https://api.mycustom.com/v1/chat/completions")
        )
    }

    @Test
    fun `gemini template url is reduced to the models base and used as-is`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models",
            modelsEndpoint(AiProvider.GEMINI, key, AiProvider.GEMINI.defaultUrl)
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models",
            modelsEndpoint(AiProvider.GEMINI, key, "")
        )
    }

    @Test
    fun `openrouter needs no key to list models`() {
        assertEquals(
            "https://openrouter.ai/api/v1/models",
            modelsEndpoint(AiProvider.OPENROUTER, "", "")
        )
    }

    @Test
    fun `non-openrouter providers without a key return null`() {
        assertNull(modelsEndpoint(AiProvider.GROQ, "", ""))
        assertNull(modelsEndpoint(AiProvider.GEMINI, "", ""))
        assertNull(modelsEndpoint(AiProvider.GITHUB, "  ", ""))
    }
}
