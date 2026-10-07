package com.lioravrahami.souschef.optimizer

import com.lioravrahami.souschef.domain.optimizer.OptimizerMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class OptimizerMathTest {

    @Test
    fun softmaxSumsToOneAndFavorsHigherScores() {
        val w = OptimizerMath.softmax(listOf(9.0, 3.0, 6.0), 1.0)
        assertEquals(1.0, w.sum(), 1e-12)
        assertTrue(w[0] > w[2] && w[2] > w[1])
        // Higher temperature flattens the distribution.
        val flat = OptimizerMath.softmax(listOf(9.0, 3.0, 6.0), 2.0)
        assertTrue(flat[1] > w[1])
        assertTrue(OptimizerMath.softmax(emptyList(), 1.0).isEmpty())
    }

    @Test
    fun pickWeightedFollowsTheWeights() {
        val random = Random(42)
        val counts = IntArray(3)
        repeat(10_000) { counts[OptimizerMath.pickWeighted(listOf(0.7, 0.2, 0.1), random)]++ }
        assertEquals(7_000.0, counts[0].toDouble(), 300.0)
        assertEquals(2_000.0, counts[1].toDouble(), 300.0)
        assertEquals(1_000.0, counts[2].toDouble(), 300.0)
    }

    @Test
    fun gaussianHasUnitVarianceAndStaysFinite() {
        val random = Random(42)
        val xs = List(20_000) { OptimizerMath.gaussian(random) }
        assertTrue(xs.all { it.isFinite() })
        val mean = xs.average()
        val variance = xs.sumOf { (it - mean) * (it - mean) } / xs.size
        assertEquals(0.0, mean, 0.03)
        assertEquals(1.0, variance, 0.05)
    }

    @Test
    fun ridgeRecoversLinearSlopesIgnoringTheIntercept() {
        // y = 5 + 2·a − 1·b, with a tiny ridge penalty.
        val rows = listOf(
            doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 0.0), doubleArrayOf(0.0, 1.0),
            doubleArrayOf(1.0, 1.0), doubleArrayOf(2.0, 1.0), doubleArrayOf(1.0, 3.0),
        )
        val y = rows.map { 5 + 2 * it[0] - it[1] }
        val beta = OptimizerMath.ridge(rows, y, 1e-9)!!
        assertEquals(2.0, beta[0], 1e-6)
        assertEquals(-1.0, beta[1], 1e-6)
    }

    @Test
    fun ridgeGivesZeroSlopeToAConstantColumn() {
        val rows = listOf(doubleArrayOf(1.0, 4.0), doubleArrayOf(2.0, 4.0), doubleArrayOf(3.0, 4.0))
        val beta = OptimizerMath.ridge(rows, listOf(1.0, 2.0, 3.0), 0.1)!!
        assertTrue(beta[0] > 0.5)
        assertEquals(0.0, beta[1], 1e-12)
    }

    @Test
    fun solveHandlesPivotingAndRejectsSingularSystems() {
        val v = OptimizerMath.solve(arrayOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(2.0, 0.0)), doubleArrayOf(3.0, 4.0))!!
        assertEquals(2.0, v[0], 1e-12)
        assertEquals(3.0, v[1], 1e-12)
        assertNull(OptimizerMath.solve(arrayOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(2.0, 4.0)), doubleArrayOf(1.0, 2.0)))
    }
}
