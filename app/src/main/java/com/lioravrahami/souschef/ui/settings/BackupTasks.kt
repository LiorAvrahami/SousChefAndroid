package com.lioravrahami.souschef.ui.settings

import com.lioravrahami.souschef.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.WeakHashMap

/**
 * What the backup section shows about its import/export work.
 *
 * @property busyLabel name of the task that is running ("Export", "Import"), or null when idle.
 * @property result title and message of the last finished task, until the user dismisses it.
 * @property completed number of tasks that have finished; bumps whenever one ends, so
 *   derived values (such as the share preview) can be rebuilt.
 */
data class BackupTaskState(
    val busyLabel: String? = null,
    val result: Pair<String, String>? = null,
    val completed: Int = 0,
)

/**
 * Runs backup import/export in a long-lived [scope] (the app scope) and keeps their
 * progress and outcome in [state]. Rotating the phone, pressing Back or opening the
 * trash therefore neither cancels the work nor loses its result: a recreated or
 * returning Settings screen collects [state] and shows the progress or the result dialog.
 */
class BackupTaskRunner(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(BackupTaskState())

    /** Current progress and the last result. */
    val state: StateFlow<BackupTaskState> = _state.asStateFlow()

    /**
     * Starts [task] under [label] unless another task is running. The task returns the
     * title and message to show; an exception becomes a "<label> failed" message.
     *
     * @return false (and does nothing) when a task is already running.
     */
    fun run(label: String, task: suspend () -> Pair<String, String>): Boolean {
        while (true) {
            val current = _state.value
            if (current.busyLabel != null) return false
            if (_state.compareAndSet(current, current.copy(busyLabel = label, result = null))) break
        }
        scope.launch {
            var outcome: Pair<String, String>? = null
            try {
                outcome = try {
                    task()
                } catch (e: CancellationException) {
                    outcome = "$label interrupted" to "The app stopped the task before it finished. Please check your data and try again."
                    throw e
                } catch (e: Exception) {
                    "$label failed" to (e.message ?: e.toString())
                }
            } finally {
                _state.update { it.copy(busyLabel = null, result = outcome, completed = it.completed + 1) }
            }
        }
        return true
    }

    /** Hides the result of the last task. */
    fun dismissResult() {
        _state.update { it.copy(result = null) }
    }
}

private val runners = WeakHashMap<AppContainer, BackupTaskRunner>()

/** The process-wide backup task runner of this container, running in [AppContainer.appScope]. */
internal fun AppContainer.backupTasks(): BackupTaskRunner = synchronized(runners) {
    runners.getOrPut(this) { BackupTaskRunner(appScope) }
}
