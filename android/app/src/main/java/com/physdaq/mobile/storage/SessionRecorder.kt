package com.physdaq.mobile.storage

import android.content.Context
import android.os.SystemClock
import com.physdaq.mobile.ble.DeviceLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Date

data class RecordingState(
    val recording: Boolean = false,
    val rows: Int = 0,
    val location: String? = null,
    val error: String? = null
)

/**
 * Records one device's samples to a CSV in the logger schema.
 *
 * All writing happens on Dispatchers.IO from a single collecting coroutine, so
 * [SessionCsvWriter] (which is not thread-safe) is only ever touched from one
 * place and the BLE callback thread never waits on the disk.
 *
 * The file is named YYYY-MM-DD_HH-MM-SS.csv, the same as analysis/logger.py's
 * output, so `make process FILE=...` works on it unchanged.
 *
 * If the BLE link drops mid-recording the file stays open and simply stops
 * growing; press Stop to close it.
 */
class SessionRecorder(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var job: Job? = null

    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state

    /**
     * @param sessionStartNanos time zero for the file's timestamp column, on the
     *   SystemClock.elapsedRealtimeNanos() clock. When several devices are
     *   recorded together they must all be given the SAME value, which is what
     *   puts their files on one time axis.
     * @param fileName name of the file to create in Download/PhysDAQ.
     */
    fun start(
        samples: Flow<DeviceLine>,
        sessionStartNanos: Long = SystemClock.elapsedRealtimeNanos(),
        fileName: String = RecordingFileNames.stamp(Date()) + ".csv"
    ) {
        if (job?.isActive == true) return
        _state.value = RecordingState(recording = true)

        job = scope.launch(Dispatchers.IO) {
            var writer: SessionCsvWriter? = null
            try {
                val target = RecordingStorage.open(context, fileName)
                _state.value = _state.value.copy(location = target.description)

                val w = SessionCsvWriter(target.writer, sessionStartNanos)
                writer = w

                samples.collect { line ->
                    if (line is DeviceLine.Sample) {
                        w.append(line)
                        if (w.rowCount % PUBLISH_EVERY == 0) {
                            _state.value = _state.value.copy(rows = w.rowCount)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: e.javaClass.simpleName)
            } finally {
                try {
                    writer?.close()
                } catch (_: Exception) {
                    // Closing a half-failed file; the error above is the useful one.
                }
                _state.value = _state.value.copy(recording = false, rows = writer?.rowCount ?: 0)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val PUBLISH_EVERY = 25   // about once a second at 25 Hz
    }
}
