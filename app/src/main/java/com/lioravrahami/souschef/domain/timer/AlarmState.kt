package com.lioravrahami.souschef.domain.timer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide state of the timer alarm, shared by [AlarmService], the receivers and every
 * [TimerScheduler] instance (receivers create their own), so the UI always sees the truth.
 */
object AlarmState {
    internal val ringing = MutableStateFlow(false)

    /** True while the alarm is sounding (from [AlarmService] or the insistent fallback notification). */
    val isRinging: StateFlow<Boolean> = ringing.asStateFlow()

    /** Incremented every time [AlarmService] has gone to the foreground and started ringing. */
    internal val serviceStarts = MutableStateFlow(0)

    /** Incremented on every stop request, so in-flight firing code can tell the user already silenced it. */
    internal val stopRequests = MutableStateFlow(0)
}
