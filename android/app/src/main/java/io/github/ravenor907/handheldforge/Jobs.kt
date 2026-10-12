package io.github.ravenor907.handheldforge

import io.github.ravenor907.handheldforge.core.Stage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Outcome { OK, CANCELLED, ERROR }

sealed interface JobState {
    data object Idle : JobState

    data class Running(
        val distro: String,
        val drive: String,
        val stage: Stage = Stage.LOOKUP,
        val done: Long = 0,
        val total: Long = 0,
        val stopping: Boolean = false,
    ) : JobState

    data class Finished(val outcome: Outcome, val message: String, val distro: String, val drive: String) : JobState
}

/** Process-wide job state shared by the service (writer) and the activity (reader). */
object Jobs {
    private val _state = MutableStateFlow<JobState>(JobState.Idle)
    val state: StateFlow<JobState> = _state

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    @Volatile
    var cancelRequested = false
        private set

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun begin(distro: String, drive: String) {
        cancelRequested = false
        _log.value = emptyList()
        _state.value = JobState.Running(distro, drive)
    }

    fun progress(stage: Stage, done: Long, total: Long) = _state.update {
        if (it is JobState.Running) it.copy(stage = stage, done = done, total = total) else it
    }

    fun log(msg: String) = _log.update { (it + "${clock.format(Date())}  $msg").takeLast(300) }

    fun finish(outcome: Outcome, message: String) = _state.update {
        if (it is JobState.Running) JobState.Finished(outcome, message, it.distro, it.drive) else it
    }

    fun cancel() {
        cancelRequested = true
        _state.update { if (it is JobState.Running) it.copy(stopping = true) else it }
    }

    /** Debug builds only: show a canned state so each screen can be screenshotted. */
    fun demo(state: JobState, lines: List<String>) {
        _state.value = state
        _log.value = lines
    }

    fun reset() {
        if (_state.value !is JobState.Running) _state.value = JobState.Idle
    }
}
