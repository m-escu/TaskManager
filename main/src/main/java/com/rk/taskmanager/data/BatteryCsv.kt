package com.rk.taskmanager.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * CSV export of the local battery history (Battery tab -> Export CSV).
 * Pure and framework-free so it is unit-tested on the JVM (BatteryCsvTest).
 *
 * Columns keep the raw stored units (µA, µV, µW, tenths of °C) next to an
 * ISO-8601 local time, so the file is lossless and spreadsheet-friendly.
 */
object BatteryCsv {
    const val HEADER =
        "timestamp_ms,local_time,capacity_pct,charging,status,current_ua,voltage_uv,power_uw,temp_tenths_c,cycle_count"

    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    /** RFC 4180 quoting for the one free-text column (status). */
    private fun quote(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    fun row(s: BatterySampleEntity, zone: ZoneId): String = listOf(
        s.timestamp.toString(),
        timeFormat.format(Instant.ofEpochMilli(s.timestamp).atZone(zone).toLocalDateTime().withNano(0)),
        s.capacity.toString(),
        s.charging.toString(),
        quote(s.status),
        s.currentUA.toString(),
        s.voltageUV.toString(),
        s.powerUW.toString(),
        s.tempTenthsC.toString(),
        s.cycleCount.toString(),
    ).joinToString(",")

    fun build(samples: List<BatterySampleEntity>, zone: ZoneId = ZoneId.systemDefault()): String =
        buildString {
            append(HEADER).append('\n')
            for (s in samples) append(row(s, zone)).append('\n')
        }
}
