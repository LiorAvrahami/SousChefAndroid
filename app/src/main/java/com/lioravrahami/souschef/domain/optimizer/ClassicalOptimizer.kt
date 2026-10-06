package com.lioravrahami.souschef.domain.optimizer

import com.lioravrahami.souschef.data.model.RecipeDetails
import com.lioravrahami.souschef.domain.recipe.StepParser
import kotlin.random.Random

// STUB — contract only. The real implementation replaces this file.

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
 */
class ClassicalOptimizer(private val random: Random = Random.Default) {

    /** The best known values: the best-rated trial's values, else the latest version's base values. */
    fun best(details: RecipeDetails): Proposal {
        val bestTrial = details.bestTrial()
        if (bestTrial != null) {
            return Proposal(bestTrial.versionId, bestTrial.values, "Best rated so far", ProposalKind.BASELINE)
        }
        val version = details.latestVersion() ?: error("Recipe has no versions")
        return Proposal(version.id, StepParser.baseValues(version.steps), "As written", ProposalKind.BASELINE)
    }

    /** Proposes a new point to try. */
    fun propose(details: RecipeDetails, settings: OptimizerSettings): Proposal = best(details)
}
