package com.lioravrahami.souschef.data.backup

import com.lioravrahami.souschef.data.repo.RecipeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Export / import of all recipes as JSON.
 *
 * A thin wrapper around [BackupCodec]: it reads everything from the [repository],
 * lets the codec decide what to add, and writes the additions in one transaction.
 */
class BackupManager(private val repository: RecipeRepository) {

    data class ImportResult(
        val recipesAdded: Int,
        val versionsAdded: Int,
        val trialsAdded: Int,
        val recipesSkipped: Int,
    )

    /** Full backup of every recipe, version and trial (including trashed ones). */
    suspend fun exportJson(): String {
        val recipes = repository.getAllRecipes()
        val versions = repository.getAllVersions()
        val trials = repository.getAllTrials()
        return withContext(Dispatchers.Default) {
            BackupCodec.encode(recipes, versions, trials, exportedAt = System.currentTimeMillis())
        }
    }

    /**
     * Imports this app's backup format or the old web app's `recipes.json`.
     * Adds what is missing and never deletes or overwrites existing data.
     * Throws IllegalArgumentException with a readable message if the file is not understood.
     */
    suspend fun importJson(json: String): ImportResult {
        require(json.length <= MAX_IMPORT_CHARS) { "This file is too large to be a Sous Chef backup (over 50 MB)." }
        val existingRecipes = repository.getAllRecipes()
        val existingVersionIds = repository.getAllVersions().mapTo(HashSet()) { it.id }
        val existingTrialIds = repository.getAllTrials().mapTo(HashSet()) { it.id }
        val plan = withContext(Dispatchers.Default) {
            BackupCodec.planImport(json, existingRecipes, existingVersionIds, existingTrialIds)
        }
        if (plan.recipes.isNotEmpty() || plan.versions.isNotEmpty() || plan.trials.isNotEmpty()) {
            repository.upsertAll(plan.recipes, plan.versions, plan.trials)
        }
        return plan.result
    }

    companion object {
        /** Largest backup file the app accepts, in bytes. */
        const val MAX_IMPORT_BYTES: Long = 50L * 1024 * 1024

        /** Upper bound on characters (a character takes at least one byte in UTF-8). */
        private const val MAX_IMPORT_CHARS: Int = (MAX_IMPORT_BYTES).toInt()
    }
}
