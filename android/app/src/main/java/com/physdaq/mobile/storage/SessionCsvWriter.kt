package com.physdaq.mobile.storage

import com.physdaq.mobile.ble.DeviceLine
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.Writer
import java.util.Locale

/**
 * Writes one device's samples in the **logger schema** that analysis/pipeline.py
 * reads (docs/protocol.md section 4):
 *
 *     timestamp,red,ir,ax,ay,az,gx,gy,gz
 *
 * This is the fix for the desktop app's schema mismatch: `timestamp` is a plain
 * float in seconds, never an ISO-8601 string, and the PPG columns are `red` /
 * `ir`, not `ppg_red` / `ppg_ir`.
 *
 * Matches analysis/logger.py row for row:
 *  - timestamp: seconds since the *session* start, 4 decimals. The logger uses
 *    time.monotonic() - t0; here the origin is [sessionStartNanos] on the
 *    SystemClock.elapsedRealtimeNanos() timebase that DeviceLine.Sample already
 *    carries. Pass the SAME origin to every device's writer in a session so the
 *    files share one time axis.
 *  - red, ir: integers as received.
 *  - ax..gz: 3 decimals, as on the wire (m/s^2 and rad/s, not deg/s).
 *
 * Only these nine numeric columns are written. pipeline.py does
 * `pd.read_csv(path).astype(float)`, so a text column would break it, and an
 * extra column named `contact` would be silently overwritten by the pipeline's
 * own. Derived values (BPM, quaternion) belong in a separate file.
 *
 * Not thread-safe: call [append] from one coroutine/thread (collect the sample
 * flow in a single place, not from the BLE callback thread).
 */
class SessionCsvWriter(
    writer: Writer,
    private val sessionStartNanos: Long,
    private val flushEvery: Int = 250   // ~10 s of samples at 25 Hz
) : Closeable {

    /** Plain-file convenience, used by the unit tests. */
    constructor(file: File, sessionStartNanos: Long, flushEvery: Int = 250) :
            this(file.also { it.parentFile?.mkdirs() }.bufferedWriter(), sessionStartNanos, flushEvery)

    private val out: BufferedWriter = writer as? BufferedWriter ?: BufferedWriter(writer)
    private var rows = 0

    var rowCount: Int = 0
        private set

    init {
        out.write(HEADER)
        out.write("\n")
    }

    fun append(s: DeviceLine.Sample) {
        val t = (s.hostTimestampNanos - sessionStartNanos) / 1_000_000_000.0
        // Locale.ROOT matters: on a phone set to German/French/etc. the default
        // locale prints "0,213", which would add commas to a comma-separated file.
        out.write(
            String.format(
                Locale.ROOT,
                "%.4f,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f\n",
                t, s.red, s.ir, s.ax, s.ay, s.az, s.gx, s.gy, s.gz
            )
        )
        rowCount++
        if (++rows >= flushEvery) {
            out.flush()
            rows = 0
        }
    }

    override fun close() {
        out.flush()
        out.close()
    }

    companion object {
        const val HEADER = "timestamp,red,ir,ax,ay,az,gx,gy,gz"
    }
}