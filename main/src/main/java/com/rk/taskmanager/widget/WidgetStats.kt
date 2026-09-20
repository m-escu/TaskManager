package com.rk.taskmanager.widget

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

/**
 * Pure stat collectors for the home-screen widget.
 *
 * Everything here deliberately uses public, root-free APIs so both widget
 * modes keep working when the app is closed and no daemon is connected:
 *  - CPU: deltas of the first /proc/stat line (world-readable on Android,
 *    same technique the app already uses for per-core info)
 *  - RAM: ActivityManager.MemoryInfo
 *  - Battery: BatteryManager.BATTERY_PROPERTY_CAPACITY
 */
object WidgetStats {

    /**
     * Latest /proc/stat sample, shared by the ephemeral and live paths so a
     * warm app process never needs an artificial re-sample delay.
     */
    object CpuStore {
        @Volatile var total: Long = 0L
        @Volatile var idle: Long = 0L
        @Volatile var stampMs: Long = 0L
    }

    /**
     * Parses a "cpu  user nice system idle iowait ..." line into
     * (total, idle + iowait). Returns null for malformed input.
     */
    fun parseCpuTimes(line: String?): Pair<Long, Long>? {
        if (line == null || !line.startsWith("cpu ")) return null
        val parts = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (parts.size < 4) return null
        val total = parts.sum()
        val idle = parts[3] + if (parts.size > 4) parts[4] else 0L
        return total to idle
    }

    /**
     * Busy-window percentage between two samples; -1 when the window is
     * invalid (first observation or counter wrap).
     */
    fun usagePercent(prevTotal: Long, prevIdle: Long, nowTotal: Long, nowIdle: Long): Int {
        val dTotal = nowTotal - prevTotal
        if (prevTotal <= 0L || dTotal <= 0L) return -1
        val busy = dTotal - (nowIdle - prevIdle)
        return ((busy * 100L) / dTotal).toInt().coerceIn(0, 100)
    }

    private fun readProcStatLine(): String? = runCatching {
        File("/proc/stat").bufferedReader().use { it.readLine() }
    }.getOrNull()

    /** Reads /proc/stat, stores the sample in [CpuStore] and returns it. */
    fun sampleCpuTimes(): Pair<Long, Long>? {
        val parsed = parseCpuTimes(readProcStatLine()) ?: return null
        CpuStore.total = parsed.first
        CpuStore.idle = parsed.second
        CpuStore.stampMs = System.currentTimeMillis()
        return parsed
    }

    /**
     * CPU usage over the window since the previous sample. When there is no
     * usable previous sample (cold process or stale store), it takes one,
     * waits [settleMs] and measures over that short window instead.
     * Returns -1 on failure.
     */
    suspend fun cpuUsage(settleMs: Long = 350L): Int {
        val prevTotal = CpuStore.total
        val prevIdle = CpuStore.idle
        val age = System.currentTimeMillis() - CpuStore.stampMs

        if (prevTotal <= 0L || age !in 1..60_000L) {
            val first = sampleCpuTimes() ?: return -1
            delay(settleMs)
            val second = sampleCpuTimes() ?: return -1
            return usagePercent(first.first, first.second, second.first, second.second)
        }

        val now = sampleCpuTimes() ?: return -1
        return usagePercent(prevTotal, prevIdle, now.first, now.second)
    }

    /** Returns (used, total) RAM bytes; (0, 0) when unavailable. */
    fun readRam(context: Context): Pair<Long, Long> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0L to 0L
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val total = info.totalMem
        if (total <= 0L) return 0L to 0L
        val used = (total - info.availMem).coerceAtLeast(0L)
        return used to total
    }

    /** Battery percentage 0..100, or -1 when unavailable. */
    fun readBatteryPercent(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return -1
        val value = runCatching {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull() ?: return -1
        return if (value in 1..100) value else -1
    }

    /** Compact byte size for the narrow widget column: 4.6G / 512M / 64K. */
    fun formatCompact(bytes: Long): String = when {
        bytes >= (1L shl 30) -> String.format(Locale.US, "%.1fG", bytes / 1073741824.0)
        bytes >= (1L shl 20) -> String.format(Locale.US, "%.0fM", bytes / 1048576.0)
        else -> String.format(Locale.US, "%dK", bytes / 1024L)
    }
}
