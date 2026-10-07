package com.lioravrahami.souschef.recipes

import com.lioravrahami.souschef.data.model.DefaultAxes
import com.lioravrahami.souschef.data.model.RatingAxis
import com.lioravrahami.souschef.data.model.Recipe
import com.lioravrahami.souschef.data.model.RecipeDetails
import com.lioravrahami.souschef.data.model.RecipeVersion
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.data.model.Trial
import com.lioravrahami.souschef.data.model.TrialMode
import com.lioravrahami.souschef.data.model.TrialStatus
import com.lioravrahami.souschef.domain.optimizer.ClassicalOptimizer
import com.lioravrahami.souschef.domain.optimizer.OptimizerSettings
import com.lioravrahami.souschef.domain.optimizer.Proposal
import com.lioravrahami.souschef.domain.optimizer.ProposalKind
import com.lioravrahami.souschef.ui.recipe.RecipeDetailText
import com.lioravrahami.souschef.ui.recipe.ScoreBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RecipeDetailTextTest {
    private val recipe = Recipe(id = "r", name = "Rice")
    private val steps = listOf(
        Step.Text("Add 1.75[cups] water and 3[shakes] of salt"),
        Step.Wait(label = "Simmer", seconds = 900),
    )
    private val v1 = RecipeVersion(id = "v1", recipeId = "r", name = "v1", steps = steps, createdAt = 1_000)
    private val v2 = RecipeVersion(id = "v2", recipeId = "r", name = "v2", steps = steps, createdAt = 2_000)

    private fun trial(
        id: String,
        score: Double?,
        values: List<Double> = listOf(1.75, 3.0, 900.0),
        version: String = "v1",
        status: TrialStatus = TrialStatus.DONE,
        finishedAt: Long = 10_000,
    ) = Trial(
        id = id,
        recipeId = "r",
        versionId = version,
        values = values,
        status = status,
        mode = TrialMode.EXPLORE,
        overallScore = score,
        finishedAt = finishedAt,
        createdAt = finishedAt - 100,
    )

    @Test
    fun scoreBuckets() {
        assertEquals(ScoreBucket.HIGH, RecipeDetailText.scoreBucket(8.0))
        assertEquals(ScoreBucket.HIGH, RecipeDetailText.scoreBucket(10.0))
        assertEquals(ScoreBucket.MEDIUM, RecipeDetailText.scoreBucket(7.5))
        assertEquals(ScoreBucket.MEDIUM, RecipeDetailText.scoreBucket(5.0))
        assertEquals(ScoreBucket.LOW, RecipeDetailText.scoreBucket(4.5))
        assertEquals(ScoreBucket.LOW, RecipeDetailText.scoreBucket(0.0))
    }

    @Test
    fun versionStats() {
        assertEquals("never cooked", RecipeDetailText.versionStats(0, null))
        assertEquals("1 cooking · best 8.5", RecipeDetailText.versionStats(1, 8.5))
        assertEquals("4 cookings · best 7", RecipeDetailText.versionStats(4, 7.0))
    }

    @Test
    fun bestSubtitle() {
        val fmt: (Long) -> String = { "day$it" }
        assertEquals("as written (never cooked yet)", RecipeDetailText.bestSubtitle(null, false, fmt))
        assertEquals("as written (no usable rating yet)", RecipeDetailText.bestSubtitle(null, true, fmt))
        assertEquals("best score 8.5 from day10000", RecipeDetailText.bestSubtitle(trial("t", 8.5), true, fmt))
    }

    @Test
    fun bestTrialForFindsTheOptimizersBest() {
        val good = trial("good", 8.5, listOf(2.0, 3.0, 900.0), finishedAt = 20_000)
        val bad = trial("bad", 4.0)
        val details = RecipeDetails(recipe, listOf(v1), listOf(bad, good))
        val best = ClassicalOptimizer(Random(1)).best(details)
        assertEquals("good", RecipeDetailText.bestTrialFor(details, best)?.id)
    }

    @Test
    fun bestTrialForIsNullWhenCookingAsWritten() {
        val details = RecipeDetails(recipe, listOf(v1), emptyList())
        val best = ClassicalOptimizer(Random(1)).best(details)
        assertNull(RecipeDetailText.bestTrialFor(details, best))
    }

    @Test
    fun firstDifferentRerollsRepeats() {
        val previous = Proposal("v1", listOf(1.0, 2.0), "", ProposalKind.LOCAL)
        val queue = ArrayDeque(
            listOf(
                Proposal("v1", listOf(1.0, 2.0), "same", ProposalKind.LOCAL),
                Proposal("v1", listOf(1.0, 2.0000000001), "same within noise", ProposalKind.LOCAL),
                Proposal("v1", listOf(1.1, 2.0), "different", ProposalKind.LOCAL),
            ),
        )
        val result = RecipeDetailText.firstDifferent(previous) { queue.removeFirst() }
        assertEquals("different", result.rationale)
    }

    @Test
    fun firstDifferentTreatsOtherVersionAsDifferent() {
        val previous = Proposal("v1", listOf(1.0), "", ProposalKind.LOCAL)
        var calls = 0
        val result = RecipeDetailText.firstDifferent(previous) {
            calls++
            Proposal("v2", listOf(1.0), "", ProposalKind.LOCAL)
        }
        assertEquals(1, calls)
        assertEquals("v2", result.versionId)
    }

    @Test
    fun firstDifferentGivesUpAfterMaxAttempts() {
        val same = Proposal("v1", listOf(1.0), "", ProposalKind.LOCAL)
        var calls = 0
        val result = RecipeDetailText.firstDifferent(same, maxAttempts = 5) {
            calls++
            same
        }
        assertEquals(5, calls)
        assertSame(same, result)
    }

    @Test
    fun classicalAnotherSuggestionAlwaysDiffers() {
        // A single small tweakable value makes repeats likely without the re-roll.
        val tiny = RecipeVersion(id = "t", recipeId = "r", name = "t", steps = listOf(Step.Text("Add 2[] eggs")))
        val details = RecipeDetails(recipe, listOf(tiny), emptyList())
        val optimizer = ClassicalOptimizer(Random(42))
        val settings = OptimizerSettings(boldness = 0.15, explorationRate = 0.25)
        var previous: Proposal? = null
        repeat(30) {
            val next = RecipeDetailText.firstDifferent(previous) { optimizer.propose(details, settings) }
            assertFalse(RecipeDetailText.sameProposal(previous, next))
            previous = next
        }
    }

    @Test
    fun changesAgainstWrittenValues() {
        assertEquals(listOf("water: 1.75 → 2 cups"), RecipeDetailText.changes(v1, listOf(2.0, 3.0, 900.0)))
        assertEquals(emptyList<String>(), RecipeDetailText.changes(v1, listOf(1.75, 3.0, 900.0)))
        assertNull(RecipeDetailText.changes(v1, listOf(1.0)))
        assertNull(RecipeDetailText.changes(null, listOf(1.75, 3.0, 900.0)))
        assertNull(RecipeDetailText.changes(v1, listOf(Double.NaN, 3.0, 900.0)))
    }

    @Test
    fun trialChangeLine() {
        assertEquals("as written", RecipeDetailText.trialChangeLine(v1, listOf(1.75, 3.0, 900.0)))
        assertEquals(
            "water: 1.75 → 2 cups; Simmer: 15 min → 12 min",
            RecipeDetailText.trialChangeLine(v1, listOf(2.0, 3.0, 720.0)),
        )
        assertEquals(RecipeDetailText.MISMATCH, RecipeDetailText.trialChangeLine(v1, listOf(2.0)))
        assertEquals(RecipeDetailText.MISMATCH, RecipeDetailText.trialChangeLine(null, listOf(2.0, 3.0, 720.0)))
    }

    @Test
    fun axisChips() {
        val axes = DefaultAxes.all + RatingAxis("crisp", "Soggy", "Too crunchy")
        val chips = RecipeDetailText.axisChips(
            axes,
            mapOf("salt" to -2.0, "moisture" to 1.0, "doneness" to 0.0, "crisp" to 1.0, "gone" to -1.0),
        )
        assertEquals(listOf("Too wet +1", "Bland +2", "Too crunchy +1", "gone −1"), chips)
        assertTrue(RecipeDetailText.axisChips(axes, emptyMap()).isEmpty())
    }

    @Test
    fun historyIsDoneTrialsNewestFirstAndCountsAbandoned() {
        val details = RecipeDetails(
            recipe,
            listOf(v1),
            listOf(
                trial("old", 6.0, finishedAt = 1_000),
                trial("aborted", null, status = TrialStatus.ABORTED),
                trial("new", 7.0, finishedAt = 5_000),
                trial("running", null, status = TrialStatus.IN_PROGRESS),
            ),
        )
        assertEquals(listOf("new", "old"), RecipeDetailText.history(details).map { it.id })
        assertEquals(1, RecipeDetailText.abandonedCount(details))
    }

    @Test
    fun canArchiveOnlyWhileAnotherVersionStaysActive() {
        val one = RecipeDetails(recipe, listOf(v1), emptyList())
        assertFalse(RecipeDetailText.canArchive(one, v1))
        val two = RecipeDetails(recipe, listOf(v1, v2), emptyList())
        assertTrue(RecipeDetailText.canArchive(two, v1))
        val otherArchived = RecipeDetails(recipe, listOf(v1, v2.copy(archived = true)), emptyList())
        assertFalse(RecipeDetailText.canArchive(otherArchived, v1))
    }

    @Test
    fun versionsNewestFirst() {
        val details = RecipeDetails(recipe, listOf(v1, v2), emptyList())
        assertEquals(listOf("v2", "v1"), RecipeDetailText.versionsNewestFirst(details).map { it.id })
    }

    @Test
    fun modeLabelsAndNotes() {
        assertEquals("Exploration", RecipeDetailText.modeLabel(TrialMode.EXPLORE))
        assertEquals("From cooking on Oct 7, 2026", RecipeDetailText.fromCookingNote("Oct 7, 2026"))
        assertNotNull(RecipeDetailText.basedOn(v1))
        assertNull(RecipeDetailText.basedOn(null))
    }
}
