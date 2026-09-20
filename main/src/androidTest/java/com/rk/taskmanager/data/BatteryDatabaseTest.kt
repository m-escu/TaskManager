package com.rk.taskmanager.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The battery history store runs the fork's most user-visible persistence
 * path (30-day rolling window, chart seeding, "Reset graph data"). These
 * checks execute the real Room/SQLite stack on an emulator — the JVM unit
 * run cannot cover it.
 */
@RunWith(AndroidJUnit4::class)
class BatteryDatabaseTest {

    private lateinit var db: BatteryDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BatteryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sample(timestamp: Long, capacity: Int) = BatterySampleEntity(
        timestamp = timestamp,
        capacity = capacity,
        charging = false,
        status = "Discharging",
        currentUA = -850_000L,
        voltageUV = 3_850_000L,
        powerUW = 3_272_500L,
        tempTenthsC = 310,
        cycleCount = 42,
    )

    @Test
    fun insertSincePruneClearRoundTrip() = runBlocking {
        val dao = db.batterySampleDao()
        val now = System.currentTimeMillis()
        dao.insert(sample(timestamp = now - 3_600_000, capacity = 80))
        dao.insert(sample(timestamp = now - 60_000, capacity = 75))
        dao.insert(sample(timestamp = now, capacity = 70))
        assertEquals(3, dao.count())

        // since() must return ASCENDING by timestamp — the history chart
        // plots xs in list order.
        val recent = dao.since(now - 120_000)
        assertEquals(listOf(75, 70), recent.map { it.capacity })

        // Rolling window: anything older than the cutoff is dropped.
        dao.prune(now - 600_000)
        assertEquals(2, dao.count())

        // "Reset graph data" wipes everything (fork14/15 reset flow).
        dao.clearAll()
        assertEquals(0, dao.count())
        assertTrue(dao.since(0).isEmpty())
    }

    @Test
    fun negativeSentinelsSurviveStorage() = runBlocking {
        // -1 = "unknown" sentinels (current/voltage/power/temp) must round
        // trip unchanged — chart + stats logic branches on them.
        val dao = db.batterySampleDao()
        val s = BatterySampleEntity(
            timestamp = 1_000L,
            capacity = 50,
            charging = true,
            status = "Charging",
            currentUA = -1L,
            voltageUV = -1L,
            powerUW = -1L,
            tempTenthsC = -1,
            cycleCount = -1,
        )
        dao.insert(s)
        val loaded = dao.since(0).single()
        assertEquals(-1L, loaded.currentUA)
        assertEquals(-1L, loaded.voltageUV)
        assertEquals(-1L, loaded.powerUW)
        assertEquals(-1, loaded.tempTenthsC)
        assertEquals(-1, loaded.cycleCount)
        assertTrue(loaded.charging)
    }
}
