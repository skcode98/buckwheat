package com.danilkinkin.buckwheat.ai

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey

// The shared AI engine's provider table. One router (AiProviderRouter) drives every AI feature in
// the app (voice parsing, spend categorization, monthly insights) and falls back across these
// providers in order. Each provider ships with sensible defaults, but every field (API key, URL,
// model) is replaceable in the AI engine settings sheet — nothing is hardcoded at the call sites.
enum class AiProvider(
    val id: String,
    val displayName: String,
    val defaultUrl: String,
    val defaultModel: String,
    val usesChatCompletions: Boolean,
) {
    GEMINI(
        id = "gemini",
        displayName = "Gemini",
        defaultUrl = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
        defaultModel = "gemini-2.5-flash",
        usesChatCompletions = false,
    ),
    GROQ(
        id = "groq",
        displayName = "Groq",
        defaultUrl = "https://api.groq.com/openai/v1/chat/completions",
        // llama-3.3-70b-versatile shut down 2026-08-16 -> openai/gpt-oss-120b (Groq's recommended
        // replacement, active + fast on LPU).
        defaultModel = "openai/gpt-oss-120b",
        usesChatCompletions = true,
    ),
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter",
        defaultUrl = "https://openrouter.ai/api/v1/chat/completions",
        defaultModel = "openrouter/free",
        usesChatCompletions = true,
    ),
    CEREBRAS(
        id = "cerebras",
        displayName = "Cerebras",
        defaultUrl = "https://api.cerebras.ai/v1/chat/completions",
        defaultModel = "gpt-oss-120b",
        usesChatCompletions = true,
    ),
    GITHUB(
        id = "github",
        displayName = "GitHub Models",
        // GitHub Models requires publisher-qualified ids ("openai/gpt-4o-mini"); bare ids 404.
        // The old models.inference.ai.azure.com endpoint is dead; models.github.ai/inference is
        // current (OpenAI-compatible, no trailing /v1).
        defaultUrl = "https://models.github.ai/inference/chat/completions",
        defaultModel = "openai/gpt-4o-mini",
        usesChatCompletions = true,
    ),
    NIM(
        id = "nim",
        displayName = "NVIDIA NIM",
        defaultUrl = "https://integrate.api.nvidia.com/v1/chat/completions",
        // v1 deprecated (shutdown 2026-08-25); v1.5 is the current serve.
        defaultModel = "nvidia/llama-3.3-nemotron-super-49b-v1.5",
        usesChatCompletions = true,
    );

    // Structural key check so obviously wrong keys never waste a request (and are blocked from
    // being saved). Keys are trimmed before validation.
    fun isValidKey(key: String): Boolean {
        val trimmed = key.trim()
        return when (this) {
            GEMINI -> GEMINI_KEY.matches(trimmed)
            GROQ -> GROQ_KEY.matches(trimmed)
            OPENROUTER -> OPENROUTER_KEY.matches(trimmed)
            CEREBRAS -> trimmed.length > 20 &&
                (trimmed.startsWith("cerebras_") || trimmed.length > 30)
            GITHUB -> trimmed.length > 10 &&
                (trimmed.startsWith("github_pat_") || trimmed.startsWith("ghp_") || trimmed.length > 20)
            NIM -> NIM_KEY.matches(trimmed)
        }
    }

    companion object {
        // The shared fallback chain: the first configured provider that answers wins.
        val FALLBACK_ORDER = listOf(GEMINI, GROQ, OPENROUTER, CEREBRAS, GITHUB, NIM)

        private val GEMINI_KEY = Regex("^[A-Za-z0-9_-]{35,}$")
        private val GROQ_KEY = Regex("^gsk_[A-Za-z0-9_-]{48,}$")
        private val OPENROUTER_KEY = Regex("^sk-or-v1-[A-Za-z0-9_-]{40,}$")
        private val NIM_KEY = Regex("^nvapi-[A-Za-z0-9_-]{30,}$")
    }
}

// Per-provider DataStore keys. API keys are never written to backups (see BackupRepository).
fun aiApiKeyStoreKey(provider: AiProvider): Preferences.Key<String> =
    stringPreferencesKey("ai.${provider.id}.apiKey")
fun aiProviderUrlStoreKey(provider: AiProvider): Preferences.Key<String> =
    stringPreferencesKey("ai.${provider.id}.providerUrl")
fun aiModelStoreKey(provider: AiProvider): Preferences.Key<String> =
    stringPreferencesKey("ai.${provider.id}.model")
// The user-editable fallback chain as a comma-separated id list. Absent or unparseable values fall
// back to FALLBACK_ORDER, and any provider missing from the stored list is appended at the end, so
// the chain always covers every provider.
fun aiProviderOrderStoreKey(): Preferences.Key<String> =
    stringPreferencesKey("ai.providerOrder")

// Resolves the effective fallback order from a DataStore snapshot: stored ids first (deduped,
// unknown ids dropped), then any remaining providers in the default order. Pure so it is
// unit-testable.
fun resolveProviderOrder(prefs: Preferences): List<AiProvider> {
    val stored = prefs[aiProviderOrderStoreKey()]
    val ordered = stored
        ?.split(",")
        ?.mapNotNull { id -> AiProvider.values().firstOrNull { it.id == id.trim() } }
        ?.distinct()
        .orEmpty()
    return ordered + AiProvider.FALLBACK_ORDER.filter { it !in ordered }
}
