package com.rk.taskmanager.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
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
import com.rk.commons.strings
import com.rk.components.compose.preferences.base.PreferenceTemplate

/**
 * Shared color-picking primitives (fork): a clickable row with a color
 * swatch and a hex-color dialog. Used by Themes (accent) and ProcSettings
 * (process-list colors).
 */

/** ARGB int -> hex string ("#RRGGBB"); 0 renders as the localized "Default". */
internal fun colorArgbSummary(argb: Int): String =
    if (argb == 0) {
        stringResDefault()
    } else {
        String.format("#%06X", argb and 0x00FFFFFF)
    }

private fun stringResDefault(): String =
    com.rk.commons.application!!.getString(strings.color_default)

/**
 * Clickable preference row showing a color swatch (or [defaultSwatch] when
 * [argb] is 0, i.e. "follow the default").
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ColorRow(
    title: String,
    argb: Int,
    defaultSwatch: Color,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    PreferenceTemplate(
        modifier = Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = ripple(),
            onClick = onClick,
        ),
        title = { Text(title) },
        description = { Text(colorArgbSummary(argb)) },
        startWidget = {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (argb == 0) defaultSwatch else Color(argb))
            )
        },
    )
}

/**
 * Hex color entry ("#RRGGBB", "#AARRGGBB" or bare digits). The result is
 * always forced opaque. With [allowEmptyReset], an empty input confirms with
 * 0 (reset to the default color) instead of failing validation.
 */
@Composable
internal fun HexColorDialog(
    title: String,
    initial: String,
    allowEmptyReset: Boolean,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf(false) }

    fun parseHexOrNull(): Int? {
        val input = text.trim()
        if (input.isEmpty() && allowEmptyReset) return 0
        val digits = if (input.startsWith("#")) input.substring(1) else input
        if (digits.length !in 6..8) return null
        val argb = digits.toLongOrNull(16) ?: return null
        return (0xFF000000L or (argb and 0x00FFFFFFL)).toInt()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    error = false
                },
                label = { Text(stringResource(strings.accent_hex_label)) },
                isError = error,
                supportingText = if (error) {
                    { Text(stringResource(strings.accent_hex_invalid)) }
                } else if (allowEmptyReset) {
                    { Text(stringResource(strings.color_reset_hint)) }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier
                    .padding(top = 4.dp),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsed = parseHexOrNull()
                    if (parsed == null) {
                        error = true
                    } else {
                        onConfirm(parsed)
                    }
                }
            ) {
                Text(stringResource(strings.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(strings.cancel)) }
        },
    )
}
