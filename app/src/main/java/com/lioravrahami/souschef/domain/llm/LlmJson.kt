package com.lioravrahami.souschef.domain.llm

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** JSON helpers shared by the client and the reply parsers. Never string hacks: everything goes through [parser]. */
internal object LlmJson {

    /** Forgiving parser: cheap models like unquoted strings, trailing commas and comments. */
    @OptIn(ExperimentalSerializationApi::class)
    val parser: Json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        allowTrailingComma = true
        allowComments = true
    }

    private val FENCE = Regex("""```[A-Za-z0-9_-]*""")

    /** Parses [text] as a JSON object; null when it is not valid JSON or not an object. */
    fun parseObject(text: String): JsonObject? {
        if (text.isBlank()) return null
        return try {
            parser.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Finds the JSON object in a model reply: strips Markdown code fences, then parses the
     * substring from the first `{` to the last `}`. Null when there is no parseable object.
     */
    fun extractObject(reply: String): JsonObject? {
        val unfenced = reply.replace(FENCE, "")
        val start = unfenced.indexOf('{')
        val end = unfenced.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return parseObject(unfenced.substring(start, end + 1))
    }

    /** String value of [key], or null when absent / not a primitive. Numbers are returned as text. */
    fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** A number, also when the model quoted it ("1.5"). Null for anything else or non-finite values. */
    fun JsonElement?.number(): Double? {
        val primitive = this as? JsonPrimitive ?: return null
        val value = primitive.doubleOrNull ?: primitive.contentOrNull?.trim()?.toDoubleOrNull() ?: return null
        return value.takeIf { it.isFinite() }
    }

    /** A boolean, also when the model quoted it ("true") or wrote yes/no. */
    fun JsonElement?.flag(): Boolean? {
        val primitive = this as? JsonPrimitive ?: return null
        primitive.booleanOrNull?.let { return it }
        return when (primitive.contentOrNull?.trim()?.lowercase()) {
            "true", "yes" -> true
            "false", "no" -> false
            else -> null
        }
    }
}
