package com.lioravrahami.souschef.domain.llm

import com.lioravrahami.souschef.data.settings.AppSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException

/**
 * Minimal OpenRouter chat-completions client (OpenAI-compatible API).
 *
 * The public constructor reads the API key and default model from [AppSettings] on every
 * call, so changes made on the Settings screen apply immediately. The internal constructor
 * exists for JVM unit tests, which cannot build an [AppSettings] (it needs an Android
 * `Context`) and must not touch the network.
 */
class OpenRouterClient internal constructor(
    private val apiKey: () -> String,
    private val defaultModel: () -> String,
    private val transport: ChatTransport,
) {

    /** Production client: key and model from [settings], HTTP through OkHttp. */
    constructor(settings: AppSettings) : this(
        apiKey = { settings.openRouterApiKey },
        defaultModel = { settings.openRouterModel },
        transport = OkHttpChatTransport(),
    )

    /** A failure the UI can show as-is: the message is written for a non-technical cook. */
    class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Sends one chat completion and returns the assistant's text.
     * Throws [LlmException] with a human-readable message on any failure (no key, network, HTTP error, empty reply).
     */
    suspend fun chat(
        system: String,
        user: String,
        model: String = defaultModel(),
        temperature: Double = 0.7,
        maxTokens: Int = 2000,
    ): String {
        val key = apiKey().trim()
        if (key.isEmpty()) throw LlmException("Add your OpenRouter API key in Settings first.")
        // OkHttp throws IllegalArgumentException for header characters outside \t and 0x20..0x7E
        // (e.g. a key typed on a Hebrew layout, or pasted with a zero-width or non-breaking space).
        if (key.any { it != '\t' && it !in ' '..'~' }) throw LlmException(INVALID_KEY_CHARS)
        val request = ChatRequest(
            apiKey = key,
            model = model.trim(),
            system = system,
            user = user,
            temperature = temperature,
            maxTokens = maxTokens,
        )
        val body = try {
            transport.send(request)
        } catch (e: HttpStatusException) {
            throw httpError(e.code, e.body, request.model, e)
        } catch (e: IOException) {
            throw LlmException("Could not reach OpenRouter. Are you online?", e)
        } catch (e: IllegalArgumentException) {
            // Safety net: OkHttp rejects header values it cannot send; never leak a raw exception.
            throw LlmException(INVALID_KEY_CHARS, e)
        }
        return parseContent(body, request.model)
    }

    /** Cheap connectivity check. Returns the model's short reply or throws [LlmException]. */
    suspend fun testConnection(): String =
        chat(
            system = "You are a connectivity check. Follow the instruction exactly.",
            user = "Reply with the single word OK.",
            temperature = 0.0,
            maxTokens = 10,
        ).trim()

    /** Extracts `choices[0].message.content` from a successful response body. */
    private fun parseContent(body: String, model: String): String {
        val root = LlmJson.parseObject(body)
            ?: throw LlmException("OpenRouter sent a reply that could not be read. Try again.")

        // OpenRouter sometimes answers HTTP 200 with an error object (e.g. upstream provider failures).
        val error = root["error"] as? JsonObject
        val choices = root["choices"] as? JsonArray
        if (error != null && choices.isNullOrEmpty()) {
            val code = (error["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 500
            throw httpError(code, body, model, null)
        }

        val message = (choices?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
        val text = contentText(message?.get("content")).trim()
        if (text.isEmpty()) throw LlmException("The AI returned an empty reply.")
        return text
    }

    /** `content` is normally a string; some providers send an array of `{type, text}` parts. */
    private fun contentText(content: JsonElement?): String = when (content) {
        null, JsonNull -> ""
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.joinToString("") { part ->
            when (part) {
                is JsonPrimitive -> part.contentOrNull.orEmpty()
                is JsonObject -> (part["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                else -> ""
            }
        }
        is JsonObject -> (content["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }

    internal companion object {
        const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        const val REFERER = "https://github.com/LiorAvrahami/SousChefAndroid"
        const val APP_TITLE = "Sous Chef"
        private const val MAX_SERVER_MESSAGE = 160
        const val INVALID_KEY_CHARS =
            "The API key contains characters that are not allowed. Paste it again in Settings."
        private val WHITESPACE = Regex("""\s+""")

        /** Maps an HTTP failure to a message a cook understands, plus the server's short explanation. */
        fun httpError(code: Int, body: String, model: String, cause: Throwable?): LlmException {
            val serverMessage = serverMessage(body)
            val mentionsModel = serverMessage.orEmpty().contains("model", ignoreCase = true)
            val base = when {
                code == 401 || code == 403 -> "OpenRouter rejected the API key. Check it in Settings."
                code == 402 -> "Your OpenRouter account has no credits left."
                code == 404 || (code == 400 && mentionsModel) ->
                    "Model '$model' was not found on OpenRouter. Check the model name in Settings."
                code == 408 -> "OpenRouter took too long to answer. Try again."
                code == 429 -> "OpenRouter is rate-limiting requests. Try again in a minute."
                code >= 500 -> "OpenRouter is having trouble (HTTP $code). Try again later."
                else -> "OpenRouter refused the request (HTTP $code)."
            }
            val full = if (serverMessage == null) base else "$base (OpenRouter says: $serverMessage)"
            return LlmException(full, cause)
        }

        /** The server's `error.message`, shortened to one line; null when absent. */
        fun serverMessage(body: String): String? {
            val root = LlmJson.parseObject(body) ?: return null
            val raw = when (val error = root["error"]) {
                is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> error.contentOrNull
                else -> null
            } ?: (root["message"] as? JsonPrimitive)?.contentOrNull
            val oneLine = raw?.replace(WHITESPACE, " ")?.trim()
            if (oneLine.isNullOrEmpty()) return null
            return if (oneLine.length <= MAX_SERVER_MESSAGE) {
                oneLine
            } else {
                oneLine.take(MAX_SERVER_MESSAGE - 1).trimEnd() + "…"
            }
        }
    }
}
