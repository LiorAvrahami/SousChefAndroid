package com.lioravrahami.souschef.domain.timer

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// STUB — contract only. The real implementation replaces this file.

/**
 * Schedules the alarm for a wait step as an exact system alarm so that it fires on time
 * even when the screen is off and the app is in the background, and controls the
 * ringing alarm once it fires.
 */
class TimerScheduler(private val context: Context) {
    private val _isRinging = MutableStateFlow(false)

    /** True while the alarm sound is playing. */
    val isRinging: StateFlow<Boolean> = _isRinging

    /** Schedules (or re-schedules) the single app alarm to fire at [endAtMillis] (wall clock). */
    fun schedule(trialId: String, stepIndex: Int, endAtMillis: Long) {}

    /** Cancels the scheduled alarm, if any. */
    fun cancel() {}

    /** Stops the ringing alarm sound and dismisses its notification. Safe to call anytime. */
    fun stopRinging() { _isRinging.value = false }

    /** Whether the system allows exact alarms (always true below Android 12). */
    fun canScheduleExact(): Boolean = true

    /** An intent to the system screen where the user can allow exact alarms, or null if not needed. */
    fun exactAlarmSettingsIntent(): Intent? = null

    /** Whether full-screen alarm notifications are allowed (always true below Android 14). */
    fun canUseFullScreenIntent(): Boolean = true

    /** An intent to the system screen where the user can allow full-screen notifications, or null if not needed. */
    fun fullScreenIntentSettingsIntent(): Intent? = null
}
