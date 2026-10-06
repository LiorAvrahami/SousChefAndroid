package com.lioravrahami.souschef.domain.timer

import android.app.Service
import android.content.Intent
import android.os.IBinder

// STUB — contract only. The real implementation replaces this file.

/** Foreground service that plays the alarm sound and vibrates until stopped. */
class AlarmService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
