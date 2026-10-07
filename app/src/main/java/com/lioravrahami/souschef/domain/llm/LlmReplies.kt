package com.lioravrahami.souschef.domain.llm

import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.domain.llm.LlmJson.flag
import com.lioravrahami.souschef.domain.llm.LlmJson.number
import com.lioravrahami.souschef.domain.llm.LlmJson.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/**
 * A proposal exactly as a model wrote it, after JSON parsing but before any validation
 * against the recipe. Version references are still the model's text (alias like "v2" or a
 * full id); see `ProposalValidator`.
 */
internal sealed class RawProposal {
    abstract val summary: String
    abstract val rationale: String

    data class Values(
        val versionRef: String,
        val values: List<Double>,
        override val summary: String,
        override val rationale: String,
    ) : RawProposal()

    data class NewVersion(
        val parentRef: String,
        val name: String,
        val steps: List<Step>,
        override val summary: String,
        override val rationale: String,
    ) : RawProposal()
}

/** The checker's answer. */
internal sealed class CheckerVerdict {
    data object Approved : CheckerVerdict()

    /** Not ok. [revised] is the toned-down proposal if the checker gave a parseable one. */
    data class Rejected(val reason: String, val revised: RawProposal?) : CheckerVerdict()

    /** The reply was not the JSON we asked for. */
    data object Unreadable : CheckerVerdict()
}

/** Turns model replies into [RawProposal] / [CheckerVerdict]. Pure functions, no recipe knowledge. */
internal object LlmReplies {

    /** Parses a suggester reply (or a checker's `revised` object). Null when it is not a usable proposal. */
    fun parseProposal(reply: String): RawProposal? = LlmJson.extractObject(reply)?.let(::proposalFrom)

    /** Parses a checker reply. */
    fun parseVerdict(reply: String): CheckerVerdict {
        val obj = LlmJson.extractObject(reply) ?: return CheckerVerdict.Unreadable
        val ok = obj["ok"].flag() ?: return CheckerVerdict.Unreadable
        if (ok) return CheckerVerdict.Approved
        val reason = obj.string("reason")?.trim().orEmpty()
        val revised = (obj["revised"] as? JsonObject)?.let(::proposalFrom)
        return CheckerVerdict.Rejected(reason, revised)
    }

    fun proposalFrom(obj: JsonObject): RawProposal? {
        val type = obj.string("type")?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_')
        val summary = obj.string("summary")?.trim().orEmpty()
        val rationale = obj.string("rationale")?.trim().orEmpty()
        val isNewVersion = when (type) {
            "values", "value" -> false
            "new_version", "newversion", "version" -> true
            else -> when {
                obj["steps"] is JsonArray -> true
                obj["values"] is JsonArray -> false
                else -> return null
            }
        }
        return if (isNewVersion) {
            val steps = (obj["steps"] as? JsonArray)?.let(::parseSteps) ?: return null
            RawProposal.NewVersion(
                parentRef = (obj.string("parentVersionId") ?: obj.string("versionId")).orEmpty().trim(),
                name = obj.string("name")?.trim().orEmpty(),
                steps = steps,
                summary = summary,
                rationale = rationale,
            )
        } else {
            val array = obj["values"] as? JsonArray ?: return null
            val values = array.map { it.number() ?: return null }
            RawProposal.Values(
                versionRef = obj.string("versionId").orEmpty().trim(),
                values = values,
                summary = summary,
                rationale = rationale,
            )
        }
    }

    /** All steps or null: one malformed step makes the whole list unusable. */
    private fun parseSteps(array: JsonArray): List<Step>? {
        val steps = ArrayList<Step>(array.size)
        for (element in array) {
            val obj = element as? JsonObject ?: return null
            steps += parseStep(obj) ?: return null
        }
        return steps
    }

    private fun parseStep(obj: JsonObject): Step? {
        val type = obj.string("type")?.trim()?.lowercase()
        val isWait = when (type) {
            "wait", "timer" -> true
            "text", "step" -> false
            else -> obj["seconds"] != null && obj["text"] == null
        }
        return if (isWait) {
            val seconds = obj["seconds"].number() ?: obj["minutes"].number()?.times(60) ?: return null
            Step.Wait(
                label = obj.string("label")?.trim().orEmpty(),
                seconds = seconds.roundToInt(),
                locked = obj["locked"].flag() ?: false,
            )
        } else {
            val text = obj.string("text") ?: return null
            val locked = (obj["locked"] as? JsonArray)
                ?.mapNotNull { it.number()?.takeIf { n -> n >= 0 && n == Math.floor(n) }?.toInt() }
                ?.distinct()
                ?.sorted()
                .orEmpty()
            Step.Text(text = text.trim(), locked = locked)
        }
    }
}
