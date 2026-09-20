package com.rk.taskmanager.ui.theme

import androidx.annotation.Keep
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.rk.commons.strings
import com.rk.taskmanager.ui.theme.cosmos.Cosmos

/**
 * Void: pure-black (AMOLED) dark scheme — true #000000 surfaces instead of
 * the default dark-gray ones. Light mode reuses Cosmos untouched, because a
 * black theme only means anything on dark surfaces; pixels that are off on
 * OLED panels also save real battery.
 */
@Keep
object VoidTheme : Theme() {
    override val nameRes: Int = strings.void_theme

    private val Black = Color(0xFF000000)
    private val OnBlack = Color(0xFFE8E8EA)
    private val SurfaceLow = Color(0xFF0A0A0C)
    private val SurfaceContainer = Color(0xFF101013)
    private val SurfaceHigh = Color(0xFF161619)
    private val SurfaceHighest = Color(0xFF1D1D21)
    private val SurfaceVariant = Color(0xFF141416)
    private val OnSurfaceVariant = Color(0xFFBABABF)
    private val SurfaceBright = Color(0xFF17171A)

    override val lightScheme: ColorScheme
        get() = Cosmos.lightScheme

    override val darkScheme: ColorScheme = Cosmos.darkScheme.copy(
        background = Black,
        onBackground = OnBlack,
        surface = Black,
        onSurface = OnBlack,
        surfaceVariant = SurfaceVariant,
        onSurfaceVariant = OnSurfaceVariant,
        surfaceDim = Black,
        surfaceBright = SurfaceBright,
        surfaceContainerLowest = Black,
        surfaceContainerLow = SurfaceLow,
        surfaceContainer = SurfaceContainer,
        surfaceContainerHigh = SurfaceHigh,
        surfaceContainerHighest = SurfaceHighest,
        scrim = Black,
    )
}
