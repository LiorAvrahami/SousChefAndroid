package com.lioravrahami.souschef

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lioravrahami.souschef.data.db.AppDatabase
import com.lioravrahami.souschef.data.model.RatingAxis
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.data.model.Trial
import com.lioravrahami.souschef.data.model.TrialMode
import com.lioravrahami.souschef.data.model.TrialStatus
import com.lioravrahami.souschef.data.model.VersionOrigin
import com.lioravrahami.souschef.data.repo.RecipeRepository
import com.lioravrahami.souschef.domain.recipe.StepParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real SQLite/Room stack on a device: converters, queries, trash, purge. */
@RunWith(AndroidJUnit4::class)
class DatabaseSmokeTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: RecipeRepository

    private val steps = listOf(
        Step.Text("Add 1.75[cups] water and 3[shakes] of salt", locked = listOf(1)),
        Step.Wait(label = "Microwave", seconds = 900),
        Step.Text("Fluff with a fork"),
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = RecipeRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun recipeVersionAndTrialRoundTrip() = runBlocking {
        val axes = listOf(RatingAxis("crunch", "Soggy", "Too crunchy"))
        val recipe = repo.createRecipe("Microwaved Rice", steps, customAxes = axes, notes = "family classic")
        val details = repo.getDetails(recipe.id)!!
        assertEquals("Microwaved Rice", details.recipe.name)
        assertEquals(axes, details.recipe.customAxes)
        assertEquals(1, details.versions.size)
        assertEquals(steps, details.versions[0].steps)
        assertEquals(4, details.axes.size)

        val version = details.versions[0]
        val values = StepParser.baseValues(version.steps)
        assertEquals(listOf(1.75, 3.0, 900.0), values)

        val trial = Trial(
            recipeId = recipe.id,
            versionId = version.id,
            values = listOf(2.0, 3.0, 840.0),
            status = TrialStatus.DONE,
            mode = TrialMode.EXPLORE,
            overallScore = 8.5,
            axes = mapOf("moisture" to 1.0, "crunch" to -2.0),
            notes = "a bit wet",
            finishedAt = 1234L,
        )
        repo.saveTrial(trial)
        val loaded = repo.getTrial(trial.id)!!
        assertEquals(trial.values, loaded.values)
        assertEquals(trial.axes, loaded.axes)
        assertEquals(TrialMode.EXPLORE, loaded.mode)
        assertEquals(8.5, loaded.overallScore!!, 1e-9)
        assertEquals(trial.id, repo.getDetails(recipe.id)!!.bestTrial()!!.id)

        val v2 = repo.addVersion(recipe.id, steps.reversed(), origin = VersionOrigin.AI, parentVersionId = version.id)
        assertEquals("v2", v2.name)
        assertEquals(2, repo.getDetails(recipe.id)!!.versions.size)

        val summary = repo.observeSummaries().first().single()
        assertEquals(2, summary.versionCount)
        assertEquals(1, summary.trialCount)
        assertEquals(8.5, summary.bestScore!!, 1e-9)
    }

    @Test
    fun inProgressTrialIsObservable() = runBlocking {
        val recipe = repo.createRecipe("Tea", listOf(Step.Wait("Steep", 180)))
        val version = repo.getDetails(recipe.id)!!.versions[0]
        assertNull(repo.observeInProgressTrial().first())
        val trial = Trial(
            recipeId = recipe.id, versionId = version.id, values = listOf(180.0),
            status = TrialStatus.IN_PROGRESS, mode = TrialMode.BEST,
            currentStep = 0, timerStepIndex = 0, timerEndAt = 99_000L, timerStartedAt = 1_000L,
        )
        repo.saveTrial(trial)
        val observed = repo.observeInProgressTrial().first()
        assertNotNull(observed)
        assertEquals(99_000L, observed!!.timerEndAt)
        assertEquals(1, repo.getInProgressTrials().size)
        repo.saveTrial(trial.copy(status = TrialStatus.ABORTED))
        assertNull(repo.observeInProgressTrial().first())
    }

    @Test
    fun trashRestoreAndPurge() = runBlocking {
        val keep = repo.createRecipe("Keep me", steps)
        val gone = repo.createRecipe("Delete me", steps)
        val goneVersion = repo.getDetails(gone.id)!!.versions[0]
        repo.saveTrial(
            Trial(recipeId = gone.id, versionId = goneVersion.id, values = listOf(1.0, 3.0, 900.0),
                status = TrialStatus.DONE, mode = TrialMode.BEST, overallScore = 5.0),
        )

        repo.moveToTrash(gone.id)
        assertEquals(listOf("Keep me"), repo.observeSummaries().first().map { it.recipe.name })
        assertEquals(listOf("Delete me"), repo.observeTrash().first().map { it.name })
        // Trashing never deletes anything.
        assertEquals(1, repo.getDetails(gone.id)!!.trials.size)

        repo.restoreFromTrash(gone.id)
        assertEquals(2, repo.observeSummaries().first().size)
        assertTrue(repo.observeTrash().first().isEmpty())

        repo.moveToTrash(gone.id)
        repo.emptyTrash()
        assertNull(repo.getDetails(gone.id))
        assertTrue(repo.getAllTrials().none { it.recipeId == gone.id })
        assertTrue(repo.getAllVersions().none { it.recipeId == gone.id })
        // The other recipe is untouched.
        assertNotNull(repo.getDetails(keep.id))
        assertEquals(1, repo.getDetails(keep.id)!!.versions.size)
    }
}
