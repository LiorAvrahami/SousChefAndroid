package com.lioravrahami.souschef

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.data.model.TrialStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The requirement: "it's important that this timer be reliable".
 *
 * These tests schedule a real system alarm for a wait step and assert that the alarm
 * actually starts ringing (the foreground alarm service is running and the alarm
 * notification is posted) both while the app is on screen and while it is in the
 * background behind the launcher.
 */
@RunWith(AndroidJUnit4::class)
class AlarmReliabilityTest {
    private val c get() = TestSupport.container
    private val waitSeconds = 4

    @Before
    fun setUp() {
        TestSupport.resetSessions()
    }

    @After
    fun tearDown() {
        TestSupport.resetSessions()
        TestSupport.device.pressHome()
    }

    private fun activeNotificationCount(): Int {
        val nm = TestSupport.context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.activeNotifications.size
    }

    private fun startTimedSession(name: String): String {
        val recipe = TestSupport.createRecipe(name, listOf(Step.Text("Boil 1[l] water"), Step.Wait("Steep", waitSeconds)))
        val trial = TestSupport.startSession(recipe)
        runBlocking { c.sessions.startWait(trial.id, 1, waitSeconds) }
        return trial.id
    }

    @Test
    fun exactAlarmsAreAllowed() {
        assertTrue("exact alarms must be permitted for timers to be on time", c.timerScheduler.canScheduleExact())
    }

    @Test
    fun alarmRingsWhileAppIsInForeground() {
        ActivityScenario.launch<MainActivity>(TestSupport.launchIntent()).use {
            val trialId = startTimedSession("Alarm foreground tea")
            assertFalse(c.timerScheduler.isRinging.value)
            TestSupport.waitFor(25_000, "alarm to start ringing in the foreground") { c.timerScheduler.isRinging.value }
            Screenshots.take("alarm_foreground_ringing")
            assertTrue("an alarm notification must be showing", activeNotificationCount() > 0)

            runBlocking { c.sessions.clearWait(trialId) }
            TestSupport.waitFor(5_000, "alarm to stop after clearWait") { !c.timerScheduler.isRinging.value }
            val trial = runBlocking { c.repository.getTrial(trialId) }
            assertNotNull(trial)
            assertNull("timer state must be cleared", trial!!.timerEndAt)
            assertEquals(TrialStatus.IN_PROGRESS, trial.status)
        }
    }

    @Test
    fun alarmRingsWhileAppIsInBackground() {
        val scenario = ActivityScenario.launch<MainActivity>(TestSupport.launchIntent())
        val trialId = startTimedSession("Alarm background tea")
        // Leave the app: the alarm must still fire from behind the launcher.
        TestSupport.device.pressHome()
        TestSupport.waitFor(30_000, "alarm to start ringing in the background") { c.timerScheduler.isRinging.value }
        Thread.sleep(1500)
        Screenshots.take("alarm_background_ringing")
        assertTrue("an alarm notification must be showing", activeNotificationCount() > 0)
        // It keeps ringing until someone stops it.
        Thread.sleep(3000)
        assertTrue("alarm must keep ringing until stopped", c.timerScheduler.isRinging.value)

        c.timerScheduler.stopRinging()
        TestSupport.waitFor(5_000, "alarm to stop") { !c.timerScheduler.isRinging.value }
        runBlocking { c.sessions.abort(trialId) }
        scenario.close()
    }

    @Test
    fun cancelledTimerNeverRings() {
        ActivityScenario.launch<MainActivity>(TestSupport.launchIntent()).use {
            val trialId = startTimedSession("Alarm cancelled tea")
            runBlocking { c.sessions.clearWait(trialId) }
            Thread.sleep((waitSeconds + 4) * 1000L)
            assertFalse("a cancelled timer must stay silent", c.timerScheduler.isRinging.value)
        }
    }
}
