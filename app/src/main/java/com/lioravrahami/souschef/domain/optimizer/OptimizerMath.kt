package com.lioravrahami.souschef.domain.optimizer

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** Small numeric helpers used by [ClassicalOptimizer]. Pure Kotlin, deterministic for a given [Random]. */
internal object OptimizerMath {

    /**
     * Softmax weights of [scores] at [temperature] (in score units). Larger scores get
     * exponentially more weight; the result sums to 1. Empty input gives an empty list.
     */
    fun softmax(scores: List<Double>, temperature: Double): List<Double> {
        if (scores.isEmpty()) return emptyList()
        val t = if (temperature > 0.0) temperature else 1.0
        val max = scores.max()
        val exps = scores.map { exp((it - max) / t) }
        val sum = exps.sum()
        return exps.map { it / sum }
    }

    /** Index drawn from the discrete distribution [weights] (which need not be normalized). */
    fun pickWeighted(weights: List<Double>, random: Random): Int {
        require(weights.isNotEmpty()) { "Nothing to pick from" }
        val total = weights.sum()
        if (total <= 0.0 || total.isNaN()) return random.nextInt(weights.size)
        var r = random.nextDouble() * total
        weights.forEachIndexed { i, w ->
            r -= w
            if (r < 0.0) return i
        }
        return weights.lastIndex
    }

    /** A standard normal sample (Box–Muller). */
    fun gaussian(random: Random): Double {
        // nextDouble() is in [0, 1); 1 - u is in (0, 1] so the logarithm stays finite.
        val u1 = 1.0 - random.nextDouble()
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
    }

    /**
     * Ridge regression of [y] on the rows of [x] with an unpenalized intercept:
     * both are mean-centered, then `(XᵀX + λI) β = Xᵀy` is solved. Returns the slope
     * vector β (one entry per column), or null when the system cannot be solved.
     */
    fun ridge(x: List<DoubleArray>, y: List<Double>, lambda: Double): DoubleArray? {
        val n = x.size
        if (n == 0 || n != y.size) return null
        val p = x[0].size
        if (p == 0 || x.any { it.size != p }) return null
        val xMean = DoubleArray(p) { j -> x.sumOf { it[j] } / n }
        val yMean = y.sum() / n
        val a = Array(p) { DoubleArray(p) }
        val b = DoubleArray(p)
        for (r in 0 until n) {
            val yc = y[r] - yMean
            for (i in 0 until p) {
                val xi = x[r][i] - xMean[i]
                b[i] += xi * yc
                for (j in 0 until p) a[i][j] += xi * (x[r][j] - xMean[j])
            }
        }
        for (i in 0 until p) a[i][i] += lambda
        return solve(a, b)
    }

    /** Solves `a · v = b` by Gaussian elimination with partial pivoting; null if singular. */
    fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        if (a.size != n || a.any { it.size != n }) return null
        val m = Array(n) { a[it].copyOf() }
        val v = b.copyOf()
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
            if (abs(m[pivot][col]) < 1e-12) return null
            if (pivot != col) {
                val rowTmp = m[pivot]
                m[pivot] = m[col]
                m[col] = rowTmp
                val valueTmp = v[pivot]
                v[pivot] = v[col]
                v[col] = valueTmp
            }
            for (r in col + 1 until n) {
                val f = m[r][col] / m[col][col]
                if (f == 0.0) continue
                for (c in col until n) m[r][c] -= f * m[col][c]
                v[r] -= f * v[col]
            }
        }
        val out = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = v[r]
            for (c in r + 1 until n) s -= m[r][c] * out[c]
            out[r] = s / m[r][r]
        }
        return if (out.all { it.isFinite() }) out else null
    }
}
