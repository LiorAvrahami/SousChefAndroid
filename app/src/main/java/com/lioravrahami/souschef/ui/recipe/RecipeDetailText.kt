package com.lioravrahami.souschef.ui.recipe

import com.lioravrahami.souschef.data.model.RatingAxis
import com.lioravrahami.souschef.data.model.RecipeDetails
import com.lioravrahami.souschef.data.model.RecipeVersion
import com.lioravrahami.souschef.data.model.Trial
import com.lioravrahami.souschef.data.model.TrialMode
import com.lioravrahami.souschef.data.model.TrialStatus
import com.lioravrahami.souschef.data.model.VersionOrigin
import com.lioravrahami.souschef.domain.optimizer.Proposal
import com.lioravrahami.souschef.domain.recipe.ChangeSummary
import com.lioravrahami.souschef.domain.recipe.StepParser
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

/** Colour bucket of a score chip: ≥ 8 great, ≥ 5 okay, below that poor. */
enum class ScoreBucket { HIGH, MEDIUM, LOW }

/** Pure helpers of the recipe detail screen (no Android classes, unit-tested). */
object RecipeDetailText {
    private const val SAME_EPSILON = 1e-6

    /** Shown instead of a change list when a cooking's values no longer fit its version. */
    const val MISMATCH = "values don't match this version"

    /** Shown when a proposal or cooking uses exactly the written values. */
    const val AS_WRITTEN = "Exactly as written"

    /** Date in the user's locale, e.g. "Oct 7, 2026". */
    fun formatDate(millis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

    /** "8.5", "7". */
    fun formatScore(score: Double): String = StepParser.formatValue(StepParser.round(score))

    fun scoreBucket(score: Double): ScoreBucket = when {
        score >= 8.0 -> ScoreBucket.HIGH
        score >= 5.0 -> ScoreBucket.MEDIUM
        else -> ScoreBucket.LOW
    }

    fun modeLabel(mode: TrialMode): String = when (mode) {
        TrialMode.BEST -> "Best so far"
        TrialMode.EXPLORE -> "Exploration"
        TrialMode.AI -> "AI suggestion"
        TrialMode.AS_WRITTEN -> "As written"
    }

    fun originLabel(origin: VersionOrigin): String = when (origin) {
        VersionOrigin.MANUAL -> "manual"
        VersionOrigin.AI -> "AI"
        VersionOrigin.IMPORT -> "import"
    }

    /** "1 cooking", "3 cookings". */
    fun plural(count: Int, singular: String, plural: String = singular + "s"): String =
        "$count ${if (count == 1) singular else plural}"

    /** Version card stats: "3 cookings · best 8.5", or "never cooked". */
    fun versionStats(cookings: Int, best: Double?): String = when {
        cookings == 0 -> "never cooked"
        best == null -> plural(cookings, "cooking")
        else -> plural(cookings, "cooking") + " · best " + formatScore(best)
    }

    /** Whether two value vectors are the same (same length, equal within rounding noise). */
    fun sameValues(a: List<Double>, b: List<Double>): Boolean =
        a.size == b.size && a.indices.all { abs(a[it] - b[it]) <= SAME_EPSILON }

    /** Whether [proposal] repeats the version and values of [previous]. */
    fun sameProposal(previous: Proposal?, proposal: Proposal): Boolean =
        previous != null && previous.versionId == proposal.versionId && sameValues(previous.values, proposal.values)

    /**
     * Calls [generate] until it returns something different from [previous], at most
     * [maxAttempts] times, so that "Another suggestion" never shows the same tweak twice in a
     * row. Returns the last result if every attempt repeated.
     */
    fun firstDifferent(previous: Proposal?, maxAttempts: Int = 12, generate: () -> Proposal): Proposal {
        var result = generate()
        var attempts = 1
        while (attempts < maxAttempts && sameProposal(previous, result)) {
            result = generate()
            attempts++
        }
        return result
    }

    /** Whether [values] line up with [version]'s parameters (and are all finite). */
    fun matchesVersion(version: RecipeVersion?, values: List<Double>): Boolean =
        version != null &&
            values.size == StepParser.params(version.steps).size &&
            values.all { it.isFinite() }

    /**
     * The changes a cooking of [version] with [values] makes to the written recipe, one line
     * each ("water: 1.75 → 1.9 cups"); null when the values do not fit the version.
     */
    fun changes(version: RecipeVersion?, values: List<Double>): List<String>? {
        if (version == null || !matchesVersion(version, values)) return null
        return ChangeSummary.describe(version.steps, null, values)
    }

    /** One-line change summary for the history: "water: 1.75 → 1.9 cups; …", "as written" or [MISMATCH]. */
    fun trialChangeLine(version: RecipeVersion?, values: List<Double>): String {
        if (version == null || !matchesVersion(version, values)) return MISMATCH
        return ChangeSummary.oneLine(version.steps, null, values)
    }

    /**
     * The finished trial that [best] (from `ClassicalOptimizer.best`) refers to: the
     * highest-rated DONE trial with the same version and values. Null when [best] is the
     * written recipe rather than a cooking.
     */
    fun bestTrialFor(details: RecipeDetails, best: Proposal): Trial? =
        details.doneTrials
            .filter { it.versionId == best.versionId && sameValues(it.values, best.values) }
            .maxWithOrNull(compareBy<Trial> { it.overallScore ?: 0.0 }.thenBy { it.finishedAt ?: 0L })

    /**
     * Subtitle under "Cook the best so far": "best score 8.5 from Oct 3, 2026", or
     * "as written (never cooked yet)" — or "as written (no usable rating yet)" when cookings
     * exist but none counts (archived version, damaged data).
     */
    fun bestSubtitle(bestTrial: Trial?, anyRatedCooking: Boolean, formatDate: (Long) -> String): String {
        val score = bestTrial?.overallScore
        if (bestTrial == null || score == null) {
            return if (anyRatedCooking) "as written (no usable rating yet)" else "as written (never cooked yet)"
        }
        val at = bestTrial.finishedAt ?: bestTrial.createdAt
        return "best score ${formatScore(score)} from ${formatDate(at)}"
    }

    /**
     * Small feedback chips for a cooking's axis ratings, e.g. "Too wet +1", "Bland +2".
     * Zero ("just right") values are skipped; axes unknown to the recipe use their id.
     */
    fun axisChips(axes: List<RatingAxis>, values: Map<String, Double>): List<String> {
        val known = axes.associateBy { it.id }
        val ordered = axes.map { it.id }.filter { it in values } + values.keys.filter { it !in known }
        return ordered.mapNotNull { id ->
            val v = values[id] ?: return@mapNotNull null
            if (!v.isFinite()) return@mapNotNull null
            val steps = v.roundToInt()
            if (steps == 0) return@mapNotNull null
            val axis = known[id]
            val label = when {
                axis == null -> id
                steps > 0 -> axis.highLabel
                else -> axis.lowLabel
            }
            val magnitude = if (axis == null) (if (steps > 0) "+$steps" else "−${-steps}") else "+${abs(steps)}"
            "${label.trim()} $magnitude"
        }
    }

    /** Finished cookings, newest first. */
    fun history(details: RecipeDetails): List<Trial> =
        details.trials
            .filter { it.status == TrialStatus.DONE }
            .sortedByDescending { it.finishedAt ?: it.createdAt }

    fun abandonedCount(details: RecipeDetails): Int = details.trials.count { it.status == TrialStatus.ABORTED }

    /** Versions newest first. */
    fun versionsNewestFirst(details: RecipeDetails): List<RecipeVersion> = details.versions.sortedByDescending { it.createdAt }

    /** A version may be archived only while another version stays active. */
    fun canArchive(details: RecipeDetails, version: RecipeVersion): Boolean =
        !version.archived && details.activeVersions.any { it.id != version.id }

    /** Note stored on a version created from a cooking. */
    fun fromCookingNote(dateText: String): String = "From cooking on $dateText"

    /** "based on “v1”" for a proposed new version whose parent is known. */
    fun basedOn(parent: RecipeVersion?): String? = parent?.let { "based on “${it.name}”" }
}
