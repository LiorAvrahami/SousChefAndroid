package com.lioravrahami.souschef

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.data.model.TrialStatus
import com.lioravrahami.souschef.ui.TestTags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the app the way its owner will: create a recipe (text step + wait step picked on
 * the dial), cook the best version page by page, rate it, then explore a classical tweak.
 * Screenshots of every screen are saved for visual inspection.
 */
@RunWith(AndroidJUnit4::class)
class CookingJourneyTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val c get() = TestSupport.container

    @Before
    fun setUp() {
        TestSupport.resetSessions()
    }

    @After
    fun tearDown() {
        TestSupport.resetSessions()
    }

    @Test
    fun createCookRateAndExplore() {
        val name = "Journey rice " + (System.currentTimeMillis() % 100000)

        // ---- recipe list
        rule.waitForTag(TestTags.RECIPES_NEW)
        Screenshots.take("recipe_list")
        rule.onNodeWithTag(TestTags.RECIPES_NEW).performClick()

        // ---- editor: name + a text step with two parameters
        rule.waitForTag(TestTags.EDITOR_NAME)
        rule.onNodeWithTag(TestTags.EDITOR_NAME).performTextInput(name)
        Espresso.closeSoftKeyboard()
        rule.onNodeWithTag(TestTags.EDITOR_ADD_STEP).performScrollTo().performClick()
        rule.waitForTag(TestTags.STEP_TEXT_FIELD)
        rule.onNodeWithTag(TestTags.STEP_TEXT_FIELD).performTextInput("Add 1.75[cups] water and 3[shakes] of salt")
        Espresso.closeSoftKeyboard()
        Screenshots.take("editor_text_step")
        rule.onNodeWithTag(TestTags.STEP_DONE).performClick()

        // ---- editor: a wait step chosen on the egg-timer dial (tap at 3 o'clock)
        rule.waitForTag(TestTags.EDITOR_ADD_WAIT)
        rule.onNodeWithTag(TestTags.EDITOR_ADD_WAIT).performScrollTo().performClick()
        rule.waitForTag(TestTags.DIAL)
        rule.onNodeWithTag(TestTags.WAIT_LABEL_FIELD).performTextInput("Microwave")
        Espresso.closeSoftKeyboard()
        rule.onNodeWithTag(TestTags.DIAL).performScrollTo()
        rule.onNodeWithTag(TestTags.DIAL).performTouchInput { click(Offset(width * 0.82f, height * 0.5f)) }
        rule.waitForIdle()
        Screenshots.take("editor_wait_dial")
        rule.onNodeWithTag(TestTags.WAIT_DONE).performScrollTo().performClick()

        rule.waitForTag(TestTags.EDITOR_SAVE)
        Screenshots.take("editor_filled")
        rule.onNodeWithTag(TestTags.EDITOR_SAVE).performClick()

        // ---- recipe detail
        rule.waitForTag(TestTags.COOK_BEST)
        Screenshots.take("recipe_detail_new")
        val recipe = runBlocking { c.repository.getAllRecipes().first { it.name == name } }
        val version = runBlocking { c.repository.getDetails(recipe.id)!!.versions.single() }
        assertEquals(2, version.steps.size)
        val wait = version.steps[1] as Step.Wait
        assertEquals("Microwave", wait.label)
        assertTrue("dial tap at 3 o'clock should pick about a quarter of the scale, got ${wait.seconds}s", wait.seconds in 600..1200)

        // ---- cook the best (= as written)
        rule.onNodeWithTag(TestTags.COOK_BEST).performScrollTo().performClick()
        rule.waitForTag(TestTags.PROPOSAL_COOK)
        Screenshots.take("proposal_best")
        rule.onNodeWithTag(TestTags.PROPOSAL_COOK).performClick()

        // ---- cooking: text page
        rule.waitForTag(TestTags.COOK_PAGER)
        rule.waitForNode(hasText("1.75 cups", substring = true))
        Screenshots.take("cooking_text_page")
        rule.onNodeWithTag(TestTags.COOK_OVERVIEW).performClick()
        rule.waitForIdle()
        Screenshots.take("cooking_overview_sheet")
        Espresso.pressBack()
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.COOK_NEXT).performClick()

        // ---- cooking: wait page (timer auto-starts), then skip
        rule.waitForTag(TestTags.WAIT_COUNTDOWN)
        TestSupport.waitFor(10_000, "wait timer to be persisted") {
            runBlocking { c.repository.getInProgressTrials().firstOrNull()?.timerEndAt != null }
        }
        Screenshots.take("cooking_wait_page")
        rule.onNodeWithTag(TestTags.WAIT_SKIP).performClick()

        // ---- done page → rating
        rule.waitForTag(TestTags.COOK_FINISH_RATE)
        Screenshots.take("cooking_done_page")
        rule.onNodeWithTag(TestTags.COOK_FINISH_RATE).performClick()
        rule.waitForTag(TestTags.RATING_SAVE)
        rule.onNodeWithTag(TestTags.RATING_NOTES).performScrollTo().performTextInput("a little too wet")
        Espresso.closeSoftKeyboard()
        Screenshots.take("rating")
        rule.onNodeWithTag(TestTags.RATING_SAVE).performScrollTo().performClick()

        // ---- back on the detail screen with one rated cooking
        rule.waitForTag(TestTags.COOK_EXPLORE)
        val done = runBlocking { c.repository.getDetails(recipe.id)!!.doneTrials }
        assertEquals(1, done.size)
        assertEquals(TrialStatus.DONE, done[0].status)
        assertNotNull(done[0].overallScore)
        assertEquals("a little too wet", done[0].notes)
        Screenshots.take("recipe_detail_after_rating")

        // ---- explore: classical tweak proposes something different from "as written"
        rule.onNodeWithTag(TestTags.COOK_EXPLORE).performScrollTo().performClick()
        rule.waitForTag(TestTags.EXPLORE_CLASSICAL)
        Screenshots.take("explore_chooser")
        rule.onNodeWithTag(TestTags.EXPLORE_CLASSICAL).performClick()
        rule.waitForTag(TestTags.PROPOSAL_COOK)
        Screenshots.take("proposal_classical")
        rule.onNodeWithTag(TestTags.PROPOSAL_COOK).performClick()
        rule.waitForTag(TestTags.COOK_PAGER)
        val exploring = runBlocking { c.repository.getInProgressTrials().single() }
        assertTrue(
            "an exploration must change at least one value",
            exploring.values != done[0].values,
        )
        Screenshots.take("cooking_explore_text_page")
    }
}
