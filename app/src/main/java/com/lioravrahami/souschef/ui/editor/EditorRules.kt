package com.lioravrahami.souschef.ui.editor

import com.lioravrahami.souschef.data.model.RatingAxis
import com.lioravrahami.souschef.data.model.Step

/** Pure rules of the recipe editor: validation, quick entry and lock bookkeeping. */
object EditorRules {
    /** Leading list markers stripped by the quick entry: "- ", "* ", "• ", "1. ", "2) ", "(3) ". */
    private val BULLET = Regex("""^\s*(?:[-*•–]\s+|\d+[.)]\s+|\(\d+\)\s+)""")

    /** Default length of a newly added wait step: 5 minutes. */
    const val NEW_WAIT_SECONDS: Int = 300

    /** True when a step carries something worth keeping (text, or a wait with a time or label). */
    fun hasContent(step: Step): Boolean = when (step) {
        is Step.Text -> step.text.isNotBlank()
        is Step.Wait -> step.seconds > 0 || step.label.isNotBlank()
    }

    /**
     * Why the recipe cannot be saved yet, or null when it can: a name is required, at least one
     * step must have content, and every custom rating axis needs both ends (or none, then it is
     * dropped by [cleanAxes]).
     */
    fun validate(name: String, steps: List<Step>, customAxes: List<RatingAxis>): String? = when {
        name.isBlank() -> "Give the recipe a name."
        steps.none(::hasContent) -> "Add at least one step."
        customAxes.any { it.lowLabel.isBlank() != it.highLabel.isBlank() } ->
            "Fill in both ends of every rating axis, or remove it."
        else -> null
    }

    /** Custom axes as they are stored: labels trimmed, axes with both labels blank dropped. */
    fun cleanAxes(axes: List<RatingAxis>): List<RatingAxis> = axes
        .map { it.copy(lowLabel = it.lowLabel.trim(), highLabel = it.highLabel.trim()) }
        .filter { it.lowLabel.isNotEmpty() || it.highLabel.isNotEmpty() }

    /**
     * Quick entry: every non-blank line of [text] becomes a text step, with leading bullets
     * and list numbers ("- ", "* ", "1. ", "2) ") removed. Bracketed numbers stay as written.
     */
    fun parsePastedSteps(text: String): List<Step.Text> = text.lines()
        .map { it.replace(BULLET, "").trim() }
        .filter { it.isNotEmpty() }
        .map { Step.Text(it) }

    /** The name suggested for the next manual version of a recipe that has [existingVersions]. */
    fun nextVersionName(existingVersions: Int): String = "v${existingVersions + 1}"

    /**
     * Carries the locked parameter indices of a text step across an edit of its text.
     *
     * Parameters are compared as (value, unit) pairs. Locks on the unchanged parameters before
     * the edited region keep their index, locks after it shift with the inserted/removed
     * parameters, and locks inside the edited region keep their index while it still exists
     * there. Indices that no longer exist are dropped.
     *
     * @param old parameters before the edit (`StepParser.paramsInText`).
     * @param new parameters after the edit.
     * @param locks locked indices into [old].
     * @return locked indices into [new], sorted.
     */
    fun remapLocks(
        old: List<Pair<Double, String>>,
        new: List<Pair<Double, String>>,
        locks: Collection<Int>,
    ): List<Int> {
        var prefix = 0
        while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (
            suffix < old.size - prefix &&
            suffix < new.size - prefix &&
            old[old.size - 1 - suffix] == new[new.size - 1 - suffix]
        ) suffix++
        val shift = new.size - old.size
        val oldSuffixStart = old.size - suffix
        val newSuffixStart = new.size - suffix
        return locks.mapNotNull { i ->
            when {
                i < 0 || i >= old.size -> null
                i < prefix -> i
                i >= oldSuffixStart -> i + shift
                i < newSuffixStart -> i
                else -> null
            }
        }.distinct().sorted()
    }
}
