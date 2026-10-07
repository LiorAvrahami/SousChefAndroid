package com.lioravrahami.souschef.llm

import com.lioravrahami.souschef.domain.llm.HttpStatusException
import com.lioravrahami.souschef.domain.llm.OpenRouterClient.LlmException
import com.lioravrahami.souschef.llm.FakeTransport.Companion.client
import com.lioravrahami.souschef.llm.FakeTransport.Companion.reply
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class OpenRouterClientTest {

    private fun errorBody(message: String) = """{"error":{"code":0,"message":"$message"}}"""

    /** Runs one chat call whose transport fails with [failure]; returns the resulting message. */
    private fun messageFor(failure: Throwable): String = runTestFor(FakeTransport({ throw failure }))

    private fun runTestFor(transport: FakeTransport, apiKey: String = "sk-test"): String {
        var message = ""
        runTest {
            try {
                client(transport, apiKey).chat("system", "user")
                fail("Expected LlmException")
            } catch (e: LlmException) {
                message = e.message.orEmpty()
            }
        }
        return message
    }

    @Test
    fun returnsTheAssistantContentAndSendsTheRequestedBody() = runTest {
        val transport = FakeTransport(reply("  Hello cook  "))
        val text = client(transport).chat("sys", "usr", temperature = 0.2, maxTokens = 50)
        assertEquals("Hello cook", text)

        val request = transport.requests.single()
        assertEquals("sk-test", request.apiKey)
        assertEquals(FakeTransport.MODEL, request.model)
        val body = Json.parseToJsonElement(request.bodyJson()).jsonObject
        assertEquals(FakeTransport.MODEL, body["model"]!!.jsonPrimitive.content)
        assertEquals("0.2", body["temperature"]!!.jsonPrimitive.content)
        assertEquals("50", body["max_tokens"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray.map { it as JsonObject }
        assertEquals(listOf("system", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        assertEquals(listOf("sys", "usr"), messages.map { it["content"]!!.jsonPrimitive.content })
    }

    @Test
    fun requestDoesNotLeakTheKeyInToString() {
        val transport = FakeTransport(reply("x"))
        runTest { client(transport, apiKey = "sk-secret").chat("s", "u") }
        assertTrue("sk-secret" !in transport.requests.single().toString())
    }

    @Test
    fun explicitModelIsUsedInRequestAndErrors() = runTest {
        val transport = FakeTransport({ throw HttpStatusException(404, "") })
        try {
            client(transport).chat("s", "u", model = "other/model")
            fail("Expected LlmException")
        } catch (e: LlmException) {
            assertEquals("other/model", transport.requests.single().model)
            assertTrue(e.message!!.startsWith("Model 'other/model' was not found on OpenRouter."))
        }
    }

    @Test
    fun missingKeyFailsWithoutCallingTheServer() {
        val transport = FakeTransport()
        assertEquals("Add your OpenRouter API key in Settings first.", runTestFor(transport, apiKey = "  "))
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun keyWithCharactersOkHttpCannotSendFailsWithoutCallingTheServer() {
        val expected = "The API key contains characters that are not allowed. Paste it again in Settings."
        for (key in listOf("sk-or-\u200Babc", "sk-or\u00A0abc", "\u05E9k-or-abc")) {
            val transport = FakeTransport()
            assertEquals(expected, runTestFor(transport, apiKey = key))
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test
    fun illegalArgumentFromTheTransportBecomesAnLlmException() {
        assertEquals(
            "The API key contains characters that are not allowed. Paste it again in Settings.",
            messageFor(IllegalArgumentException("Unexpected char 0x200b at 13 in Authorization value")),
        )
    }

    @Test
    fun unauthorizedAndForbiddenMeanABadKey() {
        assertEquals("OpenRouter rejected the API key. Check it in Settings.", messageFor(HttpStatusException(401, "")))
        assertTrue(messageFor(HttpStatusException(403, "")).startsWith("OpenRouter rejected the API key."))
    }

    @Test
    fun serverMessageIsAppended() {
        val message = messageFor(HttpStatusException(401, errorBody("No auth credentials found")))
        assertEquals(
            "OpenRouter rejected the API key. Check it in Settings. (OpenRouter says: No auth credentials found)",
            message,
        )
    }

    @Test
    fun paymentRequiredMeansNoCredits() {
        assertTrue(messageFor(HttpStatusException(402, errorBody("Insufficient credits")))
            .startsWith("Your OpenRouter account has no credits left."))
    }

    @Test
    fun notFoundAndBadModelMeanUnknownModel() {
        val expected = "Model '${FakeTransport.MODEL}' was not found on OpenRouter. Check the model name in Settings."
        assertEquals(expected, messageFor(HttpStatusException(404, "")))
        assertTrue(messageFor(HttpStatusException(400, errorBody("foo/bar is not a valid model ID"))).startsWith(expected))
        assertTrue(messageFor(HttpStatusException(400, errorBody("messages must not be empty")))
            .startsWith("OpenRouter refused the request (HTTP 400)."))
    }

    @Test
    fun tooManyRequestsMeansRateLimit() {
        assertEquals(
            "OpenRouter is rate-limiting requests. Try again in a minute.",
            messageFor(HttpStatusException(429, "")),
        )
    }

    @Test
    fun serverErrorsMentionTheCode() {
        assertEquals(
            "OpenRouter is having trouble (HTTP 503). Try again later.",
            messageFor(HttpStatusException(503, "<html>Service unavailable</html>")),
        )
    }

    @Test
    fun networkFailuresMeanOffline() {
        assertEquals("Could not reach OpenRouter. Are you online?", messageFor(IOException("unreachable")))
        assertEquals("Could not reach OpenRouter. Are you online?", messageFor(SocketTimeoutException("timeout")))
    }

    @Test
    fun emptyContentIsAnError() {
        assertEquals("The AI returned an empty reply.", runTestFor(FakeTransport(reply("   "))))
        assertEquals(
            "The AI returned an empty reply.",
            runTestFor(FakeTransport({ """{"choices":[{"message":{"role":"assistant","content":null}}]}""" })),
        )
    }

    @Test
    fun errorObjectInsideA200IsMapped() {
        val body = """{"error":{"code":429,"message":"Provider returned error"}}"""
        assertEquals(
            "OpenRouter is rate-limiting requests. Try again in a minute. (OpenRouter says: Provider returned error)",
            runTestFor(FakeTransport({ body })),
        )
    }

    @Test
    fun unreadableBodyIsAnError() {
        assertTrue(runTestFor(FakeTransport({ "<html>oops</html>" })).startsWith("OpenRouter sent a reply that could not be read."))
    }

    @Test
    fun contentPartsAreJoined() = runTest {
        val body = """{"choices":[{"message":{"content":[{"type":"text","text":"Hel"},{"type":"text","text":"lo"}]}}]}"""
        assertEquals("Hello", client(FakeTransport({ body })).chat("s", "u"))
    }

    @Test
    fun testConnectionSendsATinyRequestAndTrims() = runTest {
        val transport = FakeTransport(reply(" OK\n"))
        assertEquals("OK", client(transport).testConnection())
        val request = transport.requests.single()
        assertEquals(10, request.maxTokens)
        assertEquals("Reply with the single word OK.", request.user)
    }
}
