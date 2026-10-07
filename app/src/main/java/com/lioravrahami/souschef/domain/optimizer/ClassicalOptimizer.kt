package com.lioravrahami.souschef.domain.optimizer

import com.lioravrahami.souschef.data.model.RecipeDetails
import com.lioravrahami.souschef.data.model.RecipeVersion
import com.lioravrahami.souschef.data.model.Trial
import com.lioravrahami.souschef.domain.recipe.ChangeSummary
import com.lioravrahami.souschef.domain.recipe.ParamSpec
import com.lioravrahami.souschef.domain.recipe.StepParser
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

data class OptimizerSettings(
    /** Relative size of a local tweak, e.g. 0.15 = about 15% of a value. */
    val boldness: Double,
    /** Probability of a global jump instead of a local tweak. */
    val explorationRate: Double,
)

enum class ProposalKind { BASELINE, LOCAL, GLOBAL }

/** A concrete set of parameter values to cook, for a given version. */
data class Proposal(
    val versionId: String,
    val values: List<Double>,
    val rationale: String,
    val kind: ProposalKind,
)

/**
 * Score-driven optimizer that proposes parameter values. It only uses overall scores;
 * the per-axis feedback (too wet, too salty...) is interpreted by the AI optimizer.
 *
 * Most proposals are *local*: a small step around the best cooking of a version, either
 * along the score trend fitted by ridge regression (once there is enough data) or a random
 * nudge of a few values. With probability [OptimizerSettings.explorationRate] it makes a
 * *global* jump instead, so the search does not get stuck around one good point.
 *
 * Every proposed value stays within [MIN_FACTOR]–[MAX_FACTOR] times the value it starts from,
 * so an amount or a wait never collapses to 0 or balloons, and values are rounded to what a
 * cook can measure (see [roundValue]).
 *
 * All randomness comes from [random], so a seeded [Random] gives reproducible proposals.
 */
class ClassicalOptimizer(private val random: Random = Random.Default) {

    /**
     * The best known values: the best-rated valid trial of a non-archived version, else the
     * latest version's base values. Trials whose value vector does not match their version's
     * parameters are ignored.
     */
    fun best(details: RecipeDetails): Proposal {
        val bestTrial = details.doneTrials
            .filter { trial -> details.version(trial.versionId)?.let { !it.archived && isValid(trial, it) } == true }
            .maxWithOrNull(compareBy<Trial> { it.overallScore ?: 0.0 }.thenBy { it.finishedAt ?: 0L })
        if (bestTrial != null) {
            return Proposal(bestTrial.versionId, bestTrial.values, "Best rated so far", ProposalKind.BASELINE)
        }
        val version = details.latestVersion() ?: error("Recipe has no versions")
        return Proposal(version.id, StepParser.baseValues(version.steps), "As written", ProposalKind.BASELINE)
    }

    /**
     * Proposes a new point to try: a LOCAL tweak around the best cooking of a version, or
     * (with probability `settings.explorationRate`) a GLOBAL jump. Returns BASELINE when every
     * value is locked.
     *
     * @throws IllegalStateException if the recipe has no non-archived version.
     */
    fun propose(details: RecipeDetails, settings: OptimizerSettings): Proposal {
        val active = details.activeVersions
        if (active.isEmpty()) throw IllegalStateException("Recipe has no versions")

        val boldness = settings.boldness.takeIf { it.isFinite() }?.coerceIn(MIN_BOLDNESS, MAX_BOLDNESS) ?: DEFAULT_BOLDNESS
        val explorationRate = settings.explorationRate.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0

        val candidates = active.map { versionData(details, it) }.filter { it.tweakable.isNotEmpty() }
        if (candidates.isEmpty()) {
            return best(details).copy(rationale = NOTHING_TO_TWEAK, kind = ProposalKind.BASELINE)
        }

        val data = chooseVersion(candidates)
        val anyTrials = candidates.any { it.trials.isNotEmpty() }
        // A global jump needs something to jump away from: before the first rated cooking
        // only local tweaks are proposed, so the very first exploration stays edible.
        val draft = if (anyTrials && random.nextDouble() < explorationRate) {
            globalDraft(data, boldness)
        } else {
            localDraft(data, boldness)
        }

        val values = deduplicate(data, draft, boldness)
        val versionPrefix = if (active.size > 1 && data.version.name.isNotBlank()) {
            "Version “${data.version.name}” — "
        } else {
            ""
        }
        val changes = ChangeSummary.oneLine(data.version.steps, draft.center, values, unchanged = "same values")
        return Proposal(
            versionId = data.version.id,
            values = values,
            rationale = versionPrefix + draft.headline(anyTrials) + ": " + changes,
            kind = draft.kind,
        )
    }

    // ---------------------------------------------------------------- version data

    /** One active version with its parameters and its valid trials. */
    private class VersionData(
        val version: RecipeVersion,
        val params: List<ParamSpec>,
        /** Valid, finished, rated trials. */
        val trials: List<Trial>,
        /** Value vectors of every valid trial (any status): proposals should differ from these. */
        val tried: List<List<Double>>,
    ) {
        val base: List<Double> = params.map { it.baseValue }
        val tweakable: List<Int> = params.filter { !it.locked }.map { it.index }
        val bestTrial: Trial? = trials.maxWithOrNull(
            compareBy<Trial> { it.overallScore ?: 0.0 }.thenBy { it.finishedAt ?: 0L },
        )
        val bestScore: Double? = bestTrial?.overallScore
        val meanScore: Double? = trials.mapNotNull { it.overallScore }.takeIf { it.isNotEmpty() }?.average()
    }

    private fun versionData(details: RecipeDetails, version: RecipeVersion): VersionData {
        val params = StepParser.params(version.steps)
        val valid = details.trials.filter { it.versionId == version.id && isValid(it, params) }
        val done = details.trialsOf(version.id).filter { isValid(it, params) }
        return VersionData(version, params, done, valid.map { it.values })
    }

    /**
     * Softmax over each version's best score (temperature 1); versions without trials get the
     * mean of the others' best scores so they are not starved. Without any trials: the latest.
     */
    private fun chooseVersion(candidates: List<VersionData>): VersionData {
        if (candidates.size == 1) return candidates.first()
        val known = candidates.mapNotNull { it.bestScore }
        if (known.isEmpty()) return candidates.maxBy { it.version.createdAt }
        val fill = known.average()
        val weights = OptimizerMath.softmax(candidates.map { it.bestScore ?: fill }, VERSION_TEMPERATURE)
        return candidates[OptimizerMath.pickWeighted(weights, random)]
    }

    // ---------------------------------------------------------------- drafts

    private enum class Origin { GLOBAL_FROM_BASE, GLOBAL_FROM_TRIAL, LOCAL_GRADIENT, LOCAL_NUDGE_BEST, LOCAL_NUDGE_BASE }

    /** A proposal before post-processing: the raw values, the point they came from and why. */
    private class Draft(
        val origin: Origin,
        val center: List<Double>,
        val raw: List<Double>,
        val centerScore: Double?,
    ) {
        val kind: ProposalKind
            get() = if (origin == Origin.GLOBAL_FROM_BASE || origin == Origin.GLOBAL_FROM_TRIAL) {
                ProposalKind.GLOBAL
            } else {
                ProposalKind.LOCAL
            }

        fun headline(anyTrials: Boolean): String {
            val score = centerScore?.let { String.format(Locale.US, "%.1f", it) }
            return when (origin) {
                Origin.GLOBAL_FROM_TRIAL -> "Global jump from the cooking rated $score"
                Origin.GLOBAL_FROM_BASE ->
                    if (anyTrials) "Global jump from the recipe as written"
                    else "First exploration — global jump from the recipe as written"
                Origin.LOCAL_GRADIENT -> "Local step along the score trend from the best cooking ($score)"
                Origin.LOCAL_NUDGE_BEST -> "Local nudge around the best cooking ($score)"
                Origin.LOCAL_NUDGE_BASE ->
                    if (anyTrials) "Local nudge around this version as written"
                    else "First exploration — small tweak of the recipe as written"
            }
        }
    }

    /**
     * GLOBAL: pick a center among the base values and every trial (softmax over scores,
     * temperature 2; the base counts as the mean score), then shake every unlocked value hard.
     */
    private fun globalDraft(data: VersionData, boldness: Double): Draft {
        val centers = listOf(data.base) + data.trials.map { it.values }
        val scores = listOf(data.meanScore ?: 0.0) + data.trials.map { it.overallScore ?: 0.0 }
        val pick = if (centers.size == 1) 0 else {
            OptimizerMath.pickWeighted(OptimizerMath.softmax(scores, GLOBAL_TEMPERATURE), random)
        }
        val center = centers[pick]
        val sigma = GLOBAL_SIGMA_FACTOR * boldness
        val raw = center.toMutableList()
        for (i in data.tweakable) {
            var v = center[i] + scale(data, center, i) * sigma * OptimizerMath.gaussian(random)
            if (random.nextDouble() < GLOBAL_MULTIPLIER_PROBABILITY) {
                v *= GLOBAL_MULTIPLIER_MIN + random.nextDouble() * (GLOBAL_MULTIPLIER_MAX - GLOBAL_MULTIPLIER_MIN)
            }
            raw[i] = v
        }
        return if (pick == 0) {
            Draft(Origin.GLOBAL_FROM_BASE, center, raw, null)
        } else {
            Draft(Origin.GLOBAL_FROM_TRIAL, center, raw, data.trials[pick - 1].overallScore)
        }
    }

    /** LOCAL: a gradient step when the version has enough data, else a random nudge of a few values. */
    private fun localDraft(data: VersionData, boldness: Double): Draft {
        val bestTrial = data.bestTrial
        val center = bestTrial?.values ?: data.base
        gradientStep(data, center, boldness)?.let { raw ->
            return Draft(Origin.LOCAL_GRADIENT, center, raw, bestTrial?.overallScore)
        }
        val raw = nudge(data, center, boldness)
        return if (bestTrial != null) {
            Draft(Origin.LOCAL_NUDGE_BEST, center, raw, bestTrial.overallScore)
        } else {
            Draft(Origin.LOCAL_NUDGE_BASE, center, raw, null)
        }
    }

    /**
     * Fits score ≈ β·(x / scale) by ridge regression over the version's trials and steps from
     * [center] along β so that the largest relative change equals [boldness], plus a little noise.
     * Returns null when there is too little data or the fit shows no trend.
     */
    private fun gradientStep(data: VersionData, center: List<Double>, boldness: Double): List<Double>? {
        if (data.trials.size < MIN_TRIALS_FOR_GRADIENT) return null
        if (data.trials.map { it.values }.distinct().size < 2) return null
        val columns = data.tweakable
        val scales = columns.map { scale(data, center, it) }
        val x = data.trials.map { t -> DoubleArray(columns.size) { c -> t.values[columns[c]] / scales[c] } }
        val y = data.trials.map { it.overallScore ?: 0.0 }
        val beta = OptimizerMath.ridge(x, y, RIDGE_LAMBDA) ?: return null
        val maxAbs = beta.maxOf { abs(it) }
        if (!maxAbs.isFinite() || maxAbs < DEGENERATE_GRADIENT) return null
        val raw = center.toMutableList()
        columns.forEachIndexed { c, i ->
            val relative = beta[c] / maxAbs * boldness +
                GRADIENT_NOISE_FACTOR * boldness * OptimizerMath.gaussian(random)
            raw[i] = center[i] + scales[c] * relative
        }
        return raw
    }

    /**
     * Random local rule: each unlocked value joins the tweak with probability 0.6 (at least one
     * does) and moves by a Gaussian with relative σ = [sigma]; the others stay as in [center].
     */
    private fun nudge(data: VersionData, center: List<Double>, sigma: Double): List<Double> {
        val chosen = data.tweakable.filter { random.nextDouble() < NUDGE_PROBABILITY }
            .ifEmpty { listOf(data.tweakable[random.nextInt(data.tweakable.size)]) }
        val raw = center.toMutableList()
        for (i in chosen) raw[i] = center[i] + scale(data, center, i) * sigma * OptimizerMath.gaussian(random)
        return raw
    }

    // ---------------------------------------------------------------- post-processing

    /**
     * Cleans [draft] (locks, rounding, no negatives) and makes sure the result is new: if it
     * repeats the center or an already-tried vector it is re-perturbed with a growing σ, and
     * as a last resort the first unlocked value is nudged up by whole boldness steps.
     */
    private fun deduplicate(data: VersionData, draft: Draft, boldness: Double): List<Double> {
        val center = draft.center
        val first = clean(data, center, draft.raw)
        if (!isRepeat(data, center, first)) return first

        val baseSigma = if (draft.kind == ProposalKind.GLOBAL) GLOBAL_SIGMA_FACTOR * boldness else boldness
        for (attempt in 1..MAX_RETRIES) {
            val candidate = clean(data, center, nudge(data, center, baseSigma * (1.0 + 0.5 * attempt)))
            if (!isRepeat(data, center, candidate)) return candidate
        }

        val i = data.tweakable.first()
        val param = data.params[i]
        val step = maxOf(scale(data, center, i) * boldness, resolution(param, center[i]))
        var candidate = first
        for (k in 1..MAX_FALLBACK_STEPS) {
            val raw = center.toMutableList().also { it[i] = center[i] + k * step }
            candidate = clean(data, center, raw)
            if (!isRepeat(data, center, candidate)) return candidate
        }
        return candidate
    }

    /** Locked values come back from [center] untouched; the others are bounded and rounded. */
    private fun clean(data: VersionData, center: List<Double>, raw: List<Double>): List<Double> =
        data.params.map { p ->
            val i = p.index
            if (p.locked) return@map center[i]
            val v = bound(data, center, i, raw[i].takeIf { it.isFinite() } ?: center[i])
            val rounded = roundValue(p, v)
            // A tiny positive value must not round down to nothing: use the smallest measurable step.
            if (rounded <= 0.0 && v > 0.0) resolution(p, v) else rounded
        }

    /**
     * Keeps value [v] of parameter [i] within reach of where the proposal starts: at least
     * [MIN_FACTOR] and at most [MAX_FACTOR] times the reference (the value at [center], else
     * the written value when the center is 0), so an amount or a wait never drops to 0 nor
     * jumps to several times its size. A center value of 0 keeps 0 as the lower bound (the
     * value stays where it is when it is not tweaked); with no positive reference at all the
     * value is only kept non-negative.
     */
    private fun bound(data: VersionData, center: List<Double>, i: Int, v: Double): Double {
        val c = center[i]
        val reference = if (c > 0.0) c else data.base[i]
        if (!(reference > 0.0)) return v.coerceAtLeast(0.0)
        val low = if (c > 0.0) reference * MIN_FACTOR else 0.0
        return v.coerceIn(low, reference * MAX_FACTOR)
    }

    private fun isRepeat(data: VersionData, center: List<Double>, values: List<Double>): Boolean =
        sameValues(data.params, values, center) || data.tried.any { sameValues(data.params, values, it) }

    private fun sameValues(params: List<ParamSpec>, a: List<Double>, b: List<Double>): Boolean =
        params.all { p ->
            val x = a[p.index]
            val y = b[p.index]
            if (p.isWait) x.roundToInt() == y.roundToInt() else abs(x - y) <= SAME_EPSILON
        }

    /**
     * Unit of relative change for parameter [i]: its written value, else its value at
     * [center] when the written value is 0, else 1.
     */
    private fun scale(data: VersionData, center: List<Double>, i: Int): Double {
        val base = abs(data.base[i])
        if (base > 0.0) return base
        val c = abs(center[i])
        return if (c > 0.0) c else 1.0
    }

    internal companion object {
        const val NOTHING_TO_TWEAK = "Nothing to tweak — all values are locked"

        private const val MIN_BOLDNESS = 0.01
        private const val MAX_BOLDNESS = 1.0
        private const val DEFAULT_BOLDNESS = 0.15
        private const val VERSION_TEMPERATURE = 1.0
        private const val GLOBAL_TEMPERATURE = 2.0
        private const val GLOBAL_SIGMA_FACTOR = 3.0
        private const val GLOBAL_MULTIPLIER_PROBABILITY = 0.3
        private const val GLOBAL_MULTIPLIER_MIN = 0.5
        private const val GLOBAL_MULTIPLIER_MAX = 1.5
        private const val MIN_TRIALS_FOR_GRADIENT = 4
        private const val RIDGE_LAMBDA = 0.1
        private const val GRADIENT_NOISE_FACTOR = 0.3
        private const val DEGENERATE_GRADIENT = 1e-9
        private const val NUDGE_PROBABILITY = 0.6
        private const val MAX_RETRIES = 10
        private const val MAX_FALLBACK_STEPS = 50
        private const val SAME_EPSILON = 1e-6

        /** A proposal never goes below this fraction of the value it starts from. */
        const val MIN_FACTOR = 0.25

        /** A proposal never goes above this multiple of the value it starts from. */
        const val MAX_FACTOR = 3.0

        /** Whole-number amounts below this move in half steps; from here up in whole steps. */
        private const val HALF_STEP_LIMIT = 10.0

        /** Whether [trial]'s values line up with [version]'s parameters and are all finite. */
        fun isValid(trial: Trial, version: RecipeVersion): Boolean =
            isValid(trial, StepParser.params(version.steps))

        private fun isValid(trial: Trial, params: List<ParamSpec>): Boolean =
            trial.values.size == params.size && trial.values.all { it.isFinite() }

        /**
         * Rounds a proposed value to something a cook can measure: wait durations to whole
         * seconds (multiples of 5 s from one minute up); amounts written as whole numbers
         * (`2[eggs]`, `180[°C]`) to halves below 10 and whole numbers from 10 up; everything
         * else through [StepParser.round].
         */
        fun roundValue(param: ParamSpec, value: Double): Double {
            if (!value.isFinite()) return StepParser.round(value)
            if (param.isWait) {
                val seconds = Math.round(value)
                return if (seconds >= 60) (Math.round(value / 5.0) * 5).toDouble() else seconds.toDouble()
            }
            if (!isWholeNumber(param.baseValue)) return StepParser.round(value)
            return if (abs(value) < HALF_STEP_LIMIT) Math.round(value * 2.0) / 2.0 else Math.round(value).toDouble()
        }

        /** Whether [value] is a finite whole number, i.e. the cook wrote it without a fraction. */
        private fun isWholeNumber(value: Double): Boolean = value.isFinite() && value == Math.rint(value)

        /** The smallest change of [param] around [value] that survives [roundValue]. */
        private fun resolution(param: ParamSpec, value: Double): Double = when {
            param.isWait -> if (value >= 60.0) 5.0 else 1.0
            isWholeNumber(param.baseValue) -> if (abs(value) >= HALF_STEP_LIMIT) 1.0 else 0.5
            abs(value) >= 100 -> 1.0
            abs(value) >= 10 -> 0.1
            else -> 0.01
        }
    }
}
