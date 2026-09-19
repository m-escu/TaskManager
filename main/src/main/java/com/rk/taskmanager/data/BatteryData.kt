package com.rk.taskmanager.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * One battery sample, taken while the battery screen is open (fork decision
 * #4: local-only history with a ~30 day rolling window). Values mirror the
 * daemon's BATTERY_PING schema; -1 means "unknown / not reported by kernel".
 *
 * Lives in its own database file: apps.db is pre-shipped from an asset with
 * destructive migrations disabled, so adding entities there would be risky.
 */
@Entity(tableName = "battery_samples")
data class BatterySampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Epoch milliseconds. */
    val timestamp: Long,
    /** 0..100, from power_supply capacity. */
    val capacity: Int,
    val charging: Boolean,
    val status: String,
    /** Signed microamps; sign convention varies by vendor (charging flag is truth). */
    val currentUA: Long,
    val voltageUV: Long,
    /** Magnitude, microvolts * microamps / 1e6. */
    val powerUW: Long,
    /** Tenths of a degree Celsius (kernel convention). */
    val tempTenthsC: Int,
    val cycleCount: Int,
)

@Dao
interface BatterySampleDao {
    @Insert
    suspend fun insert(sample: BatterySampleEntity)

    @Query("DELETE FROM battery_samples WHERE timestamp < :cutoff")
    suspend fun prune(cutoff: Long)

    @Query("SELECT * FROM battery_samples WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun since(since: Long): List<BatterySampleEntity>

    @Query("SELECT COUNT(*) FROM battery_samples")
    suspend fun count(): Int
}

@Database(entities = [BatterySampleEntity::class], version = 1, exportSchema = false)
abstract class BatteryDatabase : RoomDatabase() {
    abstract fun batterySampleDao(): BatterySampleDao
}
