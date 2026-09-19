package com.rk.taskmanager.settings

import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rk.commons.settings.Settings
import com.rk.components.SettingsToggle
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.components.compose.preferences.base.PreferenceLayout
import com.rk.commons.getString
import com.rk.commons.strings
import com.rk.taskmanager.daemon.KillAction

var pullToRefresh_procs by mutableStateOf(Settings.pullToRefresh_procs)

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
            SettingsToggle(label = stringResource(strings.default_to_process), description = stringResource(strings.default_to_process_desc), default = Settings.defaultToProcessScreen, showSwitch = true, sideEffect = {
                Settings.defaultToProcessScreen = it
            })
            SettingsToggle(label = stringResource(strings.auto_refresh), description = stringResource(strings.auto_refresh_desc), default = Settings.procAutoRefresh
                , showSwitch = true, sideEffect = {
                Settings.procAutoRefresh = it
            })
        }

        KillBehaviorSettings()
    }
}

/**
 * Kill policy (fork decision #5): the Kill button either asks every time
 * between "Terminate" (SIGTERM -> SIGKILL after a grace period) and "Force
 * kill" (immediate SIGKILL), or always uses one of the two. The grace period
 * only affects Terminate and is clamped by the daemon (200..10000 ms).
 */
@Composable
private fun KillBehaviorSettings() {
    val selectedAction = remember { mutableIntStateOf(Settings.defaultKillAction) }
    val selectedGrace = remember { mutableIntStateOf(Settings.killGraceMs) }

    PreferenceGroup(heading = stringResource(strings.kill_action)) {
        SettingsToggle(
            label = stringResource(strings.kill_action_ask),
            description = stringResource(strings.kill_action_desc),
            default = selectedAction.intValue == KillAction.ASK.id,
            showSwitch = false,
            sideEffect = { Settings.defaultKillAction = KillAction.ASK.id },
            startWidget = {
                RadioButton(
                    selected = selectedAction.intValue == KillAction.ASK.id,
                    onClick = {
                        Settings.defaultKillAction = KillAction.ASK.id
                        selectedAction.intValue = KillAction.ASK.id
                    },
                )
            },
        )
        SettingsToggle(
            label = stringResource(strings.kill_action_terminate),
            description = stringResource(strings.kill_action_terminate_desc),
            default = selectedAction.intValue == KillAction.TERMINATE.id,
            showSwitch = false,
            sideEffect = { Settings.defaultKillAction = KillAction.TERMINATE.id },
            startWidget = {
                RadioButton(
                    selected = selectedAction.intValue == KillAction.TERMINATE.id,
                    onClick = {
                        Settings.defaultKillAction = KillAction.TERMINATE.id
                        selectedAction.intValue = KillAction.TERMINATE.id
                    },
                )
            },
        )
        SettingsToggle(
            label = stringResource(strings.kill_action_force),
            description = stringResource(strings.kill_action_force_desc),
            default = selectedAction.intValue == KillAction.FORCE.id,
            showSwitch = false,
            sideEffect = { Settings.defaultKillAction = KillAction.FORCE.id },
            startWidget = {
                RadioButton(
                    selected = selectedAction.intValue == KillAction.FORCE.id,
                    onClick = {
                        Settings.defaultKillAction = KillAction.FORCE.id
                        selectedAction.intValue = KillAction.FORCE.id
                    },
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
                sideEffect = { Settings.killGraceMs = ms },
                startWidget = {
                    RadioButton(
                        selected = selectedGrace.intValue == ms,
                        onClick = {
                            Settings.killGraceMs = ms
                            selectedGrace.intValue = ms
                        },
                    )
                },
            )
        }
    }
}
