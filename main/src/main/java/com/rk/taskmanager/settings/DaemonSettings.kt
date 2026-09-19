package com.rk.taskmanager.settings

import android.widget.Toast
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.rk.commons.settings.Settings
import com.rk.components.SettingsToggle
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.components.compose.preferences.base.PreferenceLayout
import com.rk.components.compose.preferences.base.PreferenceTemplate
import com.rk.taskmanager.daemon.daemonCaps
import com.rk.taskmanager.daemon.daemonProtocolVersion
import com.rk.taskmanager.daemon.daemonVersionString
import com.rk.taskmanager.daemon.isConnected
import com.rk.commons.getString
import com.rk.commons.strings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

@Composable
fun DaemonSettings(modifier: Modifier = Modifier) {
    PreferenceLayout(label = stringResource(strings.daemon)) {
        val context = LocalContext.current
        val selectedMode = remember { mutableIntStateOf(Settings.workingMode) }

        // Live snapshot of the connected daemon (from the HELLO handshake).
        // Helps debugging and makes protocol/capability mismatches visible.
        PreferenceGroup(heading = stringResource(strings.daemon_status)) {
            PreferenceTemplate(
                title = { Text(text = stringResource(strings.daemon), style = MaterialTheme.typography.titleMedium) },
                description = {
                    Text(
                        text = stringResource(
                            if (isConnected) strings.daemon_status_connected else strings.daemon_status_disconnected
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                },
            )
            if (isConnected) {
                PreferenceTemplate(
                    title = { Text(text = stringResource(strings.daemon_version), style = MaterialTheme.typography.titleMedium) },
                    description = { Text(text = daemonVersionString, style = MaterialTheme.typography.titleSmall) },
                )
                PreferenceTemplate(
                    title = { Text(text = stringResource(strings.daemon_protocol), style = MaterialTheme.typography.titleMedium) },
                    description = { Text(text = "v$daemonProtocolVersion", style = MaterialTheme.typography.titleSmall) },
                )
                if (daemonCaps.isNotEmpty()) {
                    PreferenceTemplate(
                        title = { Text(text = stringResource(strings.daemon_caps), style = MaterialTheme.typography.titleMedium) },
                        description = { Text(text = daemonCaps.sorted().joinToString(", "), style = MaterialTheme.typography.titleSmall) },
                    )
                }
            }
        }

        PreferenceGroup(heading = stringResource(strings.working_mode)) {
            WorkingMode.entries.forEach { mode ->
                if (mode != WorkingMode.NOT_SET){
                    SettingsToggle(
                        label = stringResource(mode.nameRes!!),
                        description = null,
                        default = selectedMode.intValue == mode.id,
                        sideEffect = {
                            Settings.workingMode = mode.id
                            selectedMode.intValue = mode.id

                            Toast.makeText(context, strings.requires_daemon_restart.getString(), Toast.LENGTH_SHORT).show()
                        },
                        showSwitch = false,
                        startWidget = {
                            RadioButton(selected = selectedMode.intValue == mode.id, onClick = {
                                Settings.workingMode = mode.id
                                selectedMode.intValue = mode.id
                                Toast.makeText(context, strings.requires_daemon_restart.getString(), Toast.LENGTH_SHORT)
                                    .show()

                            })
                        },
                    )
                }

            }
        }
    }
}