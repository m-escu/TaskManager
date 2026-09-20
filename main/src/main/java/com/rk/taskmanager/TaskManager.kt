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

        // fork10 -> fork11 migration: the widget refresh pref moved from
        // whole minutes to free-form seconds. Runs before anything can read
        // the new key (Application.onCreate precedes every component).
        runCatching {
            val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
            if (prefs.contains("widget_refresh_minutes") &&
                prefs.contains("widget_refresh_seconds").not()
            ) {
                com.rk.commons.settings.Settings.widgetRefreshSeconds =
                    prefs.getInt("widget_refresh_minutes", 30) * 60
            }
            prefs.edit().remove("widget_refresh_minutes").apply()
        }

        // Keep the user-configurable widget refresh alarm armed. Self-heals
        // after reboots/process restarts; a no-op while no widget is placed
        // (the scheduler checks placed instances first).
        runCatching {
            com.rk.taskmanager.widget.WidgetRefreshScheduler.schedule(this)
        }
    }
}
