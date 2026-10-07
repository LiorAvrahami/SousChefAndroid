package com.lioravrahami.souschef.ui.editor

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * One full turn of the [EggTimerDial].
 *
 * @property totalSeconds the time one full turn represents.
 * @property snapSeconds the grid every selected time snaps to (also the "+"/"−" step).
 * @property majorTickEvery every n-th of the 60 ticks is drawn longer.
 * @property numberLabels labels drawn evenly around the face, starting at 12 o'clock.
 */
enum class DialScale(
    val label: String,
    val totalSeconds: Int,
    val snapSeconds: Int,
    val majorTickEvery: Int,
    val numberLabels: List<String>,
) {
    FIVE_MINUTES("5 min", 300, 5, 12, listOf("0", "1m", "2m", "3m", "4m")),
    ONE_HOUR("1 hour", 3600, 15, 5, listOf("0", "15", "30", "45")),
    TWELVE_HOURS("12 hours", 43200, 300, 5, listOf("0", "3h", "6h", "9h"));

    /** The next bigger scale, or null for the biggest one. */
    val larger: DialScale? get() = entries.getOrNull(ordinal + 1)
}

/**
 * Pure geometry and arithmetic behind the [EggTimerDial]. Angles are in radians,
 * measured from 12 o'clock, clockwise (screen coordinates: y grows downwards).
 */
object DialMath {
    const val FULL_TURN: Double = 2 * PI

    /** Number of tick marks around the face. */
    const val TICKS: Int = 60

    /** Angle in `[0, 2π)` of the point ([dx], [dy]) relative to the dial's center. */
    fun angleOf(dx: Float, dy: Float): Double {
        val a = atan2(dx.toDouble(), -dy.toDouble())
        return if (a < 0) a + FULL_TURN else a
    }

    /** The snapped time for an [angle] in `[0, 2π]` (values outside are clamped). */
    fun secondsForAngle(angle: Double, scale: DialScale): Int {
        val fraction = (angle / FULL_TURN).coerceIn(0.0, 1.0)
        return snap(fraction * scale.totalSeconds, scale)
    }

    /** Rounds [seconds] to the nearest multiple of the scale's snap and clamps it to the scale. */
    fun snap(seconds: Double, scale: DialScale): Int {
        val steps = (seconds / scale.snapSeconds).roundToInt()
        return (steps * scale.snapSeconds).coerceIn(0, scale.totalSeconds)
    }

    /** How much of the full turn [seconds] covers, in `[0, 1]`. */
    fun fractionOf(seconds: Int, scale: DialScale): Float =
        (seconds.toFloat() / scale.totalSeconds).coerceIn(0f, 1f)

    /**
     * The smallest scale on which [seconds] sits strictly inside the turn (so the knob can
     * still move both ways); the biggest scale for anything longer. 5 min -> "1 hour".
     */
    fun scaleFor(seconds: Int): DialScale =
        DialScale.entries.firstOrNull { seconds < it.totalSeconds } ?: DialScale.entries.last()

    /** [seconds] limited to what [scale] can show. */
    fun clampToScale(seconds: Int, scale: DialScale): Int = seconds.coerceIn(0, scale.totalSeconds)

    /**
     * The next grid point of [scale] above ([up]) or below [seconds], clamped to the scale.
     * Off-grid values move to the neighbouring grid point first (7 s, up, 5 s grid -> 10 s).
     */
    fun nudge(seconds: Int, scale: DialScale, up: Boolean): Int {
        val snap = scale.snapSeconds
        val s = seconds.coerceAtLeast(0)
        val next = if (up) {
            (s / snap + 1) * snap
        } else {
            if (s % snap == 0) s - snap else (s / snap) * snap
        }
        return next.coerceIn(0, scale.totalSeconds)
    }

    /**
     * Where a drag that starts at the finger's [rawAngle] begins on the continuous scale.
     * Grabbing the knob near 12 o'clock must not flip the value to the other end: when the
     * finger lands within [GRAB_RADIANS] of the knob's [knobAngle] (in `[0, 2π]`), the drag
     * starts at the knob's side of the top (e.g. slightly beyond a full turn).
     */
    fun dragStartAngle(rawAngle: Double, knobAngle: Double): Double {
        val d = delta(knobAngle, rawAngle)
        return if (kotlin.math.abs(d) <= GRAB_RADIANS) knobAngle + d else rawAngle
    }

    /** How close (in radians, about 30°) to the knob a drag must start to count as grabbing it. */
    const val GRAB_RADIANS: Double = PI / 6

    /** Signed shortest rotation from [from] to [to], in `(-π, π]`. */
    fun delta(from: Double, to: Double): Double {
        var d = to - from
        while (d > PI) d -= FULL_TURN
        while (d <= -PI) d += FULL_TURN
        return d
    }

    /**
     * Follows a drag around the dial without wrapping past 12 o'clock: the finger's angle is
     * accumulated continuously, and the reported angle stays pinned at 0 or at a full turn
     * until the finger comes back across the top.
     */
    class DragTracker(startAngle: Double) {
        private var lastRaw = startAngle
        private var unwrapped = startAngle

        /** Feeds the finger's raw angle in `[0, 2π)`; returns the dial angle in `[0, 2π]`. */
        fun moveTo(rawAngle: Double): Double {
            // Bounded so that spinning around several times does not need as many turns back.
            unwrapped = (unwrapped + delta(lastRaw, rawAngle)).coerceIn(-PI, FULL_TURN + PI)
            lastRaw = rawAngle
            return unwrapped.coerceIn(0.0, FULL_TURN)
        }
    }
}
