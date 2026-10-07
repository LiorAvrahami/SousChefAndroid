package com.lioravrahami.souschef.data.repo

import androidx.room.withTransaction
import com.lioravrahami.souschef.data.db.AppDatabase
import com.lioravrahami.souschef.data.model.RatingAxis
import com.lioravrahami.souschef.data.model.Recipe
import com.lioravrahami.souschef.data.model.RecipeDetails
import com.lioravrahami.souschef.data.model.RecipeSummary
import com.lioravrahami.souschef.data.model.RecipeVersion
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.data.model.Trial
import com.lioravrahami.souschef.data.model.TrialStatus
import com.lioravrahami.souschef.data.model.VersionOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Single entry point for everything stored on the device. */
class RecipeRepository(private val db: AppDatabase) {
    private val recipeDao get() = db.recipeDao()
    private val versionDao get() = db.versionDao()
    private val trialDao get() = db.trialDao()

    // ---------------------------------------------------------------- observe

    fun observeSummaries(): Flow<List<RecipeSummary>> =
        combine(recipeDao.observeActive(), versionDao.observeAll(), trialDao.observeAll()) { recipes, versions, trials ->
            recipes.map { r ->
                val vs = versions.filter { it.recipeId == r.id }
                val done = trials.filter { it.recipeId == r.id && it.status == TrialStatus.DONE }
                RecipeSummary(
                    recipe = r,
                    versionCount = vs.size,
                    trialCount = done.size,
                    bestScore = done.mapNotNull { it.overallScore }.maxOrNull(),
                    lastCookedAt = done.mapNotNull { it.finishedAt }.maxOrNull(),
                )
            }
        }

    fun observeTrash(): Flow<List<Recipe>> = recipeDao.observeDeleted()

    fun observeDetails(recipeId: String): Flow<RecipeDetails?> =
        combine(
            recipeDao.observe(recipeId),
            versionDao.observeForRecipe(recipeId),
            trialDao.observeForRecipe(recipeId),
        ) { recipe, versions, trials ->
            recipe?.let { RecipeDetails(it, versions, trials) }
        }

    suspend fun getDetails(recipeId: String): RecipeDetails? {
        val recipe = recipeDao.get(recipeId) ?: return null
        return RecipeDetails(recipe, versionDao.getForRecipe(recipeId), trialDao.getForRecipe(recipeId))
    }

    fun observeInProgressTrial(): Flow<Trial?> = trialDao.observeInProgress()
    suspend fun getInProgressTrials(): List<Trial> = trialDao.getInProgress()
    fun observeTrial(id: String): Flow<Trial?> = trialDao.observe(id)
    suspend fun getTrial(id: String): Trial? = trialDao.get(id)
    suspend fun getRecipe(id: String): Recipe? = recipeDao.get(id)
    suspend fun getVersion(id: String): RecipeVersion? = versionDao.get(id)

    // ---------------------------------------------------------------- write

    /** Creates a recipe together with its first version. */
    suspend fun createRecipe(
        name: String,
        steps: List<Step>,
        customAxes: List<RatingAxis> = emptyList(),
        notes: String = "",
        versionName: String = "v1",
    ): Recipe {
        val recipe = Recipe(name = name.trim(), customAxes = customAxes, notes = notes)
        val version = RecipeVersion(recipeId = recipe.id, name = versionName, steps = steps)
        db.withTransaction {
            recipeDao.upsert(recipe)
            versionDao.upsert(version)
        }
        return recipe
    }

    suspend fun saveRecipe(recipe: Recipe) {
        recipeDao.upsert(recipe.copy(updatedAt = System.currentTimeMillis()))
    }

    /** Adds a new version to an existing recipe and bumps the recipe's updatedAt. */
    suspend fun addVersion(
        recipeId: String,
        steps: List<Step>,
        name: String? = null,
        parentVersionId: String? = null,
        origin: VersionOrigin = VersionOrigin.MANUAL,
        note: String = "",
    ): RecipeVersion {
        val existing = versionDao.getForRecipe(recipeId)
        val version = RecipeVersion(
            recipeId = recipeId,
            name = name?.takeIf { it.isNotBlank() } ?: "v${existing.size + 1}",
            steps = steps,
            parentVersionId = parentVersionId,
            origin = origin,
            note = note,
        )
        db.withTransaction {
            versionDao.upsert(version)
            recipeDao.get(recipeId)?.let { recipeDao.upsert(it.copy(updatedAt = System.currentTimeMillis())) }
        }
        return version
    }

    suspend fun saveVersion(version: RecipeVersion) = versionDao.upsert(version)

    /**
     * Saves the recipe's metadata and, when [steps] is given, a new version based on them,
     * all in ONE transaction: a crash can never leave the recipe updated without its version.
     * Returns the new version, or null when [steps] is null.
     */
    suspend fun saveRecipeWithVersion(
        recipe: Recipe,
        steps: List<Step>?,
        versionName: String? = null,
        parentVersionId: String? = null,
        origin: VersionOrigin = VersionOrigin.MANUAL,
        note: String = "",
    ): RecipeVersion? = db.withTransaction {
        recipeDao.upsert(recipe.copy(updatedAt = System.currentTimeMillis()))
        if (steps == null) {
            null
        } else {
            addVersion(recipe.id, steps, versionName, parentVersionId, origin, note)
        }
    }

    suspend fun saveTrial(trial: Trial) {
        db.withTransaction {
            trialDao.upsert(trial)
            if (trial.status == TrialStatus.DONE) {
                recipeDao.get(trial.recipeId)?.let { recipeDao.upsert(it.copy(updatedAt = System.currentTimeMillis())) }
            }
        }
    }

    suspend fun deleteTrial(id: String) = trialDao.delete(id)

    suspend fun moveToTrash(recipeId: String) {
        recipeDao.get(recipeId)?.let { recipeDao.upsert(it.copy(deletedAt = System.currentTimeMillis())) }
    }

    suspend fun restoreFromTrash(recipeId: String) {
        recipeDao.get(recipeId)?.let { recipeDao.upsert(it.copy(deletedAt = null, updatedAt = System.currentTimeMillis())) }
    }

    /** Permanently deletes one recipe with all versions and trials. */
    suspend fun purge(recipeId: String) {
        db.withTransaction {
            trialDao.deleteForRecipe(recipeId)
            versionDao.deleteForRecipe(recipeId)
            recipeDao.delete(recipeId)
        }
    }

    suspend fun emptyTrash() {
        val deleted = recipeDao.getAll().filter { it.deletedAt != null }
        db.withTransaction { deleted.forEach { purge(it.id) } }
    }

    // ---------------------------------------------------------------- bulk (backup)

    suspend fun getAllRecipes(): List<Recipe> = recipeDao.getAll()
    suspend fun getAllVersions(): List<RecipeVersion> = versionDao.getAll()
    suspend fun getAllTrials(): List<Trial> = trialDao.getAll()

    /** Inserts or replaces everything given, in one transaction. */
    suspend fun upsertAll(recipes: List<Recipe>, versions: List<RecipeVersion>, trials: List<Trial>) {
        db.withTransaction {
            if (recipes.isNotEmpty()) recipeDao.upsertAll(recipes)
            if (versions.isNotEmpty()) versionDao.upsertAll(versions)
            if (trials.isNotEmpty()) trialDao.upsertAll(trials)
        }
    }
}
