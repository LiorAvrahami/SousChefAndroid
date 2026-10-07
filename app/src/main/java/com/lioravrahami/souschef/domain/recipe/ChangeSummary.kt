package com.lioravrahami.souschef.domain.recipe

import com.lioravrahami.souschef.data.model.Step
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Human-readable descriptions of how one set of parameter values differs from another.
 * Used wherever the app tells the cook "this time, do X differently": the optimizer's
 * rationale, the proposal sheet, the cooking pages, the rating screen and the history.
 */
object ChangeSummary {
    private const val EPSILON = 1e-9
    const val ARROW = "→"

    /** One parameter whose value differs. */
    data class Change(val param: ParamSpec, val from: Double, val to: Double) {
        /** "1.75 cups" / "15 min" */
        val fromText: String get() = valueText(param, from)
        val toText: String get() = valueText(param, to)

        /**
         * "water: 1.75 → 1.9 cups", "Microwave: 15 min → 13 min", "20 → 22 min" (no name),
         * "eggs: 2 → 3" (no unit).
         */
        val text: String
            get() {
                val prefix = when {
                    param.name.isNotBlank() -> param.name + ": "
                    param.isWait -> "wait: "
                    param.unit.isBlank() -> "amount: "
                    else -> ""
                }
                return if (param.isWait) {
                    "$prefix$fromText $ARROW $toText"
                } else {
                    val unit = if (param.unit.isBlank()) "" else " " + param.unit
                    prefix + StepParser.formatValue(from) + " " + ARROW + " " + StepParser.formatValue(to) + unit
                }
            }
    }

    /** A single value with its unit: "1.75 cups", "15 min", "3". */
    fun valueText(param: ParamSpec, value: Double): String = when {
        param.isWait -> StepParser.formatDuration(value.roundToInt())
        param.unit.isBlank() -> StepParser.formatValue(value)
        else -> StepParser.formatValue(value) + " " + param.unit
    }

    /**
     * The parameters of [steps] whose value in [to] differs from [from]
     * (`from == null` compares against the values written in the steps).
     * Returns an empty list when [to] does not match the steps' parameter count.
     */
    fun changes(steps: List<Step>, from: List<Double>?, to: List<Double>): List<Change> {
        val params = StepParser.params(steps)
        if (to.size != params.size) return emptyList()
        return params.mapNotNull { p ->
            val a = from?.getOrNull(p.index) ?: p.baseValue
            val b = to[p.index]
            if (differs(p, a, b)) Change(p, a, b) else null
        }
    }

    /** One line per changed parameter, see [Change.text]. */
    fun describe(steps: List<Step>, from: List<Double>?, to: List<Double>): List<String> =
        changes(steps, from, to).map { it.text }

    /** All changes on one line joined by "; ", or [unchanged] when nothing differs. */
    fun oneLine(steps: List<Step>, from: List<Double>?, to: List<Double>, unchanged: String = "as written"): String =
        describe(steps, from, to).ifEmpty { listOf(unchanged) }.joinToString("; ")

    /** Indices at which two value vectors differ (vectors of different sizes differ everywhere). */
    fun changedIndices(from: List<Double>, to: List<Double>): List<Int> {
        if (from.size != to.size) return to.indices.toList()
        return to.indices.filter { abs(from[it] - to[it]) > EPSILON }
    }

    private fun differs(param: ParamSpec, a: Double, b: Double): Boolean =
        if (param.isWait) a.roundToInt() != b.roundToInt() else abs(a - b) > EPSILON
}
