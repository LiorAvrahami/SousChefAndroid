package com.lioravrahami.souschef.domain.llm

import com.lioravrahami.souschef.data.model.RatingAxis
import com.lioravrahami.souschef.data.model.RecipeVersion
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.data.model.Trial
import com.lioravrahami.souschef.domain.recipe.ChangeSummary
import com.lioravrahami.souschef.domain.recipe.ParamSpec
import com.lioravrahami.souschef.domain.recipe.StepParser
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Prompt texts for the two agents. Compact on purpose: cheap models, every token is paid for. */
internal object LlmPrompts {

    val SUGGESTER_SYSTEM: String = """
        You are a careful, experienced cook who improves one recipe through small, informative cooking experiments. Every time the recipe is cooked (a "trial") the cook rates it, and you decide what to try next.

        HOW THE RECIPE IS WRITTEN
        - A recipe has one or more versions; a version is a numbered list of steps. Versions have short ids like "v1".
        - In a text step every tweakable amount is written as number[unit], e.g. "Add 1.75[cups] water" or "Bake at 180[°C]".
        - A wait step is a timer; its duration in seconds is also a tweakable amount (unit "s").
        - The amounts of a version form a vector of values in the order of that version's parameter table (#0, #1, ...). Each trial records the full vector it was cooked with.
        - LOCKED amounts must stay exactly at their base value.

        HOW COOKS ARE RATED
        - overall: 0 (inedible) to 10 (perfect).
        - Axes from -2 to +2: negative means the low label (e.g. "Too dry"), positive the high label (e.g. "Too wet"), 0 = just right; 1 = a bit, 2 = a lot.
        - Notes are free text from the cook and are often the most telling feedback.

        YOUR TASK
        Propose ONE tweak that is most likely to raise the overall score.
        - Prefer fixing the strongest consistent axis complaint: repeatedly "Too wet" -> less liquid or longer cooking; "Too salty" -> less salt; "Burnt" -> lower heat or shorter time.
        - Start from the current best values unless the history clearly points elsewhere. Change only the one to three amounts that matter.
        - Size each change by BOLDNESS, a relative change per amount (0.15 = about 15%). Go up to about 2x BOLDNESS only when the evidence is strong and repeated.
        - Prefer changing values. Propose a new version (reorder, add, remove or reword steps) only when the notes or history clearly call for it, e.g. "burnt outside, raw inside" -> lower heat and longer time, or "add the garlic later". A new version changes at most 2 steps of its parent and keeps every locked amount exactly (same number and unit).
        - Never change locked amounts. Never make an amount zero or negative. Keep food safe: meat, poultry, fish and eggs must still cook through.
        - Never pick an archived version for a values tweak.

        REPLY FORMAT
        Reply with ONLY one JSON object and nothing else. Either:
        {"type":"values","versionId":"v1","values":[...],"summary":"<one line>","rationale":"<2-4 sentences>"}
        or:
        {"type":"new_version","parentVersionId":"v1","name":"<short>","steps":[{"type":"text","text":"Add 1.5[cups] water","locked":[0]},{"type":"wait","label":"Simmer","seconds":600,"locked":false}],"summary":"<one line>","rationale":"<2-4 sentences>"}
        - "values" holds one plain number per parameter of that version, in parameter order, including unchanged and locked ones; wait steps in seconds.
        - In new_version steps write every amount as number[unit]; "locked" lists the 0-based positions of the locked amounts within that step.
        - summary: what to do differently this time, in plain words for the cook, e.g. "Use a little less water and simmer 2 minutes longer".
        - rationale: why, citing the feedback that motivates it.
    """.trimIndent()

    val CHECKER_SYSTEM: String = """
        You are a cautious reviewer of recipe experiments. Another cook proposed the next tweak to a recipe. Check it against every rule:
        (a) A values proposal has exactly one value per parameter of its version.
        (b) Locked parameters are unchanged.
        (c) No parameter changes by more than 3x BOLDNESS relative to its starting value (BOLDNESS 0.15 -> at most 45%). Up to 5x BOLDNESS is acceptable only when the rationale cites strong, repeated feedback.
        (d) No value becomes zero or negative unless it was already zero.
        (e) Nothing is unsafe or absurd for cooking, e.g. raw meat, poultry, fish or eggs cooked much shorter or colder, a wait cut to almost nothing, an absurd quantity.
        (f) A new version changes at most 2 steps relative to its parent and keeps every locked amount exactly (same number and unit).

        Reply with ONLY one JSON object and nothing else:
        {"ok":true} when every rule holds, or
        {"ok":false,"reason":"<one short sentence>","revised":<the proposal in the same JSON schema, toned down so that every rule holds>}
        A revised proposal keeps the same "type" and version id and lists ALL values (one per parameter, in order).
    """.trimIndent()

    /** The suggester's view of the whole recipe history. */
    fun suggesterUser(context: RecipeContext, boldness: Double): String = buildString {
        val details = context.details
        appendLine("RECIPE: ${details.recipe.name}")
        details.recipe.notes.oneLine().takeIf { it.isNotEmpty() }?.let { appendLine("Recipe notes: $it") }
        appendLine("Rating axes (id: -2 label / +2 label): " + details.axes.joinToString("; ") { axisText(it) })
        appendLine("BOLDNESS: ${number(boldness)} (typical change about ${percent(boldness)} per tweaked amount)")
        appendLine()
        appendLine("VERSIONS (oldest first)")
        for (version in context.versions) appendVersion(context, version)
        appendLine()
        val total = details.doneTrials.size
        if (context.recentTrials.isEmpty()) {
            appendLine("TRIALS: none rated yet.")
        } else {
            appendLine("TRIALS (newest first, ${context.recentTrials.size} of $total rated cooks)")
            for (trial in context.recentTrials) appendLine("- " + trialLine(context, trial))
        }
        appendLine()
        append(currentBest(context))
    }

    private fun StringBuilder.appendVersion(context: RecipeContext, version: RecipeVersion) {
        val parent = version.parentVersionId?.let { context.alias(it) } ?: "none"
        val state = if (version.archived) "ARCHIVED" else "active"
        appendLine("== ${context.alias(version)} \"${version.name}\" ($state, origin ${version.origin}, parent $parent) ==")
        version.note.oneLine().takeIf { it.isNotEmpty() }?.let { appendLine("Note: $it") }
        appendLine("Steps:")
        version.steps.forEachIndexed { i, step -> appendLine("${i + 1}. " + stepText(step)) }
        val params = StepParser.params(version.steps)
        if (params.isEmpty()) {
            appendLine("Parameters: none (only a new version can change this one)")
        } else {
            appendLine("Parameters (#, step, name, unit, base):")
            for (p in params) appendLine(paramLine(p))
        }
    }

    /** Text steps raw (they already show number[unit]); wait steps as seconds. */
    private fun stepText(step: Step): String = when (step) {
        is Step.Text -> step.text.oneLine()
        is Step.Wait -> "WAIT ${step.seconds} s" + (if (step.label.isBlank()) "" else " \"${step.label.oneLine()}\"")
    }

    private fun paramLine(p: ParamSpec): String {
        val duration = if (p.isWait) " (${StepParser.formatDuration(p.baseValue.roundToInt())})" else ""
        val locked = if (p.locked) " LOCKED" else ""
        return "#${p.index} | step ${p.stepIndex + 1} | ${p.label} | ${p.unit.ifBlank { "-" }} | " +
            "${number(p.baseValue)}$duration$locked"
    }

    private fun trialLine(context: RecipeContext, trial: Trial): String {
        val version = context.details.version(trial.versionId)
        val parts = mutableListOf(
            context.alias(trial.versionId),
            date(trial.finishedAt ?: trial.createdAt),
            trial.mode.name,
            "values " + vector(trial.values),
        )
        if (version != null && trial.values.size == StepParser.params(version.steps).size) {
            parts += "vs written: " + ChangeSummary.oneLine(version.steps, null, trial.values)
        }
        parts += "score " + number(trial.overallScore ?: 0.0) + "/10"
        axesText(context, trial).takeIf { it.isNotEmpty() }?.let { parts += it }
        trial.notes.oneLine().takeIf { it.isNotEmpty() }?.let { parts += "notes: \"$it\"" }
        return parts.joinToString(" | ")
    }

    private fun currentBest(context: RecipeContext): String {
        val ref = context.reference
        val alias = context.alias(ref.version)
        val trial = ref.trial
        return if (trial != null) {
            "CURRENT BEST: $alias, score ${number(trial.overallScore ?: 0.0)}/10, values ${vector(ref.values)}"
        } else {
            "CURRENT BEST: no rated cooks yet; start from $alias as written, values ${vector(ref.values)}"
        }
    }

    /** "moisture: +1 (Too wet), salt: 0 (just right)" */
    fun axesText(context: RecipeContext, trial: Trial): String {
        val axes = context.details.axes.associateBy { it.id }
        return trial.axes.entries.joinToString(", ") { (id, value) ->
            val axis = axes[id]
            val label = when {
                abs(value) < 1e-9 -> "just right"
                axis == null -> null
                value < 0 -> axis.lowLabel
                else -> axis.highLabel
            }
            val signed = (if (value > 0) "+" else "") + number(value)
            if (label == null) "$id: $signed" else "$id: $signed ($label)"
        }
    }

    /** The checker's view: the proposal, its changes, and recent feedback. */
    fun checkerUser(context: RecipeContext, boldness: Double, proposal: ValidProposal): String = buildString {
        appendLine("RECIPE: ${context.details.recipe.name}")
        appendLine(
            "BOLDNESS: ${number(boldness)} -> normal limit ${percent(3 * boldness)} per amount, " +
                "${percent(5 * boldness)} with strong repeated feedback",
        )
        appendLine()
        when (proposal) {
            is ValidProposal.Values -> {
                val version = proposal.version
                appendLine("PROPOSAL: new values for ${context.alias(version)} \"${version.name}\"")
                appendLine("Steps:")
                version.steps.forEachIndexed { i, step -> appendLine("${i + 1}. " + stepText(step)) }
                appendLine("Parameters (#, name, starting value -> proposed, relative change):")
                for (p in StepParser.params(version.steps)) {
                    val from = proposal.from.getOrNull(p.index) ?: p.baseValue
                    val to = proposal.values[p.index]
                    appendLine(
                        "#${p.index} ${p.label}: ${ChangeSummary.valueText(p, from)} -> " +
                            "${ChangeSummary.valueText(p, to)} (${relative(from, to)})" +
                            (if (p.locked) " LOCKED" else ""),
                    )
                }
            }
            is ValidProposal.NewVersion -> {
                val parent = proposal.parent
                appendLine("PROPOSAL: new version \"${proposal.name}\" based on ${context.alias(parent)} \"${parent.name}\"")
                appendLine("Parent steps:")
                parent.steps.forEachIndexed { i, step -> appendLine("${i + 1}. " + stepText(step)) }
                val locked = StepParser.params(parent.steps).filter { it.locked }
                appendLine(
                    "Locked amounts in the parent: " +
                        locked.joinToString("; ") { "${it.label}: ${ChangeSummary.valueText(it, it.baseValue)}" }
                            .ifEmpty { "none" },
                )
                appendLine("Proposed steps:")
                proposal.steps.forEachIndexed { i, step -> appendLine("${i + 1}. " + stepText(step)) }
            }
        }
        appendLine()
        val recent = context.recentTrials.take(RECENT_FEEDBACK)
        if (recent.isNotEmpty()) {
            appendLine("RECENT FEEDBACK (newest first):")
            for (trial in recent) {
                val parts = mutableListOf(
                    context.alias(trial.versionId),
                    "score " + number(trial.overallScore ?: 0.0) + "/10",
                )
                axesText(context, trial).takeIf { it.isNotEmpty() }?.let { parts += it }
                trial.notes.oneLine().takeIf { it.isNotEmpty() }?.let { parts += "notes: \"$it\"" }
                appendLine("- " + parts.joinToString(" | "))
            }
            appendLine()
        }
        appendLine("PROPOSAL JSON:")
        append(proposalJson(context, proposal).toString())
    }

    /** The validated proposal in the reply schema, so the checker can revise it in place. */
    fun proposalJson(context: RecipeContext, proposal: ValidProposal): JsonObject = buildJsonObject {
        when (proposal) {
            is ValidProposal.Values -> {
                put("type", "values")
                put("versionId", context.alias(proposal.version))
                putJsonArray("values") { proposal.values.forEach { add(it) } }
            }
            is ValidProposal.NewVersion -> {
                put("type", "new_version")
                put("parentVersionId", context.alias(proposal.parent))
                put("name", proposal.name)
                putJsonArray("steps") {
                    for (step in proposal.steps) {
                        addJsonObject {
                            when (step) {
                                is Step.Text -> {
                                    put("type", "text")
                                    put("text", step.text)
                                    putJsonArray("locked") { step.locked.forEach { add(it) } }
                                }
                                is Step.Wait -> {
                                    put("type", "wait")
                                    put("label", step.label)
                                    put("seconds", step.seconds)
                                    put("locked", step.locked)
                                }
                            }
                        }
                    }
                }
            }
        }
        put("summary", proposal.raw.summary)
        put("rationale", proposal.raw.rationale)
    }

    private fun axisText(axis: RatingAxis) = "${axis.id}: ${axis.lowLabel} / ${axis.highLabel}"

    private fun vector(values: List<Double>) = values.joinToString(", ", "[", "]") { number(it) }

    private fun number(value: Double) = StepParser.formatValue(value)

    fun percent(fraction: Double): String = "${(fraction * 100).roundToInt()}%"

    private fun relative(from: Double, to: Double): String = when {
        abs(to - from) < 1e-9 -> "unchanged"
        abs(from) < 1e-9 -> "from zero"
        else -> String.format(Locale.US, "%+.0f%%", (to - from) / abs(from) * 100)
    }

    private fun date(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private val WHITESPACE = Regex("""\s+""")

    private fun String.oneLine(): String = replace(WHITESPACE, " ").trim()

    private const val RECENT_FEEDBACK = 5
}
