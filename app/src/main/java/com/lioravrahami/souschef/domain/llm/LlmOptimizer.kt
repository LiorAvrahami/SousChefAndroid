package com.lioravrahami.souschef.domain.llm

import com.lioravrahami.souschef.data.model.RecipeDetails
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.data.settings.AppSettings
import com.lioravrahami.souschef.domain.optimizer.OptimizerSettings

// STUB — contract only. The real implementation replaces this file.

/** What the AI proposes to cook next. */
sealed class LlmSuggestion {
    abstract val summary: String
    abstract val rationale: String

    /** Same steps as [versionId], different parameter values. */
    data class Values(
        val versionId: String,
        val values: List<Double>,
        override val summary: String,
        override val rationale: String,
    ) : LlmSuggestion()

    /** A structural change (reordered / added / removed / reworded steps): becomes a new version. */
    data class NewVersion(
        val parentVersionId: String,
        val name: String,
        val steps: List<Step>,
        override val summary: String,
        override val rationale: String,
    ) : LlmSuggestion()
}

/**
 * Two-agent AI optimizer: a *suggester* proposes one tweak from the recipe's history
 * (versions, trials, scores, axis feedback, notes); a *checker* vets it and tones it down
 * if the change is too large or unsafe.
 */
class LlmOptimizer(private val client: OpenRouterClient, private val settings: AppSettings) {

    /** Throws [OpenRouterClient.LlmException] with a readable message on failure. */
    suspend fun suggest(details: RecipeDetails, optimizerSettings: OptimizerSettings): LlmSuggestion =
        throw OpenRouterClient.LlmException("Not implemented")
}
