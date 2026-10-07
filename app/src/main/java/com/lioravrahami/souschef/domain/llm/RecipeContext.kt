package com.lioravrahami.souschef.domain.llm

import com.lioravrahami.souschef.data.model.RecipeDetails
import com.lioravrahami.souschef.data.model.RecipeVersion
import com.lioravrahami.souschef.data.model.Trial
import com.lioravrahami.souschef.domain.recipe.StepParser

/**
 * The recipe as the AI agents see it. Versions get short aliases ("v1", "v2", … oldest
 * first) because cheap models mangle UUIDs; replies may use either the alias or the real id.
 */
internal class RecipeContext(val details: RecipeDetails) {

    /** Oldest first, so aliases stay stable as versions are added. */
    val versions: List<RecipeVersion> = details.versions.sortedWith(compareBy({ it.createdAt }, { it.id }))

    private val aliasById: Map<String, String> =
        versions.mapIndexed { i, v -> v.id to "v${i + 1}" }.toMap()

    /** The values the next cook should improve on: best rated trial, else the latest version as written. */
    val reference: Reference = run {
        val best = details.bestTrial()?.takeIf { trial ->
            val version = details.version(trial.versionId)
            version != null && trial.values.size == StepParser.params(version.steps).size
        }
        if (best != null) {
            Reference(details.version(best.versionId)!!, best.values, best)
        } else {
            val version = details.latestVersion()
                ?: throw OpenRouterClient.LlmException("This recipe has no versions yet. Add its steps first.")
            Reference(version, StepParser.baseValues(version.steps), null)
        }
    }

    /** Rated trials, newest first, at most [MAX_TRIALS]. */
    val recentTrials: List<Trial> = details.doneTrials
        .sortedByDescending { it.finishedAt ?: it.createdAt }
        .take(MAX_TRIALS)

    fun alias(version: RecipeVersion): String = aliasById[version.id] ?: version.id

    fun alias(versionId: String): String = aliasById[versionId] ?: versionId

    /**
     * Resolves a model's version reference: alias ("v2", "V2", "version 2"), full id, or the
     * exact version name. Null when nothing matches.
     */
    fun resolve(ref: String): RecipeVersion? {
        val clean = ref.trim().trim('"', '\'')
        if (clean.isEmpty()) return null
        details.version(clean)?.let { return it }
        val number = ALIAS.matchEntire(clean)?.groupValues?.get(1)?.toIntOrNull()
        if (number != null) return versions.getOrNull(number - 1)
        return versions.singleOrNull { it.name.equals(clean, ignoreCase = true) }
    }

    /**
     * What a value proposal for [version] is compared with: the reference values for the
     * reference version, else that version's best rated values, else its base values.
     */
    fun startingValues(version: RecipeVersion): List<Double> {
        if (version.id == reference.version.id) return reference.values
        val size = StepParser.params(version.steps).size
        return details.trialsOf(version.id)
            .filter { it.values.size == size }
            .maxWithOrNull(compareBy<Trial> { it.overallScore ?: 0.0 }.thenBy { it.finishedAt ?: 0L })
            ?.values
            ?: StepParser.baseValues(version.steps)
    }

    /** "the best cook so far" / "v2 as written", for rationale texts. */
    fun startingPointName(version: RecipeVersion): String = when {
        version.id == reference.version.id && reference.trial != null -> "the best cook so far"
        details.trialsOf(version.id).isNotEmpty() -> "the best cook of \"${version.name}\""
        else -> "\"${version.name}\" as written"
    }

    data class Reference(val version: RecipeVersion, val values: List<Double>, val trial: Trial?)

    companion object {
        const val MAX_TRIALS = 40
        private val ALIAS = Regex("""(?i)(?:v|version)\s*#?\s*(\d+)""")
    }
}
