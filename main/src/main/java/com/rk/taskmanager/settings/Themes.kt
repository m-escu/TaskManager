package com.rk.taskmanager.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.rk.commons.settings.Settings
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.components.compose.preferences.base.PreferenceLayout
import com.rk.components.compose.preferences.base.PreferenceTemplate
import com.rk.taskmanager.MainActivity
import com.rk.commons.strings
import com.rk.taskmanager.ui.theme.accentColor
import com.rk.taskmanager.ui.theme.currentTheme
import com.rk.taskmanager.ui.theme.dynamicTheme
import com.rk.taskmanager.ui.theme.themeMode
import com.rk.taskmanager.ui.theme.themes
import kotlinx.coroutines.launch

/** Quick-pick accents. 0 (theme default) is a separate swatch that always
 *  shows the current theme's own primary color. */
private val ACCENT_PRESETS = intArrayOf(
    0xFFECECEC.toInt(), // near-white — monochrome Void
    0xFFEF5350.toInt(), // red
    0xFFFF5370.toInt(), // rose (widget LIVE badge)
    0xFFFF9E22.toInt(), // orange
    0xFFFFB300.toInt(), // amber
    0xFFFDD835.toInt(), // yellow
    0xFF66BB6A.toInt(), // green
    0xFF26A69A.toInt(), // teal
    0xFF26C6DA.toInt(), // cyan
    0xFF4D8DFF.toInt(), // blue
    0xFF7986CB.toInt(), // indigo
    0xFFAB47BC.toInt(), // purple
    0xFFF06292.toInt(), // pink
)

@Composable
fun Themes(modifier: Modifier = Modifier) {
    var showHexDialog by remember { mutableStateOf(false) }

    PreferenceLayout(label = stringResource(strings.themes)) {
        PreferenceGroup(heading = stringResource(strings.theme_mode)) {
            val modes = listOf(
                Triple(0, strings.auto, null),
                Triple(1, strings.light, null),
                Triple(2, strings.dark, null)
            )

            modes.forEach { (mode, labelRes, descRes) ->
                SelectableCard(
                    selected = themeMode.intValue == mode,
                    label = stringResource(labelRes),
                    description = if (descRes != null) stringResource(descRes) else null,
                    onClick = {
                        MainActivity.instance?.lifecycleScope?.launch {
                            themeMode.intValue = mode
                            Settings.themeMode = mode
                        }
                    }
                )
            }
        }


        PreferenceGroup(heading = stringResource(strings.theme)) {
            SelectableCard(
                selected = dynamicTheme.value,
                label = stringResource(strings.dynamic_theme),
                description = null,
                isEnaled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                onClick = {
                    MainActivity.instance?.lifecycleScope?.launch {
                        dynamicTheme.value = true
                        Settings.monet = true
                    }
                })

            themes.forEach {
                SelectableCard(
                    selected = currentTheme.intValue == it.key && !dynamicTheme.value,
                    label = stringResource(it.value.nameRes),
                    description = null,
                    onClick = {
                        MainActivity.instance?.lifecycleScope?.launch {
                            currentTheme.intValue = it.key
                            Settings.theme = it.key
                            if (dynamicTheme.value) {
                                dynamicTheme.value = false
                                Settings.monet = false
                            }
                        }
                    })
            }
        }

        PreferenceGroup(heading = stringResource(strings.accent_color)) {
            PreferenceTemplate(
                title = { Text(stringResource(strings.accent_color)) },
                description = { Text(stringResource(strings.accent_desc)) },
            )

            AccentSwatchRow(
                onPick = { argb -> applyAccentChoice(argb) }
            )

            ColorRow(
                title = stringResource(strings.accent_custom),
                argb = accentColor.intValue,
                defaultSwatch = MaterialTheme.colorScheme.primary,
                onClick = { showHexDialog = true },
            )
        }
    }

    if (showHexDialog) {
        HexColorDialog(
            title = stringResource(strings.accent_custom),
            initial = currentAccentHex(),
            allowEmptyReset = false,
            onConfirm = { argb ->
                applyAccentChoice(argb)
                showHexDialog = false
            },
            onDismiss = { showHexDialog = false },
        )
    }
}

/** Writes the accent and makes it visible: dynamic (monet) color ignores
 *  the override entirely, so picking an accent turns it off. */
private fun applyAccentChoice(argb: Int) {
    MainActivity.instance?.lifecycleScope?.launch {
        accentColor.intValue = argb
        Settings.accentColor = argb
        if (argb != 0 && dynamicTheme.value) {
            dynamicTheme.value = false
            Settings.monet = false
        }
    }
}

private fun currentAccentHex(): String {
    val argb = accentColor.intValue
    return if (argb == 0) "#" else String.format("#%06X", argb and 0x00FFFFFF)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentSwatchRow(onPick: (Int) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // "Theme default": always renders the current theme's own accent.
        AccentSwatch(
            color = MaterialTheme.colorScheme.primary,
            selected = accentColor.intValue == 0,
            onClick = { onPick(0) },
        )
        ACCENT_PRESETS.forEach { argb ->
            AccentSwatch(
                color = Color(argb),
                selected = accentColor.intValue == argb,
                onClick = { onPick(argb) },
            )
        }
    }
}

@Composable
private fun AccentSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.outline
                },
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}
