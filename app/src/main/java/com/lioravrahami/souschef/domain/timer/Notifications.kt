package com.lioravrahami.souschef.domain.timer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lioravrahami.souschef.MainActivity
import java.util.Date

/**
 * Notification channels and builders for the timer alarm.
 *
 * Channels:
 * - [CHANNEL_ALARM]: high importance with the alarm sound and vibration. Used when the alarm
 *   has to ring through the notification alone (the ringing service could not start); such a
 *   notification is also marked insistent so the sound repeats until it is stopped.
 * - [CHANNEL_ALARM_RINGING]: high importance, full-screen capable, but silent: [AlarmService]
 *   plays the looping alarm sound and vibration itself, so there is exactly one sound source.
 * - [CHANNEL_SESSION]: low importance, silent: the "timer running" countdown and the quiet
 *   "time's up" notes.
 */
object Notifications {
    const val CHANNEL_ALARM = "timer_alarm"
    const val CHANNEL_SESSION = "cooking_session"
    const val CHANNEL_ALARM_RINGING = "timer_alarm_ringing"

    /** The ringing alarm (also the foreground notification of [AlarmService]). */
    const val ALARM_NOTIFICATION_ID = 4102

    /** The ongoing countdown while a wait-step timer runs. */
    const val TIMER_RUNNING_NOTIFICATION_ID = 4101

    /** Quiet "time's up" note after the alarm stopped by itself or was missed. */
    const val ALARM_DONE_NOTIFICATION_ID = 4103

    private const val TAG = "SousChefTimer"
    private const val REQ_OPEN = 201
    private const val REQ_FULL_SCREEN = 202
    private const val REQ_STOP = 203
    private const val REQ_OPEN_RUNNING = 204
    private const val REQ_OPEN_DONE = 205

    /** Vibration pattern of the alarm: 600 ms on, 400 ms off. */
    internal val VIBRATION_PATTERN = longArrayOf(0, 600, 400)

    internal val alarmAudioAttributes: AudioAttributes
        get() = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    /** Default alarm sound, falling back to the notification and ringtone sounds. */
    internal fun alarmSoundUri(): Uri =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: Settings.System.DEFAULT_ALARM_ALERT_URI

    /** Creates the channels if missing. Called from Application.onCreate. Never throws. */
    fun ensureChannels(context: Context) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val alarm = NotificationChannel(CHANNEL_ALARM, "Timer alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rings when a wait step is over."
                setSound(alarmSoundUri(), alarmAudioAttributes)
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
                enableLights(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val ringing = NotificationChannel(
                CHANNEL_ALARM_RINGING,
                "Timer alarm screen",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Shows the ringing timer over the lock screen. The app plays the alarm sound itself."
                setSound(null, null)
                enableVibration(false)
                enableLights(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val session = NotificationChannel(CHANNEL_SESSION, "Cooking session", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Countdown of the running wait step and quiet timer notes."
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannels(listOf(alarm, ringing, session))
        } catch (e: Exception) {
            Log.e(TAG, "Could not create notification channels", e)
        }
    }

    /** Whether notifications can be shown (POST_NOTIFICATIONS granted on Android 13+, not blocked by the user). */
    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Builds the ringing-alarm notification: alarm category, max priority, full-screen intent and
     * content intent opening the cooking screen of [trialId], and a "Stop alarm" action (also sent
     * when the user swipes the notification away).
     *
     * @param withSound true: on [CHANNEL_ALARM] and insistent, so the notification itself rings
     *   until stopped (fallback when [AlarmService] cannot run). false: on the silent
     *   [CHANNEL_ALARM_RINGING], for use while [AlarmService] plays the sound.
     */
    fun buildAlarmNotification(
        context: Context,
        trialId: String,
        stepIndex: Int,
        title: String,
        text: String,
        subText: String? = null,
        withSound: Boolean = true,
    ): Notification {
        val channel = if (withSound) CHANNEL_ALARM else CHANNEL_ALARM_RINGING
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(subText)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setContentIntent(openTrialIntent(context, trialId, REQ_OPEN))
            .setFullScreenIntent(openTrialIntent(context, trialId, REQ_FULL_SCREEN), true)
            .addAction(0, "Stop alarm", stopAlarmIntent(context))
            // Android 13+/14+ let users swipe away even ongoing / foreground notifications: treat
            // that like "Stop alarm" so the sound never keeps playing without a stop control.
            // (Updating or cancelling the notification from code does not send this intent.)
            .setDeleteIntent(stopAlarmIntent(context))
            .addExtras(Bundle().apply { putInt(EXTRA_STEP_INDEX, stepIndex) })
        val notification = builder.build()
        // Insistent: the channel's alarm sound repeats until the notification is stopped. Updating
        // the same id to the silent ringing channel (service took over) stops that sound.
        if (withSound) notification.flags = notification.flags or Notification.FLAG_INSISTENT
        return notification
    }

    /** Posts (or updates) the ringing-alarm notification. */
    internal fun showAlarm(context: Context, trialId: String, stepIndex: Int, message: AlarmMessage, withSound: Boolean) {
        notifySafely(
            context,
            ALARM_NOTIFICATION_ID,
            buildAlarmNotification(context, trialId, stepIndex, message.title, message.text, message.subText, withSound),
        )
    }

    /** Shows the silent ongoing countdown of a running wait-step timer ending at [endAtMillis]. */
    fun showTimerRunning(context: Context, trialId: String, endAtMillis: Long) {
        val endsAt = DateFormat.getTimeFormat(context).format(Date(endAtMillis))
        val notification = NotificationCompat.Builder(context, CHANNEL_SESSION)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Timer running")
            .setContentText("Rings at $endsAt")
            .setWhen(endAtMillis)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openTrialIntent(context, trialId, REQ_OPEN_RUNNING))
            .build()
        notifySafely(context, TIMER_RUNNING_NOTIFICATION_ID, notification)
    }

    /** Posts a quiet, dismissable "time's up" note (alarm stopped by itself, or missed). */
    internal fun showAlarmDone(context: Context, trialId: String, title: String, text: String, subText: String?) {
        val notification = NotificationCompat.Builder(context, CHANNEL_SESSION)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(subText)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openTrialIntent(context, trialId, REQ_OPEN_DONE))
            .build()
        notifySafely(context, ALARM_DONE_NOTIFICATION_ID, notification)
    }

    /** Removes the countdown notification. */
    fun cancelTimerRunning(context: Context) = cancelSafely(context, TIMER_RUNNING_NOTIFICATION_ID)

    /** Removes the ringing-alarm notification and the quiet "time's up" note. */
    fun cancelAlarm(context: Context) {
        cancelSafely(context, ALARM_NOTIFICATION_ID)
        cancelSafely(context, ALARM_DONE_NOTIFICATION_ID)
    }

    /** Activity intent opening the cooking screen of [trialId] (also used as the alarm clock's "show" intent). */
    internal fun openTrialIntent(context: Context, trialId: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TRIAL_ID, trialId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * "Stop alarm" action. It is a broadcast to [AlarmReceiver] rather than a service start, so it
     * works whether or not [AlarmService] is running and can never start a foreground service that
     * would then stop without calling startForeground.
     */
    private fun stopAlarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQ_STOP,
        Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_STOP_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // Permission is checked right before posting; a revoked permission only drops the notification.
    @SuppressLint("MissingPermission")
    private fun notifySafely(context: Context, id: Int, notification: Notification) {
        if (!canPostNotifications(context)) {
            Log.w(TAG, "Notifications are not allowed; notification $id not shown")
            return
        }
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Could not post notification $id", e)
        }
    }

    private fun cancelSafely(context: Context, id: Int) {
        try {
            NotificationManagerCompat.from(context).cancel(id)
        } catch (e: Exception) {
            Log.e(TAG, "Could not cancel notification $id", e)
        }
    }

    /** Notification extra carrying the wait step's index. */
    const val EXTRA_STEP_INDEX = "com.lioravrahami.souschef.stepIndex"
}
