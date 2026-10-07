package com.lioravrahami.souschef.llm

import com.lioravrahami.souschef.domain.llm.ChatRequest
import com.lioravrahami.souschef.domain.llm.ChatTransport
import com.lioravrahami.souschef.domain.llm.OpenRouterClient
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Canned OpenRouter answers, consumed in order. Records every request. No network. */
internal class FakeTransport(vararg answers: () -> String) : ChatTransport {
    private val queue = ArrayDeque(answers.toList())
    val requests = mutableListOf<ChatRequest>()

    override suspend fun send(request: ChatRequest): String {
        requests += request
        val next = queue.removeFirstOrNull() ?: error("FakeTransport: unexpected request #${requests.size}")
        return next()
    }

    companion object {
        const val MODEL = "test/model"

        /** A successful chat-completions body whose assistant message is [content]. */
        fun completion(content: String): String = buildJsonObject {
            put("id", "gen-1")
            putJsonArray("choices") {
                addJsonObject {
                    putJsonObject("message") {
                        put("role", "assistant")
                        put("content", content)
                    }
                    put("finish_reason", "stop")
                }
            }
        }.toString()

        /** An answer that replies with [content]. */
        fun reply(content: String): () -> String = { completion(content) }

        fun client(transport: FakeTransport, apiKey: String = "sk-test"): OpenRouterClient =
            OpenRouterClient(apiKey = { apiKey }, defaultModel = { MODEL }, transport = transport)
    }
}
