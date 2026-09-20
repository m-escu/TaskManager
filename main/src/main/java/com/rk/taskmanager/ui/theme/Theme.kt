package com.rk.taskmanager.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.rk.commons.settings.Settings
import com.rk.taskmanager.ui.theme.cosmos.Cosmos
import com.rk.taskmanager.ui.theme.flame.Flame
import com.rk.taskmanager.ui.theme.leaf.Leaf
import com.rk.taskmanager.ui.theme.wave.Wave

abstract class Theme{
    abstract val nameRes:Int
    abstract val lightScheme: ColorScheme
    abstract val darkScheme: ColorScheme
}

val themes = hashMapOf(
    0 to Wave,
    1 to Leaf,
    2 to Flame,
    3 to Cosmos,
    4 to VoidTheme
)

var currentTheme = mutableIntStateOf(Settings.theme)
var dynamicTheme = mutableStateOf(Settings.monet)
var themeMode = mutableIntStateOf(Settings.themeMode)

/** Accent override (ARGB int from Settings -> Themes); 0 = theme's own accent. */
var accentColor = mutableIntStateOf(Settings.accentColor)

/**
 * Stamps a user-picked accent over the accent-bearing roles of [base].
 * Containers are translucent tints of the accent so arbitrary colors keep
 * usable contrast in both light and dark mode; the on-color flips by
 * luminance. Only primary/secondary/tertiary roles change — surfaces,
 * backgrounds and text stay exactly as the theme (e.g. Void) defined them.
 */
private fun applyAccent(base: ColorScheme, argb: Int): ColorScheme {
    if (argb == 0) return base
    val accent = Color(argb)
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
    val container = accent.copy(alpha = 0.24f)
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = container,
        onPrimaryContainer = onAccent,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = container,
        onSecondaryContainer = onAccent,
        tertiary = accent,
        onTertiary = onAccent,
        tertiaryContainer = container,
        onTertiaryContainer = onAccent,
    )
}

@Composable
fun TaskManagerTheme(
    darkTheme: Boolean = when (themeMode.intValue) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    },
    dynamicColor: Boolean = dynamicTheme.value,
    content: @Composable () -> Unit
) {
    val baseScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }else{
        if (darkTheme){
            themes[currentTheme.intValue]!!.darkScheme
        }else{
            themes[currentTheme.intValue]!!.lightScheme
        }
    }

    val colorScheme = applyAccent(baseScheme, accentColor.intValue)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
