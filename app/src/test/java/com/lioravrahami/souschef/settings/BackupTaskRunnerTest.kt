package com.lioravrahami.souschef.settings

import com.lioravrahami.souschef.ui.settings.BackupTaskRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackupTaskRunnerTest {

    @Test
    fun runsTaskAndKeepsResultUntilDismissed() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val runner = BackupTaskRunner(scope)

        assertTrue(runner.run("Export") { "Backup saved" to "ok" })
        assertEquals("Export", runner.state.value.busyLabel)

        advanceUntilIdle()
        val state = runner.state.value
        assertNull(state.busyLabel)
        assertEquals("Backup saved" to "ok", state.result)
        assertEquals(1, state.completed)

        runner.dismissResult()
        assertNull(runner.state.value.result)
        assertEquals(1, runner.state.value.completed)
        scope.cancel()
    }

    @Test
    fun refusesSecondTaskWhileBusy() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val runner = BackupTaskRunner(scope)
        val gate = CompletableDeferred<Unit>()

        assertTrue(runner.run("Import") { gate.await(); "Import finished" to "done" })
        advanceUntilIdle()
        assertEquals("Import", runner.state.value.busyLabel)
        assertFalse(runner.run("Export") { "Backup saved" to "ok" })

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("Import finished" to "done", runner.state.value.result)
        assertTrue(runner.run("Export") { "Backup saved" to "ok" })
        advanceUntilIdle()
        assertEquals(2, runner.state.value.completed)
        scope.cancel()
    }

    @Test
    fun failureBecomesMessage() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val runner = BackupTaskRunner(scope)

        runner.run("Import") { throw IllegalArgumentException("This file is not a Sous Chef backup.") }
        advanceUntilIdle()
        assertNull(runner.state.value.busyLabel)
        assertEquals("Import failed" to "This file is not a Sous Chef backup.", runner.state.value.result)
        scope.cancel()
    }

    @Test
    fun cancelledTaskStillClearsBusyAndReports() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val runner = BackupTaskRunner(scope)

        runner.run("Import") { CompletableDeferred<Unit>().await(); "never" to "never" }
        advanceUntilIdle()
        scope.cancel()
        advanceUntilIdle()
        val state = runner.state.value
        assertNull(state.busyLabel)
        assertEquals("Import interrupted", state.result?.first)
    }
}
