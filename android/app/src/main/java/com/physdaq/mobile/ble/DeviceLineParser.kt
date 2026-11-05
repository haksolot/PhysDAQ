package com.physdaq.mobile.ble

/**
 * Parses the ASCII lines a PhysDAQ node prints (docs/firmware.md, docs/protocol.md).
 *
 * Single-PPG node lines are read in full. A hub's sample line carries an extra
 * "| PPG1 red=... ir=..." section before the IMU section; it is accepted, and
 * only sensor 0 (the first "PPG red=... ir=...") is kept. That is exactly what
 * scripts/bridge.py and analysis/logger.py do, and it is what lets a hub stand
 * in for a node. Reading both sensors is the later hub work.
 */
sealed class DeviceLine {

    data class Identity(
        val model: String,       // "node" or "hub"
        val proto: Int,
        val firmwareVersion: String,
        val ppgCount: Int,
        val hasSd: Boolean,
        val name: String
    ) : DeviceLine()

    /**
     * One PPG + IMU sample.
     *
     * [hostTimestampNanos] is when this app decoded the line, from
     * SystemClock.elapsedRealtimeNanos() — a monotonic clock, not wall-clock
     * time. This is the value the CSV writer will use for the numeric
     * timestamp column, and later the input to multi-node offset estimation.
     */
    data class Sample(
        val hostTimestampNanos: Long,
        val red: Int,
        val ir: Int,
        val ax: Double, val ay: Double, val az: Double,
        val gx: Double, val gy: Double, val gz: Double
    ) : DeviceLine()

    data class Battery(val percent: Int, val millivolts: Int) : DeviceLine()

    /** Recognised but not yet acted on: boot banner, Power:, Boot:, Crash:, Hang:, BLE: ... lines. */
    data class Raw(val text: String) : DeviceLine()

    /** Looked like it should be structured but didn't match a known format — surfaced, not dropped. */
    data class Unparsed(val text: String, val reason: String) : DeviceLine()
}

object DeviceLineParser {

    private val idRegex = Regex(
        """^ID model=(\w+) proto=(\d+) fw=(\S+) ppg=(\d+) sd=(\d+)(?: name=(\S*))?"""
    )

    // "PPG red=<int> ir=<int> | [PPG1 red=<int> ir=<int> | ]IMU ax=<f> ay=<f> az=<f> gx=<f> gy=<f> gz=<f>"
    // The PPG1 section (hubs only) is non-capturing on purpose: sensor 1 is dropped
    // and the IMU groups stay 3..8 for both device types.
    private val sampleRegex = Regex(
        """^PPG red=(\d+) ir=(\d+) \| (?:PPG1 red=\d+ ir=\d+ \| )?IMU ax=(-?[\d.]+) ay=(-?[\d.]+) az=(-?[\d.]+) gx=(-?[\d.]+) gy=(-?[\d.]+) gz=(-?[\d.]+)"""
    )

    private val batteryRegex = Regex("""^Battery: (\d+)% \((\d+) mV\)""")

    fun parse(line: String, hostTimestampNanos: Long): DeviceLine {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return DeviceLine.Raw("")

        idRegex.find(trimmed)?.let { m ->
            val g = m.groupValues
            return DeviceLine.Identity(
                model = g[1],
                proto = g[2].toInt(),
                firmwareVersion = g[3],
                ppgCount = g[4].toInt(),
                hasSd = g[5] == "1",
                name = g[6]
            )
        }

        if (trimmed.startsWith("PPG")) {
            sampleRegex.find(trimmed)?.let { m ->
                val g = m.groupValues
                return DeviceLine.Sample(
                    hostTimestampNanos = hostTimestampNanos,
                    red = g[1].toInt(),
                    ir = g[2].toInt(),
                    ax = g[3].toDouble(), ay = g[4].toDouble(), az = g[5].toDouble(),
                    gx = g[6].toDouble(), gy = g[7].toDouble(), gz = g[8].toDouble()
                )
            }
            // Firmware invariant: no other line may start with "PPG". Getting here means
            // a malformed or truncated sample; surface it rather than dropping it silently.
            return DeviceLine.Unparsed(
                trimmed,
                "starts with PPG but didn't match the sample line format"
            )
        }

        batteryRegex.find(trimmed)?.let { m ->
            return DeviceLine.Battery(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }

        return DeviceLine.Raw(trimmed)
    }
}
