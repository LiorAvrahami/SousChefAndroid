package com.lioravrahami.souschef.ui.recipe

import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.data.model.TrialMode
import com.lioravrahami.souschef.domain.llm.LlmSuggestion

/** Where a pending cooking came from; decides the sheet title and whether it can be re-rolled. */
enum class ProposalSource(val title: String, val mode: TrialMode, val canReroll: Boolean) {
    BEST("Best so far", TrialMode.BEST, canReroll = false),
    CLASSICAL("Classical tweak", TrialMode.EXPLORE, canReroll = true),
    AI("AI suggestion", TrialMode.AI, canReroll = true),
    AS_WRITTEN("As written", TrialMode.AS_WRITTEN, canReroll = false),
}

/**
 * Everything the proposal sheet shows before a cooking starts, and what "Cook this" needs.
 *
 * For an existing version [versionId] is set and [values] are the values to cook. For an AI
 * structural change [newVersion] is set instead: the version is created only once the user
 * confirms, then cooked with its written values.
 */
data class PendingCook(
    val source: ProposalSource,
    val versionId: String?,
    val versionName: String,
    /** Steps of the version (or of the proposed new version). */
    val steps: List<Step>,
    /** Values to cook; null for a new version (cooked as written). */
    val values: List<Double>?,
    /** One-line summary (AI suggestions only). */
    val summary: String? = null,
    /** Why: the optimizer's rationale sentence or the AI's paragraphs. */
    val rationale: String,
    /** Changes relative to the version as written; empty = exactly as written. */
    val changes: List<String>,
    val newVersion: LlmSuggestion.NewVersion? = null,
    /** Extra context line such as "based on “v1”". */
    val basedOn: String? = null,
) {
    /** Text stored on the trial so the cooking and rating screens can say why. */
    val trialRationale: String
        get() = listOfNotNull(summary?.takeIf { it.isNotBlank() }, rationale.takeIf { it.isNotBlank() })
            .joinToString("\n\n")
}

/** State of the "start cooking" flow of the detail screen. */
sealed interface ProposalState {
    data object Idle : ProposalState

    /** Waiting for the AI; the loading dialog can cancel it. */
    data object Loading : ProposalState

    /**
     * The proposal sheet is open. [confirmAbandon] shows the "a session is in progress"
     * warning; [starting] disables the buttons while the trial is being created.
     */
    data class Ready(
        val cook: PendingCook,
        val confirmAbandon: Boolean = false,
        val starting: Boolean = false,
    ) : ProposalState

    /** A dialog with a title and a message: errors and "nothing to tweak" style information. */
    data class Message(val title: String, val text: String, val isError: Boolean) : ProposalState
}
