package com.lioravrahami.souschef

import android.content.Context
import com.lioravrahami.souschef.data.backup.BackupManager
import com.lioravrahami.souschef.data.db.AppDatabase
import com.lioravrahami.souschef.data.repo.RecipeRepository
import com.lioravrahami.souschef.data.settings.AppSettings
import com.lioravrahami.souschef.domain.llm.LlmOptimizer
import com.lioravrahami.souschef.domain.llm.OpenRouterClient
import com.lioravrahami.souschef.domain.optimizer.ClassicalOptimizer
import com.lioravrahami.souschef.domain.optimizer.OptimizerSettings
import com.lioravrahami.souschef.domain.session.CookingSessions
import com.lioravrahami.souschef.domain.timer.TimerScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-rolled dependency container; created once by [SousChefApp]. */
class AppContainer(val appContext: Context) {
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database: AppDatabase by lazy { AppDatabase.build(appContext) }
    val repository: RecipeRepository by lazy { RecipeRepository(database) }
    val settings: AppSettings by lazy { AppSettings(appContext) }
    val timerScheduler: TimerScheduler by lazy { TimerScheduler(appContext) }
    val sessions: CookingSessions by lazy { CookingSessions(repository, timerScheduler) }
    val optimizer: ClassicalOptimizer by lazy { ClassicalOptimizer() }
    val openRouter: OpenRouterClient by lazy { OpenRouterClient(settings) }
    val llmOptimizer: LlmOptimizer by lazy { LlmOptimizer(openRouter, settings) }
    val backup: BackupManager by lazy { BackupManager(repository) }

    /** Current optimizer settings as chosen on the Settings screen. */
    fun optimizerSettings(): OptimizerSettings =
        OptimizerSettings(boldness = settings.boldness.toDouble(), explorationRate = settings.explorationRate.toDouble())

    companion object {
        /** Convenience for code that only has a Context (receivers, services). */
        fun from(context: Context): AppContainer = (context.applicationContext as SousChefApp).container
    }
}
