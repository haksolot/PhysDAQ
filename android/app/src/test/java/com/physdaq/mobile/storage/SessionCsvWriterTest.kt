package com.physdaq.mobile.storage

import com.physdaq.mobile.ble.DeviceLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Locale

class SessionCsvWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sample(tNanos: Long, red: Int = 28451, ir: Int = 29033) = DeviceLine.Sample(
        hostTimestampNanos = tNanos,
        red = red, ir = ir,
        ax = 0.213, ay = -9.706, az = 0.884,
        gx = 0.001, gy = -0.004, gz = 0.002
    )

    @Test
    fun headerIsExactlyTheLoggerSchema() {
        val f = tmp.newFile("a.csv")
        SessionCsvWriter(f, sessionStartNanos = 0L).close()
        assertEquals("timestamp,red,ir,ax,ay,az,gx,gy,gz", f.readLines().first())
    }

    @Test
    fun rowMatchesLoggerFormat() {
        val f = tmp.newFile("b.csv")
        SessionCsvWriter(f, sessionStartNanos = 1_000_000_000L).use {
            it.append(sample(tNanos = 1_040_000_000L)) // 40 ms after session start
        }
        assertEquals(
            "0.0400,28451,29033,0.213,-9.706,0.884,0.001,-0.004,0.002",
            f.readLines()[1]
        )
    }

    @Test
    fun timestampIsAPlainFloatNotAnIsoString() {
        val f = tmp.newFile("c.csv")
        SessionCsvWriter(f, 0L).use { it.append(sample(2_500_000_000L)) }
        val first = f.readLines()[1].split(",")[0]
        assertEquals(2.5, first.toDouble(), 1e-9)
        assertTrue("no ISO-8601 separators", !first.contains("T") && !first.contains(":"))
    }

    @Test
    fun twoWritersWithTheSameOriginShareOneTimeAxis() {
        val a = tmp.newFile("node1.csv")
        val b = tmp.newFile("node2.csv")
        val origin = 5_000_000_000L
        SessionCsvWriter(a, origin).use { it.append(sample(origin + 1_000_000_000L)) }
        SessionCsvWriter(b, origin).use { it.append(sample(origin + 1_000_000_000L)) }
        assertEquals(a.readLines()[1].substringBefore(","), b.readLines()[1].substringBefore(","))
    }

    @Test
    fun commaDecimalLocaleDoesNotCorruptTheFile() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)   // formats 0.213 as "0,213" by default
            val f = tmp.newFile("d.csv")
            SessionCsvWriter(f, 0L).use { it.append(sample(40_000_000L)) }
            val cols = f.readLines()[1].split(",")
            assertEquals("every row must have exactly 9 columns", 9, cols.size)
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun rowCountTracksAppends() {
        val f = tmp.newFile("e.csv")
        SessionCsvWriter(f, 0L).use { w ->
            repeat(3) { w.append(sample(it * 40_000_000L)) }
            assertEquals(3, w.rowCount)
        }
        assertEquals(4, f.readLines().size) // header + 3 rows
    }
}
