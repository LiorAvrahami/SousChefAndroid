package com.lioravrahami.souschef.ui.cooking

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.data.model.Trial
import com.lioravrahami.souschef.domain.recipe.StepParser
import com.lioravrahami.souschef.ui.TestTags
import com.lioravrahami.souschef.ui.components.BigButton
import com.lioravrahami.souschef.ui.components.BigOutlinedButton
import kotlinx.coroutines.delay

/** How often countdowns re-read the wall clock. */
private const val TICK_MILLIS = 250L

/**
 * The current wall-clock time, re-read every 250 ms while [ticking] is true. Countdowns
 * always derive from a persisted end time and this clock, never from a local counter.
 */
@Composable
fun rememberWallClock(ticking: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ticking) {
        now = System.currentTimeMillis()
        while (ticking) {
            delay(TICK_MILLIS)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * Page of a wait step: label, planned duration and a large circular countdown.
 *
 * The timer starts automatically when this page becomes the settled page (see
 * [WaitClock.shouldAutoStart]); leaving the page never cancels it.
 *
 * @param isSettled whether this page is the pager's settled page.
 * @param autoStartArmed whether the cook has moved away from the page the screen opened on.
 * @param alreadyHandled whether this step's timer was already started, skipped or stopped during this visit.
 * @param onAutoStart starts the timer because the page was reached.
 * @param onStart starts or restarts the timer with the given number of seconds.
 * @param onClearAndAdvance clears this step's timer and moves to the next page.
 * @param onAdvance moves to the next page without touching any timer.
 */
@Composable
fun WaitPage(
    session: CookingSession,
    stepIndex: Int,
    step: Step.Wait,
    isSettled: Boolean,
    autoStartArmed: Boolean,
    alreadyHandled: Boolean,
    onAutoStart: (seconds: Int) -> Unit,
    onStart: (seconds: Int) -> Unit,
    onClearAndAdvance: () -> Unit,
    onAdvance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trial = session.trial
    val planned = session.waitSeconds(stepIndex)
    val ownsTimer = trial.timerStepIndex == stepIndex && trial.timerEndAt != null
    val endAt = trial.timerEndAt

    // Only the settled page may start its timer; keyed on settling so that clearing the
    // timer (skip / stop) never restarts it while the page animates away.
    val latestTimerEnd by rememberUpdatedState(trial.timerEndAt)
    LaunchedEffect(isSettled, autoStartArmed, alreadyHandled) {
        if (WaitClock.shouldAutoStart(isSettled, autoStartArmed, alreadyHandled, latestTimerEnd)) {
            onAutoStart(planned)
        }
    }

    val now = rememberWallClock(ticking = ownsTimer && endAt != null && endAt > System.currentTimeMillis())
    val phase = WaitClock.phase(stepIndex, trial.timerStepIndex, trial.timerEndAt, now)
    val remaining = if (ownsTimer && endAt != null) WaitClock.remainingSeconds(endAt, now) else planned
    val fraction = when {
        ownsTimer && endAt != null -> WaitClock.fractionRemaining(trial.timerStartedAt, endAt, now, planned)
        else -> 1f
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = step.label.ifBlank { "Wait" },
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "Planned: " + StepParser.formatDuration(planned),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        CookingTexts.changeLine(session.changesForStep(stepIndex))?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            CountdownRing(
                fraction = fraction,
                timesUp = phase == WaitPhase.TIMES_UP,
                remainingSeconds = remaining,
                caption = when (phase) {
                    WaitPhase.RUNNING -> "left"
                    WaitPhase.TIMES_UP -> null
                    WaitPhase.IDLE -> otherTimerCaption(trial, stepIndex)
                },
            )
        }

        when (phase) {
            WaitPhase.TIMES_UP -> {
                Text(
                    text = "Time's up!",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                BigButton(
                    text = "Stop alarm & continue",
                    onClick = onClearAndAdvance,
                    modifier = Modifier
                        .heightIn(min = 88.dp)
                        .testTag(TestTags.WAIT_STOP_ALARM),
                )
                SmallTimerButtons(
                    onRestart = { onStart(planned) },
                    onPlusMinute = { onStart(WaitClock.extendedSeconds(0)) },
                )
            }
            WaitPhase.RUNNING -> {
                SmallTimerButtons(
                    onRestart = { onStart(planned) },
                    onPlusMinute = { onStart(WaitClock.extendedSeconds(remaining)) },
                )
                BigOutlinedButton(
                    text = "Skip wait",
                    onClick = onClearAndAdvance,
                    modifier = Modifier.testTag(TestTags.WAIT_SKIP),
                )
            }
            WaitPhase.IDLE -> {
                BigButton(text = "Start timer", onClick = { onStart(planned) })
                BigOutlinedButton(
                    text = "Skip wait",
                    onClick = onAdvance,
                    modifier = Modifier.testTag(TestTags.WAIT_SKIP),
                )
            }
        }
    }
}

/** Under the idle clock: warns that starting replaces the timer of another step. */
private fun otherTimerCaption(trial: Trial, stepIndex: Int): String {
    val other = trial.timerStepIndex
    return if (trial.timerEndAt != null && other != null && other != stepIndex) {
        "Starting stops the timer of step ${other + 1}"
    } else {
        "not started"
    }
}

@Composable
private fun SmallTimerButtons(onRestart: () -> Unit, onPlusMinute: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = onRestart,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp),
        ) { Text("Restart", style = MaterialTheme.typography.titleMedium) }
        OutlinedButton(
            onClick = onPlusMinute,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp),
        ) { Text("+1 min", style = MaterialTheme.typography.titleMedium) }
    }
}

/**
 * A ring that empties as the wait runs out, with the clock in its centre. The clock text
 * carries [TestTags.WAIT_COUNTDOWN].
 */
@Composable
private fun CountdownRing(
    fraction: Float,
    timesUp: Boolean,
    remainingSeconds: Int,
    caption: String?,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val progressColor = if (timesUp) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(
        // Largest square that fits the space left between the texts and the buttons.
        modifier = Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.07f
            val diameter = size.minDimension - stroke
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke),
            )
            val sweep = if (timesUp) 360f else 360f * fraction.coerceIn(0f, 1f)
            if (sweep > 0f) {
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = StepParser.formatClock(remainingSeconds),
                style = if (remainingSeconds >= 3600) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = if (timesUp) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.testTag(TestTags.WAIT_COUNTDOWN),
            )
            if (caption != null) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
    }
}
