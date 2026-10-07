package com.lioravrahami.souschef.domain.llm

import com.lioravrahami.souschef.data.model.RecipeVersion
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.domain.llm.OpenRouterClient.LlmException
import com.lioravrahami.souschef.domain.recipe.ChangeSummary
import com.lioravrahami.souschef.domain.recipe.ParamSpec
import com.lioravrahami.souschef.domain.recipe.StepParser
import kotlin.math.abs
import kotlin.math.roundToInt

/** A proposal that passed the code-level checks and is safe to hand to the app. */
internal sealed class ValidProposal {
    abstract val raw: RawProposal

    /** [values] are cleaned; [from] is what they are compared with (see [RecipeContext.startingValues]). */
    data class Values(
        val version: RecipeVersion,
        val values: List<Double>,
        val from: List<Double>,
        override val raw: RawProposal.Values,
    ) : ValidProposal() {
        val changes: List<ChangeSummary.Change> get() = ChangeSummary.changes(version.steps, from, values)
    }

    data class NewVersion(
        val parent: RecipeVersion,
        val name: String,
        val steps: List<Step>,
        override val raw: RawProposal.NewVersion,
    ) : ValidProposal()
}

/**
 * Code-level validation of model proposals. The models are never trusted: every rule the
 * prompts state that can be checked mechanically is enforced here. Failures throw
 * [LlmException] with a message the cook can read.
 */
internal class ProposalValidator(private val context: RecipeContext) {

    fun validate(raw: RawProposal): ValidProposal = when (raw) {
        is RawProposal.Values -> validateValues(raw)
        is RawProposal.NewVersion -> validateNewVersion(raw)
    }

    private fun resolve(ref: String): RecipeVersion =
        if (ref.isBlank()) {
            context.reference.version
        } else {
            context.resolve(ref) ?: throw LlmException("The AI referred to a recipe version that does not exist.")
        }

    private fun validateValues(raw: RawProposal.Values): ValidProposal.Values {
        val version = resolve(raw.versionRef)
        if (version.archived) throw LlmException("The AI picked the archived version \"${version.name}\".")
        val params = StepParser.params(version.steps)
        if (raw.values.size != params.size) {
            throw LlmException(
                "The AI gave ${raw.values.size} amounts, but \"${version.name}\" has ${params.size}.",
            )
        }
        val values = params.map { clean(it, raw.values[it.index]) }
        val from = context.startingValues(version)
        if (ChangeSummary.changes(version.steps, from, values).isEmpty()) {
            throw LlmException("The AI suggested no change.")
        }
        return ValidProposal.Values(version, values, from, raw)
    }

    /**
     * Limits every change of [proposal] to [maxRelative] (0.45 = ±45%) of its starting value.
     * Returns the capped proposal and whether anything had to be limited.
     */
    fun cap(proposal: ValidProposal.Values, maxRelative: Double): Pair<ValidProposal.Values, Boolean> {
        val params = StepParser.params(proposal.version.steps)
        var limited = false
        val values = params.map { p ->
            val target = proposal.values[p.index]
            val start = proposal.from.getOrNull(p.index) ?: p.baseValue
            val allowed = abs(start) * maxRelative
            if (p.locked || start <= 0.0 || abs(target - start) <= allowed + 1e-9) {
                target
            } else {
                limited = true
                clean(p, if (target > start) start + allowed else start - allowed)
            }
        }
        return proposal.copy(values = values) to limited
    }

    /** Locked → base value; negative → 0; rounded like the editor (waits to whole seconds). */
    private fun clean(param: ParamSpec, value: Double): Double = when {
        param.locked -> param.baseValue
        param.isWait -> value.coerceAtLeast(0.0).roundToInt().toDouble()
        else -> StepParser.round(value.coerceAtLeast(0.0))
    }

    private fun validateNewVersion(raw: RawProposal.NewVersion): ValidProposal.NewVersion {
        val parent = resolve(raw.parentRef)
        val steps = raw.steps
            .filterNot { it is Step.Text && it.text.isBlank() }
            .map { step ->
                when (step) {
                    is Step.Text -> step.copy(locked = step.locked.filter { it < StepParser.countParams(step.text) })
                    is Step.Wait -> {
                        if (step.seconds <= 0) throw LlmException("The AI wrote a wait step without a time.")
                        step
                    }
                }
            }
        if (steps.isEmpty()) throw LlmException("The AI's new version has no steps.")
        val withLocks = carryOverLocks(parent, steps)
        if (withLocks == parent.steps) throw LlmException("The AI suggested no change.")
        val name = raw.name.ifBlank { "${parent.name} (AI)" }.take(MAX_NAME_LENGTH).trim()
        return ValidProposal.NewVersion(parent, name, withLocks, raw)
    }

    /**
     * Every locked amount of [parent] must survive unchanged in the new steps (same kind,
     * unit and value); the matching amount is marked locked there too.
     */
    private fun carryOverLocks(parent: RecipeVersion, steps: List<Step>): List<Step> {
        val lockedInParent = StepParser.params(parent.steps).filter { it.locked }
        if (lockedInParent.isEmpty()) return steps
        val candidates = StepParser.params(steps)
        val used = HashSet<Int>()
        for (locked in lockedInParent) {
            val match = candidates
                .filter { it.index !in used && matches(locked, it) }
                .sortedWith(compareBy({ !it.locked }, { !it.name.equals(locked.name, ignoreCase = true) }))
                .firstOrNull()
                ?: throw LlmException(
                    "The AI changed a locked amount " +
                        "(${locked.label}: ${ChangeSummary.valueText(locked, locked.baseValue)}).",
                )
            used += match.index
        }
        val lockedParams = candidates.filter { it.index in used }
        return steps.mapIndexed { stepIndex, step ->
            val here = lockedParams.filter { it.stepIndex == stepIndex }
            if (here.isEmpty()) {
                step
            } else {
                when (step) {
                    is Step.Text -> step.copy(locked = (step.locked + here.map { it.indexInStep }).distinct().sorted())
                    is Step.Wait -> step.copy(locked = true)
                }
            }
        }
    }

    private fun matches(locked: ParamSpec, candidate: ParamSpec): Boolean =
        locked.isWait == candidate.isWait &&
            locked.unit.equals(candidate.unit, ignoreCase = true) &&
            abs(locked.baseValue - candidate.baseValue) < 1e-9

    private companion object {
        const val MAX_NAME_LENGTH = 60
    }
}
