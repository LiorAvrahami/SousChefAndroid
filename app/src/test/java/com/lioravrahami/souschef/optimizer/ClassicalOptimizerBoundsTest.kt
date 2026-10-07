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
import com.lioravrahami.souschef.domain.recipe.ParamSpec
import com.lioravrahami.souschef.domain.recipe.StepParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Proposals stay in a sane range around their starting point and are rounded to measurable steps. */
class ClassicalOptimizerBoundsTest {

    private val steps = listOf(
        Step.Text("Add 1.75[cups] water, 2[] eggs and 3[shakes] of salt"),
        Step.Wait(label = "Microwave", seconds = 900),
        Step.Text("Bake at 180[°C] for 20[min]"),
        Step.Wait(label = "Rest", seconds = 45),
    )
    private val params = StepParser.params(steps)
    private val base = StepParser.baseValues(steps)

    private val version = RecipeVersion(id = "v1", recipeId = "r", name = "V1", steps = steps, createdAt = 1L)

    private fun trial(values: List<Double>, score: Double, finishedAt: Long) = Trial(
        id = "t$finishedAt",
        recipeId = "r",
        versionId = version.id,
        values = values,
        status = TrialStatus.DONE,
        mode = TrialMode.EXPLORE,
        createdAt = finishedAt,
        finishedAt = finishedAt,
        overallScore = score,
    )

    private fun details(trials: List<Trial> = emptyList()) = RecipeDetails(
        recipe = Recipe(id = "r", name = "Soup", createdAt = 0L, updatedAt = 0L),
        versions = listOf(version),
        trials = trials,
    )

    /** How far rounding may push a value past its bound. */
    private fun slack(p: ParamSpec, value: Double): Double = when {
        p.isWait -> 5.0
        p.baseValue == Math.rint(p.baseValue) -> if (value >= 10.0) 1.0 else 0.5
        else -> 0.01
    }

    @Test
    fun boldProposalsNeverZeroAValueNorBlowItUp() {
        val d = details()
        for (boldness in listOf(0.3, 0.5, 1.0)) {
            for (rate in listOf(0.0, 1.0)) {
                for (seed in 0 until 300) {
                    val p = ClassicalOptimizer(Random(seed)).propose(d, OptimizerSettings(boldness, rate))
                    // Without trials every proposal starts from the written values.
                    params.forEach { spec ->
                        val v = p.values[spec.index]
                        val b = base[spec.index]
                        val s = slack(spec, v)
                        assertTrue("${spec.label} became $v (seed $seed, boldness $boldness)", v > 0.0)
                        assertTrue("${spec.label}: $b → $v is too low", v >= b * ClassicalOptimizer.MIN_FACTOR - s)
                        assertTrue("${spec.label}: $b → $v is too high", v <= b * ClassicalOptimizer.MAX_FACTOR + s)
                    }
                }
            }
        }
    }

    @Test
    fun boundsFollowTheCenterWhenTrialsExist() {
        val trials = listOf(
            trial(listOf(1.5, 2.0, 3.0, 840.0, 175.0, 20.0, 40.0), 6.0, 1),
            trial(listOf(2.0, 3.0, 4.0, 960.0, 185.0, 22.0, 50.0), 8.0, 2),
        )
        val centers = listOf(base) + trials.map { it.values }
        val d = details(trials)
        for (seed in 0 until 300) {
            val p = ClassicalOptimizer(Random(seed)).propose(d, OptimizerSettings(boldness = 0.5, explorationRate = 0.5))
            params.forEach { spec ->
                val v = p.values[spec.index]
                val s = slack(spec, v)
                val low = centers.minOf { it[spec.index] } * ClassicalOptimizer.MIN_FACTOR - s
                val high = centers.maxOf { it[spec.index] } * ClassicalOptimizer.MAX_FACTOR + s
                assertTrue("${spec.label} became $v (seed $seed)", v > 0.0 && v >= low && v <= high)
            }
        }
    }

    @Test
    fun wholeNumberAmountsMoveInMeasurableSteps() {
        val trials = listOf(
            trial(listOf(1.75, 2.0, 3.0, 900.0, 180.0, 20.0, 45.0), 6.0, 1),
            trial(listOf(1.9, 2.5, 3.5, 840.0, 175.0, 22.0, 40.0), 7.5, 2),
        )
        for (d in listOf(details(), details(trials))) {
            for (rate in listOf(0.0, 0.3, 1.0)) {
                for (seed in 0 until 200) {
                    val p = ClassicalOptimizer(Random(seed)).propose(d, OptimizerSettings(0.15, rate))
                    val (water, eggs, salt) = p.values
                    val oven = p.values[4]
                    val minutes = p.values[5]
                    assertEquals("water keeps 2 decimals: $water", StepParser.round(water), water, 1e-9)
                    for (x in listOf(eggs, salt)) {
                        assertEquals("half steps only: ${p.values}", x * 2, Math.rint(x * 2), 1e-9)
                    }
                    for (x in listOf(oven, minutes)) {
                        val step = if (x >= 10.0) 1.0 else 0.5
                        assertEquals("whole steps from 10 up: ${p.values}", x / step, Math.rint(x / step), 1e-9)
                    }
                }
            }
        }
    }

    @Test
    fun roundValueRespectsTheWrittenPrecision() {
        val (water, eggs) = params
        val oven = params[4]
        val microwave = params[3]
        val rest = params[6]
        assertEquals(2.5, ClassicalOptimizer.roundValue(eggs, 2.27), 0.0)
        assertEquals(2.0, ClassicalOptimizer.roundValue(eggs, 2.2), 0.0)
        assertEquals(2.0, ClassicalOptimizer.roundValue(eggs, 1.84), 0.0)
        assertEquals(1.5, ClassicalOptimizer.roundValue(eggs, 1.7), 0.0)
        assertEquals(12.0, ClassicalOptimizer.roundValue(eggs, 12.4), 0.0)
        assertEquals(183.0, ClassicalOptimizer.roundValue(oven, 182.7), 0.0)
        assertEquals(1.84, ClassicalOptimizer.roundValue(water, 1.837), 0.0)
        assertEquals(780.0, ClassicalOptimizer.roundValue(microwave, 781.2), 0.0)
        assertEquals(43.0, ClassicalOptimizer.roundValue(rest, 42.6), 0.0)
        assertEquals(0.0, ClassicalOptimizer.roundValue(eggs, Double.NaN), 0.0)
    }

    @Test
    fun aRepeatOfAWholeNumberAmountStepsToTheNextHalf() {
        val eggSteps = listOf(Step.Text("Add 2[] eggs"))
        val v = version.copy(steps = eggSteps)
        val trials = listOf(2.0, 1.5, 2.5).mapIndexed { i, x ->
            trial(listOf(x), 5.0, i.toLong() + 1).copy(versionId = v.id)
        }
        val d = RecipeDetails(
            recipe = Recipe(id = "r", name = "Eggs", createdAt = 0L, updatedAt = 0L),
            versions = listOf(v),
            trials = trials,
        )
        for (seed in 0 until 60) {
            val p = ClassicalOptimizer(Random(seed)).propose(d, OptimizerSettings(boldness = 0.01, explorationRate = 0.0))
            val x = p.values[0]
            assertTrue("seed $seed proposed a tried value $x", x !in listOf(2.0, 1.5, 2.5))
            assertEquals("half steps only: $x", x * 2, Math.rint(x * 2), 1e-9)
            assertTrue("$x stays within bounds", x > 0.0 && x <= 2.5 * ClassicalOptimizer.MAX_FACTOR + 0.5)
        }
    }
}
