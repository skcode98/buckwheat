package com.danilkinkin.buckwheat.ai

import androidx.datastore.preferences.core.preferencesOf
import com.danilkinkin.buckwheat.di.voiceAiApiKeyStoreKey
import com.danilkinkin.buckwheat.di.voiceAiModelStoreKey
import com.danilkinkin.buckwheat.di.voiceAiProviderUrlStoreKey
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiBackendTest {

    private fun config(model: String = "my-model") =
        AiBackendConfig("https://example.com", "secret-key", model)

    @Test
    fun `chat completions url appends the v1 suffix to a base url`() {
        assertEquals(
            "https://example.com/v1/chat/completions",
            chatCompletionsUrl("https://example.com"),
        )
        assertEquals(
            "https://example.com/v1/chat/completions",
            chatCompletionsUrl("  https://example.com/  "),
        )
    }

    @Test
    fun `chat completions url is kept as-is when it already ends in the path`() {
        assertEquals(
            "https://example.com/v1/chat/completions",
            chatCompletionsUrl("https://example.com/v1/chat/completions"),
        )
    }

    @Test
    fun `models endpoint derives from a base or chat completions url`() {
        assertEquals(
            "https://example.com/v1/models",
            modelsEndpoint("https://example.com"),
        )
        assertEquals(
            "https://example.com/v1/models",
            modelsEndpoint("https://example.com/v1/chat/completions"),
        )
        assertEquals(
            "https://example.com/v1/models",
            modelsEndpoint("https://example.com/v1/models"),
        )
    }

    @Test
    fun `normalizes v1 provider urls without duplicating the path`() {
        assertEquals(
            "https://example.com/v1/chat/completions",
            chatCompletionsUrl("https://example.com/v1"),
        )
        assertEquals(
            "https://example.com/v1/models",
            modelsEndpoint("https://example.com/v1"),
        )
        assertEquals(
            "https://example.com/api/v1/chat/completions",
            chatCompletionsUrl("https://example.com/api/v1"),
        )
        assertEquals(
            "https://example.com/api/v1/models",
            modelsEndpoint("https://example.com/api/v1"),
        )
    }

    @Test
    fun `resolve config allows optional model when url and key are present`() {
        val prefs = preferencesOf(
            voiceAiProviderUrlStoreKey to " https://example.com ",
            voiceAiApiKeyStoreKey to " key ",
            voiceAiModelStoreKey to " model ",
        )
        val resolved = resolveAiBackendConfig(prefs)
        assertEquals("https://example.com", resolved?.url)
        assertEquals("key", resolved?.apiKey)
        assertEquals("model", resolved?.model)

        val withoutModel = resolveAiBackendConfig(
            preferencesOf(
                voiceAiProviderUrlStoreKey to "https://example.com",
                voiceAiApiKeyStoreKey to "key",
            )
        )
        assertEquals("https://example.com", withoutModel?.url)
        assertEquals("key", withoutModel?.apiKey)
        assertEquals("", withoutModel?.model)
    }

    @Test
    fun `resolve config returns null when url or key is blank`() {
        assertNull(resolveAiBackendConfig(preferencesOf()))
        assertNull(
            resolveAiBackendConfig(
                preferencesOf(
                    voiceAiProviderUrlStoreKey to "https://example.com",
                )
            )
        )
        assertNull(
            resolveAiBackendConfig(
                preferencesOf(
                    voiceAiApiKeyStoreKey to "key",
                )
            )
        )
    }

    @Test
    fun `request uses model system and user roles`() {
        val body = JSONObject(
            buildRequestBody(config("openai/gpt-oss-120b"), "You are a helper", "Tell the date")
        )
        assertEquals("openai/gpt-oss-120b", body.getString("model"))
        assertEquals(6000, body.getInt("max_tokens"))
        assertEquals(0, body.getInt("temperature"))
        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("You are a helper", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("Tell the date", messages.getJSONObject(1).getString("content"))
    }

    @Test
    fun `request omits model field when model is blank`() {
        val body = JSONObject(
            buildRequestBody(config(""), "You are a helper", "Tell the date")
        )
        assertFalse(body.has("model"))
    }

    @Test
    fun `request omits system message when prompt is blank`() {
        val body = JSONObject(buildRequestBody(config(), "", "Tell the date"))
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
    }

    @Test
    fun `parses top-level content envelope`() {
        val response = """{"id":"1","content":" the answer ","model":"fast"}"""
        assertEquals("the answer", extractProviderText(response))
    }

    @Test
    fun `parses openai choices envelope with string content`() {
        val response = """
            {"choices":[{"message":{"role":"assistant","content":" 42 "}}]}
        """.trimIndent()
        assertEquals("42", extractProviderText(response))
    }

    @Test
    fun `parses array chat content joining text parts`() {
        val response = """
            {"choices":[{"message":{"content":[
                {"type":"text","text":"hello"},
                {"type":"image_url","image_url":{"url":"x"}},
                {"type":"text","text":" world"}
            ]}}]}
        """.trimIndent()
        assertEquals("hello world", extractProviderText(response))
    }

    @Test
    fun `top-level content wins over choices`() {
        val response = """
            {"content":"direct","choices":[{"message":{"content":"nested"}}]}
        """.trimIndent()
        assertEquals("direct", extractProviderText(response))
    }

    @Test
    fun `empty or malformed responses yield empty string`() {
        assertEquals("", extractProviderText(""))
        assertEquals("", extractProviderText("not json"))
        assertEquals("", extractProviderText("{}"))
        assertEquals("", extractProviderText("""{"choices":[]}"""))
        assertEquals("", extractProviderText("""{"choices":[{"message":{}}]}"""))
        assertEquals("", extractProviderText("""{"choices":[{"message":{"content":""}}]}"""))
    }

    @Test
    fun `top-level content null does not produce the literal string null`() {
        // JSONObject.optString("content", "") would return "null" here, which the downstream
        // parsers would happily accept as an answer. We want an empty string instead.
        assertEquals("", extractProviderText("""{"content":null,"requestId":"req_x"}"""))
    }

    @Test
    fun `top-level content as an object is ignored`() {
        // optString would return "{}" — the same trap as null. Skip it.
        assertEquals("", extractProviderText("""{"content":{},"requestId":"req_x"}"""))
    }

    @Test
    fun `top-level content as a text array is joined`() {
        val response = """{"content":[{"type":"text","text":"hello"},{"type":"image_url"},{"type":"text","text":" world"}]}"""
        assertEquals("hello world", extractProviderText(response))
    }

    @Test
    fun `choices message content null is treated as empty`() {
        val response = """{"choices":[{"message":{"role":"assistant","content":null}}]}"""
        assertEquals("", extractProviderText(response))
    }

    @Test
    fun `clean output drops fences thinking and stray html`() {
        assertEquals("the plan", cleanAiOutput("```markdown\nthe plan\n```"))
        val dirty = """
            <html><body>
            <think>chain of thought</think>
            the plan
            </body></html>
        """.trimIndent()
        assertEquals("the plan", cleanAiOutput(dirty))
    }

    @Test
    fun `clean output keeps think blocks out of answers`() {
        assertEquals(
            "The answer is 500.",
            cleanAiOutput("<think>budget math here</think>The answer is 500."),
        )
    }
}
