package com.rk.taskmanager.daemon

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for the pure helpers of [DaemonClient] (no Android
 * framework dependencies). These run in CI (`:main:testDebugUnitTest`).
 */
class DaemonClientTest {

    // ------------------------------------------------------------------ //
    // parseCaps                                                          //
    // ------------------------------------------------------------------ //

    @Test
    fun parseCaps_extractsAllEntries() {
        val hello = JSONObject(
            """{"type":"HELLO","proto":2,"version":"1.6.0-fork2",
                "caps":["kill_graceful","core_ping","battery_ping"]}""",
        )
        assertEquals(setOf("kill_graceful", "core_ping", "battery_ping"), parseCaps(hello))
    }

    @Test
    fun parseCaps_missingArrayYieldsEmpty() {
        val v1Hello = JSONObject("""{"type":"HELLO","proto":1,"version":"1.5.0"}""")
        assertEquals(emptySet<String>(), parseCaps(v1Hello))
    }

    @Test
    fun parseCaps_skipsEmptyEntries() {
        val hello = JSONObject("""{"caps":["a",""]}""")
        assertEquals(setOf("a"), parseCaps(hello))
    }

    // ------------------------------------------------------------------ //
    // buildKillRequest                                                   //
    // ------------------------------------------------------------------ //

    private fun build(
        isApp: Boolean = false,
        action: KillAction = KillAction.TERMINATE,
        graceMs: Int = 3_000,
        supportsGraceful: Boolean = true,
    ): JSONObject = buildKillRequest(
        isInstalledApp = isApp,
        packageName = "com.example.app",
        pid = 42,
        action = action,
        graceMs = graceMs,
        daemonSupportsGracefulKill = supportsGraceful,
    )

    @Test
    fun installedAppsAlwaysUseForceStop() {
        for (action in KillAction.entries) {
            val req = build(isApp = true, action = action)
            assertEquals("FORCE_STOP", req.getString("cmd"))
            assertEquals("com.example.app", req.getString("pkg"))
        }
    }

    @Test
    fun terminate_usesKillGracefulWithTimeout_whenSupported() {
        val req = build(action = KillAction.TERMINATE, graceMs = 2_500)
        assertEquals("KILL_GRACEFUL", req.getString("cmd"))
        assertEquals(42, req.getInt("pid"))
        assertEquals(2_500, req.getInt("timeoutMs"))
    }

    @Test
    fun terminate_fallsBackToPlainKill_whenCapabilityMissing() {
        val req = build(action = KillAction.TERMINATE, supportsGraceful = false)
        assertEquals("KILL", req.getString("cmd"))
        assertEquals(42, req.getInt("pid"))
    }

    @Test
    fun force_alwaysUsesPlainKill() {
        val req = build(action = KillAction.FORCE, supportsGraceful = true)
        assertEquals("KILL", req.getString("cmd"))
        assertEquals(42, req.getInt("pid"))
    }

    @Test
    fun gracePeriod_isClampedToDaemonRange() {
        assertEquals(200, build(graceMs = 10).getInt("timeoutMs"))
        assertEquals(10_000, build(graceMs = 999_999).getInt("timeoutMs"))
    }

    // ------------------------------------------------------------------ //
    // KillAction                                                         //
    // ------------------------------------------------------------------ //

    @Test
    fun killAction_fromIdRoundTrips() {
        assertEquals(KillAction.ASK, KillAction.fromId(0))
        assertEquals(KillAction.TERMINATE, KillAction.fromId(1))
        assertEquals(KillAction.FORCE, KillAction.fromId(2))
    }

    @Test
    fun killAction_fromIdDefaultsToAsk_forUnknownValues() {
        assertEquals(KillAction.ASK, KillAction.fromId(-7))
        assertEquals(KillAction.ASK, KillAction.fromId(99))
    }

    @Test
    fun killAction_resolveMapsAskToTerminate() {
        assertEquals(KillAction.TERMINATE, KillAction.ASK.resolve())
        assertEquals(KillAction.TERMINATE, KillAction.TERMINATE.resolve())
        assertEquals(KillAction.FORCE, KillAction.FORCE.resolve())
    }
}
