package com.lioravrahami.souschef.domain.timer

import android.content.Context

/**
 * Small SharedPreferences file remembering the alarm registered with AlarmManager and the last
 * alarm that rang, so the boot path can double check the database and never rings the same
 * timer twice (e.g. after an app update while a finished timer is still on screen).
 */
internal class TimerStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The alarm currently registered with AlarmManager. */
    data class Scheduled(val trialId: String, val stepIndex: Int, val endAt: Long)

    var scheduled: Scheduled?
        get() {
            val trialId = prefs.getString(KEY_TRIAL, null) ?: return null
            val endAt = prefs.getLong(KEY_END, -1L).takeIf { it > 0 } ?: return null
            return Scheduled(trialId, prefs.getInt(KEY_STEP, 0), endAt)
        }
        set(value) {
            val editor = prefs.edit()
            if (value == null) {
                editor.remove(KEY_TRIAL).remove(KEY_STEP).remove(KEY_END)
            } else {
                editor.putString(KEY_TRIAL, value.trialId).putInt(KEY_STEP, value.stepIndex).putLong(KEY_END, value.endAt)
            }
            editor.apply()
        }

    /**
     * Whether the alarm in [scheduled] was registered as an exact alarm. False after an inexact
     * fallback, so it can be re-registered once the user allows exact alarms.
     */
    var scheduledExact: Boolean
        get() = prefs.getBoolean(KEY_EXACT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_EXACT, value).apply()
        }

    /** Remembers that the alarm of [trialId] ending at [endAt] has rung (or was reported). */
    fun markFired(trialId: String, endAt: Long) {
        prefs.edit().putString(KEY_FIRED_TRIAL, trialId).putLong(KEY_FIRED_END, endAt).commit()
    }

    /** End time of the alarm that already rang for [trialId], or null. */
    fun firedEndAt(trialId: String): Long? =
        if (prefs.getString(KEY_FIRED_TRIAL, null) == trialId) prefs.getLong(KEY_FIRED_END, -1L).takeIf { it > 0 } else null

    private companion object {
        const val PREFS_NAME = "souschef_timer"
        const val KEY_TRIAL = "scheduled_trial"
        const val KEY_STEP = "scheduled_step"
        const val KEY_END = "scheduled_end"
        const val KEY_FIRED_TRIAL = "fired_trial"
        const val KEY_FIRED_END = "fired_end"
        const val KEY_EXACT = "scheduled_exact"
    }
}
