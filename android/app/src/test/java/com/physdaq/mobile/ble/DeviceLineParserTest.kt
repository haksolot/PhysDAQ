package com.physdaq.mobile.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLineParserTest {

    private fun parse(line: String) = DeviceLineParser.parse(line, hostTimestampNanos = 123L)

    private val nodeLine =
        "PPG red=28451 ir=29033 | IMU ax=0.213 ay=-9.706 az=0.884 gx=0.001 gy=-0.004 gz=0.002"

    private val hubLine =
        "PPG red=28451 ir=29033 | PPG1 red=11072 ir=11840 | IMU ax=0.213 ay=-9.706 az=0.884 gx=0.001 gy=-0.004 gz=0.002"

    @Test
    fun nodeSampleLineParsesEveryField() {
        val s = parse(nodeLine) as DeviceLine.Sample
        assertEquals(123L, s.hostTimestampNanos)
        assertEquals(28451, s.red)
        assertEquals(29033, s.ir)
        assertEquals(0.213, s.ax, 1e-9)
        assertEquals(-9.706, s.ay, 1e-9)
        assertEquals(0.884, s.az, 1e-9)
        assertEquals(0.001, s.gx, 1e-9)
        assertEquals(-0.004, s.gy, 1e-9)
        assertEquals(0.002, s.gz, 1e-9)
    }

    @Test
    fun hubSampleLineYieldsSensorZeroAndTheSameImu() {
        val s = parse(hubLine) as DeviceLine.Sample
        // Sensor 0, not sensor 1 (11072 / 11840).
        assertEquals(28451, s.red)
        assertEquals(29033, s.ir)
        assertEquals(-9.706, s.ay, 1e-9)
        assertEquals(0.002, s.gz, 1e-9)
    }

    @Test
    fun nodeAndHubLinesWithTheSameSensorZeroGiveIdenticalSamples() {
        assertEquals(parse(nodeLine), parse(hubLine))
    }

    @Test
    fun trailingCarriageReturnIsTolerated() {
        assertTrue(parse(nodeLine + "\r") is DeviceLine.Sample)
    }

    @Test
    fun truncatedSampleLineIsSurfacedNotDropped() {
        val r = parse("PPG red=28451 ir=2")
        assertTrue(r is DeviceLine.Unparsed)
    }

    @Test
    fun nodeIdentityLine() {
        val id = parse("ID model=node proto=2 fw=1.2.0 ppg=1 sd=0 name=PhysDAQ-FDF9") as DeviceLine.Identity
        assertEquals("node", id.model)
        assertEquals(2, id.proto)
        assertEquals("1.2.0", id.firmwareVersion)
        assertEquals(1, id.ppgCount)
        assertFalse(id.hasSd)
        assertEquals("PhysDAQ-FDF9", id.name)
    }

    @Test
    fun hubIdentityLine() {
        val id = parse("ID model=hub proto=2 fw=1.2.0 ppg=2 sd=1 name=PhysDAQ-Hub-FDF9") as DeviceLine.Identity
        assertEquals("hub", id.model)
        assertEquals(2, id.ppgCount)
        assertTrue(id.hasSd)
        assertEquals("PhysDAQ-Hub-FDF9", id.name)
    }

    @Test
    fun identityLineWithoutNameStillParses() {
        val id = parse("ID model=node proto=1 fw=1.0.0 ppg=1 sd=0") as DeviceLine.Identity
        assertEquals("", id.name)
    }

    @Test
    fun batteryLine() {
        val b = parse("Battery: 71% (3940 mV)") as DeviceLine.Battery
        assertEquals(71, b.percent)
        assertEquals(3940, b.millivolts)
    }

    @Test
    fun statusAndDiagnosticLinesAreRawNotUnparsed() {
        listOf(
            "Power: idle 4s/10s | skin: yes (dc=29050) | ble: yes (dropped 12) | usb: no | peak ~12mrad/s (thresh 100mrad/s)",
            "BLE: MTU 247 bytes (244 payload)",
            "Boot: cause=0x4 (software (fatal error or reboot)), up 0 s",
            "Hub: up=12s s0 ok n=1200 100.0Hz ovf=0 ri=0 ir=29033 sd 0 sat=0/8"
        ).forEach { assertTrue("expected Raw for: $it", parse(it) is DeviceLine.Raw) }
    }
}
