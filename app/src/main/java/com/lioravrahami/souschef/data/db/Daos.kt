package com.lioravrahami.souschef.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lioravrahami.souschef.data.model.Recipe
import com.lioravrahami.souschef.data.model.RecipeVersion
import com.lioravrahami.souschef.data.model.Trial
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipeDao {
    @Query("SELECT * FROM recipes WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeActive(): Flow<List<Recipe>>

    @Query("SELECT * FROM recipes WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeDeleted(): Flow<List<Recipe>>

    @Query("SELECT * FROM recipes WHERE id = :id")
    fun observe(id: String): Flow<Recipe?>

    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun get(id: String): Recipe?

    @Query("SELECT * FROM recipes")
    suspend fun getAll(): List<Recipe>

    @Upsert
    suspend fun upsert(recipe: Recipe)

    @Upsert
    suspend fun upsertAll(recipes: List<Recipe>)

    @Query("DELETE FROM recipes WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface VersionDao {
    @Query("SELECT * FROM versions WHERE recipeId = :recipeId ORDER BY createdAt ASC")
    fun observeForRecipe(recipeId: String): Flow<List<RecipeVersion>>

    @Query("SELECT * FROM versions WHERE recipeId = :recipeId ORDER BY createdAt ASC")
    suspend fun getForRecipe(recipeId: String): List<RecipeVersion>

    @Query("SELECT * FROM versions")
    fun observeAll(): Flow<List<RecipeVersion>>

    @Query("SELECT * FROM versions")
    suspend fun getAll(): List<RecipeVersion>

    @Query("SELECT * FROM versions WHERE id = :id")
    suspend fun get(id: String): RecipeVersion?

    @Upsert
    suspend fun upsert(version: RecipeVersion)

    @Upsert
    suspend fun upsertAll(versions: List<RecipeVersion>)

    @Query("DELETE FROM versions WHERE recipeId = :recipeId")
    suspend fun deleteForRecipe(recipeId: String)
}

@Dao
interface TrialDao {
    @Query("SELECT * FROM trials WHERE recipeId = :recipeId ORDER BY createdAt ASC")
    fun observeForRecipe(recipeId: String): Flow<List<Trial>>

    @Query("SELECT * FROM trials WHERE recipeId = :recipeId ORDER BY createdAt ASC")
    suspend fun getForRecipe(recipeId: String): List<Trial>

    @Query("SELECT * FROM trials")
    fun observeAll(): Flow<List<Trial>>

    @Query("SELECT * FROM trials")
    suspend fun getAll(): List<Trial>

    @Query("SELECT * FROM trials WHERE id = :id")
    suspend fun get(id: String): Trial?

    @Query("SELECT * FROM trials WHERE id = :id")
    fun observe(id: String): Flow<Trial?>

    @Query("SELECT * FROM trials WHERE status = 'IN_PROGRESS' ORDER BY createdAt DESC LIMIT 1")
    fun observeInProgress(): Flow<Trial?>

    @Query("SELECT * FROM trials WHERE status = 'IN_PROGRESS' ORDER BY createdAt DESC")
    suspend fun getInProgress(): List<Trial>

    @Upsert
    suspend fun upsert(trial: Trial)

    @Upsert
    suspend fun upsertAll(trials: List<Trial>)

    @Query("DELETE FROM trials WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM trials WHERE recipeId = :recipeId")
    suspend fun deleteForRecipe(recipeId: String)
}
