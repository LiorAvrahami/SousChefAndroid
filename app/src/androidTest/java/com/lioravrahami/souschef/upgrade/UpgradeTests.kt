package com.lioravrahami.souschef.upgrade

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lioravrahami.souschef.AppContainer
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.data.model.Trial
import com.lioravrahami.souschef.data.model.TrialMode
import com.lioravrahami.souschef.data.model.TrialStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the promise "updating the app keeps your recipes".
 *
 * The CI script installs version 1, runs [UpgradeSeedTest], installs version 2 over it
 * (a normal in-place update), then runs [UpgradeVerifyTest]. If anyone ever changes the
 * application id, the signing key, the database name, or adds a destructive migration,
 * the verify test fails.
 */
internal object UpgradeData {
    const val RECIPE_NAME = "Upgrade survival rice"
    const val NOTES = "seeded by version 1"
    const val BOLDNESS = 0.33f
    val steps = listOf(
        Step.Text("Add 1.75[cups] water and 3[shakes] of salt", locked = listOf(1)),
        Step.Wait(label = "Microwave", seconds = 900),
    )
    val trialValues = listOf(2.0, 3.0, 840.0)
}

private fun container(): AppContainer =
    AppContainer.from(ApplicationProvider.getApplicationContext<Context>())

private fun versionCode(): Long {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
}

@RunWith(AndroidJUnit4::class)
class UpgradeSeedTest {
    @Test
    fun seedDataWithVersionOne() = runBlocking {
        assertEquals("seed must run on version 1", 1L, versionCode())
        val c = container()
        if (c.repository.getAllRecipes().none { it.name == UpgradeData.RECIPE_NAME }) {
            val recipe = c.repository.createRecipe(UpgradeData.RECIPE_NAME, UpgradeData.steps, notes = UpgradeData.NOTES)
            val version = c.repository.getDetails(recipe.id)!!.versions.single()
            c.repository.saveTrial(
                Trial(
                    recipeId = recipe.id, versionId = version.id, values = UpgradeData.trialValues,
                    status = TrialStatus.DONE, mode = TrialMode.EXPLORE, overallScore = 9.0,
                    axes = mapOf("moisture" to 1.0), notes = "seed trial", finishedAt = 42L,
                ),
            )
        }
        c.settings.boldness = UpgradeData.BOLDNESS
        assertNotNull(c.repository.getAllRecipes().firstOrNull { it.name == UpgradeData.RECIPE_NAME })
    }
}

@RunWith(AndroidJUnit4::class)
class UpgradeVerifyTest {
    @Test
    fun dataSurvivedTheUpdate() = runBlocking {
        assertEquals("verify must run on version 2", 2L, versionCode())
        val c = container()
        val recipe = c.repository.getAllRecipes().firstOrNull { it.name == UpgradeData.RECIPE_NAME }
        assertNotNull("the recipe created before the update is gone", recipe)
        val details = c.repository.getDetails(recipe!!.id)!!
        assertEquals(UpgradeData.NOTES, details.recipe.notes)
        assertEquals(UpgradeData.steps, details.versions.single().steps)
        val trial = details.trials.single()
        assertEquals(UpgradeData.trialValues, trial.values)
        assertEquals(9.0, trial.overallScore!!, 1e-9)
        assertEquals(mapOf("moisture" to 1.0), trial.axes)
        assertEquals(UpgradeData.BOLDNESS, c.settings.boldness, 1e-6f)
    }
}
