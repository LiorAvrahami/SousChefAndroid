package com.lioravrahami.souschef.backup

import com.lioravrahami.souschef.data.backup.BackupCodec
import com.lioravrahami.souschef.data.model.RatingAxis
import com.lioravrahami.souschef.data.model.Recipe
import com.lioravrahami.souschef.data.model.RecipeVersion
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.data.model.Trial
import com.lioravrahami.souschef.data.model.TrialMode
import com.lioravrahami.souschef.data.model.TrialStatus
import com.lioravrahami.souschef.data.model.VersionOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCodecTest {
    private val recipe = Recipe(
        id = "r1",
        name = "Rice",
        createdAt = 1,
        updatedAt = 2,
        customAxes = listOf(RatingAxis("sticky", "Loose", "Sticky")),
        notes = "family favourite",
    )
    private val trashed = Recipe(id = "r2", name = "Old soup", deletedAt = 99, createdAt = 1, updatedAt = 1)
    private val version = RecipeVersion(
        id = "v1",
        recipeId = "r1",
        name = "v1",
        steps = listOf(
            Step.Text("Add 1.75[cups] water and 3[shakes] of salt", locked = listOf(1)),
            Step.Wait(label = "Microwave", seconds = 900, locked = true),
        ),
        createdAt = 5,
    )
    private val soupVersion = RecipeVersion(id = "v2", recipeId = "r2", name = "v1", steps = listOf(Step.Text("Boil")), createdAt = 5)
    private val done = Trial(
        id = "t1", recipeId = "r1", versionId = "v1", values = listOf(1.9, 3.0, 840.0),
        status = TrialStatus.DONE, mode = TrialMode.EXPLORE, createdAt = 10, finishedAt = 20,
        overallScore = 8.5, axes = mapOf("moisture" to -1.0), notes = "good", rationale = "local tweak",
    )
    private val aborted = Trial(
        id = "t2", recipeId = "r1", versionId = "v1", values = listOf(1.75, 3.0, 900.0),
        status = TrialStatus.ABORTED, mode = TrialMode.BEST, createdAt = 30,
    )

    private fun plan(text: String, recipes: List<Recipe> = emptyList(), versionIds: Set<String> = emptySet(), trialIds: Set<String> = emptySet()) =
        BackupCodec.planImport(text, recipes, versionIds, trialIds, now = 1_000_000L)

    @Test
    fun exportHasTheDocumentedEnvelope() {
        val text = BackupCodec.encode(listOf(recipe), listOf(version), listOf(done), exportedAt = 1730000000000)
        assertTrue(text.contains("\"format\": \"souschef-android\""))
        assertTrue(text.contains("\"formatVersion\": 1"))
        assertTrue(text.contains("\"exportedAt\": 1730000000000"))
    }

    @Test
    fun roundTripIntoAnEmptyDeviceRestoresEverythingExactly() {
        val recipes = listOf(recipe, trashed)
        val versions = listOf(version, soupVersion)
        val trials = listOf(done, aborted)
        val text = BackupCodec.encode(recipes, versions, trials, exportedAt = 123)

        val p = plan(text)
        assertEquals(recipes, p.recipes)
        assertEquals(versions, p.versions)
        assertEquals(trials, p.trials)
        assertEquals(0, p.recipesSkipped)
        assertEquals(2, p.result.recipesAdded)
        assertEquals(2, p.result.versionsAdded)
        assertEquals(2, p.result.trialsAdded)
    }

    @Test
    fun existingIdsAreLeftAloneAndCounted() {
        val text = BackupCodec.encode(listOf(recipe, trashed), listOf(version, soupVersion), listOf(done, aborted), 0)
        val existingRice = recipe.copy(name = "Rice (edited on this phone)")

        val p = plan(text, recipes = listOf(existingRice), versionIds = setOf("v1"), trialIds = setOf("t1"))
        assertEquals(listOf("r2"), p.recipes.map { it.id })
        assertEquals(listOf("v2"), p.versions.map { it.id })
        assertEquals(listOf("t2"), p.trials.map { it.id })
        assertEquals(1, p.recipesSkipped)
    }

    @Test
    fun importingTheSameBackupTwiceAddsNothing() {
        val text = BackupCodec.encode(listOf(recipe), listOf(version), listOf(done), 0)
        val p = plan(text, recipes = listOf(recipe), versionIds = setOf("v1"), trialIds = setOf("t1"))
        assertTrue(p.recipes.isEmpty() && p.versions.isEmpty() && p.trials.isEmpty())
        assertEquals(1, p.recipesSkipped)
    }

    @Test
    fun orphanVersionsAndTrialsAreDropped() {
        val orphanVersion = version.copy(id = "vx", recipeId = "missing")
        val orphanTrial = done.copy(id = "tx", versionId = "missing")
        val text = BackupCodec.encode(listOf(recipe), listOf(version, orphanVersion), listOf(done, orphanTrial), 0)
        val p = plan(text)
        assertEquals(listOf("v1"), p.versions.map { it.id })
        assertEquals(listOf("t1"), p.trials.map { it.id })
    }

    @Test
    fun aCookingInProgressComesInAsAborted() {
        val running = done.copy(
            id = "t3", status = TrialStatus.IN_PROGRESS, overallScore = null, finishedAt = null,
            currentStep = 1, timerStepIndex = 1, timerEndAt = 5000, timerStartedAt = 4000,
        )
        val p = plan(BackupCodec.encode(listOf(recipe), listOf(version), listOf(running), 0))
        val t = p.trials.single()
        assertEquals(TrialStatus.ABORTED, t.status)
        assertNull(t.timerEndAt)
        assertNull(t.timerStepIndex)
        assertNull(t.timerStartedAt)
        assertEquals(running.values, t.values)
    }

    @Test
    fun unknownFieldsFromANewerAppAreIgnored() {
        val text = """
            {"format":"souschef-android","formatVersion":1,"exportedAt":5,"futureField":{"a":1},
             "recipes":[{"id":"r9","name":"Tea","createdAt":1,"updatedAt":1,"colour":"green"}],
             "versions":[{"id":"v9","recipeId":"r9","name":"v1","steps":[{"type":"text","text":"Steep 3[min]"},{"type":"wait","seconds":180}]}],
             "trials":[]}
        """.trimIndent()
        val p = plan(text)
        assertEquals("Tea", p.recipes.single().name)
        assertEquals(listOf(Step.Text("Steep 3[min]"), Step.Wait(seconds = 180)), p.versions.single().steps)
    }

    @Test
    fun garbageIsRejectedWithAReadableMessage() {
        listOf("hello", "{}", "[1,2,3]", """{"format":"something-else"}""", """[{"title":"x"}]""").forEach { text ->
            try {
                plan(text)
                fail("accepted: $text")
            } catch (e: IllegalArgumentException) {
                assertEquals("This file is not a Sous Chef backup.", e.message)
            }
        }
    }

    @Test
    fun aDamagedBackupSaysSo() {
        try {
            plan("""{"format":"souschef-android","recipes":"oops"}""")
            fail("accepted a damaged backup")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("damaged"))
        }
    }

    @Test
    fun legacyOriginIsNotUsedForNativeImports() {
        val p = plan(BackupCodec.encode(listOf(recipe), listOf(version), emptyList(), 0))
        assertEquals(VersionOrigin.MANUAL, p.versions.single().origin)
    }
}
