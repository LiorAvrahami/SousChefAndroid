package com.lioravrahami.souschef.ui.rating

import com.lioravrahami.souschef.data.model.RatingAxis
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.data.model.TrialMode
import com.lioravrahami.souschef.domain.recipe.ChangeSummary
import com.lioravrahami.souschef.domain.recipe.StepParser
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pure helpers of the rating screen (snapping, captions, labels); unit-tested. */
object RatingLogic {
    const val DEFAULT_SCORE = 7.0
    const val MIN_SCORE = 0f
    const val MAX_SCORE = 10f

    /** Intermediate slider stops for 0..10 in steps of 0.5 (21 positions). */
    const val SCORE_SLIDER_STEPS = 19

    const val AXIS_MIN = -2f
    const val AXIS_MAX = 2f

    /** Intermediate slider stops for -2..+2 in whole steps (5 positions). */
    const val AXIS_SLIDER_STEPS = 3

    /** Rounds a slider position to the nearest 0.5 within 0..10. */
    fun snapScore(value: Float): Double = ((value * 2f).roundToInt() / 2.0).coerceIn(0.0, 10.0)

    /** Rounds an axis slider position to a whole number within -2..+2. */
    fun snapAxis(value: Float): Double = value.roundToInt().toDouble().coerceIn(-2.0, 2.0)

    /** "7", "7.5". */
    fun formatScore(score: Double): String = StepParser.formatValue(score)

    /**
     * Caption under an axis slider: "Just right" at 0, otherwise the side's label with
     * "Slightly" (±1) or "Much" (±2): "Slightly too wet", "Much too dry".
     */
    fun axisCaption(axis: RatingAxis, value: Double): String {
        val v = value.roundToInt().coerceIn(-2, 2)
        if (v == 0) return "Just right"
        val label = if (v < 0) axis.lowLabel else axis.highLabel
        val intensity = if (abs(v) == 1) "Slightly" else "Much"
        return "$intensity ${label.trim().lowercaseFirst()}"
    }

    /** Human label of how a cooking was started. */
    fun modeLabel(mode: TrialMode): String = when (mode) {
        TrialMode.BEST -> "Best so far"
        TrialMode.EXPLORE -> "Exploration"
        TrialMode.AI -> "AI suggestion"
        TrialMode.AS_WRITTEN -> "As written"
    }

    /**
     * What was different in this cooking compared with the version as written, one line
     * per change ("water: 1.75 → 1.9 cups"), or a single "Cooked as written" line.
     */
    fun differences(steps: List<Step>, values: List<Double>): List<String> {
        if (values.size != StepParser.params(steps).size) {
            return listOf("The saved amounts don't match this version")
        }
        return ChangeSummary.describe(steps, null, values).ifEmpty { listOf("Cooked as written") }
    }

    /** The axis values to store: every axis of the recipe, defaulting to 0 ("just right"). */
    fun axesToSave(axes: List<RatingAxis>, chosen: Map<String, Double>): Map<String, Double> =
        axes.associate { it.id to (chosen[it.id] ?: 0.0).coerceIn(-2.0, 2.0) }

    private fun String.lowercaseFirst(): String {
        if (isEmpty()) return this
        // Keep acronyms ("MSG") as they are; only lower a capitalised word.
        if (length > 1 && this[1].isUpperCase()) return this
        return substring(0, 1).lowercase(Locale.ROOT) + substring(1)
    }
}
