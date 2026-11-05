package com.physdaq.mobile

import android.content.Context
import com.physdaq.mobile.ble.BleConnectionState
import com.physdaq.mobile.ble.DeviceLine
import com.physdaq.mobile.ble.PhysDaqBleClient
import com.physdaq.mobile.ble.StreamStats
import com.physdaq.mobile.ble.label
import com.physdaq.mobile.dsp.HeartRateEstimator
import com.physdaq.mobile.dsp.MadgwickImu
import com.physdaq.mobile.dsp.Tilt
import com.physdaq.mobile.nodes.NodeChecks
import com.physdaq.mobile.dsp.PpgDisplayFilter
import com.physdaq.mobile.ui.SignalBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** What the screen shows about the wearer: worn or not, and the smoothed heart rate if there is one. */
data class HeartRateState(val contact: Boolean = false, val bpm: Double? = null)

/**
 * One connected device and everything derived from its stream. Each device has
 * its own connection, rate statistics, heart-rate estimator, display filter and
 * chart buffers, so devices never share signal-processing state.
 *
 * Created by [AcquisitionRuntime.connect]; main thread only.
 *
 * @param log where to send log lines; each is prefixed with this device's name.
 */
class DeviceSession(
    context: Context,
    val address: String,
    private val scannedName: String?,
    scope: CoroutineScope,
    private val log: (String) -> Unit
) {
    val client = PhysDaqBleClient(context)
    val stats = StreamStats()

    // About 10 s of each trace at the measured ~24 Hz.
    val ppgFiltered = SignalBuffer(CHART_SAMPLES)
    val irRaw = SignalBuffer(CHART_SAMPLES)

    private val estimator = HeartRateEstimator()
    private val displayFilter = PpgDisplayFilter()
    private val imu = MadgwickImu()

    /**
     * Latest orientation. A plain property, not a flow: it changes 25 times a second,
     * so the screen polls it (see TiltIndicator) instead of recomposing per sample.
     * Main thread only.
     */
    var tilt: Tilt = Tilt.FLAT
        private set

    private val _heartRate = MutableStateFlow(HeartRateState())
    val heartRate: StateFlow<HeartRateState> = _heartRate

    private val _identity = MutableStateFlow<DeviceLine.Identity?>(null)
    val identity: StateFlow<DeviceLine.Identity?> = _identity

    private val _battery = MutableStateFlow<DeviceLine.Battery?>(null)
    val battery: StateFlow<DeviceLine.Battery?> = _battery

    /** True after a link loss that looks like the node fell asleep; cleared when it reconnects. */
    private val _maybeAsleep = MutableStateFlow(false)
    val maybeAsleep: StateFlow<Boolean> = _maybeAsleep

    private var sampleCounter = 0
    private val jobs = mutableListOf<Job>()

    /** The best name known so far: the device's own ID line, else the scan name, else the address. */
    val label: String get() = _identity.value?.name?.takeIf { it.isNotBlank() } ?: scannedName ?: address

    init {
        jobs += scope.launch { client.lines.collect { handleLine(it) } }
        jobs += scope.launch { client.connectionState.collect { onState(it) } }
        jobs += scope.launch {
            client.notice.filterNotNull().collect {
                say("!! $it")
                if (NodeChecks.looksAsleep(it) && !_maybeAsleep.value) {
                    _maybeAsleep.value = true
                    say(NodeChecks.SLEEP_HINT)
                }
            }
        }
    }

    fun connect() = client.connect(address)

    /** Disconnects for good and stops watching this device's streams. */
    fun close() {
        jobs.forEach { it.cancel() }
        client.disconnect()
    }

    private fun say(text: String) = log("[$label] $text")

    private fun onState(state: BleConnectionState) {
        say("Link: ${state.label()}")
        if (state is BleConnectionState.Connected) {
            _maybeAsleep.value = false
            // A fresh link means a gap in the data; do not carry a stale window across it.
            estimator.reset()
            _heartRate.value = HeartRateState()
            displayFilter.reset()
            imu.reset()
            tilt = Tilt.FLAT
            ppgFiltered.clear()
            irRaw.clear()
        }
        if (state is BleConnectionState.Disconnected) {
            _identity.value = null
            _battery.value = null
        }
    }

    private fun handleLine(line: DeviceLine) {
        when (line) {
            is DeviceLine.Identity -> {
                _identity.value = line
                say(
                    "ID: model=${line.model} fw=${line.firmwareVersion} ppg=${line.ppgCount} name=${line.name}" +
                        if (line.ppgCount > 1) "  [using sensor 0 only]" else ""
                )
            }
            is DeviceLine.Sample -> {
                stats.record(line.hostTimestampNanos)
                val hr = estimator.update(line.hostTimestampNanos, line.ir.toDouble())
                _heartRate.value = HeartRateState(hr.contact, hr.bpm?.let { Math.round(it).toDouble() })
                tilt = imu.update(line.hostTimestampNanos, line.ax, line.ay, line.az, line.gx, line.gy, line.gz)

                // Once a second, tell the display filter the rate that is actually arriving.
                if (sampleCounter % RATE_UPDATE_EVERY == 0) {
                    val rate = stats.snapshot().rateHz
                    if (rate in 10.0..50.0) displayFilter.setSampleRate(rate)
                }
                ppgFiltered.push(displayFilter.process(line.ir.toDouble()).toFloat())
                irRaw.push(line.ir.toFloat())

                // The charts show the signal, so the log only needs an occasional sample.
                if (++sampleCounter % SAMPLE_LOG_EVERY == 0) {
                    say("Sample red=${line.red} ir=${line.ir} ax=${line.ax} ay=${line.ay} az=${line.az}")
                }
            }
            is DeviceLine.Battery -> {
                _battery.value = line
                say("Battery: ${line.percent}% (${line.millivolts} mV)")
            }
            is DeviceLine.Raw -> say("Raw: ${line.text}")
            is DeviceLine.Unparsed -> say("!! Unparsed: ${line.text} (${line.reason})")
        }
    }

    private companion object {
        const val CHART_SAMPLES = 250
        const val RATE_UPDATE_EVERY = 25
        const val SAMPLE_LOG_EVERY = 25
    }
}
