package com.lioravrahami.souschef.ui.cooking

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.lioravrahami.souschef.domain.timer.TimerScheduler

/** What currently stands in the way of a reliable, audible timer. */
private data class Reliability(
    val notificationsAllowed: Boolean,
    val exactAlarmsAllowed: Boolean,
    val fullScreenAllowed: Boolean,
)

private fun readReliability(context: Context, timers: TimerScheduler): Reliability = Reliability(
    notificationsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED,
    exactAlarmsAllowed = timers.canScheduleExact(),
    fullScreenAllowed = timers.canUseFullScreenIntent(),
)

/**
 * Asks once for the notification permission (Android 13+) and shows a compact card for
 * every setting that would keep wait-step alarms from ringing on time. The checks are
 * repeated whenever the screen resumes, e.g. after returning from system settings.
 */
@Composable
fun ReliabilityBanner(timers: TimerScheduler, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(readReliability(context, timers)) }
    var askedForNotifications by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        status = readReliability(context, timers)
    }

    LifecycleResumeEffect(timers) {
        status = readReliability(context, timers)
        onPauseOrDispose { }
    }

    LaunchedEffect(Unit) {
        if (!askedForNotifications && !status.notificationsAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askedForNotifications = true
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!status.notificationsAllowed) {
            ReliabilityCard(
                text = "Allow notifications so the timer alarm can reach you",
                action = "Allow",
                onAction = {
                    context.startSafely(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                },
            )
        }
        if (!status.exactAlarmsAllowed) {
            ReliabilityCard(
                text = "Allow exact alarms so timers ring on time",
                action = "Allow",
                onAction = { timers.exactAlarmSettingsIntent()?.let { context.startSafely(it) } },
            )
        }
        if (!status.fullScreenAllowed) {
            ReliabilityCard(
                text = "Allow full-screen alarms so a finished timer shows even when the phone is locked",
                action = "Allow",
                onAction = { timers.fullScreenIntentSettingsIntent()?.let { context.startSafely(it) } },
            )
        }
    }
}

@Composable
private fun ReliabilityCard(text: String, action: String, onAction: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(action, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Opens a system screen, ignoring devices that lack it. */
private fun Context.startSafely(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // Nothing to open on this device; the card stays visible as a hint.
    } catch (e: SecurityException) {
        // Some vendors restrict these screens; same as above.
    }
}
