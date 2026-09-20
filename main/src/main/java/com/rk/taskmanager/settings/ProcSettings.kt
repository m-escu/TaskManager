package com.rk.taskmanager.settings

import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.rk.commons.settings.Settings
import com.rk.components.SettingsToggle
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.components.compose.preferences.base.PreferenceLayout
import com.rk.commons.getString
import com.rk.commons.strings
import com.rk.taskmanager.daemon.KillAction

var pullToRefresh_procs by mutableStateOf(Settings.pullToRefresh_procs)

/** Compose mirrors for the process-list kill option and name colors, so the
 *  process list recomposes the moment a setting changes. */
var killSystemAppsEnabled by mutableStateOf(Settings.killSystemApps)
var procColorUserState by mutableIntStateOf(Settings.procColorUser)
var procColorSystemState by mutableIntStateOf(Settings.procColorSystem)
var procColorKernelState by mutableIntStateOf(Settings.procColorKernel)

private val KILL_GRACE_OPTIONS = intArrayOf(500, 1000, 2000, 3000, 5000)

@Composable
fun ProcSettings(modifier: Modifier = Modifier) {
    PreferenceLayout(label = stringResource(strings.procs)) {
        PreferenceGroup() {
            SettingsToggle(label = stringResource(strings.pull_to_refresh), description = stringResource(strings.pull_to_refresh_desc), default = Settings.pullToRefresh_procs, showSwitch = true, sideEffect = {
                Settings.pullToRefresh_procs = it
                pullToRefresh_procs = it
            })
            SettingsToggle(label = stringResource(strings.confirm_stop), description = stringResource(strings.confirm_stop_desc), default = Settings.confirmkill, showSwitch = true, sideEffect = {
                Settings.confirmkill = it
            })
            SettingsToggle(label = stringResource(strings.kill_system_toggle), description = stringResource(strings.kill_system_toggle_desc), default = Settings.killSystemApps, showSwitch = true, sideEffect = {
                Settings.killSystemApps = it
                killSystemAppsEnabled = it
            })
            SettingsToggle(label = stringResource(strings.default_to_process), description = stringResource(strings.default_to_process_desc), default = Settings.defaultToProcessScreen, showSwitch = true, sideEffect = {
                Settings.defaultToProcessScreen = it
            })
            SettingsToggle(label = stringResource(strings.auto_refresh), description = stringResource(strings.auto_refresh_desc), default = Settings.procAutoRefresh
                , showSwitch = true, sideEffect = {
                Settings.procAutoRefresh = it
            })
            SettingsToggle(label = stringResource(strings.show_cpu_time), description = stringResource(strings.show_cpu_time_desc), default = Settings.showCpuTime, showSwitch = true, sideEffect = {
                Settings.showCpuTime = it
            })
        }

        KillBehaviorSettings()
        ProcessColorSettings()
    }
}

/**
 * Kill policy (fork decision #5): the Kill button either asks every time
 * between "Terminate" (SIGTERM -> SIGKILL after a grace period) and "Force
 * kill" (immediate SIGKILL), or always uses one of the two. An explicit
 * default action skips the confirmation dialog; system apps are always
 * confirmed regardless. The grace period only affects Terminate and is
 * clamped by the daemon (200..10000 ms).
 */
@Composable
private fun KillBehaviorSettings() {
    val selectedAction = remember { mutableIntStateOf(Settings.defaultKillAction) }
    val selectedGrace = remember { mutableIntStateOf(Settings.killGraceMs) }

    fun pickAction(id: Int) {
        Settings.defaultKillAction = id
        selectedAction.intValue = id
    }

    fun pickGrace(ms: Int) {
        Settings.killGraceMs = ms
        selectedGrace.intValue = ms
    }

    PreferenceGroup(heading = stringResource(strings.kill_action)) {
        SettingsToggle(
            label = stringResource(strings.kill_action_ask),
            description = stringResource(strings.kill_action_desc),
            default = selectedAction.intValue == KillAction.ASK.id,
            showSwitch = false,
            // Row taps AND radio taps must move the radio: update the
            // selected state here, not only in the radio's own onClick.
            sideEffect = { pickAction(KillAction.ASK.id) },
            startWidget = {
                RadioButton(
                    selected = selectedAction.intValue == KillAction.ASK.id,
                    onClick = { pickAction(KillAction.ASK.id) },
                )
            },
        )
        SettingsToggle(
            label = stringResource(strings.kill_action_terminate),
            description = stringResource(strings.kill_action_terminate_desc),
            default = selectedAction.intValue == KillAction.TERMINATE.id,
            showSwitch = false,
            sideEffect = { pickAction(KillAction.TERMINATE.id) },
            startWidget = {
                RadioButton(
                    selected = selectedAction.intValue == KillAction.TERMINATE.id,
                    onClick = { pickAction(KillAction.TERMINATE.id) },
                )
            },
        )
        SettingsToggle(
            label = stringResource(strings.kill_action_force),
            description = stringResource(strings.kill_action_force_desc),
            default = selectedAction.intValue == KillAction.FORCE.id,
            showSwitch = false,
            sideEffect = { pickAction(KillAction.FORCE.id) },
            startWidget = {
                RadioButton(
                    selected = selectedAction.intValue == KillAction.FORCE.id,
                    onClick = { pickAction(KillAction.FORCE.id) },
                )
            },
        )
    }

    PreferenceGroup(heading = stringResource(strings.grace_period)) {
        KILL_GRACE_OPTIONS.forEach { ms ->
            SettingsToggle(
                label = stringResource(strings.ms_unit, ms),
                description = stringResource(strings.grace_period_desc),
                default = selectedGrace.intValue == ms,
                showSwitch = false,
                sideEffect = { pickGrace(ms) },
                startWidget = {
                    RadioButton(
                        selected = selectedGrace.intValue == ms,
                        onClick = { pickGrace(ms) },
                    )
                },
            )
        }
    }
}

/**
 * Color-coding for the process list names: user apps (theme default),
 * system apps (reddish) and kernel processes (greenish). Each is a hex
 * color; empty input in the dialog resets a color to its default.
 */
@Composable
private fun ProcessColorSettings() {
    var editing by remember { mutableStateOf<String?>(null) }

    PreferenceGroup(heading = stringResource(strings.proc_colors)) {
        ColorRow(
            title = stringResource(strings.proc_color_user),
            argb = procColorUserState,
            defaultSwatch = Color.White,
            onClick = { editing = "user" },
        )
        ColorRow(
            title = stringResource(strings.proc_color_system),
            argb = procColorSystemState,
            defaultSwatch = Color(Settings.procColorSystem),
            onClick = { editing = "system" },
        )
        ColorRow(
            title = stringResource(strings.proc_color_kernel),
            argb = procColorKernelState,
            defaultSwatch = Color(Settings.procColorKernel),
            onClick = { editing = "kernel" },
        )
    }

    when (editing) {
        "user" -> HexColorDialog(
            title = stringResource(strings.proc_color_user),
            initial = currentHex(procColorUserState),
            allowEmptyReset = true,
            onConfirm = {
                Settings.procColorUser = it
                procColorUserState = it
                editing = null
            },
            onDismiss = { editing = null },
        )

        "system" -> HexColorDialog(
            title = stringResource(strings.proc_color_system),
            initial = currentHex(procColorSystemState),
            allowEmptyReset = true,
            onConfirm = {
                Settings.procColorSystem = it
                procColorSystemState = it
                editing = null
            },
            onDismiss = { editing = null },
        )

        "kernel" -> HexColorDialog(
            title = stringResource(strings.proc_color_kernel),
            initial = currentHex(procColorKernelState),
            allowEmptyReset = true,
            onConfirm = {
                Settings.procColorKernel = it
                procColorKernelState = it
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

private fun currentHex(argb: Int): String =
    if (argb == 0) "" else String.format("#%06X", argb and 0x00FFFFFF)
