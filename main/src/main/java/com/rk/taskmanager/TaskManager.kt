package com.rk.taskmanager

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.rk.taskmanager.data.AppDatabase
import com.rk.taskmanager.data.BatteryDatabase

class TaskManager : Application() {
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        @Volatile
        private var BATTERY_INSTANCE: BatteryDatabase? = null

        /**
         * Local-only battery history store (separate file on purpose: apps.db
         * is shipped as an asset and must not gain new entities).
         */
        fun getBatteryDatabase(context: Context): BatteryDatabase {
            return BATTERY_INSTANCE ?: synchronized(this) {
                BATTERY_INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BatteryDatabase::class.java,
                    "battery_history.db"
                )
                    .build()
                    .also { BATTERY_INSTANCE = it }
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "apps.db"
                )
                    .createFromAsset("databases/apps.db")
                    .fallbackToDestructiveMigration(false)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        private lateinit var instance: TaskManager
        val application get() = instance

        fun requireContext(): Context {
            if (::instance.isInitialized.not()) {
                throw IllegalStateException("Application not initialized")
            } else {
                return instance.applicationContext
            }
        }

        fun getContext(): Context {
            return requireContext()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.rk.commons.application = this
    }
}
