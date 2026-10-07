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
import kotlinx.coroutines.flow.StateFlow

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
 * receivers may create their own.
 */
class TimerScheduler(private val context: Context) {
    private val appContext: Context = context.applicationContext ?: context
    private val alarmManager: AlarmManager? get() = appContext.getSystemService(AlarmManager::class.java)

    /** True while the alarm sound is playing. */
    val isRinging: StateFlow<Boolean> = AlarmState.isRinging

    /** Schedules (or re-schedules) the single app alarm to fire at [endAtMillis] (wall clock). */
    fun schedule(trialId: String, stepIndex: Int, endAtMillis: Long) {
        val manager = alarmManager
        val operation = alarmPendingIntent(trialId, stepIndex, endAtMillis)
        TimerStore(appContext).scheduled = TimerStore.Scheduled(trialId, stepIndex, endAtMillis)
        if (manager == null) {
            Log.e(TAG, "No AlarmManager; cannot schedule the timer")
        } else {
            try {
                manager.cancel(operation)
            } catch (e: Exception) {
                Log.w(TAG, "Could not cancel the previous alarm", e)
            }
            setAlarm(manager, trialId, endAtMillis, operation)
        }
        Notifications.showTimerRunning(appContext, trialId, endAtMillis)
    }

    /** Cancels the scheduled alarm, if any. */
    fun cancel() {
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
        TimerStore(appContext).scheduled = null
        Notifications.cancelTimerRunning(appContext)
    }

    /** Stops the ringing alarm sound and dismisses its notification. Safe to call anytime. */
    fun stopRinging() = AlarmFiring.stopRinging(appContext)

    /** Whether the system allows exact alarms (always true below Android 12). */
    fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager?.canScheduleExactAlarms() ?: false else true

    /** An intent to the system screen where the user can allow exact alarms, or null if not needed. */
    fun exactAlarmSettingsIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canScheduleExact()) {
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

    private fun setAlarm(manager: AlarmManager, trialId: String, endAtMillis: Long, operation: PendingIntent) {
        if (canScheduleExact()) {
            try {
                val show = Notifications.openTrialIntent(appContext, trialId, REQ_SHOW)
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(endAtMillis, show), operation)
                return
            } catch (e: Exception) {
                Log.w(TAG, "setAlarmClock refused; trying an exact alarm", e)
            }
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAtMillis, operation)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Exact alarm refused; falling back to an inexact alarm", e)
            }
        }
        try {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAtMillis, operation)
        } catch (e: Exception) {
            Log.e(TAG, "Could not schedule the timer alarm at all", e)
        }
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
    }
}
