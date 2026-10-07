package com.lioravrahami.souschef.ui.cooking

import com.lioravrahami.souschef.data.model.RecipeDetails
import com.lioravrahami.souschef.data.model.RecipeVersion
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.data.model.Trial
import com.lioravrahami.souschef.data.model.TrialStatus
import com.lioravrahami.souschef.domain.recipe.ChangeSummary
import com.lioravrahami.souschef.domain.recipe.ParamSpec
import com.lioravrahami.souschef.domain.recipe.StepParser

/** Everything the cooking screen shows, derived from the persisted trial and its recipe. */
sealed interface CookingUiState {
    /** Still reading the database. */
    data object Loading : CookingUiState

    /** The trial (or its recipe / version) no longer exists. [recipeId] is known when only the version is gone. */
    data class Missing(val recipeId: String?) : CookingUiState

    /** Cooking is finished and only the rating is missing. */
    data class AwaitingRating(val trialId: String, val recipeId: String) : CookingUiState

    /** The trial was already rated or discarded; there is nothing left to cook. */
    data class Closed(val recipeId: String, val status: TrialStatus) : CookingUiState

    /** An active cooking session. */
    data class Ready(val session: CookingSession) : CookingUiState
}

/**
 * An in-progress cooking session: the version being cooked and the concrete values of
 * this trial. When the stored values do not fit the version (corrupt or legacy data),
 * [values] falls back to the amounts written in the steps and [valuesMismatch] is true.
 */
data class CookingSession(
    val trial: Trial,
    val recipeName: String,
    val version: RecipeVersion,
    val params: List<ParamSpec>,
    val values: List<Double>,
    val valuesMismatch: Boolean,
) {
    val steps: List<Step> get() = version.steps

    /** Number of pager pages: one per step plus the final "Done" page. */
    val pageCount: Int get() = steps.size + 1

    /** Every parameter whose value differs from what is written in the version. */
    val changes: List<ChangeSummary.Change> by lazy { ChangeSummary.changes(version.steps, null, values) }

    /** The changes that concern step [stepIndex], for the "this time" line of a page. */
    fun changesForStep(stepIndex: Int): List<ChangeSummary.Change> =
        changes.filter { it.param.stepIndex == stepIndex }

    /** Planned duration of the wait step at [stepIndex] with this trial's values (0 if it is not a wait). */
    fun waitSeconds(stepIndex: Int): Int {
        val step = steps.getOrNull(stepIndex) as? Step.Wait ?: return 0
        return StepParser.waitSeconds(step, stepIndex, params, values)
    }

    /** Plain-text rendering of step [stepIndex] with this trial's values (used by the overview). */
    fun render(stepIndex: Int): String = StepParser.render(steps[stepIndex], stepIndex, params, values)

    /** The page the pager opens on: the persisted current step, clamped to the existing pages. */
    val initialPage: Int get() = trial.currentStep.coerceIn(0, steps.size)
}

/** Pure construction of [CookingUiState] from database rows. */
object CookingStates {
    /** Classifies [trial] + [details] into what the cooking screen should show. */
    fun build(trial: Trial?, details: RecipeDetails?): CookingUiState {
        if (trial == null) return CookingUiState.Missing(null)
        if (trial.status != TrialStatus.IN_PROGRESS) return CookingUiState.Closed(trial.recipeId, trial.status)
        if (trial.finishedAt != null) return CookingUiState.AwaitingRating(trial.id, trial.recipeId)
        if (details == null) return CookingUiState.Missing(null)
        val version = details.version(trial.versionId) ?: return CookingUiState.Missing(trial.recipeId)
        val params = StepParser.params(version.steps)
        val mismatch = trial.values.size != params.size
        val values = if (mismatch) params.map { it.baseValue } else trial.values
        return CookingUiState.Ready(
            CookingSession(
                trial = trial,
                recipeName = details.recipe.name,
                version = version,
                params = params,
                values = values,
                valuesMismatch = mismatch,
            ),
        )
    }
}

/** State of a wait step's timer as seen from its own page. */
enum class WaitPhase {
    /** No timer is running for this step. */
    IDLE,

    /** This step's timer is counting down. */
    RUNNING,

    /** This step's timer has reached zero (the alarm rings until stopped). */
    TIMES_UP,
}

/** Arithmetic of persisted wall-clock timers. Never depends on a local counter, so it survives process death. */
object WaitClock {
    /** Whole seconds left until [endAtMillis], rounded up (0 once the end has passed). */
    fun remainingSeconds(endAtMillis: Long, nowMillis: Long): Int {
        val left = endAtMillis - nowMillis
        if (left <= 0) return 0
        return ((left + 999) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Fraction of the wait still remaining: 1.0 right after the start, 0.0 at the end.
     * Falls back to [plannedSeconds] as the total when the start time is unknown.
     */
    fun fractionRemaining(startedAtMillis: Long?, endAtMillis: Long, nowMillis: Long, plannedSeconds: Int): Float {
        val total = when {
            startedAtMillis != null && endAtMillis > startedAtMillis -> endAtMillis - startedAtMillis
            plannedSeconds > 0 -> plannedSeconds * 1000L
            else -> return 0f
        }
        val left = (endAtMillis - nowMillis).coerceIn(0L, total)
        return (left.toDouble() / total).toFloat()
    }

    /** Seconds for a "+1 min" restart: what is left plus [extraSeconds]. */
    fun extendedSeconds(remainingSeconds: Int, extraSeconds: Int = 60): Int =
        remainingSeconds.coerceAtLeast(0) + extraSeconds

    /** The phase of the wait step at [stepIndex] given the trial's timer fields. */
    fun phase(stepIndex: Int, timerStepIndex: Int?, timerEndAt: Long?, nowMillis: Long): WaitPhase = when {
        timerEndAt == null || timerStepIndex != stepIndex -> WaitPhase.IDLE
        timerEndAt > nowMillis -> WaitPhase.RUNNING
        else -> WaitPhase.TIMES_UP
    }

    /**
     * Whether a wait page should start its own timer automatically.
     *
     * Only the settled page may do so ([isSettled]); only when the cook moved to it during
     * this visit of the screen ([armed]) — the page the screen opens on may be a wait that
     * was already completed from the alarm notification; only once per step and visit
     * ([alreadyHandled]); and never while any timer exists ([timerEndAt] non-null), because
     * the app has a single alarm and starting would silently replace another step's timer.
     */
    fun shouldAutoStart(isSettled: Boolean, armed: Boolean, alreadyHandled: Boolean, timerEndAt: Long?): Boolean =
        isSettled && armed && !alreadyHandled && timerEndAt == null
}

/** Display texts shared by the cooking pages. */
object CookingTexts {
    /** "Step 3 of 8", or "All steps done" on the final page. */
    fun progressLabel(page: Int, stepCount: Int): String =
        if (page >= stepCount) "All steps done" else "Step ${page + 1} of $stepCount"

    /** Fraction for the thin progress bar at the top (the Done page is 100 %). */
    fun progressFraction(page: Int, stepCount: Int): Float =
        if (stepCount <= 0) 1f else ((page + 1).toFloat() / (stepCount + 1)).coerceIn(0f, 1f)

    /** "This time: water: 1.75 → 1.9 cups · salt: 3 → 4 shakes", or null when nothing changed. */
    fun changeLine(changes: List<ChangeSummary.Change>): String? =
        if (changes.isEmpty()) null else "This time: " + changes.joinToString(" · ") { it.text }

    /** Text of the compact bar shown on other pages while a step's timer runs or has finished. */
    fun otherTimerBar(timerStepIndex: Int, remainingSeconds: Int): String =
        if (remainingSeconds > 0) {
            "Timer running for step ${timerStepIndex + 1} — ${StepParser.formatClock(remainingSeconds)} left"
        } else {
            "Timer for step ${timerStepIndex + 1} is done — tap to go back"
        }
}
