package com.lioravrahami.souschef.domain.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Everything needed for one chat-completions call. */
internal data class ChatRequest(
    val apiKey: String,
    val model: String,
    val system: String,
    val user: String,
    val temperature: Double,
    val maxTokens: Int,
) {
    /** The OpenAI-compatible JSON body. */
    fun bodyJson(): String = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "system")
                put("content", system)
            }
            addJsonObject {
                put("role", "user")
                put("content", user)
            }
        }
        put("temperature", temperature)
        put("max_tokens", maxTokens)
    }.toString()

    override fun toString(): String = "ChatRequest(model=$model, temperature=$temperature, maxTokens=$maxTokens)"
}

/** The server answered with a non-2xx status. [body] is the raw response body (may hold `error.message`). */
internal class HttpStatusException(val code: Int, val body: String) : IOException("HTTP $code")

/**
 * Sends a [ChatRequest] and returns the raw response body of a 2xx answer.
 * Throws [HttpStatusException] for other statuses and [IOException] for network failures.
 * Swappable so tests never touch the network.
 */
internal fun interface ChatTransport {
    suspend fun send(request: ChatRequest): String
}

/** Real transport: OkHttp with generous timeouts (cheap models can be slow to start). */
internal class OkHttpChatTransport(
    private val http: OkHttpClient = defaultClient,
) : ChatTransport {

    override suspend fun send(request: ChatRequest): String = withContext(Dispatchers.IO) {
        val httpRequest = Request.Builder()
            .url(OpenRouterClient.ENDPOINT)
            .header("Authorization", "Bearer ${request.apiKey}")
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", OpenRouterClient.REFERER)
            .header("X-Title", OpenRouterClient.APP_TITLE)
            .post(request.bodyJson().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val call = http.newCall(httpRequest)
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            val body = it.body?.string().orEmpty()
                            if (!it.isSuccessful) throw HttpStatusException(it.code, body)
                            body
                        }
                    }
                    if (!continuation.isActive) return
                    result.fold(
                        onSuccess = { continuation.resume(it) },
                        onFailure = { continuation.resumeWithException(it) },
                    )
                }
            })
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}
