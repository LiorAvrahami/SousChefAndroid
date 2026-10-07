package com.lioravrahami.souschef.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.lioravrahami.souschef.domain.recipe.StepParser
import com.lioravrahami.souschef.ui.TestTags
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Taps closer to the center than this fraction of the dial's width have no clear direction. */
private const val DEAD_ZONE = 0.06f

/**
 * Egg-timer dial for picking a duration: tap anywhere on the face to jump to that time, or
 * drag around it (dragging never wraps past 12 o'clock). The selected time is printed in
 * the center. A chip row switches between the 5-minute, 1-hour and 12-hour scales, and
 * "−"/"+" buttons below the dial fine-tune by one snap step of the current scale.
 *
 * @param seconds the selected duration.
 * @param onSecondsChange called with each newly selected duration (already snapped).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EggTimerDial(seconds: Int, onSecondsChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    var scale by rememberSaveable { mutableStateOf(DialMath.scaleFor(seconds)) }
    LaunchedEffect(seconds) {
        if (seconds > scale.totalSeconds) scale = DialMath.scaleFor(seconds)
    }
    val latestSeconds by rememberUpdatedState(seconds)
    val latestOnChange by rememberUpdatedState(onSecondsChange)
    val latestScale by rememberUpdatedState(scale)
    val emit: (Int) -> Unit = { value -> if (value != latestSeconds) latestOnChange(value) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            DialScale.entries.forEach { option ->
                FilterChip(
                    selected = option == scale,
                    onClick = {
                        scale = option
                        val clamped = DialMath.clampToScale(seconds, option)
                        if (clamped != seconds) onSecondsChange(clamped)
                    },
                    label = { Text(option.label, style = MaterialTheme.typography.titleMedium) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }

        DialFace(
            seconds = seconds,
            scale = scale,
            onTapAngle = { angle -> emit(DialMath.secondsForAngle(angle, latestScale)) },
            onDragStart = { raw ->
                val knob = DialMath.fractionOf(latestSeconds, latestScale) * DialMath.FULL_TURN
                DialMath.DragTracker(DialMath.dragStartAngle(raw, knob)).also {
                    emit(DialMath.secondsForAngle(it.moveTo(raw), latestScale))
                }
            },
            onDragAngle = { angle -> emit(DialMath.secondsForAngle(angle, latestScale)) },
            onSetProgress = { target -> emit(DialMath.snap(target.toDouble(), latestScale)) },
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val step = StepParser.formatDuration(scale.snapSeconds)
            FilledTonalButton(
                onClick = { emit(DialMath.nudge(seconds, scale, up = false)) },
                enabled = seconds > 0,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 64.dp)
                    .testTag(TestTags.DIAL_MINUS),
            ) {
                Text("− $step", style = MaterialTheme.typography.titleLarge)
            }
            FilledTonalButton(
                onClick = {
                    val target = if (seconds >= scale.totalSeconds) scale.larger ?: scale else scale
                    scale = target
                    emit(DialMath.nudge(seconds, target, up = true))
                },
                enabled = seconds < DialScale.entries.last().totalSeconds,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 64.dp)
                    .testTag(TestTags.DIAL_PLUS),
            ) {
                Text("+ $step", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

/** The square dial itself: canvas plus the time printed in its center. */
@Composable
private fun DialFace(
    seconds: Int,
    scale: DialScale,
    onTapAngle: (Double) -> Unit,
    onDragStart: (rawAngle: Double) -> DialMath.DragTracker,
    onDragAngle: (Double) -> Unit,
    onSetProgress: (Float) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val colors = DialColors(
        face = colorScheme.surfaceVariant,
        wedge = colorScheme.primary,
        onWedge = colorScheme.onPrimary,
        tick = colorScheme.onSurfaceVariant,
        hub = colorScheme.surface,
        label = colorScheme.onSurface,
        labelBackground = colorScheme.surface.copy(alpha = 0.9f),
        ring = colorScheme.outline,
    )
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge
    val labelLayouts = remember(scale, labelStyle, textMeasurer) {
        scale.numberLabels.map { textMeasurer.measure(it, labelStyle) }
    }
    val fraction = DialMath.fractionOf(seconds, scale)
    val latestTap by rememberUpdatedState(onTapAngle)
    val latestDragStart by rememberUpdatedState(onDragStart)
    val latestDrag by rememberUpdatedState(onDragAngle)
    val latestSetProgress by rememberUpdatedState(onSetProgress)

    Box(
        modifier = Modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .testTag(TestTags.DIAL)
                .semantics {
                    contentDescription = "Timer dial"
                    stateDescription = StepParser.formatDuration(seconds)
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = seconds.toFloat().coerceAtMost(scale.totalSeconds.toFloat()),
                        range = 0f..scale.totalSeconds.toFloat(),
                    )
                    setProgress("Set time") { target ->
                        latestSetProgress(target)
                        true
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { pos ->
                            val dx = pos.x - size.width / 2f
                            val dy = pos.y - size.height / 2f
                            if (Offset(dx, dy).getDistance() >= size.width * DEAD_ZONE) {
                                latestTap(DialMath.angleOf(dx, dy))
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    var tracker: DialMath.DragTracker? = null
                    fun rawAngle(pos: Offset) = DialMath.angleOf(pos.x - size.width / 2f, pos.y - size.height / 2f)
                    detectDragGestures(
                        onDragStart = { pos -> tracker = latestDragStart(rawAngle(pos)) },
                        onDragEnd = { tracker = null },
                        onDragCancel = { tracker = null },
                    ) { change, _ ->
                        change.consume()
                        tracker?.let { latestDrag(it.moveTo(rawAngle(change.position))) }
                    }
                },
        ) {
            drawDial(fraction, scale, colors, labelLayouts)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val clock = StepParser.formatClock(seconds)
            Text(
                text = clock,
                style = if (clock.length > 7) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayMedium,
                color = colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = StepParser.formatDuration(seconds),
                style = MaterialTheme.typography.bodyLarge,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private class DialColors(
    val face: Color,
    val wedge: Color,
    val onWedge: Color,
    val tick: Color,
    val hub: Color,
    val label: Color,
    val labelBackground: Color,
    val ring: Color,
)

/** Unit vector pointing at [fraction] of a full turn, clockwise from 12 o'clock. */
private fun direction(fraction: Float): Offset {
    val a = fraction * 2 * PI
    return Offset(sin(a).toFloat(), -cos(a).toFloat())
}

private fun DrawScope.drawDial(fraction: Float, scale: DialScale, colors: DialColors, labels: List<TextLayoutResult>) {
    val radius = size.minDimension / 2f
    val faceRadius = radius * 0.97f
    val c = center

    drawCircle(colors.face, faceRadius, c)
    if (fraction > 0f) {
        drawArc(
            color = colors.wedge,
            startAngle = -90f,
            sweepAngle = 360f * fraction,
            useCenter = true,
            topLeft = Offset(c.x - faceRadius, c.y - faceRadius),
            size = Size(faceRadius * 2, faceRadius * 2),
        )
    }

    for (i in 0 until DialMath.TICKS) {
        val t = i.toFloat() / DialMath.TICKS
        val major = i % scale.majorTickEvery == 0
        val dir = direction(t)
        val inner = faceRadius * (if (major) 0.84f else 0.9f)
        val outer = faceRadius * 0.96f
        drawLine(
            color = if (i > 0 && t < fraction) colors.onWedge else colors.tick,
            start = c + dir * inner,
            end = c + dir * outer,
            strokeWidth = (if (major) 3.dp else 1.5.dp).toPx(),
            cap = StrokeCap.Round,
        )
    }

    drawCircle(colors.hub, faceRadius * 0.58f, c)

    val pad = 6.dp.toPx()
    labels.forEachIndexed { k, layout ->
        val p = c + direction(k.toFloat() / labels.size) * (faceRadius * 0.71f)
        val w = layout.size.width.toFloat()
        val h = layout.size.height.toFloat()
        drawRoundRect(
            color = colors.labelBackground,
            topLeft = Offset(p.x - w / 2f - pad, p.y - h / 2f),
            size = Size(w + 2 * pad, h),
            cornerRadius = CornerRadius(h / 2f),
        )
        drawText(layout, color = colors.label, topLeft = Offset(p.x - w / 2f, p.y - h / 2f))
    }

    val knob = c + direction(fraction) * (faceRadius * 0.87f)
    drawCircle(colors.hub, radius * 0.085f, knob)
    drawCircle(colors.wedge, radius * 0.06f, knob)

    drawCircle(colors.ring, faceRadius, c, style = Stroke(3.dp.toPx()))
}
