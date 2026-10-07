package com.lioravrahami.souschef.domain.timer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.ServiceCompat
import com.lioravrahami.souschef.AppContainer
import kotlinx.coroutines.flow.update

/**
 * Foreground service that plays the alarm sound and vibrates until stopped.
 *
 * Started by [AlarmFiring.ring] with [ringIntent]. It goes to the foreground first (with the
 * full-screen alarm notification on the silent ringing channel), then plays the default alarm
 * sound in a loop on the alarm stream (falling back to the notification and ringtone sounds,
 * then to generated beeps), vibrates, and stops by itself after the "alarm max seconds" setting.
 * Stopped by [TimerScheduler.stopRinging] (stopService) or [ACTION_STOP].
 */
class AlarmService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringing = false
    private var current: Request? = null
    private var maxSeconds = DEFAULT_MAX_SECONDS

    /** Set when the notification took over ringing, so destroying the service must not clear [AlarmState]. */
    private var handedOff = false

    private val autoStop = Runnable { onAutoStop() }

    private val beep = object : Runnable {
        override fun run() {
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, BEEP_MS)
            } catch (e: Exception) {
                Log.w(TAG, "Beep failed", e)
            }
            handler.postDelayed(this, BEEP_PERIOD_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Only ever delivered through startService (never startForegroundService).
            stopAlarm()
            return START_NOT_STICKY
        }
        val request = Request.from(intent)
        val notification = Notifications.buildAlarmNotification(
            this, request.trialId, request.stepIndex, request.title, request.text, request.subText, withSound = false,
        )
        // startForeground FIRST: a service started with startForegroundService must call it even
        // when it is about to stop, or the system kills the app.
        val foreground = try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            ServiceCompat.startForeground(this, Notifications.ALARM_NOTIFICATION_ID, notification, type)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not go to the foreground", e)
            false
        }
        // Then settle the pending start and check for a stop that arrived while it was pending
        // (AlarmFiring.stopRinging skips stopService during that window). Keep this order.
        if (request.stopRequests != null) {
            AlarmState.serviceStartSettled()
            if (AlarmState.stopRequests.value != request.stopRequests) {
                Log.i(TAG, "Alarm was stopped before the service started ringing")
                stopAlarm()
                return START_NOT_STICKY
            }
        }
        if (!foreground) {
            Log.e(TAG, "Ringing through the notification instead of the service")
            handOffToNotification(request)
            return START_NOT_STICKY
        }
        current = request
        try {
            startRinging()
        } catch (e: Exception) {
            Log.e(TAG, "Could not start ringing in the service; ringing through the notification", e)
            releaseAll()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            handOffToNotification(request)
            return START_NOT_STICKY
        }
        AlarmState.serviceStarts.update { it + 1 }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseAll()
        if (!handedOff) AlarmState.ringing.value = false
        super.onDestroy()
    }

    /** The service cannot ring: let the insistent notification ring instead, then go away. */
    private fun handOffToNotification(request: Request) {
        handedOff = true
        AlarmFiring.ringThroughNotification(applicationContext, request.trialId, request.stepIndex, request.message)
        stopSelf()
    }

    /** Starts (or, for a second alarm while ringing, keeps) the sound and vibration, and re-arms the auto-stop. */
    private fun startRinging() {
        maxSeconds = try {
            AppContainer.from(this).settings.alarmMaxSeconds
        } catch (e: Exception) {
            DEFAULT_MAX_SECONDS
        }
        acquireWakeLock(maxSeconds * 1000L + 10_000L)
        if (!ringing) {
            if (!startPlayer()) startBeeping()
            startVibration()
            ringing = true
        }
        AlarmState.ringing.value = true
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, maxSeconds * 1000L)
    }

    private fun startPlayer(): Boolean {
        val uris = listOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_NOTIFICATION, RingtoneManager.TYPE_RINGTONE)
            .mapNotNull { RingtoneManager.getDefaultUri(it) }
            .distinct()
        for (uri in uris) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(Notifications.alarmAudioAttributes)
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.prepare()
                mp.start()
                player = mp
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Could not play $uri", e)
                mp.release()
            }
        }
        return false
    }

    private fun startBeeping() {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
            handler.post(beep)
        } catch (e: Exception) {
            Log.e(TAG, "No alarm sound available at all", e)
        }
    }

    private fun startVibration() {
        try {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vib == null || !vib.hasVibrator()) return
            val effect = VibrationEffect.createWaveform(Notifications.VIBRATION_PATTERN, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vib.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(effect, Notifications.alarmAudioAttributes)
            }
            vibrator = vib
        } catch (e: Exception) {
            Log.w(TAG, "Could not vibrate", e)
        }
    }

    private fun acquireWakeLock(timeoutMs: Long) {
        try {
            val lock = wakeLock ?: getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                ?.apply { setReferenceCounted(false) }
            lock?.acquire(timeoutMs)
            wakeLock = lock
        } catch (e: Exception) {
            Log.w(TAG, "No wake lock while ringing", e)
        }
    }

    /** Nobody stopped the alarm: silence it and leave a quiet "time's up" note behind. */
    private fun onAutoStop() {
        val request = current
        stopAlarm()
        if (request != null) {
            Notifications.showAlarmDone(
                applicationContext,
                request.trialId,
                request.title,
                AlarmLogic.stoppedText(request.message, maxSeconds),
                request.subText,
            )
        }
    }

    private fun stopAlarm() {
        handedOff = false
        releaseAll()
        AlarmState.ringing.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        Notifications.cancelAlarm(applicationContext)
        stopSelf()
    }

    private fun releaseAll() {
        handler.removeCallbacksAndMessages(null)
        ringing = false
        try {
            player?.let { if (it.isPlaying) it.stop(); it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not release the player", e)
        }
        player = null
        try {
            toneGenerator?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Could not release the tone generator", e)
        }
        toneGenerator = null
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Could not cancel vibration", e)
        }
        vibrator = null
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not release the wake lock", e)
        }
        wakeLock = null
    }

    /** What is ringing: passed in the start intent so the service never reads the database. */
    private data class Request(
        val trialId: String,
        val stepIndex: Int,
        val title: String,
        val text: String,
        val subText: String?,
        /** The stop counter when the alarm was decided, or null if the starter did not pass it. */
        val stopRequests: Int?,
    ) {
        val message: AlarmMessage get() = AlarmMessage(title, text, subText)

        companion object {
            fun from(intent: Intent?): Request = Request(
                trialId = intent?.getStringExtra(EXTRA_TRIAL_ID).orEmpty(),
                stepIndex = intent?.getIntExtra(EXTRA_STEP_INDEX, 0) ?: 0,
                title = intent?.getStringExtra(EXTRA_TITLE) ?: AlarmLogic.TITLE,
                text = intent?.getStringExtra(EXTRA_TEXT) ?: "Wait is over",
                subText = intent?.getStringExtra(EXTRA_SUB_TEXT),
                // Only meaningful in the process that counted it: after a process restart the
                // counters start again at 0, so a foreign value must not read as "stopped".
                stopRequests = intent
                    ?.takeIf { it.hasExtra(EXTRA_STOP_REQUESTS) && it.getLongExtra(EXTRA_PROCESS_NONCE, 0L) == AlarmState.processNonce }
                    ?.getIntExtra(EXTRA_STOP_REQUESTS, 0),
            )
        }
    }

    companion object {
        /** Stops the ringing alarm. Deliver only with startService, never startForegroundService. */
        const val ACTION_STOP = "com.lioravrahami.souschef.action.STOP_ALARM_SERVICE"

        private const val EXTRA_TRIAL_ID = "trialId"
        private const val EXTRA_STEP_INDEX = "stepIndex"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_SUB_TEXT = "subText"

        /**
         * Ring-intent extra set by [AlarmFiring]: [AlarmState.stopRequests] when the alarm was
         * decided. Its presence also means a pending start was counted in
         * [AlarmState.pendingServiceStarts].
         */
        internal const val EXTRA_STOP_REQUESTS = "stopRequests"

        /** Ring-intent extra: [AlarmState.processNonce] of the process that counted the pending start. */
        internal const val EXTRA_PROCESS_NONCE = "processNonce"

        private const val TAG = "SousChefTimer"
        private const val WAKE_LOCK_TAG = "SousChef:AlarmService"
        private const val DEFAULT_MAX_SECONDS = 300
        private const val BEEP_MS = 500
        private const val BEEP_PERIOD_MS = 1_000L

        /** Intent that starts ringing for [trialId]'s wait step; start it with startForegroundService. */
        fun ringIntent(context: Context, trialId: String, stepIndex: Int, message: AlarmMessage): Intent =
            Intent(context, AlarmService::class.java)
                .putExtra(EXTRA_TRIAL_ID, trialId)
                .putExtra(EXTRA_STEP_INDEX, stepIndex)
                .putExtra(EXTRA_TITLE, message.title)
                .putExtra(EXTRA_TEXT, message.text)
                .putExtra(EXTRA_SUB_TEXT, message.subText)
    }
}
