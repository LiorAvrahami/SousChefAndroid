package com.lioravrahami.souschef.timer

import com.lioravrahami.souschef.domain.timer.AlarmState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stop / schedule bookkeeping that keeps stale alarm reads from ringing. */
class AlarmStateTest {

    @Test
    fun `a fresh token is current`() {
        val token = AlarmState.token()
        assertTrue(AlarmState.isCurrent(token))
        assertFalse(AlarmState.stoppedSince(token))
    }

    @Test
    fun `a stop after the token makes it stale`() {
        val token = AlarmState.token()
        AlarmState.recordStop()
        assertFalse(AlarmState.isCurrent(token))
        assertTrue(AlarmState.stoppedSince(token))
    }

    @Test
    fun `a schedule change after the token makes it stale without counting as a stop`() {
        val token = AlarmState.token()
        AlarmState.scheduleChanges.incrementAndGet()
        assertFalse(AlarmState.isCurrent(token))
        assertFalse(AlarmState.stoppedSince(token))
    }

    @Test
    fun `process nonce is stable within the process`() {
        assertEquals(AlarmState.processNonce, AlarmState.processNonce)
    }

    @Test
    fun `settling pending service starts never goes below zero`() {
        run {
            AlarmState.pendingServiceStarts.set(0)
            AlarmState.pendingServiceStarts.incrementAndGet()
            AlarmState.pendingServiceStarts.incrementAndGet()
            assertEquals(1, AlarmState.serviceStartSettled())
            assertEquals(0, AlarmState.serviceStartSettled())
            assertEquals(0, AlarmState.serviceStartSettled())
            assertEquals(0, AlarmState.pendingServiceStarts.get())
        }
    }
}
