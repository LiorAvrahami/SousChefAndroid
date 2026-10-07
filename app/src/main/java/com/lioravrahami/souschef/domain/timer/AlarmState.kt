package com.lioravrahami.souschef.domain.timer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

    /**
     * Incremented by every [TimerScheduler.schedule] and [TimerScheduler.cancel], so firing and
     * restoring code that read the database earlier can tell the timer was changed meanwhile
     * (restarted, extended or cleared) and must not act on its stale read.
     */
    internal val scheduleChanges = AtomicInteger(0)

    /**
     * Number of `startForegroundService` calls for [AlarmService] whose `onStartCommand` has not
     * run yet. While it is positive, stopping the service would crash the app ("did not then call
     * startForeground"), so a stop request only bumps [stopRequests] and the starting service
     * stops itself right after going to the foreground.
     */
    internal val pendingServiceStarts = AtomicInteger(0)

    /**
     * Random id of this process. Counters above restart at 0 in a new process, so a value passed
     * in an intent is only compared when it carries the same nonce.
     */
    internal val processNonce: Long = java.util.Random().nextLong()

    /** Set once the timer was restored (or an alarm handled) in this process; see [TimerScheduler]. */
    internal val restoredInProcess = AtomicBoolean(false)

    /** Guards the check-then-act of [scheduleChanges] against concurrent schedule / cancel calls. */
    internal val lock = Any()

    /** What the alarm state looked like when firing code started; compare with [isCurrent]. */
    internal data class Token(val stops: Int, val schedules: Int)

    /** A token with the current stop and schedule counters. */
    internal fun token(): Token = Token(stopRequests.value, scheduleChanges.get())

    /** True if nobody stopped the alarm nor changed the timer since [token] was taken. */
    internal fun isCurrent(token: Token): Boolean = !stoppedSince(token) && scheduleChanges.get() == token.schedules

    /** True if a stop was requested since [token] was taken. */
    internal fun stoppedSince(token: Token): Boolean = stopRequests.value != token.stops

    /** Records a stop request. */
    internal fun recordStop() = stopRequests.update { it + 1 }

    /** Records that a pending start of [AlarmService] was delivered or abandoned; returns the pending starts left. */
    internal fun serviceStartSettled(): Int = pendingServiceStarts.updateAndGet { if (it > 0) it - 1 else 0 }
}
