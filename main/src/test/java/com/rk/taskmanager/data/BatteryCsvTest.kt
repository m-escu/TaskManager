package com.rk.taskmanager.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class BatteryCsvTest {

    private fun sample(status: String = "Discharging") = BatterySampleEntity(
        id = 1,
        timestamp = 0L,
        capacity = 55,
        charging = false,
        status = status,
        currentUA = -214_000L,
        voltageUV = 3_928_000L,
        powerUW = 840_592L,
        tempTenthsC = 255,
        cycleCount = 312,
    )

    @Test fun headerThenOneRowPerSample() {
        val csv = BatteryCsv.build(listOf(sample(), sample()), ZoneId.of("UTC"))
        val lines = csv.trimEnd('\n').split('\n')
        assertEquals(3, lines.size)
        assertEquals(BatteryCsv.HEADER, lines[0])
    }

    @Test fun rowKeepsRawUnitsAndLocalTime() {
        assertEquals(
            "0,1970-01-01T00:00:00,55,false,Discharging,-214000,3928000,840592,255,312",
            BatteryCsv.row(sample(), ZoneId.of("UTC")),
        )
    }

    @Test fun freeTextStatusIsQuoted() {
        val row = BatteryCsv.row(sample(status = "Not \"charging\", slow"), ZoneId.of("UTC"))
        assertEquals(
            "\"Not \"\"charging\"\", slow\"",
            row.substringAfter("false,").substringBefore(",-214000"),
        )
    }

    @Test fun emptyHistoryIsHeaderOnly() {
        assertEquals(BatteryCsv.HEADER + "\n", BatteryCsv.build(emptyList()))
    }
}
