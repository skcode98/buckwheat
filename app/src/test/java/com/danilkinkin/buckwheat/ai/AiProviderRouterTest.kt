package com.danilkinkin.buckwheat.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AiProviderRouterTest {

    private fun config(
        provider: AiProvider = AiProvider.GEMINI,
        model: String = "gemini-2.5-flash",
    ) = AiProviderConfig(provider, "k", "https://example.com", model)

    @Test
    fun `gemini request uses generate-content shape with flattened prompts`() {
        val body = JSONObject(
            buildRequestBody(config(AiProvider.GEMINI), "You are a helper", "Tell the date")
        )
        assertFalse(body.has("model"))
        assertFalse(body.has("messages"))
        val contents = body.getJSONArray("contents")
        assertEquals(1, contents.length())
        val parts = contents.getJSONObject(0).getJSONArray("parts")
        assertEquals(1, parts.length())
        assertEquals(
            "You are a helper\n\nTell the date",
            parts.getJSONObject(0).getString("text"),
        )
        val gen = body.getJSONObject("generationConfig")
        assertEquals(0, gen.getInt("temperature"))
        assertEquals(6000, gen.getInt("maxOutputTokens"))
    }

    @Test
    fun `chat-completions request uses model system and user roles`() {
        val body = JSONObject(
            buildRequestBody(
                config(AiProvider.GROQ, "openai/gpt-oss-120b"),
                "You are a helper",
                "Tell the date",
            )
        )
        assertEquals("openai/gpt-oss-120b", body.getString("model"))
        assertEquals(6000, body.getInt("max_tokens"))
        assertEquals(0, body.getInt("temperature"))
        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("You are a helper", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertFalse(body.has("contents"))
    }

    @Test
    fun `chat-completions request omits system message when prompt is blank`() {
        val body = JSONObject(buildRequestBody(config(AiProvider.GROQ), "", "Tell the date"))
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
    }

    @Test
    fun `parses string chat content`() {
        val response = """
            {"choices":[{"message":{"role":"assistant","content":" 42 "}}]}
        """.trimIndent()
        assertEquals("42", extractProviderText(response, AiProvider.GROQ))
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
        assertEquals("hello world", extractProviderText(response, AiProvider.OPENROUTER))
    }

    @Test
    fun `parses gemini multiple text parts in order`() {
        val response = """
            {"candidates":[{"content":{"role":"model","parts":[
                {"text":"alpha"},
                {"inlineData":{"mimeType":"image/png"}},
                {"text":" beta"}
            ]}}]}
        """.trimIndent()
        assertEquals("alpha beta", extractProviderText(response, AiProvider.GEMINI))
    }

    @Test
    fun `strips thinking block from nim output`() {
        val response = """
            {"choices":[{"message":{"content":"<think>budget math here</think>The answer is 500."}}]}
        """.trimIndent()
        val raw = extractProviderText(response, AiProvider.NIM)
        assertEquals(
            "The answer is 500.",
            cleanAiOutput(raw),
        )
    }

    @Test
    fun `empty or malformed responses yield empty string`() {
        assertEquals("", extractProviderText("", AiProvider.GEMINI))
        assertEquals("", extractProviderText("not json", AiProvider.GEMINI))
        assertEquals("", extractProviderText("{}", AiProvider.GEMINI))
        assertEquals("", extractProviderText("{}", AiProvider.GROQ))
        assertEquals("", extractProviderText("""{"choices":[]}""", AiProvider.GROQ))
        assertEquals(
            "",
            extractProviderText("""{"choices":[{"message":{}}]}""", AiProvider.GROQ),
        )
        assertEquals(
            "",
            extractProviderText(
                """{"candidates":[{"content":{"parts":[]}}]}""",
                AiProvider.GEMINI,
            ),
        )
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
}
