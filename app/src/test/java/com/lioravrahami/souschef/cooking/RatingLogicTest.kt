package com.lioravrahami.souschef.cooking

import com.lioravrahami.souschef.data.model.DefaultAxes
import com.lioravrahami.souschef.data.model.RatingAxis
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.data.model.TrialMode
import com.lioravrahami.souschef.ui.rating.RatingLogic
import org.junit.Assert.assertEquals
import org.junit.Test

class RatingLogicTest {
    private val moisture = RatingAxis("moisture", "Too dry", "Too wet")

    @Test
    fun scoreSnapsToHalfPointsWithinRange() {
        assertEquals(7.0, RatingLogic.snapScore(7.1f), 0.0)
        assertEquals(7.5, RatingLogic.snapScore(7.3f), 0.0)
        assertEquals(0.0, RatingLogic.snapScore(-1f), 0.0)
        assertEquals(10.0, RatingLogic.snapScore(12f), 0.0)
        assertEquals("7.5", RatingLogic.formatScore(7.5))
        assertEquals("7", RatingLogic.formatScore(7.0))
    }

    @Test
    fun sliderStepsGiveTheRightNumberOfPositions() {
        // Material Slider: positions = steps + 2.
        assertEquals(21, RatingLogic.SCORE_SLIDER_STEPS + 2)
        assertEquals(5, RatingLogic.AXIS_SLIDER_STEPS + 2)
    }

    @Test
    fun axisSnapsToWholeSteps() {
        assertEquals(-2.0, RatingLogic.snapAxis(-1.6f), 0.0)
        assertEquals(1.0, RatingLogic.snapAxis(0.6f), 0.0)
        assertEquals(2.0, RatingLogic.snapAxis(3f), 0.0)
    }

    @Test
    fun axisCaptions() {
        assertEquals("Just right", RatingLogic.axisCaption(moisture, 0.0))
        assertEquals("Slightly too dry", RatingLogic.axisCaption(moisture, -1.0))
        assertEquals("Much too dry", RatingLogic.axisCaption(moisture, -2.0))
        assertEquals("Slightly too wet", RatingLogic.axisCaption(moisture, 1.0))
        assertEquals("Much too wet", RatingLogic.axisCaption(moisture, 2.0))
        assertEquals("Much MSG", RatingLogic.axisCaption(RatingAxis("x", "None", "MSG"), 2.0))
    }

    @Test
    fun differencesDescribeChangesOrSayAsWritten() {
        val steps = listOf(Step.Text("Add 1.75[cups] water"), Step.Wait(label = "Rest", seconds = 600))
        assertEquals(listOf("Cooked as written"), RatingLogic.differences(steps, listOf(1.75, 600.0)))
        assertEquals(
            listOf("water: 1.75 → 2 cups", "Rest: 10 min → 12 min"),
            RatingLogic.differences(steps, listOf(2.0, 720.0)),
        )
        assertEquals(
            listOf("The saved amounts don't match this version"),
            RatingLogic.differences(steps, listOf(2.0)),
        )
    }

    @Test
    fun everyAxisIsSavedWithZeroAsDefault() {
        val saved = RatingLogic.axesToSave(DefaultAxes.all, mapOf("salt" to 1.0, "unknown" to 2.0))
        assertEquals(mapOf("moisture" to 0.0, "doneness" to 0.0, "salt" to 1.0), saved)
    }

    @Test
    fun modeLabels() {
        assertEquals("Best so far", RatingLogic.modeLabel(TrialMode.BEST))
        assertEquals("Exploration", RatingLogic.modeLabel(TrialMode.EXPLORE))
        assertEquals("AI suggestion", RatingLogic.modeLabel(TrialMode.AI))
        assertEquals("As written", RatingLogic.modeLabel(TrialMode.AS_WRITTEN))
    }
}
