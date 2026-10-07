package com.lioravrahami.souschef.domain.timer

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Schedules the alarm for a wait step as an exact system alarm so that it fires on time
 * even when the screen is off and the app is in the background, and controls the
 * ringing alarm once it fires.
 *
 * Layers, from most to least reliable:
 * 1. `AlarmManager.setAlarmClock` (exempt from doze, shows the alarm icon in the status bar);
 * 2. `setExactAndAllowWhileIdle` if the alarm clock call is refused;
 * 3. `setAndAllowWhileIdle` (inexact, may be late in deep doze) if exact alarms are not allowed.
 * A silent countdown notification shows the running timer meanwhile. When the alarm fires,
 * [AlarmReceiver] rings through [AlarmService], or through an insistent notification if the
 * service cannot start.
 *
 * Instances are stateless (state lives in [AlarmState] and a small preferences file), so
 * receivers may create their own. The first instance in a process also runs [ensureScheduled]
 * once in the background, so a timer whose system alarm was dropped (force-stop, an OEM "clean")
 * is re-registered as soon as the app is used again.
 */
class TimerScheduler(private val context: Context) {
    private val appContext: Context = context.applicationContext ?: context
    private val alarmManager: AlarmManager? get() = appContext.getSystemService(AlarmManager::class.java)

    init {
        AlarmFiring.restoreOnceInBackground(appContext)
    }

    /** True while the alarm sound is playing. */
    val isRinging: StateFlow<Boolean> = AlarmState.isRinging

    /** Schedules (or re-schedules) the single app alarm to fire at [endAtMillis] (wall clock). */
    fun schedule(trialId: String, stepIndex: Int, endAtMillis: Long) {
        synchronized(AlarmState.lock) {
            AlarmState.scheduleChanges.incrementAndGet()
            val manager = alarmManager
            val operation = alarmPendingIntent(trialId, stepIndex, endAtMillis)
            val store = TimerStore(appContext)
            store.scheduled = TimerStore.Scheduled(trialId, stepIndex, endAtMillis)
            if (manager == null) {
                Log.e(TAG, "No AlarmManager; cannot schedule the timer")
                store.scheduledExact = false
            } else {
                try {
                    manager.cancel(operation)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not cancel the previous alarm", e)
                }
                store.scheduledExact = setAlarm(manager, trialId, endAtMillis, operation)
            }
        }
        Notifications.showTimerRunning(appContext, trialId, endAtMillis)
    }

    /**
     * Makes sure the system alarm matches the running timer in the database, and rings a timer
     * that ended while no alarm was registered. Call it when the app starts and when the cooking
     * screen resumes: a force-stop, an OEM "clean" or revoking the exact-alarm permission removes
     * the app's alarms, and this re-registers them (also upgrading an inexact alarm to an exact one
     * once that is allowed). Idempotent: a timer never rings twice, and a timer the cook changes
     * meanwhile is left alone. Never throws (except cancellation); runs on [Dispatchers.IO].
     */
    suspend fun ensureScheduled() {
        try {
            withContext(Dispatchers.IO) { AlarmFiring.restore(appContext) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not restore the timer alarm", e)
        }
    }

    /** Cancels the scheduled alarm, if any. */
    fun cancel() {
        synchronized(AlarmState.lock) {
            AlarmState.scheduleChanges.incrementAndGet()
            cancelAlarm()
            TimerStore(appContext).scheduled = null
        }
        Notifications.cancelTimerRunning(appContext)
    }

    private fun cancelAlarm() {
        try {
            val operation = PendingIntent.getBroadcast(
                appContext,
                REQ_ALARM,
                alarmIntent(),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (operation != null) {
                alarmManager?.cancel(operation)
                operation.cancel()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not cancel the alarm", e)
        }
    }

    /** Stops the ringing alarm sound and dismisses its notification. Safe to call anytime. */
    fun stopRinging() = AlarmFiring.stopRinging(appContext)

    /**
     * Whether the system allows exact alarms (always true below Android 12). When they are
     * allowed and the running timer was registered as an inexact fallback, it is re-registered
     * as an exact alarm right away (the reliability banner calls this on every resume, e.g. when
     * the cook comes back from granting the permission).
     */
    fun canScheduleExact(): Boolean {
        val allowed = exactAllowed()
        if (allowed) upgradeInexactAlarm()
        return allowed
    }

    private fun exactAllowed(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager?.canScheduleExactAlarms() ?: false else true

    /** Re-registers an inexact pending alarm as an exact one. Leaves alarms about to fire alone. */
    private fun upgradeInexactAlarm() {
        try {
            synchronized(AlarmState.lock) {
                val store = TimerStore(appContext)
                if (store.scheduledExact) return
                val scheduled = store.scheduled ?: return
                if (scheduled.endAt <= System.currentTimeMillis() + UPGRADE_MIN_LEAD_MS) return
                Log.i(TAG, "Exact alarms are allowed now; re-registering the timer as exact")
                schedule(scheduled.trialId, scheduled.stepIndex, scheduled.endAt)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not upgrade the timer to an exact alarm", e)
        }
    }

    /** An intent to the system screen where the user can allow exact alarms, or null if not needed. */
    fun exactAlarmSettingsIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !exactAllowed()) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + appContext.packageName))
        } else {
            null
        }

    /** Whether full-screen alarm notifications are allowed (always true below Android 14). */
    fun canUseFullScreenIntent(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            appContext.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: false
        } else {
            true
        }

    /** An intent to the system screen where the user can allow full-screen notifications, or null if not needed. */
    fun fullScreenIntentSettingsIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !canUseFullScreenIntent()) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + appContext.packageName))
        } else {
            null
        }

    /**
     * Whether the app may post notifications (Android 13+ runtime permission, or not blocked by
     * the user). Without it the alarm still rings through [AlarmService], but the lock-screen
     * alarm and the fallback notification are not shown.
     */
    fun canPostNotifications(): Boolean = Notifications.canPostNotifications(appContext)

    /** Registers the alarm through the best layer available; returns true if it is exact. */
    private fun setAlarm(manager: AlarmManager, trialId: String, endAtMillis: Long, operation: PendingIntent): Boolean {
        if (exactAllowed()) {
            try {
                val show = Notifications.openTrialIntent(appContext, trialId, REQ_SHOW)
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(endAtMillis, show), operation)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "setAlarmClock refused; trying an exact alarm", e)
            }
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAtMillis, operation)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Exact alarm refused; falling back to an inexact alarm", e)
            }
        }
        try {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAtMillis, operation)
        } catch (e: Exception) {
            Log.e(TAG, "Could not schedule the timer alarm at all", e)
        }
        return false
    }

    private fun alarmIntent(): Intent =
        Intent(appContext, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_TIMER_ALARM)

    private fun alarmPendingIntent(trialId: String, stepIndex: Int, endAtMillis: Long): PendingIntent {
        val intent = alarmIntent()
            .putExtra(EXTRA_TRIAL_ID, trialId)
            .putExtra(EXTRA_STEP_INDEX, stepIndex)
            .putExtra(EXTRA_END_AT, endAtMillis)
        return PendingIntent.getBroadcast(
            appContext,
            REQ_ALARM,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /** Alarm intent extra: trial id. */
        const val EXTRA_TRIAL_ID = "trialId"

        /** Alarm intent extra: index of the wait step. */
        const val EXTRA_STEP_INDEX = "stepIndex"

        /** Alarm intent extra: wall-clock end time in millis. */
        const val EXTRA_END_AT = "endAt"

        private const val TAG = "SousChefTimer"
        private const val REQ_ALARM = 101
        private const val REQ_SHOW = 102

        /** An inexact alarm due sooner than this is not re-registered (it may be firing right now). */
        private const val UPGRADE_MIN_LEAD_MS = 5_000L
    }
}
