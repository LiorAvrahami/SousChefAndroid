package com.lioravrahami.souschef.ui.cooking

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lioravrahami.souschef.AppContainer
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * State holder of the cooking screen. All session state lives in the persisted trial
 * (through [AppContainer.sessions]); this class only adds the bits that belong to one
 * visit of the screen, such as which wait steps were already dealt with.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CookingViewModel(
    private val container: AppContainer,
    val trialId: String,
) : ViewModel() {
    private val sessions get() = container.sessions

    private val trialFlow = container.repository.observeTrial(trialId)

    /** The screen state, rebuilt whenever the trial or its recipe changes in the database. */
    val state: StateFlow<CookingUiState> = combine(
        trialFlow,
        trialFlow
            .map { it?.recipeId }
            .distinctUntilChanged()
            .flatMapLatest { recipeId ->
                if (recipeId == null) flowOf(null) else container.repository.observeDetails(recipeId)
            },
    ) { trial, details ->
        // The details flow may still carry another recipe for a moment; never mix them.
        CookingStates.build(trial, details?.takeIf { it.recipe.id == trial?.recipeId })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CookingUiState.Loading)

    /**
     * Wait steps whose timer was completed, skipped or stopped during this visit. Their pages
     * never auto-start again (the cook can still press "Start timer").
     */
    val handledWaits: SnapshotStateList<Int> = mutableStateListOf()

    /**
     * Becomes true once the cook moved away from the page the screen opened on (or right away
     * for a fresh session on its first page); from then on
     * a wait page that becomes the settled page starts its timer by itself.
     */
    var autoStartArmed by mutableStateOf(false)
        private set

    /** True while finishing or exiting, so the screen does not flash intermediate states. */
    var leaving by mutableStateOf(false)
        private set

    private var openedOnPage: Int? = null

    /**
     * Every session write is a read-modify-write of the trial row; running them one at a
     * time (in launch order) keeps e.g. a page change from overwriting a just-started timer.
     */
    private val writeLock = Mutex()

    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch { writeLock.withLock { block() } }
    }

    /**
     * Called for every settled pager page: persists it and arms auto-start after the first
     * move. [freshSession] says the session has never run a timer and sits on its first page,
     * so a wait as the very first step may start by itself too.
     */
    fun onPageSettled(page: Int, freshSession: Boolean) {
        val first = openedOnPage
        if (first == null) {
            openedOnPage = page
            if (freshSession && page == 0) autoStartArmed = true
        } else if (page != first) {
            autoStartArmed = true
        }
        write { sessions.setStep(trialId, page) }
    }

    /** Starts (or restarts) the timer of wait step [stepIndex] for [seconds], silencing any alarm first. */
    fun startWait(stepIndex: Int, seconds: Int) {
        if (stepIndex !in handledWaits) handledWaits.add(stepIndex)
        container.timerScheduler.stopRinging()
        write { sessions.startWait(trialId, stepIndex, seconds) }
    }

    /** Auto-start of a wait page; marks the step so it never auto-starts twice in this visit. */
    fun autoStartWait(stepIndex: Int, seconds: Int) {
        if (stepIndex in handledWaits) return
        handledWaits.add(stepIndex)
        write { sessions.startWait(trialId, stepIndex, seconds) }
    }

    /** Remembers that the cook moved past wait step [stepIndex] without using its timer. */
    fun markHandled(stepIndex: Int) {
        if (stepIndex !in handledWaits) handledWaits.add(stepIndex)
    }

    /** Clears the session's timer (running or finished), silences the alarm, and remembers [stepIndex] as done. */
    fun clearWait(stepIndex: Int?) {
        if (stepIndex != null && stepIndex !in handledWaits) handledWaits.add(stepIndex)
        write { sessions.clearWait(trialId) }
    }

    /** Marks cooking as finished, then calls [onDone] (which navigates to the rating screen). */
    fun finish(onDone: () -> Unit) {
        if (leaving) return
        leaving = true
        write {
            sessions.finishCooking(trialId)
            onDone()
        }
    }

    /** Discards this cooking (it will not count for the optimizer), then calls [onDone]. */
    fun abort(onDone: () -> Unit) {
        if (leaving) return
        leaving = true
        write {
            sessions.abort(trialId)
            onDone()
        }
    }
}
