package com.lioravrahami.souschef.data.model

/** A recipe with all of its versions and trials. */
data class RecipeDetails(
    val recipe: Recipe,
    val versions: List<RecipeVersion>,
    val trials: List<Trial>,
) {
    val axes: List<RatingAxis> get() = DefaultAxes.all + recipe.customAxes

    fun version(id: String): RecipeVersion? = versions.firstOrNull { it.id == id }

    val activeVersions: List<RecipeVersion> get() = versions.filter { !it.archived }

    /** Rated, finished trials. */
    val doneTrials: List<Trial>
        get() = trials.filter { it.status == TrialStatus.DONE && it.overallScore != null }

    fun trialsOf(versionId: String): List<Trial> = doneTrials.filter { it.versionId == versionId }

    /** The highest-scoring finished trial of a non-archived version (latest wins ties). */
    fun bestTrial(): Trial? = doneTrials
        .filter { version(it.versionId)?.archived == false }
        .maxWithOrNull(compareBy<Trial> { it.overallScore ?: 0.0 }.thenBy { it.finishedAt ?: 0L })

    /** Newest non-archived version, or newest of all if everything is archived. */
    fun latestVersion(): RecipeVersion? =
        activeVersions.maxByOrNull { it.createdAt } ?: versions.maxByOrNull { it.createdAt }
}

/** Row of the recipe list. */
data class RecipeSummary(
    val recipe: Recipe,
    val versionCount: Int,
    val trialCount: Int,
    val bestScore: Double?,
    val lastCookedAt: Long?,
)
