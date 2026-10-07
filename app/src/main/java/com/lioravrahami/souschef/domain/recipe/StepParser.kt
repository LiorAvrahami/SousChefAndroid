package com.lioravrahami.souschef.domain.recipe

import com.lioravrahami.souschef.data.model.Step
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** One tweakable number of a recipe version. */
data class ParamSpec(
    /** Position in the version's flat parameter vector. */
    val index: Int,
    val stepIndex: Int,
    /** Position among the parameters of the same step. */
    val indexInStep: Int,
    val baseValue: Double,
    /** Unit text as written between the brackets, e.g. "cups". "s" for wait steps. */
    val unit: String,
    val locked: Boolean,
    val isWait: Boolean,
    /**
     * Short human name of what the number measures: the words right after the unit
     * ("water" in `1.75[cups] water`), or the label of a wait step. May be blank.
     */
    val name: String = "",
) {
    /** Best available display label: the name, else the unit, else a generic word. */
    val label: String
        get() = when {
            name.isNotBlank() -> name
            isWait -> "wait"
            unit.isNotBlank() -> unit
            else -> "amount"
        }
}

/**
 * Everything that knows how numbers are embedded in step text.
 *
 * Format: a number directly followed by a unit in square brackets, e.g. `1.75[cups]`,
 * `3 [shakes]`, `15[min]`. The unit may be empty: `2[]`.
 *
 * Accepted number spellings (group 1 of [PARAM_REGEX], parsed by [parseNumber]):
 * - decimals with a point or a decimal comma: `1.75`, `1,5`
 * - thousands separators: `1,000`, `12,500.5` (a comma followed by exactly three digits)
 * - fractions: `1/2`
 * - mixed numbers: `1 1/2`
 */
object StepParser {
    val PARAM_REGEX: Regex = Regex(
        """((?:\d+\s+)?\d+/\d+|\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:[.,]\d+)?)\s*\[([^\]]*)\]""",
    )

    /** All parameters of [steps], in the fixed order used by trial value vectors. */
    fun params(steps: List<Step>): List<ParamSpec> {
        val out = ArrayList<ParamSpec>()
        steps.forEachIndexed { stepIndex, step ->
            when (step) {
                is Step.Text -> PARAM_REGEX.findAll(step.text).forEachIndexed { i, m ->
                    out += ParamSpec(
                        index = out.size,
                        stepIndex = stepIndex,
                        indexInStep = i,
                        baseValue = parseNumber(m.groupValues[1]),
                        unit = m.groupValues[2].trim(),
                        locked = i in step.locked,
                        isWait = false,
                        name = nameAfter(step.text, m.range.last + 1),
                    )
                }
                is Step.Wait -> out += ParamSpec(
                    index = out.size,
                    stepIndex = stepIndex,
                    indexInStep = 0,
                    baseValue = step.seconds.toDouble(),
                    unit = "s",
                    locked = step.locked,
                    isWait = true,
                    name = step.label.trim(),
                )
            }
        }
        return out
    }

    private val CLAUSE_BREAK = Regex(
        """[,.;:()\n]|\s+(?:and|then|with|until|for|to|into|in|on|over|at|while|before|after)\s+""",
        RegexOption.IGNORE_CASE,
    )
    private val WHITESPACE = Regex("""\s+""")

    /**
     * The ingredient-like words that follow a parameter: the text after the closing bracket
     * up to the next parameter or clause break, at most three words, without a leading "of".
     * `1.75[cups] water and salt` -> "water"; `3[shakes] of salt` -> "salt".
     */
    internal fun nameAfter(text: String, from: Int): String {
        if (from >= text.length) return ""
        var rest = text.substring(from)
        PARAM_REGEX.find(rest)?.let { rest = rest.substring(0, it.range.first) }
        // Pad so that a break word at the very start or end ("... for ") is still recognised.
        rest = CLAUSE_BREAK.split(" $rest ", limit = 2)[0].trim()
        val words = WHITESPACE.split(rest).filter { it.isNotBlank() }.toMutableList()
        if (words.firstOrNull()?.equals("of", ignoreCase = true) == true) words.removeAt(0)
        return words.take(3).joinToString(" ")
    }

    fun baseValues(steps: List<Step>): List<Double> = params(steps).map { it.baseValue }

    /** Number of parameters in a text, used by the editor to show lock toggles. */
    fun countParams(text: String): Int = PARAM_REGEX.findAll(text).count()

    /** Units of the parameters in a text, in order. */
    fun paramsInText(text: String): List<Pair<Double, String>> =
        PARAM_REGEX.findAll(text).map { parseNumber(it.groupValues[1]) to it.groupValues[2].trim() }.toList()

    /** A piece of rendered step text. [paramIndex] is set for parameter pieces. */
    data class Segment(val text: String, val paramIndex: Int? = null, val changed: Boolean = false)

    /**
     * Splits a text step into plain-text and parameter segments with [values] substituted.
     * Pass `values = null` to render the version's base values.
     */
    fun segments(step: Step.Text, stepIndex: Int, params: List<ParamSpec>, values: List<Double>?): List<Segment> {
        val stepParams = params.filter { it.stepIndex == stepIndex && !it.isWait }
        val result = ArrayList<Segment>()
        var last = 0
        PARAM_REGEX.findAll(step.text).forEachIndexed { i, m ->
            if (m.range.first > last) result += Segment(step.text.substring(last, m.range.first))
            val spec = stepParams.getOrNull(i)
            val written = parseNumber(m.groupValues[1])
            val value = spec?.let { values?.getOrNull(it.index) } ?: written
            val changed = spec != null && values != null && abs(value - spec.baseValue) > 1e-9
            val unit = m.groupValues[2].trim()
            val text = if (unit.isEmpty()) formatValue(value) else formatValue(value) + " " + unit
            result += Segment(text, spec?.index, changed)
            last = m.range.last + 1
        }
        if (last < step.text.length) result += Segment(step.text.substring(last))
        return result
    }

    /** Plain-text rendering of a step with [values] substituted (null = base values). */
    fun render(step: Step, stepIndex: Int, params: List<ParamSpec>, values: List<Double>?): String = when (step) {
        is Step.Text -> segments(step, stepIndex, params, values).joinToString("") { it.text }
        is Step.Wait -> {
            val secs = waitSeconds(step, stepIndex, params, values)
            (step.label.ifBlank { "Wait" }) + " — " + formatDuration(secs)
        }
    }

    /** Effective duration of a wait step for the given [values] (null = base). */
    fun waitSeconds(step: Step.Wait, stepIndex: Int, params: List<ParamSpec>, values: List<Double>?): Int {
        val spec = params.firstOrNull { it.stepIndex == stepIndex && it.isWait }
        val v = spec?.let { values?.getOrNull(it.index) } ?: step.seconds.toDouble()
        return v.roundToInt().coerceAtLeast(0)
    }

    /** New steps with [values] written into the text, used to turn a trial into a new version. */
    fun applyValues(steps: List<Step>, values: List<Double>): List<Step> {
        val params = params(steps)
        return steps.mapIndexed { stepIndex, step ->
            when (step) {
                is Step.Text -> {
                    val stepParams = params.filter { it.stepIndex == stepIndex && !it.isWait }
                    var i = 0
                    val newText = PARAM_REGEX.replace(step.text) { m ->
                        val spec = stepParams.getOrNull(i++)
                        val v = spec?.let { values.getOrNull(it.index) } ?: parseNumber(m.groupValues[1])
                        formatValue(v) + "[" + m.groupValues[2] + "]"
                    }
                    step.copy(text = newText)
                }
                is Step.Wait -> step.copy(seconds = waitSeconds(step, stepIndex, params, values))
            }
        }
    }

    /** Rounds [value] to a sensible precision for display and storage (3 significant-ish decimals). */
    fun round(value: Double): Double {
        if (value.isNaN() || value.isInfinite()) return 0.0
        val a = abs(value)
        val decimals = when {
            a >= 100 -> 0
            a >= 10 -> 1
            else -> 2
        }
        var factor = 1.0
        repeat(decimals) { factor *= 10 }
        return Math.round(value * factor) / factor
    }

    fun formatValue(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "?"
        if (v == floor(v) && abs(v) < 1e9) return v.toLong().toString()
        return String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    }

    /** "1 h 05 min", "12 min 30 s", "45 s". */
    fun formatDuration(totalSeconds: Int): String {
        val t = totalSeconds.coerceAtLeast(0)
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return when {
            h > 0 && m == 0 && s == 0 -> "$h h"
            h > 0 -> "$h h ${m.toString().padStart(2, '0')} min" + (if (s > 0) " ${s} s" else "")
            m > 0 && s == 0 -> "$m min"
            m > 0 -> "$m min $s s"
            else -> "$s s"
        }
    }

    /** "1:05:00", "12:30", "0:45" — for countdown displays. */
    fun formatClock(totalSeconds: Int): String {
        val t = totalSeconds.coerceAtLeast(0)
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    private val MIXED_OR_FRACTION = Regex("""^(?:(\d+)\s+)?(\d+)/(\d+)$""")
    private val THOUSANDS = Regex("""^\d{1,3}(?:,\d{3})+(?:\.\d+)?$""")

    /** Parses any number spelling accepted by [PARAM_REGEX]; returns 0.0 for anything else. */
    fun parseNumber(s: String): Double {
        val t = s.trim()
        MIXED_OR_FRACTION.matchEntire(t)?.let { m ->
            val whole = m.groupValues[1].toDoubleOrNull() ?: 0.0
            val numerator = m.groupValues[2].toDoubleOrNull() ?: 0.0
            val denominator = m.groupValues[3].toDoubleOrNull() ?: 0.0
            // "1/0" is a typo, not infinity: fall back to the numerator.
            return if (denominator == 0.0) whole + numerator else whole + numerator / denominator
        }
        if (THOUSANDS.matches(t)) return t.replace(",", "").toDoubleOrNull() ?: 0.0
        return t.replace(',', '.').toDoubleOrNull() ?: 0.0
    }
}
