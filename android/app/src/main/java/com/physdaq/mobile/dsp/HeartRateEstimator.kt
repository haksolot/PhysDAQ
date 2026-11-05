package com.physdaq.mobile.dsp

/**
 * Live heart rate from the IR channel, one sample at a time.
 *
 * Ported from scripts/bridge.py with the changes that BLE needs:
 *
 *  - Contact: the mean of the last [contactN] IR samples must exceed
 *    [contactMin]. Same rule and same constant as the bridge, firmware and
 *    offline pipeline (CONTACT_IR_MIN = 5000), so everyone agrees what "worn"
 *    means.
 *  - Window: the last [windowN] samples (8 s at 25 Hz). It is cleared whenever
 *    contact is lost, so the first reading needs about 8 s of steady contact
 *    (the app's docs promise this).
 *  - Sample rate: measured from the window's own timestamps, never assumed.
 *    The BLE stream runs at about 24.3 Hz, not 25 and not the bridge's 100.
 *    Arrival times are jittery (about 12 ms) but over 8 s the end-to-end rate
 *    is accurate to a fraction of a percent.
 *  - Cadence: one estimate every [updateEvery] samples (about 1 s). The bridge
 *    estimated on every sample, which at 25 Hz would be 25 DFTs a second for no
 *    benefit.
 *  - Smoothing: the reading moves at most [maxStep] BPM per estimate, and is
 *    dropped after [lostAfter] consecutive estimates with no confident peak.
 *
 * No motion cancellation, same as the bridge's live path: during vigorous
 * movement the reading can lock onto the motion rhythm.
 *
 * Pure Kotlin so it runs in plain JVM unit tests. Not thread-safe: feed it from
 * one place.
 */
class HeartRateEstimator(
    private val windowN: Int = 200,
    private val updateEvery: Int = 25,
    private val contactN: Int = 30,
    private val contactMin: Double = 5000.0,
    private val maxStep: Double = 4.0,
    private val lostAfter: Int = 5
) {
    /**
     * @property contact whether the sensor is currently worn
     * @property bpm the smoothed reading, or null if there isn't one yet
     * @property estimated true only on the samples where a new estimate was computed
     * @property raw that estimate before smoothing (null if no confident peak)
     */
    data class Result(
        val contact: Boolean,
        val bpm: Double?,
        val estimated: Boolean,
        val raw: Double?
    )

    private val contactRing = DoubleArray(contactN)
    private var contactCount = 0
    private var contactHead = 0
    private var contactSum = 0.0

    private val winT = ArrayDeque<Long>()
    private val winIr = ArrayDeque<Double>()
    private var since = 0

    private var value: Double? = null
    private var lost = 0

    /** Forget everything, e.g. after the link dropped and came back. */
    fun reset() {
        contactCount = 0
        contactHead = 0
        contactSum = 0.0
        winT.clear()
        winIr.clear()
        since = 0
        value = null
        lost = 0
    }

    /** [tNanos] is the sample's arrival time, in nanoseconds on any monotonic clock. */
    fun update(tNanos: Long, ir: Double): Result {
        if (contactCount == contactN) contactSum -= contactRing[contactHead] else contactCount++
        contactRing[contactHead] = ir
        contactSum += ir
        contactHead = (contactHead + 1) % contactN

        val contact = contactCount == contactN && contactSum / contactN > contactMin
        if (!contact) {
            winT.clear()
            winIr.clear()
            since = 0
            value = null
            lost = 0
            return Result(contact = false, bpm = null, estimated = false, raw = null)
        }

        winT.addLast(tNanos)
        winIr.addLast(ir)
        if (winIr.size > windowN) {
            winT.removeFirst()
            winIr.removeFirst()
        }
        since++

        if (winIr.size < windowN || since < updateEvery) {
            return Result(contact = true, bpm = value, estimated = false, raw = null)
        }
        since = 0

        val seconds = (winT.last() - winT.first()) / 1e9
        val raw = if (seconds > 0.0) {
            SpectralBpm.estimate(winIr.toDoubleArray(), (windowN - 1) / seconds)
        } else {
            null
        }

        if (raw == null) {
            lost++
            if (lost >= lostAfter) value = null
        } else {
            lost = 0
            val current = value
            value = if (current == null) raw else current + (raw - current).coerceIn(-maxStep, maxStep)
        }
        return Result(contact = true, bpm = value, estimated = true, raw = raw)
    }
}
