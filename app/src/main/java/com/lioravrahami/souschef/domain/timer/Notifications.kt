package com.lioravrahami.souschef.domain.timer

import android.content.Context

// STUB — contract only. The real implementation replaces this file.

/** Notification channels and builders for the timer alarm. */
object Notifications {
    const val CHANNEL_ALARM = "timer_alarm"
    const val CHANNEL_SESSION = "cooking_session"

    /** Creates the channels if missing. Called from Application.onCreate. */
    fun ensureChannels(context: Context) {}
}
