package com.lioravrahami.souschef.domain.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fired by AlarmManager when a wait step ends ([ACTION_TIMER_ALARM]), and by the "Stop alarm"
 * notification action ([ACTION_STOP_ALARM]).
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP_ALARM) {
            AlarmFiring.stopRinging(context)
            return
        }
        val trialId = intent.getStringExtra(TimerScheduler.EXTRA_TRIAL_ID) ?: return
        val stepIndex = intent.getIntExtra(TimerScheduler.EXTRA_STEP_INDEX, 0)
        val app = context.applicationContext

        val pending = goAsync()
        val wakeLock = try {
            app.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                ?.apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_TIMEOUT_MS)
                }
        } catch (e: Exception) {
            Log.w(TAG, "No wake lock for the alarm", e)
            null
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Notifications.ensureChannels(app)
                val done = withTimeoutOrNull(WORK_TIMEOUT_MS) { AlarmFiring.onAlarm(app, trialId, stepIndex) }
                if (done == null) Log.e(TAG, "Alarm handling timed out")
            } catch (e: Exception) {
                Log.e(TAG, "Alarm handling failed", e)
            } finally {
                try {
                    if (wakeLock?.isHeld == true) wakeLock.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not release the wake lock", e)
                }
                pending.finish()
            }
        }
    }

    companion object {
        /** Action of the AlarmManager broadcast when a wait-step timer ends. */
        const val ACTION_TIMER_ALARM = "com.lioravrahami.souschef.action.TIMER_ALARM"

        /** Action of the "Stop alarm" notification button. */
        const val ACTION_STOP_ALARM = "com.lioravrahami.souschef.action.STOP_ALARM"

        private const val TAG = "SousChefTimer"
        private const val WAKE_LOCK_TAG = "SousChef:AlarmReceiver"
        private const val WAKE_LOCK_TIMEOUT_MS = 60_000L
        private const val WORK_TIMEOUT_MS = 9_000L
    }
}
