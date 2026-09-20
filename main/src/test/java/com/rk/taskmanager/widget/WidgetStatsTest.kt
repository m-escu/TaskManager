package com.rk.taskmanager.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression net for the battery current sign/unit logic — historically the
 * single buggiest area of the fork (fork5..fork16 each patched some aspect
 * of the vendor-dependent `current_now` conventions).
 *
 * Convention after normalization: + = charging, - = discharging,
 * -1 = unknown sentinel, 0 = passthrough. The daemon passes the vendor-raw
 * sign through UNCHANGED (some vendors read NEGATIVE while charging — the
 * user's OPPO does), so the charging flag is the only source of truth for
 * direction.
 */
class WidgetStatsTest {

    // --- normalizeCurrentUA: the vendor-sign normalizer ------------------

    @Test fun negativeVendorChargingReadsPositive() {
        // OPPO-style fuel gauge: current_now goes NEGATIVE while charging
        // at 1.5 A — must render as +1.5 A, never N/A (the fork13 bug).
        assertEquals(1_500_000L, WidgetStats.normalizeCurrentUA(-1_500_000L, charging = true))
    }

    @Test fun positiveVendorDischargingReadsNegative() {
        // Stock-style: current_now positive while discharging at 850 mA.
        assertEquals(-850_000L, WidgetStats.normalizeCurrentUA(850_000L, charging = false))
    }

    @Test fun chargingFlagOverrulesVendorSignBothWays() {
        assertEquals(-1_200_000L, WidgetStats.normalizeCurrentUA(-1_200_000L, charging = false))
        assertEquals(2_000_000L, WidgetStats.normalizeCurrentUA(2_000_000L, charging = true))
    }

    @Test fun unknownSentinelPassesThrough() {
        // -1 is the only "unknown" sentinel (the fork16 history-chart bug
        // treated ANY negative raw as unknown, flattening OPPO charging
        // samples to zero).
        assertEquals(-1L, WidgetStats.normalizeCurrentUA(-1L, charging = true))
        assertEquals(-1L, WidgetStats.normalizeCurrentUA(-1L, charging = false))
    }

    @Test fun zeroPassesThrough() {
        assertEquals(0L, WidgetStats.normalizeCurrentUA(0L, charging = false))
        assertEquals(0L, WidgetStats.normalizeCurrentUA(0L, charging = true))
    }

    // --- formatCurrent: stats row + widget rendering ----------------------

    @Test fun formatCurrentUnknownIsDash() {
        assertEquals("-", WidgetStats.formatCurrent(-1L))
    }

    @Test fun formatCurrentMilliAmpRange() {
        assertEquals("0mA", WidgetStats.formatCurrent(0L))
        assertEquals("+850mA", WidgetStats.formatCurrent(850_000L))
        assertEquals("-850mA", WidgetStats.formatCurrent(-850_000L))
    }

    @Test fun formatCurrentAmpRange() {
        assertEquals("+1.50A", WidgetStats.formatCurrent(1_500_000L))
        assertEquals("-1.23A", WidgetStats.formatCurrent(-1_230_000L))
    }

    // --- formatCompact: widget RAM column (shrink-not-clip layout) -------

    @Test fun formatCompactRanges() {
        assertEquals("512K", WidgetStats.formatCompact(512L * 1024))
        assertEquals("64M", WidgetStats.formatCompact(64L * 1024 * 1024))
        assertEquals("12.3G", WidgetStats.formatCompact((12.3 * 1024 * 1024 * 1024).toLong()))
    }

    // --- formatTemperature: widget temp column ----------------------------

    @Test fun formatTemperature() {
        assertEquals("-", WidgetStats.formatTemperature(-1))
        assertEquals("32.4°C", WidgetStats.formatTemperature(324))
        assertEquals("0.0°C", WidgetStats.formatTemperature(0))
    }
}
