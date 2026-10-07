package com.lioravrahami.souschef.domain.timer

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.lioravrahami.souschef.AppContainer
import com.lioravrahami.souschef.data.model.Trial
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The code path shared by [AlarmReceiver], [BootReceiver] and [TimerScheduler.ensureScheduled]:
 * decide whether a timer alarm should ring, and ring it through every layer available.
 *
 * Decisions are serialized by a mutex (so two paths never ring the same timer twice), and every
 * decision is checked against an [AlarmState.Token] taken before the database read, so a stop,
 * restart or clear by the cook while the row was being read always wins over the stale read.
 */
internal object AlarmFiring {
    private const val TAG = "SousChefTimer"

    /** How long to wait for [AlarmService] to confirm it is ringing before ringing via the notification. */
    private const val SERVICE_CONFIRM_TIMEOUT_MS = 4_000L

    /** Upper bound of a restore started in the background. */
    private const val RESTORE_TIMEOUT_MS = 9_000L

    private val mutex = Mutex()

    /** Process-lifetime scope for restores started without a caller scope. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** An alarm that was decided to ring; rung outside the decision mutex. */
    private data class RingPlan(
        val trialId: String,
        val stepIndex: Int,
        val message: AlarmMessage,
        val token: AlarmState.Token,
    )

    /** Handles the alarm broadcast of [trialId]'s wait step [stepIndex]. */
    suspend fun onAlarm(context: Context, trialId: String, stepIndex: Int) {
        val app = context.applicationContext
        val stops = AlarmState.stopRequests.value
        AlarmState.restoredInProcess.set(true)
        val plan = mutex.withLock {
            val token = AlarmState.Token(stops, AlarmState.scheduleChanges.get())
            val trial = AppContainer.from(app).repository.getTrial(trialId)
            handle(app, TimerStore(app), trial, stepIndex, trialId, token)
        }
        if (plan != null) ring(app, plan)
    }

    /**
     * Makes sure the system alarm matches the database: re-registers a pending timer (after a
     * reboot, an app update, a force-stop or an OEM "clean" that dropped the app's alarms, or to
     * upgrade an inexact alarm once exact alarms are allowed), or rings one that ended meanwhile.
     * Idempotent: a timer that already rang never rings again. Falls back to the remembered
     * schedule if the database cannot be read.
     */
    suspend fun restore(context: Context) {
        val app = context.applicationContext
        val stops = AlarmState.stopRequests.value
        AlarmState.restoredInProcess.set(true)
        val plan = mutex.withLock {
            val token = AlarmState.Token(stops, AlarmState.scheduleChanges.get())
            restoreLocked(app, TimerStore(app), token)
        }
        if (plan != null) ring(app, plan)
    }

    /** Runs [restore] once per process in the background, unless an alarm path already ran in this process. */
    fun restoreOnceInBackground(context: Context) {
        if (!AlarmState.restoredInProcess.compareAndSet(false, true)) return
        val app = context.applicationContext ?: context
        scope.launch {
            try {
                if (withTimeoutOrNull(RESTORE_TIMEOUT_MS) { restore(app) } == null) {
                    Log.e(TAG, "Restoring the timer timed out")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not restore the timer", e)
            }
        }
    }

    private suspend fun restoreLocked(app: Context, store: TimerStore, token: AlarmState.Token): RingPlan? {
        val trials = try {
            AppContainer.from(app).repository.getInProgressTrials()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not read sessions; using the remembered schedule", e)
            null
        }
        if (trials == null) {
            val remembered = store.scheduled ?: return null
            if (remembered.endAt > System.currentTimeMillis()) {
                ifUnchanged(token) {
                    TimerScheduler(app).schedule(remembered.trialId, remembered.stepIndex, remembered.endAt)
                }
                return null
            }
            if (store.firedEndAt(remembered.trialId) == remembered.endAt) return null
            val message = AlarmLogic.message(null, null, null, remembered.stepIndex, null, null)
            return firePlan(store, remembered.trialId, remembered.stepIndex, remembered.endAt, message, token)
        }
        val running = trials.filter { it.timerEndAt != null }.maxByOrNull { it.timerEndAt ?: 0L }
        if (running == null) {
            // Nothing is running any more: drop any stale schedule and countdown.
            ifUnchanged(token) { if (store.scheduled != null) TimerScheduler(app).cancel() }
            return null
        }
        return handle(app, store, running, running.timerStepIndex ?: 0, running.id, token)
    }

    private suspend fun handle(
        app: Context,
        store: TimerStore,
        trial: Trial?,
        fallbackStepIndex: Int,
        trialId: String,
        token: AlarmState.Token,
    ): RingPlan? {
        val now = System.currentTimeMillis()
        val endAt = trial?.timerEndAt
        val action = AlarmLogic.decide(trial?.status, endAt, now, store.firedEndAt(trialId))
        Log.i(TAG, "Timer alarm for $trialId: $action")
        if (trial == null || endAt == null || action == AlarmAction.IGNORE) {
            ifUnchanged(token) {
                val scheduled = store.scheduled
                if (scheduled == null || scheduled.trialId == trialId && scheduled.endAt <= now) {
                    store.scheduled = null
                    Notifications.cancelTimerRunning(app)
                }
            }
            return null
        }
        val stepIndex = trial.timerStepIndex ?: fallbackStepIndex
        return when (action) {
            AlarmAction.RESCHEDULE -> {
                ifUnchanged(token) { TimerScheduler(app).schedule(trial.id, stepIndex, endAt) }
                null
            }
            AlarmAction.FIRE -> firePlan(store, trial.id, stepIndex, endAt, buildMessage(app, trial, stepIndex), token)
            AlarmAction.MISSED -> {
                val message = buildMessage(app, trial, stepIndex)
                val report = ifUnchanged(token) {
                    store.markFired(trial.id, endAt)
                    store.scheduled = null
                    Notifications.cancelTimerRunning(app)
                } != null
                if (report) {
                    Notifications.showAlarmDone(app, trial.id, message.title, AlarmLogic.missedText(message), message.subText)
                }
                null
            }
            AlarmAction.IGNORE -> null
        }
    }

    /**
     * Marks the alarm as rung and returns what to ring, unless the cook changed the timer or
     * stopped the alarm since [token] was taken (then the stale read must not ring).
     */
    private fun firePlan(
        store: TimerStore,
        trialId: String,
        stepIndex: Int,
        endAt: Long,
        message: AlarmMessage,
        token: AlarmState.Token,
    ): RingPlan? {
        val fire = ifUnchanged(token) {
            store.markFired(trialId, endAt)
            store.scheduled = null
            !AlarmState.stoppedSince(token)
        } ?: false
        if (!fire) {
            Log.i(TAG, "Alarm for $trialId was stopped or changed while it was being handled; not ringing")
            return null
        }
        return RingPlan(trialId, stepIndex, message, token)
    }

    /** Runs [block] only if no schedule / cancel happened since [token]; atomic with respect to them. */
    private inline fun <T> ifUnchanged(token: AlarmState.Token, block: () -> T): T? =
        synchronized(AlarmState.lock) {
            if (AlarmState.scheduleChanges.get() == token.schedules) block() else null
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
     * notification into an insistent, sounding one. Gives up at any point once the cook stopped
     * the alarm or changed the timer after the plan's token was taken.
     */
    private suspend fun ring(app: Context, plan: RingPlan) {
        val (trialId, stepIndex, message, token) = plan
        Notifications.ensureChannels(app)
        if (!AlarmState.isCurrent(token)) return
        val startsBefore = AlarmState.serviceStarts.value
        // 1. Visible immediately (full-screen intent wakes the screen), even if the service cannot start.
        Notifications.showAlarm(app, trialId, stepIndex, message, withSound = false)
        // 2. The service plays the looping alarm sound and vibrates. The pending-start count is raised
        //    BEFORE the last stop check (stopRinging bumps the stop counter before reading it), so either
        //    this code sees the stop, or stopRinging sees the pending start and lets the service stop itself.
        AlarmState.pendingServiceStarts.incrementAndGet()
        if (!AlarmState.isCurrent(token)) {
            val left = AlarmState.serviceStartSettled()
            // The stopper's cancel may have run before we posted: remove our notification again.
            Notifications.cancelAlarm(app)
            // A stop that saw our pending start skipped stopService; do it now if nothing else is starting.
            if (AlarmState.stoppedSince(token) && left == 0) stopServiceSafely(app)
            return
        }
        val started = try {
            val intent = AlarmService.ringIntent(app, trialId, stepIndex, message)
                .putExtra(AlarmService.EXTRA_STOP_REQUESTS, token.stops)
                .putExtra(AlarmService.EXTRA_PROCESS_NONCE, AlarmState.processNonce)
            ContextCompat.startForegroundService(app, intent)
            true
        } catch (e: Exception) {
            AlarmState.serviceStartSettled()
            Log.e(TAG, "Could not start the alarm service; ringing through the notification", e)
            false
        }
        val confirmed = started && withTimeoutOrNull(SERVICE_CONFIRM_TIMEOUT_MS) {
            AlarmState.serviceStarts.first { it > startsBefore }
        } != null
        // 3. Fallback: the notification itself rings (insistent) until stopped.
        if (!confirmed && AlarmState.isCurrent(token)) {
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
        // Order matters: bump the stop counter first, then look for a pending start (see ring()).
        AlarmState.recordStop()
        if (AlarmState.pendingServiceStarts.get() > 0) {
            // The service was asked to start but has not called startForeground yet: stopping it now
            // would crash the app. It sees the new stop count right after startForeground and stops itself.
            Log.i(TAG, "Alarm service start pending; it will stop itself")
        } else {
            stopServiceSafely(app)
        }
        Notifications.cancelAlarm(app)
        AlarmState.ringing.value = false
    }

    private fun stopServiceSafely(app: Context) {
        try {
            // stopService is allowed from the background and destroys the service, whose
            // onDestroy releases the player, vibration and wake lock.
            app.stopService(Intent(app, AlarmService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Could not stop the alarm service", e)
        }
    }
}
