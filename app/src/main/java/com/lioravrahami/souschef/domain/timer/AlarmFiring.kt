package com.lioravrahami.souschef.domain.timer

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.lioravrahami.souschef.AppContainer
import com.lioravrahami.souschef.data.model.Trial
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The code path shared by [AlarmReceiver] and [BootReceiver]: decide whether a timer alarm
 * should ring, and ring it through every layer available.
 */
internal object AlarmFiring {
    private const val TAG = "SousChefTimer"

    /** How long to wait for [AlarmService] to confirm it is ringing before ringing via the notification. */
    private const val SERVICE_CONFIRM_TIMEOUT_MS = 4_000L

    /** Handles the alarm broadcast of [trialId]'s wait step [stepIndex]. */
    suspend fun onAlarm(context: Context, trialId: String, stepIndex: Int) {
        val app = context.applicationContext
        val store = TimerStore(app)
        val trial = AppContainer.from(app).repository.getTrial(trialId)
        handle(app, store, trial, stepIndex, trialId)
    }

    /**
     * After a reboot or an app update: re-registers a pending timer, or rings one that ended
     * meanwhile. Falls back to the remembered schedule if the database cannot be read.
     */
    suspend fun restore(context: Context) {
        val app = context.applicationContext
        val store = TimerStore(app)
        val trials = try {
            AppContainer.from(app).repository.getInProgressTrials()
        } catch (e: Exception) {
            Log.e(TAG, "Could not read sessions after boot; using the remembered schedule", e)
            null
        }
        if (trials == null) {
            val remembered = store.scheduled ?: return
            if (remembered.endAt > System.currentTimeMillis()) {
                TimerScheduler(app).schedule(remembered.trialId, remembered.stepIndex, remembered.endAt)
            } else if (store.firedEndAt(remembered.trialId) != remembered.endAt) {
                store.markFired(remembered.trialId, remembered.endAt)
                store.scheduled = null
                ring(app, remembered.trialId, remembered.stepIndex, AlarmLogic.message(null, null, null, remembered.stepIndex, null, null))
            }
            return
        }
        val running = trials.filter { it.timerEndAt != null }.maxByOrNull { it.timerEndAt ?: 0L }
        if (running == null) {
            // Nothing is running any more: drop any stale schedule and countdown.
            if (store.scheduled != null) TimerScheduler(app).cancel()
            return
        }
        handle(app, store, running, running.timerStepIndex ?: 0, running.id)
    }

    private suspend fun handle(app: Context, store: TimerStore, trial: Trial?, fallbackStepIndex: Int, trialId: String) {
        val now = System.currentTimeMillis()
        val endAt = trial?.timerEndAt
        val action = AlarmLogic.decide(trial?.status, endAt, now, store.firedEndAt(trialId))
        Log.i(TAG, "Timer alarm for $trialId: $action")
        if (trial == null || endAt == null || action == AlarmAction.IGNORE) {
            val scheduled = store.scheduled
            if (scheduled == null || scheduled.trialId == trialId && scheduled.endAt <= now) {
                store.scheduled = null
                Notifications.cancelTimerRunning(app)
            }
            return
        }
        val stepIndex = trial.timerStepIndex ?: fallbackStepIndex
        when (action) {
            AlarmAction.RESCHEDULE -> TimerScheduler(app).schedule(trial.id, stepIndex, endAt)
            AlarmAction.FIRE, AlarmAction.MISSED -> {
                store.markFired(trial.id, endAt)
                store.scheduled = null
                Notifications.cancelTimerRunning(app)
                val message = buildMessage(app, trial, stepIndex)
                if (action == AlarmAction.FIRE) {
                    ring(app, trial.id, stepIndex, message)
                } else {
                    Notifications.showAlarmDone(app, trial.id, message.title, AlarmLogic.missedText(message), message.subText)
                }
            }
            AlarmAction.IGNORE -> Unit
        }
    }

    private suspend fun buildMessage(app: Context, trial: Trial, stepIndex: Int): AlarmMessage {
        val repository = AppContainer.from(app).repository
        val recipeName = try { repository.getRecipe(trial.recipeId)?.name } catch (e: Exception) { null }
        val steps = try { repository.getVersion(trial.versionId)?.steps } catch (e: Exception) { null }
        return AlarmLogic.message(recipeName, steps, trial.values, stepIndex, trial.timerStartedAt, trial.timerEndAt)
    }

    /**
     * Rings: shows the full-screen alarm right away (silent channel), starts [AlarmService] which
     * plays the sound, and if the service does not confirm within a few seconds, turns the
     * notification into an insistent, sounding one.
     */
    suspend fun ring(app: Context, trialId: String, stepIndex: Int, message: AlarmMessage) {
        Notifications.ensureChannels(app)
        val startsBefore = AlarmState.serviceStarts.value
        val stopsBefore = AlarmState.stopRequests.value
        // 1. Visible immediately (full-screen intent wakes the screen), even if the service cannot start.
        Notifications.showAlarm(app, trialId, stepIndex, message, withSound = false)
        // 2. The service plays the looping alarm sound and vibrates.
        val started = try {
            ContextCompat.startForegroundService(app, AlarmService.ringIntent(app, trialId, stepIndex, message))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not start the alarm service; ringing through the notification", e)
            false
        }
        val confirmed = started && withTimeoutOrNull(SERVICE_CONFIRM_TIMEOUT_MS) {
            AlarmState.serviceStarts.first { it > startsBefore }
        } != null
        // 3. Fallback: the notification itself rings (insistent) until stopped.
        if (!confirmed && AlarmState.stopRequests.value == stopsBefore) {
            ringThroughNotification(app, trialId, stepIndex, message)
        }
    }

    /** Makes the alarm notification itself ring, for when [AlarmService] cannot. */
    fun ringThroughNotification(app: Context, trialId: String, stepIndex: Int, message: AlarmMessage) {
        Log.w(TAG, "Ringing through the notification")
        Notifications.showAlarm(app, trialId, stepIndex, message, withSound = true)
        AlarmState.ringing.value = true
    }

    /** Silences the alarm wherever it rings and removes its notification. Safe to call anytime, on any thread. */
    fun stopRinging(context: Context) {
        val app = context.applicationContext ?: context
        AlarmState.stopRequests.update { it + 1 }
        try {
            // stopService is allowed from the background and destroys the service, whose
            // onDestroy releases the player, vibration and wake lock.
            app.stopService(Intent(app, AlarmService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Could not stop the alarm service", e)
        }
        Notifications.cancelAlarm(app)
        AlarmState.ringing.value = false
    }
}
