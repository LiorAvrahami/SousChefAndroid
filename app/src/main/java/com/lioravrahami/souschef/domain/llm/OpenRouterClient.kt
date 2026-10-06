package com.lioravrahami.souschef.domain.llm

import com.lioravrahami.souschef.data.settings.AppSettings

// STUB — contract only. The real implementation replaces this file.

/** Minimal OpenRouter chat-completions client (OpenAI-compatible API). */
class OpenRouterClient(private val settings: AppSettings) {

    class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Sends one chat completion and returns the assistant's text.
     * Throws [LlmException] with a human-readable message on any failure (no key, network, HTTP error, empty reply).
     */
    suspend fun chat(
        system: String,
        user: String,
        model: String = settings.openRouterModel,
        temperature: Double = 0.7,
        maxTokens: Int = 2000,
    ): String = throw LlmException("Not implemented")

    /** Cheap connectivity check. Returns the model's short reply or throws [LlmException]. */
    suspend fun testConnection(): String = throw LlmException("Not implemented")
}
