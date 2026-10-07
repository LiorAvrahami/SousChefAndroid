package com.lioravrahami.souschef.ui.recipes

import com.lioravrahami.souschef.data.model.RecipeSummary
import com.lioravrahami.souschef.domain.recipe.StepParser

/** Pure text helpers of the recipe list (no Android classes, unit-tested). */
object RecipeListText {

    /** Show the search field once the list is at least this long. */
    const val SEARCH_THRESHOLD = 6

    /** "8.5", "7" — scores are stored in half points. */
    fun formatScore(score: Double): String = StepParser.formatValue(StepParser.round(score))

    /** "1 cooking", "4 cookings". */
    fun plural(count: Int, singular: String, plural: String = singular + "s"): String =
        "$count ${if (count == 1) singular else plural}"

    /**
     * Second line of a recipe card: "best 8.5 · 4 cookings · 2 versions". Parts that are zero
     * or unknown are left out; a recipe without cookings reads "never cooked · 1 version".
     */
    fun statsLine(summary: RecipeSummary): String {
        val parts = ArrayList<String>(3)
        if (summary.trialCount == 0) {
            parts += "never cooked"
        } else {
            summary.bestScore?.let { parts += "best ${formatScore(it)}" }
            parts += plural(summary.trialCount, "cooking")
        }
        if (summary.versionCount > 0) parts += plural(summary.versionCount, "version")
        return parts.joinToString(" · ")
    }

    /**
     * 1-based step number for a banner, kept inside 1..[stepCount] (the cooking screen's
     * final "done" page sits one past the last step).
     */
    fun stepNumber(currentStep: Int, stepCount: Int): Int =
        if (stepCount <= 0) 1 else (currentStep + 1).coerceIn(1, stepCount)

    /**
     * Banner headline: "Cooking in progress — Pasta, step 3 of 7". Degrades to
     * "Cooking in progress — Pasta" without a step count and to "Cooking in progress"
     * without a name.
     */
    fun bannerText(recipeName: String?, currentStep: Int, stepCount: Int?): String {
        val base = "Cooking in progress"
        val name = recipeName?.trim().orEmpty()
        val where = if (stepCount != null && stepCount > 0) {
            "step ${stepNumber(currentStep, stepCount)} of $stepCount"
        } else {
            ""
        }
        return when {
            name.isNotEmpty() && where.isNotEmpty() -> "$base — $name, $where"
            name.isNotEmpty() -> "$base — $name"
            where.isNotEmpty() -> "$base — $where"
            else -> base
        }
    }

    /** Recipes whose name contains every word of [query] (case-insensitive); all of them for a blank query. */
    fun filter(summaries: List<RecipeSummary>, query: String): List<RecipeSummary> {
        val words = query.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return summaries
        return summaries.filter { s -> words.all { s.recipe.name.contains(it, ignoreCase = true) } }
    }
}
