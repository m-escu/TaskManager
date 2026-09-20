package com.rk.taskmanager.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rk.commons.settings.Settings
import com.rk.commons.strings
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.components.compose.preferences.base.PreferenceLayout
import com.rk.components.compose.preferences.base.PreferenceTemplate
import com.rk.taskmanager.TaskManager
import com.rk.taskmanager.widget.WidgetRefreshScheduler

/**
 * Widget settings: the ephemeral refresh interval. Live mode (QS tile) is
 * always 1 Hz and needs no configuration.
 */
@Composable
fun WidgetSettings(modifier: Modifier = Modifier) {
    PreferenceLayout(label = stringResource(strings.widget), modifier = modifier) {
        PreferenceGroup(heading = stringResource(strings.widget_refresh_title)) {
            PreferenceTemplate(
                title = { Text(stringResource(strings.widget_refresh_title)) },
                description = { Text(stringResource(strings.widget_refresh_desc)) },
            )

            listOf(15, 30, 60, 180).forEach { minutes ->
                SelectableCard(
                    selected = Settings.widgetRefreshMinutes == minutes,
                    label = stringResource(strings.min_unit, minutes),
                    description = null,
                    onClick = {
                        Settings.widgetRefreshMinutes = minutes
                        WidgetRefreshScheduler.schedule(TaskManager.requireContext())
                    }
                )
            }
        }
    }
}
