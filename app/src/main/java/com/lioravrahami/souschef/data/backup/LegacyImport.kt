package com.lioravrahami.souschef.data.backup

import com.lioravrahami.souschef.data.model.Recipe
import com.lioravrahami.souschef.data.model.RecipeVersion
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.data.model.Trial
import com.lioravrahami.souschef.data.model.TrialMode
import com.lioravrahami.souschef.data.model.TrialStatus
import com.lioravrahami.souschef.data.model.VersionOrigin
import com.lioravrahami.souschef.domain.recipe.StepParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Reader for the old Sous Chef web app's `recipes.json`:
 *
 * ```json
 * [{"name": "Microwaved Rice",
 *   "versions": [{"BaseRecipe": ["1[cups] rice", "1.75[cups] water"],
 *                 "Trials": [{}, {"rating": 7, "freeText": "a bit dry", "TrialSteps": ["1[cups] rice", "2[cups] water"]}]}]}]
 * ```
 *
 * Every legacy recipe becomes a new [Recipe] (unless a recipe with the same name is
 * already on the device and not in the trash), every legacy version a [RecipeVersion]
 * and every rated legacy trial a finished [Trial].
 */
internal object LegacyImport {
    private val SCORE_KEYS = listOf("score", "rating", "Score", "Rating")
    private val NOTE_KEYS = listOf("info", "notes", "comment", "Info", "freeText")
    private val VALUE_KEYS = listOf("values", "measurements", "Values")
    private val TIME_KEYS = listOf("time", "date", "timestamp")
    private val TRIAL_STEP_KEYS = listOf("TrialSteps", "trialSteps")
    private val STEP_KEYS = listOf("BaseRecipe", "baseRecipe", "steps")
    private val TRIAL_KEYS = listOf("Trials", "trials")
    private val BULLET = Regex("""^[-*]\s+""")

    /** Millis below this are taken to be a Unix time in seconds. */
    private const val SECONDS_CUTOFF = 100_000_000_000L

    /** True for a JSON array whose elements are all objects with `name` and `versions`. */
    fun looksLikeLegacy(root: JsonElement): Boolean =
        root is JsonArray && root.all { it is JsonObject && "name" in it && "versions" in it }

    fun plan(root: JsonArray, existingRecipes: List<Recipe>, now: Long): ImportPlan {
        val takenNames = existingRecipes
            .filter { it.deletedAt == null }
            .mapTo(HashSet()) { nameKey(it.name) }
        val recipes = ArrayList<Recipe>()
        val versions = ArrayList<RecipeVersion>()
        val trials = ArrayList<Trial>()
        var skipped = 0

        for (element in root) {
            val legacy = element as JsonObject
            val name = legacy.string("name")?.trim().takeUnless { it.isNullOrEmpty() } ?: "Imported recipe"
            if (!takenNames.add(nameKey(name))) {
                skipped++
                continue
            }
            val recipe = Recipe(name = name, createdAt = now, updatedAt = now)
            recipes += recipe

            val legacyVersions = (legacy["versions"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            val versionSources = legacyVersions.ifEmpty { listOf(JsonObject(emptyMap())) }
            versionSources.forEachIndexed { i, source ->
                val steps = steps(source)
                // Strictly increasing so that the last legacy version is the newest one.
                val version = RecipeVersion(
                    recipeId = recipe.id,
                    name = "v${i + 1}",
                    steps = steps,
                    createdAt = now + i,
                    origin = VersionOrigin.IMPORT,
                )
                versions += version
                val legacyTrials = TRIAL_KEYS.firstNotNullOfOrNull { source[it] as? JsonArray }.orEmpty()
                legacyTrials.filterIsInstance<JsonObject>().forEach { t ->
                    trial(t, recipe.id, version, now)?.let { trials += it }
                }
            }
        }
        return ImportPlan(recipes, versions, trials, skipped)
    }

    /** The base recipe lines as text steps, bullets stripped and blank lines dropped. */
    internal fun steps(version: JsonObject): List<Step> =
        lines(STEP_KEYS.firstNotNullOfOrNull { version[it] }).map { Step.Text(it) }

    private fun lines(element: JsonElement?): List<String> {
        val raw = when (element) {
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> element.contentOrNull?.lines().orEmpty()
            else -> emptyList()
        }
        return raw.flatMap { it.lines() }
            .map { it.trim().replaceFirst(BULLET, "").trim() }
            .filter { it.isNotEmpty() }
    }

    private fun trial(t: JsonObject, recipeId: String, version: RecipeVersion, now: Long): Trial? {
        if (t.isEmpty()) return null
        val score = SCORE_KEYS.firstNotNullOfOrNull { number(t[it]) } ?: return null
        val params = StepParser.params(version.steps)
        val finishedAt = TIME_KEYS.firstNotNullOfOrNull { number(t[it]) }
            ?.toLong()
            ?.let { if (it in 1 until SECONDS_CUTOFF) it * 1000 else it }
            ?.takeIf { it > 0 }
            ?: now
        return Trial(
            recipeId = recipeId,
            versionId = version.id,
            values = values(t, params.size) ?: params.map { it.baseValue },
            status = TrialStatus.DONE,
            mode = TrialMode.AS_WRITTEN,
            createdAt = finishedAt,
            finishedAt = finishedAt,
            overallScore = score.coerceIn(0.0, 10.0),
            notes = NOTE_KEYS.firstNotNullOfOrNull { k -> t.string(k)?.trim()?.takeIf { it.isNotEmpty() } }.orEmpty(),
        )
    }

    /**
     * The trial's own parameter values: an explicit numeric list, or the numbers written in
     * the tweaked steps the web app stored. Null when neither matches [count].
     */
    private fun values(t: JsonObject, count: Int): List<Double>? {
        VALUE_KEYS.forEach { key ->
            val list = (t[key] as? JsonArray)?.map { number(it) }
            if (list != null && list.size == count && list.all { it != null }) return list.map { it!! }
        }
        TRIAL_STEP_KEYS.forEach { key ->
            val element = t[key] ?: return@forEach
            val fromSteps = lines(element).flatMap { line -> StepParser.paramsInText(line).map { it.first } }
            if (fromSteps.size == count && count > 0) return fromSteps
        }
        return null
    }

    /** A finite number from a JSON number or a numeric string; null otherwise. */
    internal fun number(element: JsonElement?): Double? {
        if (element == null || element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        val value = if (primitive.isString) primitive.content.trim().replace(',', '.').toDoubleOrNull() else primitive.doubleOrNull
        return value?.takeIf { it.isFinite() }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

    private fun nameKey(name: String): String = name.trim().lowercase()
}
