package com.lioravrahami.souschef.domain.timer

import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.data.model.TrialStatus
import com.lioravrahami.souschef.domain.recipe.StepParser

/** What to do when an alarm broadcast arrives or the alarm is restored after a reboot / update. */
enum class AlarmAction {
    /** Nothing to do: the session is gone, the timer was cleared, or this alarm already rang. */
    IGNORE,

    /** The timer ends in the future (it was restarted, or the alarm came early): schedule it again. */
    RESCHEDULE,

    /** The timer is over: ring. */
    FIRE,

    /** The timer ended hours ago (e.g. the phone was off): post a quiet "time's up" note instead of ringing. */
    MISSED,
}

/** Texts of the alarm notification. */
data class AlarmMessage(
    val title: String,
    val text: String,
    /** Recipe name, shown as the notification's sub text; null if unknown. */
    val subText: String? = null,
)

/**
 * Pure decision and text logic of the timer alarm. No Android framework calls, so it is
 * covered by JVM unit tests.
 */
object AlarmLogic {
    const val TITLE = "Time's up!"

    /** Alarms arriving up to this much before the stored end time still ring (clock jitter). */
    const val EARLY_TOLERANCE_MS: Long = 2_000L

    /** Alarms overdue by more than this (phone was off for hours) are reported quietly instead of ringing. */
    const val STALE_AFTER_MS: Long = 3L * 60 * 60 * 1000

    /**
     * Decides what an arriving alarm should do.
     *
     * @param status status of the trial the alarm belongs to, null if the trial no longer exists
     * @param timerEndAt the trial's persisted timer end (wall clock), null if the timer was cleared
     * @param now current wall-clock time
     * @param alreadyFiredEndAt end time of the alarm that already rang for this trial, if any
     */
    fun decide(status: TrialStatus?, timerEndAt: Long?, now: Long, alreadyFiredEndAt: Long?): AlarmAction = when {
        status != TrialStatus.IN_PROGRESS || timerEndAt == null -> AlarmAction.IGNORE
        timerEndAt == alreadyFiredEndAt -> AlarmAction.IGNORE
        timerEndAt - now > EARLY_TOLERANCE_MS -> AlarmAction.RESCHEDULE
        now - timerEndAt > STALE_AFTER_MS -> AlarmAction.MISSED
        else -> AlarmAction.FIRE
    }

    /**
     * Duration of the timer that just ended, in seconds: the actually started timer
     * ([timerStartedAt]..[timerEndAt]) when known, else the wait step's value for the trial's
     * [values]. Null when neither is available.
     */
    fun timerSeconds(
        steps: List<Step>?,
        values: List<Double>?,
        stepIndex: Int,
        timerStartedAt: Long?,
        timerEndAt: Long?,
    ): Int? {
        if (timerStartedAt != null && timerEndAt != null && timerEndAt > timerStartedAt) {
            return ((timerEndAt - timerStartedAt + 500) / 1000).toInt()
        }
        if (steps == null) return null
        val wait = steps.getOrNull(stepIndex) as? Step.Wait ?: return null
        return StepParser.waitSeconds(wait, stepIndex, StepParser.params(steps), values).takeIf { it > 0 }
    }

    /**
     * Builds the alarm texts: "Time's up!" / "Let the dough rise — 45 min is over" / recipe name.
     * A blank or missing step label becomes "Wait".
     */
    fun message(
        recipeName: String?,
        steps: List<Step>?,
        values: List<Double>?,
        stepIndex: Int,
        timerStartedAt: Long?,
        timerEndAt: Long?,
    ): AlarmMessage {
        val wait = steps?.getOrNull(stepIndex) as? Step.Wait
        val label = wait?.label?.trim()?.takeIf { it.isNotEmpty() } ?: "Wait"
        val seconds = timerSeconds(steps, values, stepIndex, timerStartedAt, timerEndAt)
        val text = if (seconds != null && seconds > 0) {
            "$label — ${StepParser.formatDuration(seconds)} is over"
        } else {
            "$label is over"
        }
        return AlarmMessage(TITLE, text, recipeName?.trim()?.takeIf { it.isNotEmpty() })
    }

    /** Text of the quiet note posted when the alarm stopped by itself after [ringSeconds]. */
    fun stoppedText(message: AlarmMessage, ringSeconds: Int): String =
        "${message.text}. The alarm stopped after ${StepParser.formatDuration(ringSeconds)}."

    /** Text of the quiet note posted for a timer that ended hours ago, e.g. while the phone was off. */
    fun missedText(message: AlarmMessage): String = "${message.text} (it ended while the phone was off)."
}
