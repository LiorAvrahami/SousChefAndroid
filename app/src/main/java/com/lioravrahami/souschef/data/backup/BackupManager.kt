package com.lioravrahami.souschef.data.backup

import com.lioravrahami.souschef.data.repo.RecipeRepository

// STUB — contract only. The real implementation replaces this file.

/** Export / import of all recipes as JSON. */
class BackupManager(private val repository: RecipeRepository) {

    data class ImportResult(
        val recipesAdded: Int,
        val versionsAdded: Int,
        val trialsAdded: Int,
        val recipesSkipped: Int,
    )

    /** Full backup of every recipe, version and trial (including trashed ones). */
    suspend fun exportJson(): String = "{}"

    /**
     * Imports this app's backup format or the old web app's `recipes.json`.
     * Adds what is missing and never deletes or overwrites existing data.
     * Throws IllegalArgumentException with a readable message if the file is not understood.
     */
    suspend fun importJson(json: String): ImportResult = ImportResult(0, 0, 0, 0)
}
