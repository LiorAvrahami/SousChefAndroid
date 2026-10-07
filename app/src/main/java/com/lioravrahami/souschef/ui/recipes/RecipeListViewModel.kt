package com.lioravrahami.souschef.ui.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lioravrahami.souschef.AppContainer
import com.lioravrahami.souschef.data.model.RecipeSummary
import com.lioravrahami.souschef.data.model.Trial
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The cooking session that is currently in progress, as shown in the list's banner. */
data class CookingBanner(
    val trialId: String,
    val recipeName: String?,
    val currentStep: Int,
    /** Number of steps of the trial's version; null when the version cannot be found. */
    val stepCount: Int?,
) {
    val text: String get() = RecipeListText.bannerText(recipeName, currentStep, stepCount)
}

/** State and actions of [RecipeListScreen]. */
class RecipeListViewModel(private val container: AppContainer) : ViewModel() {
    private val repository get() = container.repository

    /** All recipes outside the trash, most recently changed first; null while loading. */
    val summaries: StateFlow<List<RecipeSummary>?> = repository.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    /** The in-progress cooking session, if any. */
    val banner: StateFlow<CookingBanner?> = repository.observeInProgressTrial()
        .map { trial -> trial?.let { bannerFor(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    private suspend fun bannerFor(trial: Trial): CookingBanner {
        val details = try {
            repository.getDetails(trial.recipeId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return CookingBanner(
            trialId = trial.id,
            recipeName = details?.recipe?.name,
            currentStep = trial.currentStep,
            stepCount = details?.version(trial.versionId)?.steps?.size,
        )
    }

    /** Abandons the cooking session [trialId] (it stays in the database as ABORTED). */
    fun abandon(trialId: String) {
        viewModelScope.launch { container.sessions.abort(trialId) }
    }

    /** Moves a recipe to the trash; it can be restored from Settings → Trash. */
    fun moveToTrash(recipeId: String) {
        viewModelScope.launch { repository.moveToTrash(recipeId) }
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
