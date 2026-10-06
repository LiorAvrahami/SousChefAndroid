package com.lioravrahami.souschef.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.lioravrahami.souschef.data.model.Recipe
import com.lioravrahami.souschef.data.model.RecipeVersion
import com.lioravrahami.souschef.data.model.Trial

/**
 * The on-device database. Schema history is exported to app/schemas so that any future
 * change ships with a migration. There is deliberately NO destructive-migration fallback:
 * losing the user's recipes is never acceptable.
 */
@Database(
    entities = [Recipe::class, RecipeVersion::class, Trial::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recipeDao(): RecipeDao
    abstract fun versionDao(): VersionDao
    abstract fun trialDao(): TrialDao

    companion object {
        const val NAME = "souschef.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                // TRUNCATE journal keeps the database in a single file, which makes
                // Android Auto Backup and manual file copies consistent.
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .build()
    }
}
