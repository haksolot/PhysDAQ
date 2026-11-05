package com.physdaq.mobile.storage

import android.content.Context
import android.os.SystemClock
import com.physdaq.mobile.ble.DeviceLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Date

/** One device to record: its address (identity), a label for the file name, and its sample stream. */
data class RecordTarget(val address: String, val label: String, val samples: Flow<DeviceLine>)

/** How one device's file is doing. */
data class FileStatus(val address: String, val label: String, val state: RecordingState)

/**
 * Records several devices as one take.
 *
 * Every device's file is stamped against the SAME time zero and shares one
 * timestamp stem in its name, so the files line up on one time axis:
 *
 *     2026-10-07_14-32-10_PhysDAQ_Session_wrist-left.csv
 *     2026-10-07_14-32-10_PhysDAQ_Session_chest.csv
 *
 * Only devices connected at the moment Start is called are included; connecting
 * another later does not add it (the desktop app works the same way). Each
 * device's file can be stopped on its own, e.g. when that device is
 * disconnected, without ending the others.
 *
 * Main thread only.
 */
class RecordingCoordinator(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private class Entry(val label: String, val recorder: SessionRecorder, val watcher: Job)

    private val entries = LinkedHashMap<String, Entry>()

    private val _status = MutableStateFlow<List<FileStatus>>(emptyList())
    val status: StateFlow<List<FileStatus>> = _status

    /** True while any device is still being written. */
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    /** @param sessionName what the operator called this take; it goes into every file's name. */
    fun start(targets: List<RecordTarget>, sessionName: String = "") {
        if (_active.value || targets.isEmpty()) return

        // Forget the previous take's finished files.
        entries.values.forEach { it.watcher.cancel() }
        entries.clear()

        val origin = SystemClock.elapsedRealtimeNanos()      // the shared time zero
        val stem = RecordingFileNames.stem(Date(), sessionName)

        for (t in targets) {
            val recorder = SessionRecorder(context, scope)
            val watcher = scope.launch { recorder.state.collect { publish() } }
            entries[t.address] = Entry(t.label, recorder, watcher)
            recorder.start(t.samples, origin, RecordingFileNames.forDevice(stem, t.label))
        }
        publish()
    }

    /** Ends one device's file and leaves the rest running. */
    fun stop(address: String) {
        entries[address]?.recorder?.stop()
    }

    fun stopAll() {
        entries.values.forEach { it.recorder.stop() }
    }

    private fun publish() {
        val list = entries.map { (address, e) -> FileStatus(address, e.label, e.recorder.state.value) }
        _status.value = list
        _active.value = list.any { it.state.recording }
    }
}
