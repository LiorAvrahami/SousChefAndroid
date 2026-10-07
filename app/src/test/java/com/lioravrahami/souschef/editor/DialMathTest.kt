package com.lioravrahami.souschef.editor

import com.lioravrahami.souschef.ui.editor.DialMath
import com.lioravrahami.souschef.ui.editor.DialScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI

class DialMathTest {
    private val eps = 1e-9

    @Test
    fun anglesStartAtTwelveAndRunClockwise() {
        assertEquals(0.0, DialMath.angleOf(0f, -10f), eps)
        assertEquals(PI / 2, DialMath.angleOf(10f, 0f), eps)
        assertEquals(PI, DialMath.angleOf(0f, 10f), eps)
        assertEquals(3 * PI / 2, DialMath.angleOf(-10f, 0f), eps)
        assertEquals(PI / 4, DialMath.angleOf(10f, -10f), eps)
    }

    @Test
    fun angleMapsToSnappedSeconds() {
        val hour = DialScale.ONE_HOUR
        assertEquals(900, DialMath.secondsForAngle(PI / 2, hour))
        assertEquals(1800, DialMath.secondsForAngle(PI, hour))
        assertEquals(0, DialMath.secondsForAngle(0.0, hour))
        assertEquals(3600, DialMath.secondsForAngle(2 * PI, hour))
        // 1/7 of an hour = 514.28 s -> nearest 15 s step
        assertEquals(510, DialMath.secondsForAngle(2 * PI / 7, hour))
        // 5-minute scale snaps to 5 s, 12-hour scale to 5 min
        assertEquals(75, DialMath.secondsForAngle(PI / 2, DialScale.FIVE_MINUTES))
        assertEquals(10800, DialMath.secondsForAngle(PI / 2, DialScale.TWELVE_HOURS))
        assertEquals(300, DialMath.secondsForAngle(2 * PI * 0.0065, DialScale.TWELVE_HOURS))
    }

    @Test
    fun anglesOutsideOneTurnAreClamped() {
        assertEquals(0, DialMath.secondsForAngle(-1.0, DialScale.ONE_HOUR))
        assertEquals(3600, DialMath.secondsForAngle(7.0, DialScale.ONE_HOUR))
    }

    @Test
    fun tapAtThreeOClockOnTheDefaultScaleIsAQuarterHour() {
        val scale = DialMath.scaleFor(300)
        assertEquals(DialScale.ONE_HOUR, scale)
        assertEquals(900, DialMath.secondsForAngle(DialMath.angleOf(0.32f, 0f), scale))
    }

    @Test
    fun scaleSelectionPicksTheSmallestThatHoldsTheValueInside() {
        assertEquals(DialScale.FIVE_MINUTES, DialMath.scaleFor(0))
        assertEquals(DialScale.FIVE_MINUTES, DialMath.scaleFor(120))
        assertEquals(DialScale.ONE_HOUR, DialMath.scaleFor(300))
        assertEquals(DialScale.ONE_HOUR, DialMath.scaleFor(3599))
        assertEquals(DialScale.TWELVE_HOURS, DialMath.scaleFor(3600))
        assertEquals(DialScale.TWELVE_HOURS, DialMath.scaleFor(100_000))
    }

    @Test
    fun clampKeepsFittingValues() {
        assertEquals(240, DialMath.clampToScale(240, DialScale.FIVE_MINUTES))
        assertEquals(300, DialMath.clampToScale(900, DialScale.FIVE_MINUTES))
        assertEquals(0, DialMath.clampToScale(-5, DialScale.ONE_HOUR))
    }

    @Test
    fun fractionIsClamped() {
        assertEquals(0.25f, DialMath.fractionOf(900, DialScale.ONE_HOUR), 1e-6f)
        assertEquals(1f, DialMath.fractionOf(5000, DialScale.ONE_HOUR), 1e-6f)
        assertEquals(0f, DialMath.fractionOf(-3, DialScale.ONE_HOUR), 1e-6f)
    }

    @Test
    fun nudgeMovesOneSnapAndAlignsOffGridValues() {
        val hour = DialScale.ONE_HOUR
        assertEquals(315, DialMath.nudge(300, hour, up = true))
        assertEquals(285, DialMath.nudge(300, hour, up = false))
        assertEquals(15, DialMath.nudge(7, hour, up = true))
        assertEquals(0, DialMath.nudge(7, hour, up = false))
        assertEquals(0, DialMath.nudge(0, hour, up = false))
        assertEquals(3600, DialMath.nudge(3600, hour, up = true))
        assertEquals(10, DialMath.nudge(7, DialScale.FIVE_MINUTES, up = true))
        assertEquals(5, DialMath.nudge(7, DialScale.FIVE_MINUTES, up = false))
    }

    @Test
    fun deltaTakesTheShortWayAround() {
        assertEquals(0.2, DialMath.delta(2 * PI - 0.1, 0.1), eps)
        assertEquals(-0.2, DialMath.delta(0.1, 2 * PI - 0.1), eps)
        assertEquals(1.0, DialMath.delta(1.0, 2.0), eps)
    }

    @Test
    fun clockwiseDragAcrossTheTopStaysPinnedAtFullScale() {
        val tracker = DialMath.DragTracker(2 * PI - 0.3)
        assertEquals(2 * PI - 0.2, tracker.moveTo(2 * PI - 0.2), eps)
        assertEquals(2 * PI, tracker.moveTo(0.2), eps)
        assertEquals(2 * PI, tracker.moveTo(1.0), eps)
        // Coming back stays pinned until the finger crosses the top again.
        assertEquals(2 * PI, tracker.moveTo(0.1), eps)
        assertEquals(2 * PI - 0.5, tracker.moveTo(2 * PI - 0.5), eps)
    }

    @Test
    fun counterClockwiseDragAcrossTheTopStaysPinnedAtZero() {
        val tracker = DialMath.DragTracker(0.3)
        assertEquals(0.0, tracker.moveTo(2 * PI - 0.2), eps)
        assertEquals(0.0, tracker.moveTo(PI + 0.5), eps)
        assertEquals(0.0, tracker.moveTo(2 * PI - 0.1), eps)
        assertEquals(0.4, tracker.moveTo(0.4), eps)
    }

    @Test
    fun ordinaryDragFollowsTheFinger() {
        val tracker = DialMath.DragTracker(1.0)
        assertEquals(2.0, tracker.moveTo(2.0), eps)
        assertEquals(4.0, tracker.moveTo(4.0), eps)
        assertEquals(3.0, tracker.moveTo(3.0), eps)
        assertEquals(1800, DialMath.secondsForAngle(tracker.moveTo(PI), DialScale.ONE_HOUR))
    }

    @Test
    fun grabbingTheKnobAtFullScaleDoesNotFlipToZero() {
        // Knob at a full turn, finger lands just right of 12 o'clock.
        val start = DialMath.dragStartAngle(rawAngle = 0.05, knobAngle = 2 * PI)
        assertEquals(2 * PI + 0.05, start, eps)
        val tracker = DialMath.DragTracker(start)
        assertEquals(2 * PI, tracker.moveTo(0.05), eps)
        assertEquals(2 * PI - 0.3, tracker.moveTo(2 * PI - 0.3), eps)
        // Knob at zero, finger lands just left of 12 o'clock.
        assertEquals(-0.1, DialMath.dragStartAngle(rawAngle = 2 * PI - 0.1, knobAngle = 0.0), eps)
        // Far from the knob: the drag starts where the finger is.
        assertEquals(PI, DialMath.dragStartAngle(rawAngle = PI, knobAngle = 0.5), eps)
    }

    @Test
    fun scalesGrowInOrder() {
        assertEquals(DialScale.ONE_HOUR, DialScale.FIVE_MINUTES.larger)
        assertEquals(DialScale.TWELVE_HOURS, DialScale.ONE_HOUR.larger)
        assertNull(DialScale.TWELVE_HOURS.larger)
        DialScale.entries.forEach { assertEquals(0, it.totalSeconds % it.snapSeconds) }
    }
}
