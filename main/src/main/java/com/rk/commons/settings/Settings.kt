package com.rk.commons.settings

object BatteryCurrentUnit {
    const val UNKNOWN = -1
    const val MICROAMPS = 0
    const val MILLIAMPS = 1
}

object Settings {
    var procAutoRefresh by BooleanPref(key = "proc_auto_refresh", default = true)
    var theme by IntPref(default = 0)
    var themeMode by IntPref(key = "theme_mode", default = 0)
    var sortby by IntPref(default = 0)
    var updateFrequency by IntPref(default = 800)
    var workingMode by IntPref(default = -1)
    var monet by BooleanPref(default = false)
    var showSystemApps by BooleanPref(default = true)
    var showUserApps by BooleanPref(default = true)
    var showLinuxProcess by BooleanPref(default = false)
    var kills by IntPref(default = 0)
    var pullToRefresh_procs by BooleanPref(default = true)
    var confirmkill by BooleanPref(default = true)
    var defaultToProcessScreen by BooleanPref(default = false)
    var selectedNetInterface by StringPref(key = "selected_net_interface", default = "")
    var useImperialUnits by BooleanPref(key = "use_imperial_units", default = false)
    var batteryCurrentUnit by IntPref(key = "battery_current_unit", default = BatteryCurrentUnit.UNKNOWN)

    /** Home-screen widget ephemeral refresh interval in SECONDS (free-form
     *  user input; clamped by WidgetRefreshScheduler to 5 s .. 24 h). Driven
     *  by a self-rescheduling exact alarm chain; the stock APPWIDGET_UPDATE
     *  cadence (30 min) always remains as a backstop. */
    var widgetRefreshSeconds by IntPref(key = "widget_refresh_seconds", default = 1800)

    /** How often (seconds) the live-mode notification text refreshes.
     *  The service tick is 1 Hz, so any value >= 1 works. Default 3 s. */
    var notifRefreshSeconds by IntPref(key = "notif_refresh_seconds", default = 3)

    /** Accent override applied on top of the selected theme. 0 = use the
     *  theme's own accent (also when dynamic color / monet is active). */
    var accentColor by IntPref(key = "accent_color", default = 0)

    /** Keep the live-monitor notification running from app settings alone
     *  (no QS tile needed). Survives reboots via the boot receiver. */
    var permanentNotification by BooleanPref(key = "permanent_notification", default = false)

    /** Whether the boot receiver restores the permanent notification after a
     *  reboot (only relevant when [permanentNotification] is on). Default ON
     *  keeps the behavior the permanent-notification pref always advertised. */
    var startAtBoot by BooleanPref(key = "start_at_boot", default = true)

    /** Show the Kill button on system-app rows in the process list.
     *  Default ON — the button is useless if it has to be discovered in
     *  Process settings first. Killing a system app ALWAYS asks for
     *  confirmation before anything happens. */
    var killSystemApps by BooleanPref(key = "kill_system_apps", default = true)

    /**
     * Battery threshold alerts, evaluated by the live monitor service on
     * every tick. Master switch + delivery (0 = notification, 1 = toast,
     * 2 = both) and, per metric, the threshold (discharge mA / °C) and how
     * long it must stay exceeded before a warning fires (seconds).
     */
    var batteryAlerts by BooleanPref(key = "battery_alerts", default = false)
    var batteryAlertDelivery by IntPref(key = "battery_alert_delivery", default = 0)
    var batteryDrainMa by IntPref(key = "battery_drain_ma", default = 500)
    var batteryDrainSustainSec by IntPref(key = "battery_drain_sustain_sec", default = 60)
    var batteryTempC by IntPref(key = "battery_temp_c", default = 43)
    var batteryTempSustainSec by IntPref(key = "battery_temp_sustain_sec", default = 60)

    /** Process-list name colors (ARGB). 0 = theme default text color. */
    var procColorUser by IntPref(key = "proc_color_user", default = 0)
    var procColorSystem by IntPref(key = "proc_color_system", default = 0xFFE57373.toInt())
    var procColorKernel by IntPref(key = "proc_color_kernel", default = 0xFF81C784.toInt())

    /** How the Kill button stops processes: 0 = ask, 1 = terminate (SIGTERM->SIGKILL), 2 = force (SIGKILL). */
    var defaultKillAction by IntPref(key = "default_kill_action", default = 0)

    /** Grace period (ms) after SIGTERM before the daemon escalates to SIGKILL. Daemon clamps to 200..10000. */
    var killGraceMs by IntPref(key = "kill_grace_ms", default = 3000)

    /**
     * Process list rows show cumulative CPU time (from cpuTimeTicks, only
     * valid when the daemon advertises the proc_cpu_time cap) instead of the
     * instantaneous windowed CPU percentage.
     */
    var showCpuTime by BooleanPref(key = "show_cpu_time", default = false)

    var pinnedProcesses: Set<String>
        get() = Preference.getString("pinned_processes", "").split(",").filter { it.isNotEmpty() }.toSet()
        set(value) = Preference.setString("pinned_processes", value.joinToString(","))
}