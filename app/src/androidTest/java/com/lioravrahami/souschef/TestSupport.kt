package com.lioravrahami.souschef

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.lioravrahami.souschef.data.model.Recipe
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.data.model.Trial
import com.lioravrahami.souschef.data.model.TrialMode
import com.lioravrahami.souschef.domain.recipe.StepParser
import kotlinx.coroutines.runBlocking

/** Shared helpers for the on-device tests. */
object TestSupport {
    val context: Context get() = ApplicationProvider.getApplicationContext()
    val container: AppContainer get() = AppContainer.from(context)
    val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Aborts any cooking session, silences any alarm. Leaves recipes alone. */
    fun resetSessions() = runBlocking {
        container.repository.getInProgressTrials().forEach { container.sessions.abort(it.id) }
        container.timerScheduler.cancel()
        container.timerScheduler.stopRinging()
    }

    fun createRecipe(name: String, steps: List<Step>): Recipe = runBlocking {
        container.repository.createRecipe(name, steps)
    }

    /** Starts a cooking session of the recipe's only version with the values as written. */
    fun startSession(recipe: Recipe): Trial = runBlocking {
        val version = container.repository.getDetails(recipe.id)!!.versions.first()
        container.sessions.start(recipe.id, version.id, StepParser.baseValues(version.steps), TrialMode.AS_WRITTEN)
    }

    fun launchIntent(trialId: String? = null): Intent =
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (trialId != null) putExtra(MainActivity.EXTRA_TRIAL_ID, trialId)
        }

    /** Polls [condition] (off the UI thread) until true or [timeoutMs] elapses. */
    fun waitFor(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(200)
        }
        throw AssertionError("Timed out after $timeoutMs ms waiting for: $what")
    }
}

fun ComposeTestRule.waitForTag(tag: String, timeoutMs: Long = 15_000) =
    waitForNode(hasTestTag(tag), timeoutMs)

fun ComposeTestRule.waitForNode(matcher: SemanticsMatcher, timeoutMs: Long = 15_000) {
    waitUntil(timeoutMs) { onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
}
