package com.rk.taskmanager.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSystemSettings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rk.commons.settings.Settings
import com.rk.commons.strings
import com.rk.components.RadioBottomSheet
import com.rk.components.RadioOption
import com.rk.components.SettingsToggle
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.components.compose.preferences.base.PreferenceLayout
import com.rk.components.compose.preferences.base.PreferenceTemplate
import com.rk.taskmanager.widget.WidgetLiveService
import com.rk.taskmanager.widget.WidgetRefreshScheduler

/**
 * Widget settings. The ephemeral refresh interval is free-form (5 s .. 24 h,
 * entered in seconds or minutes) and drives an exact, self-rescheduling
 * alarm chain; the live mode (QS tile) stays at a fixed 1 Hz.
 * The live notification's text cadence is configurable here too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetSettings(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var showIntervalDialog by remember { mutableStateOf(false) }
    var showNotifDialog by remember { mutableStateOf(false) }
    var showDeliverySheet by remember { mutableStateOf(false) }
    var showDrainDialog by remember { mutableStateOf(false) }
    var showTempDialog by remember { mutableStateOf(false) }
    var showDrainSustainDialog by remember { mutableStateOf(false) }
    var showTempSustainDialog by remember { mutableStateOf(false) }

    val intervalSeconds = remember { mutableIntStateOf(WidgetRefreshScheduler.intervalSeconds()) }
    val notifSeconds = remember { mutableIntStateOf(Settings.notifRefreshSeconds) }

    PreferenceLayout(label = stringResource(strings.widget_and_notification), modifier = modifier) {
        PreferenceGroup(heading = stringResource(strings.widget_refresh_title)) {
            ActionRow(
                title = stringResource(strings.widget_refresh_title),
                description = stringResource(
                    strings.widget_refresh_desc,
                    formatInterval(intervalSeconds.intValue),
                ),
                onClick = { showIntervalDialog = true },
            )

            // SCHEDULE_EXACT_ALARM is denied by default on Android 14+;
            // without it the interval is followed only approximately, so
            // offer the one-tap jump to the special-access page.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !WidgetRefreshScheduler.canScheduleExact(context)
            ) {
                ActionRow(
                    title = stringResource(strings.exact_alarm_title),
                    description = stringResource(strings.exact_alarm_desc),
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(AndroidSystemSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                    .setData(Uri.fromParts("package", context.packageName, null))
                            )
                        }
                    },
                )
            }
        }

        PreferenceGroup(heading = stringResource(strings.live_notif_channel)) {
            // Keeps the live-monitor notification running without the QS
            // tile; survives reboots via LiveBootReceiver.
            SettingsToggle(
                label = stringResource(strings.permanent_notification),
                description = stringResource(strings.permanent_notification_desc),
                default = Settings.permanentNotification,
                showSwitch = true,
                sideEffect = { enabled ->
                    Settings.permanentNotification = enabled
                    if (enabled) {
                        runCatching {
                            androidx.core.content.ContextCompat.startForegroundService(
                                context,
                                Intent(context, WidgetLiveService::class.java)
                                    .setAction(WidgetLiveService.ACTION_START_NOTIF)
                            )
                        }
                    } else {
                        // Plain startService: the settings screen is a
                        // foreground context, and a bare stop must not
                        // demand a startForeground round-trip.
                        runCatching {
                            context.startService(
                                Intent(context, WidgetLiveService::class.java)
                                    .setAction(WidgetLiveService.ACTION_STOP_NOTIF)
                            )
                        }
                    }
                },
            )
            ActionRow(
                title = stringResource(strings.notif_refresh_title),
                description = stringResource(
                    strings.notif_refresh_desc,
                    formatInterval(notifSeconds.intValue),
                ),
                onClick = { showNotifDialog = true },
            )

            // Whether a reboot brings the permanent notification back. The
            // pref above says WHAT should run; this one says whether it
            // survives device restarts. No service interaction needed — the
            // boot receiver reads it on BOOT_COMPLETED.
            SettingsToggle(
                label = stringResource(strings.start_at_boot),
                description = stringResource(strings.start_at_boot_desc),
                default = Settings.startAtBoot,
                showSwitch = true,
                sideEffect = { enabled -> Settings.startAtBoot = enabled },
            )
        }

        // Battery threshold alerts (fork15). Evaluated by the live monitor
        // service on every tick, so they only work while that service runs
        // (permanent notification on, or an active QS-tile session) — the
        // toggle description says so explicitly.
        PreferenceGroup(heading = stringResource(strings.batt_alerts_group)) {
            SettingsToggle(
                label = stringResource(strings.batt_alerts_toggle),
                description = stringResource(strings.batt_alerts_toggle_desc),
                default = Settings.batteryAlerts,
                showSwitch = true,
                sideEffect = { enabled -> Settings.batteryAlerts = enabled },
            )

            val deliveryLabel = when (Settings.batteryAlertDelivery) {
                1 -> stringResource(strings.batt_alert_delivery_toast)
                2 -> stringResource(strings.batt_alert_delivery_both)
                else -> stringResource(strings.batt_alert_delivery_notification)
            }
            ActionRow(
                title = stringResource(strings.batt_alert_delivery),
                description = stringResource(strings.batt_alert_delivery_desc, deliveryLabel),
                onClick = { showDeliverySheet = true },
            )

            ActionRow(
                title = stringResource(strings.batt_drain_threshold),
                description = stringResource(strings.batt_drain_threshold_desc, Settings.batteryDrainMa),
                onClick = { showDrainDialog = true },
            )
            ActionRow(
                title = stringResource(strings.batt_drain_sustain),
                description = stringResource(
                    strings.batt_drain_sustain_desc,
                    formatInterval(Settings.batteryDrainSustainSec),
                ),
                onClick = { showDrainSustainDialog = true },
            )

            ActionRow(
                title = stringResource(strings.batt_temp_threshold),
                description = stringResource(strings.batt_temp_threshold_desc, Settings.batteryTempC),
                onClick = { showTempDialog = true },
            )
            ActionRow(
                title = stringResource(strings.batt_temp_sustain),
                description = stringResource(
                    strings.batt_temp_sustain_desc,
                    formatInterval(Settings.batteryTempSustainSec),
                ),
                onClick = { showTempSustainDialog = true },
            )
        }
    }

    if (showIntervalDialog) {
        NumberInputDialog(
            title = stringResource(strings.widget_refresh_title),
            inputLabel = stringResource(strings.interval_value),
            initialSeconds = intervalSeconds.intValue,
            minSeconds = WidgetRefreshScheduler.MIN_SECONDS,
            maxSeconds = WidgetRefreshScheduler.MAX_SECONDS,
            showUnitToggle = true,
            rangeHint = stringResource(
                strings.interval_range_hint,
                WidgetRefreshScheduler.MIN_SECONDS,
                WidgetRefreshScheduler.MAX_SECONDS / 3600,
            ),
            onConfirm = { seconds ->
                intervalSeconds.intValue = seconds
                Settings.widgetRefreshSeconds = seconds
                WidgetRefreshScheduler.schedule(context.applicationContext)
            },
            onDismiss = { showIntervalDialog = false },
        )
    }

    if (showNotifDialog) {
        NumberInputDialog(
            title = stringResource(strings.notif_refresh_title),
            inputLabel = stringResource(strings.unit_seconds),
            initialSeconds = notifSeconds.intValue,
            minSeconds = 1,
            maxSeconds = 3600,
            showUnitToggle = false,
            rangeHint = stringResource(strings.notif_range_hint),
            onConfirm = { seconds ->
                notifSeconds.intValue = seconds
                Settings.notifRefreshSeconds = seconds
            },
            onDismiss = { showNotifDialog = false },
        )
    }

    // Alert delivery: notification / toast / both.
    if (showDeliverySheet) {
        val options = listOf(
            RadioOption(
                0,
                stringResource(strings.batt_alert_delivery_notification),
                stringResource(strings.batt_alert_delivery_notification),
            ),
            RadioOption(
                1,
                stringResource(strings.batt_alert_delivery_toast),
                stringResource(strings.batt_alert_delivery_toast),
            ),
            RadioOption(
                2,
                stringResource(strings.batt_alert_delivery_both),
                stringResource(strings.batt_alert_delivery_both),
            ),
        )
        RadioBottomSheet(
            isVisible = true,
            onDismiss = { showDeliverySheet = false },
            options = options,
            selectedOption = options.firstOrNull { it.id == Settings.batteryAlertDelivery },
            onOptionSelected = { picked ->
                Settings.batteryAlertDelivery = picked.id
                showDeliverySheet = false
            },
            title = stringResource(strings.batt_alert_delivery),
        )
    }

    if (showDrainDialog) {
        NumberInputDialog(
            title = stringResource(strings.batt_drain_threshold),
            inputLabel = stringResource(strings.batt_drain_input_label),
            initialSeconds = Settings.batteryDrainMa,
            minSeconds = 100,
            maxSeconds = 10000,
            showUnitToggle = false,
            rangeHint = stringResource(strings.batt_threshold_range_hint, 100, 10000),
            onConfirm = { Settings.batteryDrainMa = it },
            onDismiss = { showDrainDialog = false },
        )
    }

    if (showDrainSustainDialog) {
        NumberInputDialog(
            title = stringResource(strings.batt_drain_sustain),
            inputLabel = stringResource(strings.batt_sustain_input_label),
            initialSeconds = Settings.batteryDrainSustainSec,
            minSeconds = 5,
            maxSeconds = 86_400,
            showUnitToggle = true,
            rangeHint = stringResource(strings.interval_range_hint, 5, 24),
            onConfirm = { Settings.batteryDrainSustainSec = it },
            onDismiss = { showDrainSustainDialog = false },
        )
    }

    if (showTempDialog) {
        NumberInputDialog(
            title = stringResource(strings.batt_temp_threshold),
            inputLabel = stringResource(strings.batt_temp_input_label),
            initialSeconds = Settings.batteryTempC,
            minSeconds = 30,
            maxSeconds = 60,
            showUnitToggle = false,
            rangeHint = stringResource(strings.batt_threshold_range_hint, 30, 60),
            onConfirm = { Settings.batteryTempC = it },
            onDismiss = { showTempDialog = false },
        )
    }

    if (showTempSustainDialog) {
        NumberInputDialog(
            title = stringResource(strings.batt_temp_sustain),
            inputLabel = stringResource(strings.batt_sustain_input_label),
            initialSeconds = Settings.batteryTempSustainSec,
            minSeconds = 5,
            maxSeconds = 86_400,
            showUnitToggle = true,
            rangeHint = stringResource(strings.interval_range_hint, 5, 24),
            onConfirm = { Settings.batteryTempSustainSec = it },
            onDismiss = { showTempSustainDialog = false },
        )
    }
}

/** "30 min" for whole minutes, "45 s" otherwise. */
private fun formatInterval(seconds: Int): String = if (seconds >= 60 && seconds % 60 == 0) {
    stringResQuiet(strings.min_unit, seconds / 60)
} else {
    stringResQuiet(strings.sec_unit, seconds)
}

// formatInterval is called from composable context only, so a plain
// stringResource would work; keep it simple with LocalContext-free helper.
private fun stringResQuiet(res: Int, value: Int): String =
    com.rk.commons.application!!.getString(res, value)

/**
 * A clickable preference row built on PreferenceTemplate (the template has
 * no onClick of its own — same approach as SelectableCard, minus the radio).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionRow(title: String, description: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    PreferenceTemplate(
        modifier = Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = ripple(),
            onClick = onClick,
        ),
        title = { Text(title) },
        description = { Text(description) },
    )
}

/**
 * Free-form numeric interval entry. Digits only; the value is interpreted in
 * the unit picked via the seconds/minutes chips (widget interval) or always
 * in seconds (notification). Valid ranges are enforced with an inline error.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NumberInputDialog(
    title: String,
    inputLabel: String,
    initialSeconds: Int,
    minSeconds: Int,
    maxSeconds: Int,
    showUnitToggle: Boolean,
    rangeHint: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember {
        mutableStateOf(
            if (showUnitToggle && initialSeconds >= 60 && initialSeconds % 60 == 0) {
                (initialSeconds / 60).toString()
            } else {
                initialSeconds.toString()
            }
        )
    }
    var unitIsMinutes by remember {
        // Match the pre-filled text: whole-minute values are shown in minutes.
        mutableStateOf(showUnitToggle && initialSeconds >= 60 && initialSeconds % 60 == 0)
    }
    var error by remember { mutableStateOf<String?>(null) }

    fun parsedSeconds(): Int? {
        val value = text.trim().toIntOrNull() ?: return null
        val seconds = if (unitIsMinutes) {
            val shifted = value.toLong() * 60L
            if (shifted > Int.MAX_VALUE) return null else shifted.toInt()
        } else {
            value
        }
        return seconds.takeIf { it in minSeconds..maxSeconds }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        text = input.filter { it.isDigit() }.take(6)
                        error = null
                    },
                    label = { Text(inputLabel) },
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions.Default.copy(
                        keyboardType = KeyboardType.Number,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showUnitToggle) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        FilterChip(
                            selected = !unitIsMinutes,
                            onClick = { unitIsMinutes = false; error = null },
                            label = { Text(stringResource(strings.unit_seconds)) },
                        )
                        FilterChip(
                            selected = unitIsMinutes,
                            onClick = { unitIsMinutes = true; error = null },
                            label = { Text(stringResource(strings.unit_minutes)) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val seconds = parsedSeconds()
                    if (seconds == null) {
                        error = rangeHint
                    } else {
                        onConfirm(seconds)
                        onDismiss()
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
