package com.lioravrahami.souschef.cooking

import com.lioravrahami.souschef.data.model.Recipe
import com.lioravrahami.souschef.data.model.RecipeDetails
import com.lioravrahami.souschef.data.model.RecipeVersion
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.data.model.Trial
import com.lioravrahami.souschef.data.model.TrialMode
import com.lioravrahami.souschef.data.model.TrialStatus
import com.lioravrahami.souschef.ui.cooking.CookingStates
import com.lioravrahami.souschef.ui.cooking.CookingTexts
import com.lioravrahami.souschef.ui.cooking.CookingUiState
import com.lioravrahami.souschef.ui.cooking.WaitClock
import com.lioravrahami.souschef.ui.cooking.WaitPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CookingLogicTest {
    private val recipe = Recipe(id = "r", name = "Rice")
    private val version = RecipeVersion(
        id = "v",
        recipeId = "r",
        name = "v1",
        steps = listOf(
            Step.Text("Add 1.75[cups] water and 3[shakes] of salt"),
            Step.Wait(label = "Microwave", seconds = 900),
            Step.Text("Serve"),
        ),
    )
    private val details = RecipeDetails(recipe, listOf(version), emptyList())

    private fun trial(
        values: List<Double> = listOf(1.75, 3.0, 900.0),
        status: TrialStatus = TrialStatus.IN_PROGRESS,
        finishedAt: Long? = null,
        currentStep: Int = 0,
        versionId: String = "v",
    ) = Trial(
        id = "t",
        recipeId = "r",
        versionId = versionId,
        values = values,
        status = status,
        mode = TrialMode.EXPLORE,
        finishedAt = finishedAt,
        currentStep = currentStep,
    )

    // ---------------------------------------------------------------- states

    @Test
    fun missingTrialIsMissing() {
        assertEquals(CookingUiState.Missing(null), CookingStates.build(null, details))
    }

    @Test
    fun ratedOrAbortedTrialIsClosed() {
        assertEquals(
            CookingUiState.Closed("r", TrialStatus.DONE),
            CookingStates.build(trial(status = TrialStatus.DONE), details),
        )
        assertEquals(
            CookingUiState.Closed("r", TrialStatus.ABORTED),
            CookingStates.build(trial(status = TrialStatus.ABORTED), details),
        )
    }

    @Test
    fun finishedButUnratedGoesToRating() {
        assertEquals(
            CookingUiState.AwaitingRating("t", "r"),
            CookingStates.build(trial(finishedAt = 5L), details),
        )
    }

    @Test
    fun missingVersionKeepsRecipeId() {
        assertEquals(CookingUiState.Missing("r"), CookingStates.build(trial(versionId = "gone"), details))
    }

    @Test
    fun readySessionUsesTrialValuesAndListsChangesPerStep() {
        val state = CookingStates.build(trial(values = listOf(1.9, 3.0, 780.0)), details)
        val session = (state as CookingUiState.Ready).session
        assertFalse(session.valuesMismatch)
        assertEquals(4, session.pageCount)
        assertEquals(listOf("water: 1.75 → 1.9 cups"), session.changesForStep(0).map { it.text })
        assertEquals(listOf("Microwave: 15 min → 13 min"), session.changesForStep(1).map { it.text })
        assertTrue(session.changesForStep(2).isEmpty())
        assertEquals(780, session.waitSeconds(1))
        assertEquals(0, session.waitSeconds(0))
        assertEquals("Add 1.9 cups water and 3 shakes of salt", session.render(0))
    }

    @Test
    fun mismatchedValuesFallBackToBaseValues() {
        val session = (CookingStates.build(trial(values = listOf(1.0)), details) as CookingUiState.Ready).session
        assertTrue(session.valuesMismatch)
        assertEquals(listOf(1.75, 3.0, 900.0), session.values)
        assertTrue(session.changes.isEmpty())
        assertEquals(900, session.waitSeconds(1))
    }

    @Test
    fun initialPageIsClampedToTheDonePage() {
        val session = (CookingStates.build(trial(currentStep = 42), details) as CookingUiState.Ready).session
        assertEquals(3, session.initialPage)
        val negative = (CookingStates.build(trial(currentStep = -1), details) as CookingUiState.Ready).session
        assertEquals(0, negative.initialPage)
    }

    // ---------------------------------------------------------------- clock

    @Test
    fun remainingSecondsRoundUpAndStopAtZero() {
        assertEquals(60, WaitClock.remainingSeconds(endAtMillis = 60_000, nowMillis = 0))
        assertEquals(60, WaitClock.remainingSeconds(endAtMillis = 60_000, nowMillis = 1))
        assertEquals(1, WaitClock.remainingSeconds(endAtMillis = 60_000, nowMillis = 59_999))
        assertEquals(0, WaitClock.remainingSeconds(endAtMillis = 60_000, nowMillis = 60_000))
        assertEquals(0, WaitClock.remainingSeconds(endAtMillis = 60_000, nowMillis = 90_000))
    }

    @Test
    fun fractionRemainingUsesStartTimeOrPlannedDuration() {
        assertEquals(0.5f, WaitClock.fractionRemaining(0L, 100_000L, 50_000L, plannedSeconds = 999), 1e-6f)
        assertEquals(1f, WaitClock.fractionRemaining(0L, 100_000L, -5L, plannedSeconds = 999), 1e-6f)
        assertEquals(0f, WaitClock.fractionRemaining(0L, 100_000L, 200_000L, plannedSeconds = 999), 1e-6f)
        assertEquals(0.25f, WaitClock.fractionRemaining(null, 100_000L, 75_000L, plannedSeconds = 100), 1e-6f)
        assertEquals(0f, WaitClock.fractionRemaining(null, 100_000L, 0L, plannedSeconds = 0), 1e-6f)
    }

    @Test
    fun plusOneMinuteAddsToWhatIsLeft() {
        assertEquals(260, WaitClock.extendedSeconds(200))
        assertEquals(60, WaitClock.extendedSeconds(0))
        assertEquals(60, WaitClock.extendedSeconds(-5))
    }

    @Test
    fun phaseDependsOnOwnershipAndTime() {
        assertEquals(WaitPhase.IDLE, WaitClock.phase(1, timerStepIndex = null, timerEndAt = null, nowMillis = 0))
        assertEquals(WaitPhase.IDLE, WaitClock.phase(1, timerStepIndex = 3, timerEndAt = 10_000, nowMillis = 0))
        assertEquals(WaitPhase.RUNNING, WaitClock.phase(1, timerStepIndex = 1, timerEndAt = 10_000, nowMillis = 0))
        assertEquals(WaitPhase.TIMES_UP, WaitClock.phase(1, timerStepIndex = 1, timerEndAt = 10_000, nowMillis = 10_000))
    }

    @Test
    fun autoStartOnlyOnSettledArmedUnhandledPagesWithoutAnyTimer() {
        assertTrue(WaitClock.shouldAutoStart(isSettled = true, armed = true, alreadyHandled = false, timerEndAt = null))
        assertFalse(WaitClock.shouldAutoStart(isSettled = false, armed = true, alreadyHandled = false, timerEndAt = null))
        assertFalse(WaitClock.shouldAutoStart(isSettled = true, armed = false, alreadyHandled = false, timerEndAt = null))
        assertFalse(WaitClock.shouldAutoStart(isSettled = true, armed = true, alreadyHandled = true, timerEndAt = null))
        assertFalse(WaitClock.shouldAutoStart(isSettled = true, armed = true, alreadyHandled = false, timerEndAt = 5L))
    }

    // ---------------------------------------------------------------- texts

    @Test
    fun progressLabelAndFraction() {
        assertEquals("Step 3 of 8", CookingTexts.progressLabel(2, 8))
        assertEquals("All steps done", CookingTexts.progressLabel(8, 8))
        assertEquals(0.5f, CookingTexts.progressFraction(0, 1), 1e-6f)
        assertEquals(1f, CookingTexts.progressFraction(1, 1), 1e-6f)
        assertEquals(1f, CookingTexts.progressFraction(0, 0), 1e-6f)
    }

    @Test
    fun changeLineJoinsChangesOrIsNull() {
        val session = (CookingStates.build(trial(values = listOf(1.9, 4.0, 900.0)), details) as CookingUiState.Ready).session
        assertEquals(
            "This time: water: 1.75 → 1.9 cups · salt: 3 → 4 shakes",
            CookingTexts.changeLine(session.changesForStep(0)),
        )
        assertNull(CookingTexts.changeLine(session.changesForStep(2)))
    }

    @Test
    fun otherTimerBarText() {
        assertEquals("Timer running for step 2 — 3:20 left", CookingTexts.otherTimerBar(1, 200))
        assertEquals("Timer for step 2 is done — tap to go back", CookingTexts.otherTimerBar(1, 0))
    }
}
