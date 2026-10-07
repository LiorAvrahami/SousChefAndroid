package com.lioravrahami.souschef.ui.recipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lioravrahami.souschef.AppContainer
import com.lioravrahami.souschef.data.model.RecipeDetails
import com.lioravrahami.souschef.data.model.RecipeVersion
import com.lioravrahami.souschef.data.model.Trial
import com.lioravrahami.souschef.data.model.VersionOrigin
import com.lioravrahami.souschef.domain.llm.LlmSuggestion
import com.lioravrahami.souschef.domain.optimizer.Proposal
import com.lioravrahami.souschef.domain.optimizer.ProposalKind
import com.lioravrahami.souschef.domain.recipe.StepParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the detail screen shows: still loading, a recipe, or "not found". */
sealed interface DetailContent {
    data object Loading : DetailContent
    data object NotFound : DetailContent
    data class Loaded(val details: RecipeDetails) : DetailContent
}

/** One-shot navigation events of the detail screen. */
sealed interface DetailEvent {
    /** A cooking session was created: open the cooking screen. */
    data class StartCooking(val trialId: String) : DetailEvent

    /** The recipe went to the trash: leave the screen. */
    data object Closed : DetailEvent
}

/**
 * State and actions of [RecipeDetailScreen]: the recipe, the start-cooking flow (best, classical
 * tweak, AI suggestion, as written) and the version / history actions.
 */
class RecipeDetailViewModel(
    private val container: AppContainer,
    private val recipeId: String,
) : ViewModel() {
    private val repository get() = container.repository

    val content: StateFlow<DetailContent> = repository.observeDetails(recipeId)
        .map { if (it == null) DetailContent.NotFound else DetailContent.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), DetailContent.Loading)

    /** The recipe with its versions and trials; null while loading or when it does not exist. */
    val details: StateFlow<RecipeDetails?> = content
        .map { (it as? DetailContent.Loaded)?.details }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    /** The cooking session in progress for any recipe, if one exists. */
    val inProgress: StateFlow<Trial?> = repository.observeInProgressTrial()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    private val _proposal = MutableStateFlow<ProposalState>(ProposalState.Idle)
    val proposal: StateFlow<ProposalState> = _proposal.asStateFlow()

    /** A short confirmation or refusal from a version / history action, shown in a dialog. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _events = Channel<DetailEvent>(Channel.BUFFERED)
    val events: Flow<DetailEvent> = _events.receiveAsFlow()

    private var aiJob: Job? = null
    /** Bumped on every AI request and on cancel, so a late reply of an old request is dropped. */
    private var aiRequest = 0

    /** The latest details, read on demand (also when the screen is not collecting yet). */
    private suspend fun currentDetails(): RecipeDetails? = details.value ?: repository.getDetails(recipeId)

    // ---------------------------------------------------------------- start flows

    /** What "Cook the best so far" would cook; null when the recipe has no versions. */
    fun bestProposal(details: RecipeDetails): Proposal? =
        if (details.versions.isEmpty()) null else container.optimizer.best(details)

    /** "Cook the best so far": the best rated cooking, else the latest version as written. */
    fun cookBest() = launchProposal {
        val d = currentDetails() ?: return@launchProposal
        if (d.versions.isEmpty()) return@launchProposal noVersions()
        val best = container.optimizer.best(d)
        _proposal.value = readyFrom(d, best, ProposalSource.BEST)
    }

    /** "Cook as written" on a version card. */
    fun cookAsWritten(version: RecipeVersion) {
        val values = StepParser.baseValues(version.steps)
        _proposal.value = ProposalState.Ready(
            PendingCook(
                source = ProposalSource.AS_WRITTEN,
                versionId = version.id,
                versionName = version.name,
                steps = version.steps,
                values = values,
                rationale = "Cooked exactly as written",
                changes = emptyList(),
            ),
        )
    }

    /** A classical tweak; "Another suggestion" calls this again and always gets a different one. */
    fun exploreClassical() {
        val previous = (_proposal.value as? ProposalState.Ready)?.cook
            ?.takeIf { it.source == ProposalSource.CLASSICAL && it.versionId != null && it.values != null }
            ?.let { Proposal(it.versionId!!, it.values!!, it.rationale, ProposalKind.LOCAL) }
        launchProposal {
            val d = currentDetails() ?: return@launchProposal
            if (d.versions.isEmpty()) return@launchProposal noVersions()
            if (d.activeVersions.isEmpty()) {
                _proposal.value = ProposalState.Message(
                    "Nothing to explore",
                    "Every version of this recipe is archived. Unarchive a version (or create a new one) to try tweaks.",
                    isError = false,
                )
                return@launchProposal
            }
            val settings = container.optimizerSettings()
            val proposal = try {
                withContext(Dispatchers.Default) {
                    RecipeDetailText.firstDifferent(previous) { container.optimizer.propose(d, settings) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                _proposal.value = ProposalState.Message(
                    "Nothing to explore",
                    "This recipe has no active version to tweak. Unarchive a version or create a new one.",
                    isError = false,
                )
                return@launchProposal
            }
            _proposal.value = if (proposal.kind == ProposalKind.BASELINE) {
                ProposalState.Message(
                    "Nothing to tweak",
                    proposal.rationale.trimEnd('.') + ". Unlock an amount or a time in the editor to let the optimizer try changes.",
                    isError = false,
                )
            } else {
                readyFrom(d, proposal, ProposalSource.CLASSICAL)
            }
        }
    }

    /** Asks the AI for a suggestion; cancellable with [cancelLoading]. */
    fun exploreAi() {
        aiJob?.cancel()
        val request = ++aiRequest
        _proposal.value = ProposalState.Loading
        aiJob = viewModelScope.launch {
            val d = try {
                currentDetails()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (d == null || d.versions.isEmpty()) {
                if (request == aiRequest) noVersions()
                return@launch
            }
            val result = try {
                Result.success(
                    withContext(Dispatchers.IO) { container.llmOptimizer.suggest(d, container.optimizerSettings()) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (request != aiRequest) return@launch
            _proposal.value = result.fold(
                onSuccess = { readyFrom(d, it) },
                onFailure = { e ->
                    ProposalState.Message(
                        "No AI suggestion",
                        e.message?.takeIf { it.isNotBlank() } ?: "The AI request failed (${e.javaClass.simpleName}).",
                        isError = true,
                    )
                },
            )
        }
    }

    /** Cancels a running AI request; a cancelled request shows no error. */
    fun cancelLoading() {
        aiRequest++
        aiJob?.cancel()
        aiJob = null
        if (_proposal.value == ProposalState.Loading) _proposal.value = ProposalState.Idle
    }

    /** "Another suggestion": re-runs the optimizer that produced the open proposal. */
    fun anotherSuggestion() {
        val ready = _proposal.value as? ProposalState.Ready ?: return
        if (ready.starting) return
        when (ready.cook.source) {
            ProposalSource.CLASSICAL -> exploreClassical()
            ProposalSource.AI -> exploreAi()
            ProposalSource.BEST, ProposalSource.AS_WRITTEN -> Unit
        }
    }

    /** Closes the proposal sheet or the message dialog. */
    fun dismissProposal() {
        val state = _proposal.value
        if (state is ProposalState.Ready && state.starting) return
        if (state == ProposalState.Loading) return cancelLoading()
        _proposal.value = ProposalState.Idle
    }

    /** "Cook this": warns first when another session is in progress, else starts right away. */
    fun cook() {
        val ready = _proposal.value as? ProposalState.Ready ?: return
        if (ready.starting) return
        val checking = ready.copy(starting = true)
        _proposal.value = checking
        viewModelScope.launch {
            val busy = try {
                repository.getInProgressTrials().isNotEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (_proposal.value !== checking) return@launch
            if (busy) {
                _proposal.value = ready.copy(confirmAbandon = true)
            } else {
                start(checking)
            }
        }
    }

    /** "Continue" on the abandon warning: starts the new session (which abandons the old one). */
    fun confirmAbandonAndCook() {
        val ready = _proposal.value as? ProposalState.Ready ?: return
        if (ready.starting) return
        val starting = ready.copy(confirmAbandon = false, starting = true)
        _proposal.value = starting
        viewModelScope.launch { start(starting) }
    }

    /** "Cancel" on the abandon warning: back to the proposal sheet. */
    fun dismissAbandonWarning() {
        val ready = _proposal.value as? ProposalState.Ready ?: return
        _proposal.value = ready.copy(confirmAbandon = false)
    }

    private suspend fun start(state: ProposalState.Ready) {
        val cook = state.cook
        try {
            val newVersion = cook.newVersion
            val trial = if (newVersion != null) {
                val version = repository.addVersion(
                    recipeId = recipeId,
                    steps = newVersion.steps,
                    name = newVersion.name,
                    parentVersionId = newVersion.parentVersionId,
                    origin = VersionOrigin.AI,
                    note = newVersion.summary,
                )
                container.sessions.start(
                    recipeId, version.id, StepParser.baseValues(version.steps), cook.source.mode, cook.trialRationale,
                )
            } else {
                val versionId = checkNotNull(cook.versionId) { "No version to cook" }
                val values = cook.values ?: emptyList()
                container.sessions.start(recipeId, versionId, values, cook.source.mode, cook.trialRationale)
            }
            _proposal.value = ProposalState.Idle
            _events.send(DetailEvent.StartCooking(trial.id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _proposal.value = ProposalState.Message(
                "Couldn't start cooking",
                e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong while saving.",
                isError = true,
            )
        }
    }

    // ---------------------------------------------------------------- proposals → sheet

    private fun readyFrom(details: RecipeDetails, proposal: Proposal, source: ProposalSource): ProposalState {
        val version = details.version(proposal.versionId) ?: return ProposalState.Message(
            "Couldn't prepare the cooking",
            "The proposed version no longer exists.",
            isError = true,
        )
        val changes = RecipeDetailText.changes(version, proposal.values) ?: return mismatch()
        return ProposalState.Ready(
            PendingCook(
                source = source,
                versionId = version.id,
                versionName = version.name,
                steps = version.steps,
                values = proposal.values,
                rationale = proposal.rationale,
                changes = changes,
            ),
        )
    }

    private fun readyFrom(details: RecipeDetails, suggestion: LlmSuggestion): ProposalState = when (suggestion) {
        is LlmSuggestion.Values -> {
            val version = details.version(suggestion.versionId)
            val changes = RecipeDetailText.changes(version, suggestion.values)
            if (version == null || changes == null) {
                mismatch()
            } else {
                ProposalState.Ready(
                    PendingCook(
                        source = ProposalSource.AI,
                        versionId = version.id,
                        versionName = version.name,
                        steps = version.steps,
                        values = suggestion.values,
                        summary = suggestion.summary,
                        rationale = suggestion.rationale,
                        changes = changes,
                    ),
                )
            }
        }
        is LlmSuggestion.NewVersion -> ProposalState.Ready(
            PendingCook(
                source = ProposalSource.AI,
                versionId = null,
                versionName = suggestion.name,
                steps = suggestion.steps,
                values = null,
                summary = suggestion.summary,
                rationale = suggestion.rationale,
                changes = emptyList(),
                newVersion = suggestion,
                basedOn = RecipeDetailText.basedOn(details.version(suggestion.parentVersionId)),
            ),
        )
    }

    private fun mismatch() = ProposalState.Message(
        "Couldn't prepare the cooking",
        "The proposed values don't match the recipe's steps. Try again.",
        isError = true,
    )

    private fun noVersions() {
        _proposal.value = ProposalState.Message(
            "No version to cook",
            "This recipe has no steps saved. Open the editor to write them.",
            isError = true,
        )
    }

    /** Runs a quick proposal computation, turning unexpected failures into an error dialog. */
    private fun launchProposal(block: suspend () -> Unit) {
        if ((_proposal.value as? ProposalState.Ready)?.starting == true) return
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _proposal.value = ProposalState.Message(
                    "Couldn't prepare the cooking",
                    e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName,
                    isError = true,
                )
            }
        }
    }

    // ---------------------------------------------------------------- versions and history

    /** Archives or unarchives [version]; refuses to archive the last active version. */
    fun toggleArchive(version: RecipeVersion) {
        launchAction {
            val d = currentDetails() ?: return@launchAction
            val current = d.version(version.id) ?: return@launchAction
            if (!current.archived && !RecipeDetailText.canArchive(d, current)) {
                _notice.value = "“${current.name}” is the only active version, so it can't be archived. " +
                    "Create or unarchive another version first."
                return@launchAction
            }
            repository.saveVersion(current.copy(archived = !current.archived))
        }
    }

    /** "Make this a version": writes a cooking's values into a new version of the recipe. */
    fun makeVersionFromTrial(trial: Trial) {
        launchAction {
            val d = currentDetails() ?: return@launchAction
            val version = d.version(trial.versionId)
            if (version == null || !RecipeDetailText.matchesVersion(version, trial.values)) {
                _notice.value = "This cooking's values don't match its version, so it can't become a version."
                return@launchAction
            }
            val dateText = RecipeDetailText.formatDate(trial.finishedAt ?: trial.createdAt)
            val created = repository.addVersion(
                recipeId = recipeId,
                steps = StepParser.applyValues(version.steps, trial.values),
                parentVersionId = version.id,
                note = RecipeDetailText.fromCookingNote(dateText),
            )
            _notice.value = "Saved as version “${created.name}”."
        }
    }

    /** Runs a database action, reporting a failure in the notice dialog instead of crashing. */
    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.value = "Something went wrong: " + (e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun dismissNotice() {
        _notice.value = null
    }

    /** Moves the recipe to the trash and leaves the screen. */
    fun moveToTrash() {
        launchAction {
            repository.moveToTrash(recipeId)
            _events.send(DetailEvent.Closed)
        }
    }

    override fun onCleared() {
        aiJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
