package com.lioravrahami.souschef.domain.session

import com.lioravrahami.souschef.data.model.Trial
import com.lioravrahami.souschef.data.model.TrialMode
import com.lioravrahami.souschef.data.model.TrialStatus
import com.lioravrahami.souschef.data.repo.RecipeRepository
import com.lioravrahami.souschef.domain.timer.TimerScheduler

/**
 * Lifecycle of a cooking session. All state is persisted in the IN_PROGRESS [Trial] row so
 * the session survives the app being killed; the wait-step alarm is a system alarm.
 */
class CookingSessions(
    private val repository: RecipeRepository,
    private val timers: TimerScheduler,
) {
    /** Starts a session. Any other in-progress session is aborted first. */
    suspend fun start(
        recipeId: String,
        versionId: String,
        values: List<Double>,
        mode: TrialMode,
        rationale: String = "",
    ): Trial {
        repository.getInProgressTrials().forEach { abort(it.id) }
        val trial = Trial(
            recipeId = recipeId,
            versionId = versionId,
            values = values,
            status = TrialStatus.IN_PROGRESS,
            mode = mode,
            rationale = rationale,
        )
        repository.saveTrial(trial)
        return trial
    }

    suspend fun setStep(trialId: String, step: Int) {
        val trial = repository.getTrial(trialId) ?: return
        if (trial.currentStep != step) repository.saveTrial(trial.copy(currentStep = step))
    }

    /** Starts the timer of a wait step: persists the end time and schedules the system alarm. */
    suspend fun startWait(trialId: String, stepIndex: Int, seconds: Int): Trial? {
        val trial = repository.getTrial(trialId) ?: return null
        val now = System.currentTimeMillis()
        val endAt = now + seconds.coerceAtLeast(1) * 1000L
        val updated = trial.copy(
            currentStep = stepIndex,
            timerStepIndex = stepIndex,
            timerStartedAt = now,
            timerEndAt = endAt,
        )
        repository.saveTrial(updated)
        timers.schedule(trialId, stepIndex, endAt)
        return updated
    }

    /** Clears the running/finished timer of the session and silences the alarm. */
    suspend fun clearWait(trialId: String) {
        timers.cancel()
        timers.stopRinging()
        val trial = repository.getTrial(trialId) ?: return
        if (trial.timerEndAt != null || trial.timerStepIndex != null) {
            repository.saveTrial(trial.copy(timerStepIndex = null, timerEndAt = null, timerStartedAt = null))
        }
    }

    /** Ends cooking; the trial stays IN_PROGRESS until the rating screen marks it DONE. */
    suspend fun finishCooking(trialId: String): Trial? {
        timers.cancel()
        timers.stopRinging()
        val trial = repository.getTrial(trialId) ?: return null
        val updated = trial.copy(finishedAt = System.currentTimeMillis(), timerStepIndex = null, timerEndAt = null, timerStartedAt = null)
        repository.saveTrial(updated)
        return updated
    }

    /** Saves the rating and marks the trial DONE. */
    suspend fun rate(trialId: String, overallScore: Double, axes: Map<String, Double>, notes: String): Trial? {
        val trial = repository.getTrial(trialId) ?: return null
        val updated = trial.copy(
            status = TrialStatus.DONE,
            finishedAt = trial.finishedAt ?: System.currentTimeMillis(),
            overallScore = overallScore.coerceIn(0.0, 10.0),
            axes = axes,
            notes = notes,
            timerStepIndex = null,
            timerEndAt = null,
            timerStartedAt = null,
        )
        repository.saveTrial(updated)
        return updated
    }

    suspend fun abort(trialId: String) {
        timers.cancel()
        timers.stopRinging()
        val trial = repository.getTrial(trialId) ?: return
        if (trial.status == TrialStatus.IN_PROGRESS) {
            repository.saveTrial(
                trial.copy(status = TrialStatus.ABORTED, finishedAt = System.currentTimeMillis(), timerStepIndex = null, timerEndAt = null, timerStartedAt = null),
            )
        }
    }
}
