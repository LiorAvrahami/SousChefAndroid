package com.lioravrahami.souschef.domain.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Re-schedules a pending wait-step alarm after a reboot or app update, and rings (or quietly
 * reports) a timer that ended while the phone was off.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Notifications.ensureChannels(app)
                if (withTimeoutOrNull(WORK_TIMEOUT_MS) { AlarmFiring.restore(app) } == null) {
                    Log.e(TAG, "Restoring the timer timed out")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not restore the timer", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SousChefTimer"
        const val WORK_TIMEOUT_MS = 9_000L
    }
}
