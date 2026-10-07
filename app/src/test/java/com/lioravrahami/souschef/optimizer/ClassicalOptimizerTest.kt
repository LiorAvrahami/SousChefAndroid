package com.lioravrahami.souschef.optimizer

import com.lioravrahami.souschef.data.model.Recipe
import com.lioravrahami.souschef.data.model.RecipeDetails
import com.lioravrahami.souschef.data.model.RecipeVersion
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.data.model.Trial
import com.lioravrahami.souschef.data.model.TrialMode
import com.lioravrahami.souschef.data.model.TrialStatus
import com.lioravrahami.souschef.domain.optimizer.ClassicalOptimizer
import com.lioravrahami.souschef.domain.optimizer.OptimizerSettings
import com.lioravrahami.souschef.domain.optimizer.ProposalKind
import com.lioravrahami.souschef.domain.recipe.ChangeSummary
import com.lioravrahami.souschef.domain.recipe.StepParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class ClassicalOptimizerTest {

    // ------------------------------------------------------------------ fixtures

    private val soupSteps = listOf(
        Step.Text("Add 1.75[cups] water and 3[shakes] of salt"),
        Step.Wait(label = "Microwave", seconds = 900),
        Step.Text("Bake at 180[°C] for 20[min]"),
        Step.Wait(seconds = 45),
    )

    private fun version(
        id: String = "v1",
        steps: List<Step> = soupSteps,
        createdAt: Long = 1_000L,
        archived: Boolean = false,
        name: String = "Version $id",
    ) = RecipeVersion(id = id, recipeId = "r", name = name, steps = steps, createdAt = createdAt, archived = archived)

    private fun trial(
        version: RecipeVersion,
        values: List<Double>,
        score: Double?,
        finishedAt: Long,
        status: TrialStatus = TrialStatus.DONE,
    ) = Trial(
        id = "t$finishedAt-${version.id}",
        recipeId = "r",
        versionId = version.id,
        values = values,
        status = status,
        mode = TrialMode.EXPLORE,
        createdAt = finishedAt,
        finishedAt = finishedAt,
        overallScore = score,
    )

    private fun details(versions: List<RecipeVersion>, trials: List<Trial> = emptyList()) = RecipeDetails(
        recipe = Recipe(id = "r", name = "Soup", createdAt = 0L, updatedAt = 0L),
        versions = versions,
        trials = trials,
    )

    private fun settings(boldness: Double = 0.15, explorationRate: Double = 0.0) =
        OptimizerSettings(boldness = boldness, explorationRate = explorationRate)

    private val seeds = 0 until 60

    // ------------------------------------------------------------------ guards

    @Test(expected = IllegalStateException::class)
    fun noVersionsThrows() {
        ClassicalOptimizer(Random(42)).propose(details(emptyList()), settings())
    }

    @Test(expected = IllegalStateException::class)
    fun onlyArchivedVersionsThrows() {
        ClassicalOptimizer(Random(42)).propose(details(listOf(version(archived = true))), settings())
    }

    @Test
    fun everythingLockedReturnsBaseline() {
        val v = version(
            steps = listOf(
                Step.Text("Add 1.75[cups] water and 3[shakes] of salt", locked = listOf(0, 1)),
                Step.Wait(label = "Rest", seconds = 300, locked = true),
            ),
        )
        val p = ClassicalOptimizer(Random(42)).propose(details(listOf(v)), settings(explorationRate = 1.0))
        assertEquals(ProposalKind.BASELINE, p.kind)
        assertEquals("Nothing to tweak — all values are locked", p.rationale)
        assertEquals(listOf(1.75, 3.0, 300.0), p.values)
        assertEquals("v1", p.versionId)
    }

    @Test
    fun noParametersReturnsBaseline() {
        val v = version(steps = listOf(Step.Text("Stir well"), Step.Text("Serve hot")))
        val p = ClassicalOptimizer(Random(42)).propose(details(listOf(v)), settings())
        assertEquals(ProposalKind.BASELINE, p.kind)
        assertEquals("Nothing to tweak — all values are locked", p.rationale)
        assertTrue(p.values.isEmpty())
    }

    // ------------------------------------------------------------------ constraints

    @Test
    fun locksAreRespectedValuesNonNegativeAndWaitsWholeSeconds() {
        val steps = listOf(
            Step.Text("Add 1.75[cups] water and 3[shakes] of salt", locked = listOf(1)),
            Step.Wait(label = "Rest", seconds = 63, locked = true),
            Step.Wait(label = "Microwave", seconds = 900),
            Step.Wait(label = "Steep", seconds = 40),
            Step.Text("Add 0.05[tsp] pepper"),
        )
        val v = version(steps = steps)
        val params = StepParser.params(steps)
        val base = StepParser.baseValues(steps)
        val trials = listOf(
            trial(v, listOf(1.5, 3.0, 63.0, 840.0, 35.0, 0.04), 6.0, 1),
            trial(v, listOf(1.9, 3.0, 63.0, 960.0, 45.0, 0.06), 7.5, 2),
            trial(v, listOf(1.75, 3.0, 63.0, 900.0, 50.0, 0.05), 5.0, 3),
            trial(v, listOf(2.1, 3.0, 63.0, 780.0, 30.0, 0.08), 8.0, 4),
            trial(v, listOf(1.6, 3.0, 63.0, 1020.0, 42.0, 0.03), 4.0, 5),
        )
        for (withTrials in listOf(false, true)) {
            val d = details(listOf(v), if (withTrials) trials else emptyList())
            for (seed in 0 until 200) {
                // Huge boldness to provoke negative values and wild waits.
                val p = ClassicalOptimizer(Random(seed)).propose(d, settings(boldness = 1.0, explorationRate = 0.5))
                assertEquals(params.size, p.values.size)
                assertEquals("salt is locked", base[1], p.values[1], 0.0)
                assertEquals("rest is locked", base[2], p.values[2], 0.0)
                p.values.forEach { assertTrue("non-negative: ${p.values}", it >= 0.0) }
                params.filter { it.isWait }.forEach { spec ->
                    val s = p.values[spec.index]
                    assertEquals("whole seconds: $s", s, Math.rint(s), 0.0)
                    if (s >= 60.0 && !spec.locked) assertEquals("multiple of 5 s: $s", 0.0, s % 5.0, 0.0)
                }
            }
        }
    }

    @Test
    fun withoutTrialsProposalIsLocalAndDiffersFromBase() {
        val d = details(listOf(version()))
        val base = StepParser.baseValues(soupSteps)
        for (seed in seeds) {
            val p = ClassicalOptimizer(Random(seed)).propose(d, settings())
            assertEquals(ProposalKind.LOCAL, p.kind)
            assertEquals("v1", p.versionId)
            assertTrue("differs from base (seed $seed)", ChangeSummary.changes(soupSteps, null, p.values).isNotEmpty())
            assertTrue(p.rationale, p.rationale.contains("first exploration", ignoreCase = true))
        }
    }

    @Test
    fun neverRepeatsAnAlreadyTriedVector() {
        val steps = listOf(Step.Text("Add 2[] eggs"))
        val v = version(steps = steps)
        val tried = listOf(2.0, 2.01, 1.99, 2.02, 1.98, 2.03, 1.97)
        val trials = tried.mapIndexed { i, x -> trial(v, listOf(x), 5.0, i.toLong() + 1) }
        val d = details(listOf(v), trials)
        for (seed in seeds) {
            // Tiny boldness: most raw draws round back onto a tried value.
            val p = ClassicalOptimizer(Random(seed)).propose(d, settings(boldness = 0.005))
            assertFalse("seed $seed proposed a tried value ${p.values}", tried.any { abs(it - p.values[0]) < 1e-9 })
        }
    }

    @Test
    fun sameSeedGivesSameProposal() {
        val v = version()
        val d = details(listOf(v), listOf(trial(v, listOf(1.75, 3.0, 900.0, 180.0, 20.0, 45.0), 7.0, 1)))
        val a = ClassicalOptimizer(Random(42)).propose(d, settings(explorationRate = 0.3))
        val b = ClassicalOptimizer(Random(42)).propose(d, settings(explorationRate = 0.3))
        assertEquals(a, b)
    }

    // ------------------------------------------------------------------ global vs local

    @Test
    fun explorationRateOneIsAlwaysGlobalAndZeroNever() {
        val v = version()
        val trials = listOf(
            trial(v, listOf(1.75, 3.0, 900.0, 180.0, 20.0, 45.0), 6.0, 1),
            trial(v, listOf(1.9, 3.0, 840.0, 180.0, 22.0, 45.0), 7.5, 2),
        )
        // Without any rated cooking there is nothing to jump away from: always LOCAL.
        for (seed in seeds) {
            assertEquals(
                ProposalKind.LOCAL,
                ClassicalOptimizer(Random(seed)).propose(details(listOf(v)), settings(explorationRate = 1.0)).kind,
            )
        }
        for (d in listOf(details(listOf(v), trials))) {
            for (seed in seeds) {
                assertEquals(
                    ProposalKind.GLOBAL,
                    ClassicalOptimizer(Random(seed)).propose(d, settings(explorationRate = 1.0)).kind,
                )
                assertEquals(
                    ProposalKind.LOCAL,
                    ClassicalOptimizer(Random(seed)).propose(d, settings(explorationRate = 0.0)).kind,
                )
            }
        }
    }

    @Test
    fun localGradientMovesWaterTowardTheOptimum() {
        val steps = listOf(Step.Text("Add 1.5[cups] water and 1[tsp] of salt"))
        val v = version(steps = steps)
        val waters = listOf(1.0, 1.1, 1.2, 1.3, 1.4, 1.5)
        val salts = listOf(1.0, 0.9, 1.1, 1.2, 0.8, 1.0)
        val trials = waters.indices.map { i ->
            val score = 10.0 - abs(waters[i] - 2.0) * 3.0
            trial(v, listOf(waters[i], salts[i]), score, i.toLong() + 1)
        }
        val d = details(listOf(v), trials)
        val runs = 1..20
        val moved = runs.count { seed ->
            val p = ClassicalOptimizer(Random(seed)).propose(d, settings())
            assertEquals(ProposalKind.LOCAL, p.kind)
            assertTrue(p.rationale, p.rationale.contains("score trend"))
            p.values[0] > 1.5
        }
        assertTrue("water moved up in $moved of ${runs.count()} runs", moved > runs.count() * 3 / 4)
    }

    @Test
    fun localNudgeIsCenteredOnTheBestCooking() {
        val v = version()
        val best = listOf(2.0, 4.0, 780.0, 175.0, 25.0, 45.0)
        val trials = listOf(
            trial(v, listOf(1.75, 3.0, 900.0, 180.0, 20.0, 45.0), 5.0, 1),
            trial(v, best, 8.5, 2),
        )
        val d = details(listOf(v), trials)
        for (seed in seeds) {
            val p = ClassicalOptimizer(Random(seed)).propose(d, settings(boldness = 0.1))
            assertEquals(ProposalKind.LOCAL, p.kind)
            assertTrue(p.rationale, p.rationale.startsWith("Local nudge around the best cooking (8.5): "))
            // Unchanged values keep the best cooking's value, not the written one.
            val unchanged = p.values.indices.filter { abs(p.values[it] - best[it]) < 1e-9 }
            p.values.indices.forEach { i ->
                if (i !in unchanged) assertTrue(abs(p.values[i] - best[i]) / best[i] < 0.6)
            }
        }
    }

    // ------------------------------------------------------------------ robustness

    @Test
    fun corruptTrialsAreIgnored() {
        val v = version()
        val good = listOf(
            trial(v, listOf(1.75, 3.0, 900.0, 180.0, 20.0, 45.0), 6.0, 1),
            trial(v, listOf(1.9, 3.0, 840.0, 180.0, 22.0, 45.0), 7.0, 2),
        )
        val corrupt = listOf(
            trial(v, listOf(1.0, 2.0), 10.0, 3),
            trial(v, listOf(5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0), 10.0, 4),
            trial(v, listOf(1.0, 2.0, 3.0), 9.9, 5),
            trial(v, emptyList(), 9.8, 6),
            trial(v, listOf(Double.NaN, 3.0, 900.0, 180.0, 20.0, 45.0), 9.7, 7),
            trial(version(id = "ghost"), listOf(1.0), 10.0, 8),
        )
        val d = details(listOf(v), good + corrupt)
        val optimizer = ClassicalOptimizer(Random(42))
        assertEquals(good[1].values, optimizer.best(d).values)
        for (seed in seeds) {
            for (rate in listOf(0.0, 0.5, 1.0)) {
                val p = ClassicalOptimizer(Random(seed)).propose(d, settings(explorationRate = rate))
                assertEquals(6, p.values.size)
                assertTrue(p.values.all { it.isFinite() })
                if (p.kind == ProposalKind.LOCAL) {
                    assertTrue(p.rationale, p.rationale.startsWith("Local nudge around the best cooking (7.0): "))
                } else {
                    assertTrue(
                        p.rationale,
                        listOf("rated 6.0: ", "rated 7.0: ", "recipe as written: ").any { p.rationale.contains(it) },
                    )
                }
            }
        }
    }

    @Test
    fun corruptTrialsDoNotFeedTheGradient() {
        val v = version(steps = listOf(Step.Text("Add 1.5[cups] water")))
        // Only corrupt trials: not enough valid data for a fit, so the random nudge is used.
        val corrupt = (1..6).map { trial(v, listOf(it.toDouble(), 1.0), it.toDouble(), it.toLong()) }
        val p = ClassicalOptimizer(Random(42)).propose(details(listOf(v), corrupt), settings())
        assertEquals(ProposalKind.LOCAL, p.kind)
        assertTrue(p.rationale, p.rationale.startsWith("First exploration"))
        assertEquals(1, p.values.size)
    }

    // ------------------------------------------------------------------ rationale

    @Test
    fun rationaleMentionsEveryChangedParameterWithoutTrials() {
        val d = details(listOf(version()))
        for (seed in seeds) {
            for (rate in listOf(0.0, 1.0)) {
                val p = ClassicalOptimizer(Random(seed)).propose(d, settings(explorationRate = rate))
                val lines = ChangeSummary.describe(soupSteps, null, p.values)
                assertTrue(lines.isNotEmpty())
                lines.forEach { assertTrue("'${p.rationale}' should mention '$it'", p.rationale.contains(it)) }
            }
        }
    }

    @Test
    fun rationaleDescribesChangesFromTheBestCooking() {
        val v = version()
        val best = listOf(2.0, 4.0, 780.0, 175.0, 25.0, 45.0)
        val d = details(listOf(v), listOf(trial(v, best, 8.5, 1)))
        for (seed in seeds) {
            val p = ClassicalOptimizer(Random(seed)).propose(d, settings())
            val expected = ChangeSummary.oneLine(soupSteps, best, p.values)
            assertEquals("Local nudge around the best cooking (8.5): $expected", p.rationale)
        }
    }

    @Test
    fun globalRationaleNamesTheStartingPoint() {
        val v = version()
        val d = details(listOf(v), listOf(trial(v, listOf(2.0, 4.0, 780.0, 175.0, 25.0, 45.0), 7.0, 1)))
        for (seed in seeds) {
            val p = ClassicalOptimizer(Random(seed)).propose(d, settings(explorationRate = 1.0))
            assertTrue(
                p.rationale,
                p.rationale.startsWith("Global jump from the cooking rated 7.0: ") ||
                    p.rationale.startsWith("Global jump from the recipe as written: "),
            )
        }
    }

    // ------------------------------------------------------------------ versions

    @Test
    fun betterVersionsArePickedMoreOftenButUntriedOnesAreNotStarved() {
        val a = version(id = "a", createdAt = 1)
        val b = version(id = "b", createdAt = 2)
        val c = version(id = "c", createdAt = 3)
        val base = StepParser.baseValues(soupSteps)
        val d = details(
            listOf(a, b, c),
            listOf(trial(a, base, 9.0, 1), trial(b, base, 3.0, 2)),
        )
        val picks = (0 until 400).map { ClassicalOptimizer(Random(it)).propose(d, settings()).versionId }
        val counts = picks.groupingBy { it }.eachCount()
        assertTrue(counts.toString(), (counts["a"] ?: 0) > 300)
        assertTrue(counts.toString(), (counts["c"] ?: 0) > 0)
        assertTrue(counts.toString(), (counts["c"] ?: 0) > (counts["b"] ?: 0))
    }

    @Test
    fun withoutAnyTrialsTheLatestVersionIsUsed() {
        val old = version(id = "old", createdAt = 1)
        val new = version(id = "new", createdAt = 5)
        val d = details(listOf(new, old))
        for (seed in seeds) {
            val p = ClassicalOptimizer(Random(seed)).propose(d, settings())
            assertEquals("new", p.versionId)
            assertTrue(p.rationale, p.rationale.startsWith("Version “Version new” — "))
        }
    }

    @Test
    fun archivedVersionsAreNeverProposed() {
        val archived = version(id = "old", createdAt = 1, archived = true)
        val active = version(id = "new", createdAt = 2)
        val base = StepParser.baseValues(soupSteps)
        val d = details(listOf(archived, active), listOf(trial(archived, base, 10.0, 1)))
        for (seed in seeds) {
            assertEquals("new", ClassicalOptimizer(Random(seed)).propose(d, settings(explorationRate = 0.5)).versionId)
        }
        assertEquals("new", ClassicalOptimizer(Random(1)).best(d).versionId)
    }

    @Test
    fun bestPrefersTheTopRatedTrialElseTheLatestVersion() {
        val v = version()
        assertEquals(StepParser.baseValues(soupSteps), ClassicalOptimizer().best(details(listOf(v))).values)
        val top = listOf(2.0, 4.0, 780.0, 175.0, 25.0, 45.0)
        val d = details(
            listOf(v),
            listOf(
                trial(v, StepParser.baseValues(soupSteps), 6.0, 1),
                trial(v, top, 9.0, 2),
                trial(v, listOf(1.0, 1.0, 1.0, 1.0, 1.0, 1.0), null, 3),
            ),
        )
        val best = ClassicalOptimizer().best(d)
        assertEquals(top, best.values)
        assertEquals(ProposalKind.BASELINE, best.kind)
        assertEquals("v1", best.versionId)
    }
}
