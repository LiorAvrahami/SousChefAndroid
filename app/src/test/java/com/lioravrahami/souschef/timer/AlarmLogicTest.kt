package com.lioravrahami.souschef.timer

import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.data.model.TrialStatus
import com.lioravrahami.souschef.domain.timer.AlarmAction
import com.lioravrahami.souschef.domain.timer.AlarmLogic
import com.lioravrahami.souschef.domain.timer.AlarmMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlarmLogicTest {
    private val now = 1_700_000_000_000L

    // ------------------------------------------------------------------ decide

    @Test
    fun `missing trial is ignored`() {
        assertEquals(AlarmAction.IGNORE, AlarmLogic.decide(null, now, now, null))
    }

    @Test
    fun `finished or aborted trials are ignored`() {
        assertEquals(AlarmAction.IGNORE, AlarmLogic.decide(TrialStatus.DONE, now, now, null))
        assertEquals(AlarmAction.IGNORE, AlarmLogic.decide(TrialStatus.ABORTED, now, now, null))
    }

    @Test
    fun `cleared timer is ignored`() {
        assertEquals(AlarmAction.IGNORE, AlarmLogic.decide(TrialStatus.IN_PROGRESS, null, now, null))
    }

    @Test
    fun `timer that is due fires`() {
        assertEquals(AlarmAction.FIRE, AlarmLogic.decide(TrialStatus.IN_PROGRESS, now, now, null))
        assertEquals(AlarmAction.FIRE, AlarmLogic.decide(TrialStatus.IN_PROGRESS, now - 5 * 60_000L, now, null))
    }

    @Test
    fun `alarm slightly early still fires`() {
        assertEquals(AlarmAction.FIRE, AlarmLogic.decide(TrialStatus.IN_PROGRESS, now + 1_500L, now, null))
    }

    @Test
    fun `timer ending in the future is rescheduled`() {
        assertEquals(AlarmAction.RESCHEDULE, AlarmLogic.decide(TrialStatus.IN_PROGRESS, now + 60_000L, now, null))
    }

    @Test
    fun `alarm that already rang is not rung again`() {
        assertEquals(AlarmAction.IGNORE, AlarmLogic.decide(TrialStatus.IN_PROGRESS, now - 1_000L, now, now - 1_000L))
    }

    @Test
    fun `a new timer after an earlier fired one still fires`() {
        assertEquals(AlarmAction.FIRE, AlarmLogic.decide(TrialStatus.IN_PROGRESS, now - 1_000L, now, now - 600_000L))
    }

    @Test
    fun `timer that ended hours ago is reported as missed`() {
        val endAt = now - AlarmLogic.STALE_AFTER_MS - 1
        assertEquals(AlarmAction.MISSED, AlarmLogic.decide(TrialStatus.IN_PROGRESS, endAt, now, null))
    }

    // ------------------------------------------------------------------ message

    private val steps = listOf(
        Step.Text("Mix 500[g] flour with 300[ml] water"),
        Step.Wait(label = "Let the dough rise", seconds = 2700),
        Step.Wait(label = "  ", seconds = 90),
    )

    @Test
    fun `message uses the step label and the actual timer duration`() {
        val msg = AlarmLogic.message("Bread", steps, listOf(500.0, 300.0, 2700.0, 90.0), 1, now - 600_000L, now)
        assertEquals(AlarmMessage("Time's up!", "Let the dough rise — 10 min is over", "Bread"), msg)
    }

    @Test
    fun `message falls back to the trial value of the wait step`() {
        val msg = AlarmLogic.message("Bread", steps, listOf(500.0, 300.0, 3000.0, 90.0), 1, null, now)
        assertEquals("Let the dough rise — 50 min is over", msg.text)
    }

    @Test
    fun `blank label becomes Wait`() {
        val msg = AlarmLogic.message("Bread", steps, null, 2, null, null)
        assertEquals("Wait — 1 min 30 s is over", msg.text)
    }

    @Test
    fun `unknown steps and blank recipe name still give a message`() {
        val msg = AlarmLogic.message("  ", null, null, 3, null, null)
        assertEquals("Wait is over", msg.text)
        assertEquals("Time's up!", msg.title)
        assertNull(msg.subText)
    }

    @Test
    fun `unknown steps use the started timer duration`() {
        val msg = AlarmLogic.message(null, null, null, 0, now - 45_000L, now)
        assertEquals("Wait — 45 s is over", msg.text)
    }

    @Test
    fun `timer seconds prefer the started timer over the step`() {
        assertEquals(120, AlarmLogic.timerSeconds(steps, null, 1, now - 120_000L, now))
        assertEquals(2700, AlarmLogic.timerSeconds(steps, null, 1, null, null))
        assertNull(AlarmLogic.timerSeconds(steps, null, 0, null, null))
    }

    @Test
    fun `stopped text mentions the ringing time`() {
        val msg = AlarmMessage("Time's up!", "Wait — 5 min is over")
        assertEquals("Wait — 5 min is over. The alarm stopped after 5 min.", AlarmLogic.stoppedText(msg, 300))
    }
}
