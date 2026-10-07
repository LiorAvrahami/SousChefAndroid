package com.lioravrahami.souschef.ui.rating

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lioravrahami.souschef.AppContainer
import com.lioravrahami.souschef.data.model.RatingAxis
import com.lioravrahami.souschef.data.model.RecipeDetails
import com.lioravrahami.souschef.data.model.RecipeVersion
import com.lioravrahami.souschef.data.model.Trial
import com.lioravrahami.souschef.data.model.TrialStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the rating screen shows. */
sealed interface RatingUiState {
    data object Loading : RatingUiState

    /** The trial or its recipe no longer exists. */
    data class Missing(val recipeId: String?) : RatingUiState

    /** Already rated or discarded: nothing to rate. */
    data class Closed(val recipeId: String, val status: TrialStatus, val score: Double?) : RatingUiState

    /** A cooking waiting for its rating. [version] is null if the version was removed. */
    data class Ready(
        val trial: Trial,
        val details: RecipeDetails,
        val version: RecipeVersion?,
    ) : RatingUiState {
        val axes: List<RatingAxis> get() = details.axes
    }
}

/** Loads the trial to rate and holds the form state (it survives rotation). */
@OptIn(ExperimentalCoroutinesApi::class)
class RatingViewModel(
    private val container: AppContainer,
    val trialId: String,
) : ViewModel() {
    private val trialFlow = container.repository.observeTrial(trialId)

    val state: StateFlow<RatingUiState> = combine(
        trialFlow,
        trialFlow
            .map { it?.recipeId }
            .distinctUntilChanged()
            .flatMapLatest { recipeId ->
                if (recipeId == null) flowOf(null) else container.repository.observeDetails(recipeId)
            },
    ) { trial, details ->
        when {
            trial == null -> RatingUiState.Missing(null)
            trial.status != TrialStatus.IN_PROGRESS -> RatingUiState.Closed(trial.recipeId, trial.status, trial.overallScore)
            details == null || details.recipe.id != trial.recipeId -> RatingUiState.Missing(trial.recipeId)
            else -> RatingUiState.Ready(trial, details, details.version(trial.versionId))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RatingUiState.Loading)

    /** Overall score 0..10 in steps of 0.5. */
    var score by mutableDoubleStateOf(RatingLogic.DEFAULT_SCORE)
        private set

    /** Axis id -> -2..+2; axes not in the map are at 0 ("just right"). */
    val axisValues: SnapshotStateMap<String, Double> = mutableStateMapOf()

    var notes by mutableStateOf("")

    /** True once saving or discarding started; the form is disabled and the screen is about to close. */
    var leaving by mutableStateOf(false)
        private set

    fun onScoreChange(value: Float) {
        score = RatingLogic.snapScore(value)
    }

    fun onAxisChange(axisId: String, value: Float) {
        axisValues[axisId] = RatingLogic.snapAxis(value)
    }

    /** Saves the rating (marks the trial DONE), then calls [onDone] with the recipe id. */
    fun save(axes: List<RatingAxis>, recipeId: String, onDone: (String) -> Unit) {
        if (leaving) return
        leaving = true
        val values = RatingLogic.axesToSave(axes, axisValues)
        viewModelScope.launch {
            container.sessions.rate(trialId, score, values, notes.trim())
            onDone(recipeId)
        }
    }

    /** Discards this cooking (it stays in history as aborted), then calls [onDone] with the recipe id. */
    fun discard(recipeId: String, onDone: (String) -> Unit) {
        if (leaving) return
        leaving = true
        viewModelScope.launch {
            container.sessions.abort(trialId)
            onDone(recipeId)
        }
    }
}
