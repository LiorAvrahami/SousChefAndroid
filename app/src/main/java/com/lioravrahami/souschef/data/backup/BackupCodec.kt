package com.lioravrahami.souschef.data.backup

import com.lioravrahami.souschef.data.model.Recipe
import com.lioravrahami.souschef.data.model.RecipeVersion
import com.lioravrahami.souschef.data.model.Trial
import com.lioravrahami.souschef.data.model.TrialStatus
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The on-disk shape of a Sous Chef Android backup file.
 *
 * ```json
 * {"format":"souschef-android","formatVersion":1,"exportedAt":1730000000000,
 *  "recipes":[...],"versions":[...],"trials":[...]}
 * ```
 */
@Serializable
data class BackupFile(
    val format: String = BackupCodec.FORMAT,
    val formatVersion: Int = BackupCodec.FORMAT_VERSION,
    val exportedAt: Long,
    val recipes: List<Recipe> = emptyList(),
    val versions: List<RecipeVersion> = emptyList(),
    val trials: List<Trial> = emptyList(),
)

/** Everything an import will add, plus the counts shown to the user. */
data class ImportPlan(
    val recipes: List<Recipe>,
    val versions: List<RecipeVersion>,
    val trials: List<Trial>,
    val recipesSkipped: Int,
) {
    val result: BackupManager.ImportResult
        get() = BackupManager.ImportResult(
            recipesAdded = recipes.size,
            versionsAdded = versions.size,
            trialsAdded = trials.size,
            recipesSkipped = recipesSkipped,
        )
}

/**
 * Pure conversion between stored entities and backup JSON. Knows nothing about the
 * database: callers pass in what already exists and get back exactly what to add.
 * Nothing in here ever removes or replaces existing data.
 */
object BackupCodec {
    const val FORMAT = "souschef-android"
    const val FORMAT_VERSION = 1
    const val NOT_A_BACKUP = "This file is not a Sous Chef backup."

    val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Serializes everything given (trashed recipes and aborted trials included). */
    fun encode(
        recipes: List<Recipe>,
        versions: List<RecipeVersion>,
        trials: List<Trial>,
        exportedAt: Long,
    ): String = json.encodeToString(
        BackupFile.serializer(),
        BackupFile(exportedAt = exportedAt, recipes = recipes, versions = versions, trials = trials),
    )

    /**
     * Works out what importing [text] would add on top of the existing data.
     *
     * Understands this app's backup format and the old web app's `recipes.json`.
     * @param existingRecipes every recipe on the device, trashed ones included.
     * @throws IllegalArgumentException with a readable message if the file is not understood.
     */
    fun planImport(
        text: String,
        existingRecipes: List<Recipe>,
        existingVersionIds: Set<String>,
        existingTrialIds: Set<String>,
        now: Long = System.currentTimeMillis(),
    ): ImportPlan {
        val root = parse(text)
        return when {
            root is JsonObject && root.stringField("format") == FORMAT ->
                planNative(root, existingRecipes, existingVersionIds, existingTrialIds)
            LegacyImport.looksLikeLegacy(root) ->
                LegacyImport.plan(root as JsonArray, existingRecipes, now)
            else -> throw IllegalArgumentException(NOT_A_BACKUP)
        }
    }

    private fun parse(text: String): JsonElement {
        val trimmed = text.trim().removePrefix("﻿")
        if (trimmed.isEmpty()) throw IllegalArgumentException("The file is empty.")
        return try {
            json.parseToJsonElement(trimmed)
        } catch (e: SerializationException) {
            throw IllegalArgumentException(NOT_A_BACKUP, e)
        }
    }

    private fun planNative(
        root: JsonObject,
        existingRecipes: List<Recipe>,
        existingVersionIds: Set<String>,
        existingTrialIds: Set<String>,
    ): ImportPlan {
        val file = try {
            json.decodeFromJsonElement(BackupFile.serializer(), root)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("This Sous Chef backup is damaged and cannot be read.", e)
        }
        val existingRecipeIds = existingRecipes.mapTo(HashSet()) { it.id }

        val newRecipes = file.recipes.filter { it.id !in existingRecipeIds }.distinctBy { it.id }
        val skipped = file.recipes.map { it.id }.distinct().count { it in existingRecipeIds }
        val knownRecipeIds = existingRecipeIds + newRecipes.map { it.id }

        // A version or trial only makes sense next to its recipe (and version).
        val newVersions = file.versions
            .filter { it.id !in existingVersionIds && it.recipeId in knownRecipeIds }
            .distinctBy { it.id }
        val knownVersionIds = existingVersionIds + newVersions.map { it.id }

        val newTrials = file.trials
            .filter { it.id !in existingTrialIds && it.recipeId in knownRecipeIds && it.versionId in knownVersionIds }
            .distinctBy { it.id }
            .map(::settleSession)

        return ImportPlan(newRecipes, newVersions, newTrials, skipped)
    }

    /**
     * A cooking that was in progress when the backup was made cannot be resumed here
     * (its timer belongs to the other device), so it comes in as aborted.
     */
    private fun settleSession(trial: Trial): Trial =
        if (trial.status != TrialStatus.IN_PROGRESS) trial
        else trial.copy(
            status = TrialStatus.ABORTED,
            timerStepIndex = null,
            timerEndAt = null,
            timerStartedAt = null,
        )

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
