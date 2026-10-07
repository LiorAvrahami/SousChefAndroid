package com.lioravrahami.souschef.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.lioravrahami.souschef.AppContainer
import com.lioravrahami.souschef.ui.components.BigOutlinedButton

/** Alarm durations offered on the settings screen, in minutes. */
val ALARM_MINUTE_CHOICES: List<Int> = listOf(1, 3, 5, 10)

private const val BOLDNESS_MIN = 0.02f
private const val BOLDNESS_MAX = 0.5f
private const val EXPLORATION_MAX = 0.6f

/** Boldness and exploration sliders of the classical optimizer. */
@Composable
fun OptimizerSection(container: AppContainer, settingsTick: Int) {
    val settings = container.settings
    var boldness by remember(settingsTick) {
        mutableFloatStateOf(settings.boldness.coerceIn(BOLDNESS_MIN, BOLDNESS_MAX))
    }
    var exploration by remember(settingsTick) {
        mutableFloatStateOf(settings.explorationRate.coerceIn(0f, EXPLORATION_MAX))
    }

    SettingsSection(title = "Optimizer") {
        LabeledSlider(
            title = "Boldness — how big each tweak is",
            value = boldness,
            range = BOLDNESS_MIN..BOLDNESS_MAX,
            onChange = { boldness = it },
            onChangeFinished = { settings.boldness = boldness },
            help = "Low values nudge amounts and times a little at a time; high values try bigger changes.",
        )
        LabeledSlider(
            title = "Exploration — chance of a global jump",
            value = exploration,
            range = 0f..EXPLORATION_MAX,
            onChange = { exploration = it },
            onChangeFinished = { settings.explorationRate = exploration },
            help = "How often an exploration cooking tries something far from the best so far, instead of a small tweak near it.",
        )
    }
}

@Composable
private fun LabeledSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit,
    help: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                percentText(value),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onChangeFinished,
            valueRange = range,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        )
        HelpText(help)
    }
}

/** Screen-on switch, alarm length, and the status of the permissions the timer needs. */
@Composable
fun CookingSection(container: AppContainer, settingsTick: Int) {
    val settings = container.settings
    val scheduler = container.timerScheduler
    val context = LocalContext.current
    val keepScreenOn = remember(settingsTick) { settings.keepScreenOn }
    val alarmSeconds = remember(settingsTick) { settings.alarmMaxSeconds }

    // Re-checked whenever the user comes back, e.g. from the system settings page.
    var canExact by remember { mutableStateOf(scheduler.canScheduleExact()) }
    var canFullScreen by remember { mutableStateOf(scheduler.canUseFullScreenIntent()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canExact = scheduler.canScheduleExact()
        canFullScreen = scheduler.canUseFullScreenIntent()
    }

    SettingsSection(title = "Cooking") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .toggleable(
                    value = keepScreenOn,
                    role = Role.Switch,
                    onValueChange = { settings.keepScreenOn = it },
                ),
        ) {
            Text("Keep screen on while cooking", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = keepScreenOn, onCheckedChange = null)
        }

        Text("Alarm keeps ringing for", style = MaterialTheme.typography.titleMedium)
        ALARM_MINUTE_CHOICES.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                pair.forEach { minutes ->
                    ChoiceButton(
                        text = "$minutes min",
                        selected = alarmSeconds == minutes * 60,
                        onClick = { settings.alarmMaxSeconds = minutes * 60 },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (alarmSeconds !in ALARM_MINUTE_CHOICES.map { it * 60 }) {
            HelpText("Currently ${alarmSeconds / 60} min ${alarmSeconds % 60} s.")
        }

        PermissionRow(
            title = "Exact alarms",
            allowed = canExact,
            explanation = "Needed so wait timers ring on time with the screen off.",
            onAllow = { context.startSettings(scheduler.exactAlarmSettingsIntent()) },
            canFix = scheduler.exactAlarmSettingsIntent() != null,
        )
        PermissionRow(
            title = "Full-screen alarms",
            allowed = canFullScreen,
            explanation = "Lets a finished timer light up the screen over the lock screen.",
            onAllow = { context.startSettings(scheduler.fullScreenIntentSettingsIntent()) },
            canFix = scheduler.fullScreenIntentSettingsIntent() != null,
        )
    }
}

@Composable
private fun ChoiceButton(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val padding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)
    val content: @Composable () -> Unit = {
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 1)
    }
    if (selected) {
        Button(onClick = onClick, contentPadding = padding, modifier = modifier.heightIn(min = 60.dp)) { content() }
    } else {
        OutlinedButton(onClick = onClick, contentPadding = padding, modifier = modifier.heightIn(min = 60.dp)) { content() }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    allowed: Boolean,
    explanation: String,
    onAllow: () -> Unit,
    canFix: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "$title: ${if (allowed) "allowed" else "NOT allowed"}",
            style = MaterialTheme.typography.titleMedium,
            color = if (allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        if (!allowed) {
            HelpText(explanation)
            if (canFix) BigOutlinedButton(text = "Allow $title".lowercaseAfterFirst(), onClick = onAllow)
        }
    }
}

private fun String.lowercaseAfterFirst(): String = take(1) + drop(1).lowercase()

/** Opens a system settings page, telling the user if this phone has none. */
private fun Context.startSettings(intent: Intent?) {
    if (intent == null) return
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "This phone has no such settings page.", Toast.LENGTH_LONG).show()
    } catch (e: SecurityException) {
        Toast.makeText(this, "This phone has no such settings page.", Toast.LENGTH_LONG).show()
    }
}
