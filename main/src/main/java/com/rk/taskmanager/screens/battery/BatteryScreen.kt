package com.rk.taskmanager.screens.battery

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.rk.commons.charts.ChartConfig
import com.rk.commons.charts.GraphDataHandler
import com.rk.commons.charts.UsageChart
import com.rk.commons.getString
import com.rk.commons.settings.Settings
import com.rk.commons.strings
import com.rk.commons.ui.InfoCard
import com.rk.commons.ui.InfoItem
import com.rk.commons.ui.SectionHeader
import com.rk.commons.utils.formatTemperature
import com.rk.taskmanager.TaskManager
import com.rk.taskmanager.daemon.DaemonClient
import com.rk.taskmanager.data.BatterySampleEntity
import com.rk.taskmanager.navControllerRef
import com.rk.taskmanager.screens.selectedscreen
import com.rk.taskmanager.settings.SettingsRoutes
import com.rk.taskmanager.widget.WidgetStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Live battery values parsed from BATTERY_PING; known=false on daemon ERROR. */
data class BatteryLive(
    val known: Boolean,
    val present: Boolean,
    val capacity: Int,
    val status: String,
    val charging: Boolean,
    val health: String,
    val voltageUV: Long,
    val currentUA: Long,
    val powerUW: Long,
    val tempTenthsC: Int,
    val cycleCount: Int,
    val chargeFullUAh: Long,
    val chargeFullDesignUAh: Long,
)

private fun parseBattery(response: JSONObject?): BatteryLive? = response?.let { j ->
    BatteryLive(
        known = j.has("present"),
        present = j.optBoolean("present", false),
        capacity = j.optInt("capacity", -1),
        status = j.optString("status", ""),
        charging = j.optBoolean("charging", false),
        health = j.optString("health", ""),
        voltageUV = j.optLong("voltageUV", -1),
        currentUA = j.optLong("currentUA", -1),
        powerUW = j.optLong("powerUW", -1),
        tempTenthsC = j.optInt("tempTenthsC", -1),
        cycleCount = j.optInt("cycleCount", -1),
        chargeFullUAh = j.optLong("chargeFullUAh", -1),
        chargeFullDesignUAh = j.optLong("chargeFullDesignUAh", -1),
    )
}

/** Converts a BATTERY_PING answer into a storable sample (null = nothing to store). */
private fun JSONObject.toSample(timestamp: Long): BatterySampleEntity? {
    if (!optBoolean("present", false)) return null
    val capacity = optInt("capacity", -1)
    if (capacity !in 0..100) return null
    return BatterySampleEntity(
        timestamp = timestamp,
        capacity = capacity,
        charging = optBoolean("charging", false),
        status = optString("status", ""),
        currentUA = optLong("currentUA", -1),
        voltageUV = optLong("voltageUV", -1),
        powerUW = optLong("powerUW", -1),
        tempTenthsC = optInt("tempTenthsC", -1),
        cycleCount = optInt("cycleCount", -1),
    )
}

private val HISTORY_RETENTION_MS = 30L * 24 * 3600 * 1000
private val HISTORY_PERIODS_DAYS = intArrayOf(1, 7, 30)
private const val TAG = "BatteryScreen"

/**
 * Live sliding windows for the battery tab (the net-tab pattern): 120
 * points at 1 Hz = a 2-minute real-time curve. Fed only while this screen
 * is composed; the charts keep whatever the window last held between visits.
 */
private val liveLevelGraphHandler = GraphDataHandler(seriesCount = 1)
private val liveCurrentGraphHandler = GraphDataHandler(seriesCount = 1)

/** Same visibility gate the CPU/RAM/GPU/net live charts use (see NetScreen). */
private fun liveChartGate(): Boolean =
    selectedscreen.intValue == 0 &&
        navControllerRef.get()?.currentDestination?.route == SettingsRoutes.Home.route

/**
 * Battery screen (fork decision #4): live stats from the daemon's
 * BATTERY_PING plus a local, Room-backed history with a 30-day rolling
 * window. Samples are recorded once a minute while this screen is open;
 * the store is pruned on entry. Everything stays on the device.
 */
@Composable
fun BatteryScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var live by remember { mutableStateOf<BatteryLive?>(null) }
    var periodDays by rememberSaveable { mutableIntStateOf(1) }
    var sampleCount by remember { mutableIntStateOf(0) }

    // Chart-slot mode: live sliding window (default — the user opens the
    // tab to see what the battery is doing NOW) or the recorded history for
    // the selected period. Same default as the net tab; the choice survives
    // while the process lives (rememberSaveable).
    var liveMode by rememberSaveable { mutableStateOf(true) }

    // Bumped when the user resets the history. Vico 2.0.3 FORBIDS feeding a
    // chart slot a new producer instance while it stays composed (its
    // collectAsState throws IllegalStateException "A new
    // CartesianChartModelProducer was provided..."), so every UsageChart is
    // wrapped in key(historyGeneration): the whole host subtree — including
    // Vico's internal producer-identity state — is torn down and rebuilt
    // together with the fresh producer, and the charts render empty
    // immediately after a reset instead of keeping the old curve.
    var historyGeneration by remember { mutableIntStateOf(0) }
    val capacityProducer = remember(historyGeneration) { CartesianChartModelProducer() }
    val currentProducer = remember(historyGeneration) { CartesianChartModelProducer() }
    var historyPoints by remember { mutableStateOf(0) }

    val scope = rememberCoroutineScope()
    var showResetConfirm by remember { mutableStateOf(false) }

    // Live polling (request-id correlated; legacy daemons degrade gracefully).
    // In Live mode the poll runs at 1 Hz and feeds the two sliding-window
    // charts — BATTERY_PING at 1 Hz is exactly what the widget's live
    // service already does, so the daemon load is proven fine; otherwise
    // 5 s is plenty for the stats cards below. Current is signed exactly
    // like the history curve: + charging, - discharging, vendor sign
    // normalized away, -1 (unknown) plots as 0.
    LaunchedEffect(liveMode) {
        while (isActive) {
            val parsed = parseBattery(DaemonClient.battery(timeoutMs = 2_500))
            live = parsed
            if (liveMode && parsed != null && parsed.known && parsed.present) {
                val ua = WidgetStats.normalizeCurrentUA(parsed.currentUA, parsed.charging)
                if (parsed.capacity in 0..100) {
                    liveLevelGraphHandler.update(parsed.capacity) { liveChartGate() }
                }
                liveCurrentGraphHandler.update(
                    if (ua == -1L) 0 else (ua / 1000).toInt()
                ) { liveChartGate() }
            }
            delay(if (liveMode) 1_000L else 5_000L)
        }
    }

    // History recording: prune on entry, then one sample per minute while open.
    LaunchedEffect(Unit) {
        val dao = TaskManager.getBatteryDatabase(context).batterySampleDao()
        withContext(Dispatchers.IO) { dao.prune(System.currentTimeMillis() - HISTORY_RETENTION_MS) }
        while (isActive) {
            val sample = DaemonClient.battery(timeoutMs = 2_500)
                ?.toSample(System.currentTimeMillis())
            if (sample != null) {
                withContext(Dispatchers.IO) {
                    dao.insert(sample)
                    sampleCount = dao.count()
                }
            }
            delay(60_000)
        }
    }

    // History query + chart seeding for the selected period. Re-runs when
    // sampleCount changes so the chart fills in as soon as the first sample
    // is recorded. Vico 2.0.3 throws IllegalArgumentException ("Series can't
    // be empty.") when a transaction contains an empty series — which is
    // exactly the state of a fresh database, so empty results are skipped
    // and the transactions are additionally guarded below.
    // X values are epoch MINUTES so the curve is plotted on real time: the
    // right edge is always the latest sample (recorded immediately on entry
    // and every minute after), and old samples sit at their true position.
    LaunchedEffect(periodDays, sampleCount) {
        val dao = TaskManager.getBatteryDatabase(context).batterySampleDao()
        val since = System.currentTimeMillis() - periodDays * 24L * 3600 * 1000
        val samples = withContext(Dispatchers.IO) { dao.since(since) }
        historyPoints = samples.size
        if (samples.isEmpty()) return@LaunchedEffect
        val xs = samples.map { it.timestamp / 60000.0 }
        runCatching {
            capacityProducer.runTransaction {
                lineSeries {
                    series(x = xs, y = samples.map { it.capacity })
                }
            }
            currentProducer.runTransaction {
                lineSeries {
                    series(
                        x = xs,
                        y = samples.map {
                            // Signed mA normalized against the stored charging
                            // flag: charging above zero, discharging below.
                            // The daemon passes the vendor-raw sign through
                            // (on some devices charging reads NEGATIVE), so
                            // the old "< 0 means unknown" check flattened
                            // every charging sample on those devices; -1
                            // remains the only unknown sentinel.
                            val ua = WidgetStats.normalizeCurrentUA(it.currentUA, it.charging)
                            if (ua == -1L) 0f else ua / 1000f
                        },
                    )
                }
            }
        }.onFailure { Log.e(TAG, "chart seeding failed", it) }
    }

    // Bottom-axis labels: clock time for the 24h view, day for 7d/30d.
    val timeAxisFormatter = remember(periodDays) {
        CartesianValueFormatter { _, value, _ ->
            val dt = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(value.toLong() * 60L),
                ZoneId.systemDefault(),
            )
            dt.format(
                if (periodDays == 1) DateTimeFormatter.ofPattern("HH:mm")
                else DateTimeFormatter.ofPattern("d/M")
            )
        }
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        val currentLive = live

        Text(
            text = stringResource(strings.batt_chart_level),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        if (liveMode) {
            // Zero-seeded 2-minute window; level keeps the shared 0–100 %
            // formatting, exactly like the history chart.
            UsageChart(
                modelProducer = liveLevelGraphHandler.modelProducer,
                lineColors = listOf(MaterialTheme.colorScheme.primary),
                modifier = modifier.fillMaxWidth(),
            )
        } else {
            key(historyGeneration) {
                UsageChart(
                    modelProducer = capacityProducer,
                    lineColors = listOf(MaterialTheme.colorScheme.primary),
                    modifier = modifier.fillMaxWidth(),
                    bottomAxisFormatter = timeAxisFormatter,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Same chip semantics as the net tab: Live first (default), then
            // the recorded-history periods. The chip label reuses
            // net_live_chip — same concept, same word (batt_last_* are
            // already shared the other way round).
            FilterChip(
                selected = liveMode,
                onClick = { liveMode = true },
                label = { Text(stringResource(strings.net_live_chip)) }
            )
            HISTORY_PERIODS_DAYS.forEach { days ->
                FilterChip(
                    selected = !liveMode && periodDays == days,
                    onClick = {
                        liveMode = false
                        periodDays = days
                    },
                    label = {
                        Text(
                            when (days) {
                                1 -> stringResource(strings.batt_last_24h)
                                7 -> stringResource(strings.batt_last_7d)
                                else -> stringResource(strings.batt_last_30d)
                            }
                        )
                    }
                )
            }
        }

        if (liveMode) {
            Text(
                text = stringResource(strings.batt_live_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        } else if (historyPoints < 2) {
            Text(
                text = stringResource(strings.batt_no_data_yet),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        // Signed current: charging above zero, discharging below. Live mode
        // always has data (the window is zero-seeded); the history curve
        // needs at least two samples before Vico accepts the series.
        if (liveMode || historyPoints >= 2) {
            Text(
                text = stringResource(strings.batt_chart_current),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            if (liveMode) {
                UsageChart(
                    modelProducer = liveCurrentGraphHandler.modelProducer,
                    lineColors = listOf(MaterialTheme.colorScheme.tertiary),
                    modifier = modifier.fillMaxWidth(),
                    rangeProvider = ChartConfig.AutoRangeProvider,
                    valueFormatter = ChartConfig.PlainStartAxisValueFormatter,
                    markerValueFormatter = ChartConfig.PlainMarkerValueFormatter,
                )
            } else {
                key(historyGeneration) {
                    UsageChart(
                        modelProducer = currentProducer,
                        lineColors = listOf(MaterialTheme.colorScheme.tertiary),
                        modifier = modifier.fillMaxWidth(),
                        rangeProvider = ChartConfig.AutoRangeProvider,
                        valueFormatter = ChartConfig.PlainStartAxisValueFormatter,
                        markerValueFormatter = ChartConfig.PlainMarkerValueFormatter,
                        bottomAxisFormatter = timeAxisFormatter,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.padding(vertical = 4.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HorizontalDivider()

            InfoCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(stringResource(strings.battery_stats))

                    when {
                        currentLive == null || !currentLive.known -> Text(
                            text = stringResource(strings.batt_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        !currentLive.present -> Text(
                            text = stringResource(strings.batt_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        else -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    InfoItem(
                                        stringResource(strings.batt_capacity),
                                        if (currentLive.capacity >= 0) "${currentLive.capacity}%" else stringResource(strings.no_data),
                                        highlighted = true
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    InfoItem(
                                        stringResource(strings.batt_charging),
                                        if (currentLive.charging) stringResource(strings.yes) else stringResource(strings.no)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    InfoItem(
                                        stringResource(strings.battery_current),
                                        // Vendor sign conventions differ (on
                                        // some devices current_now goes
                                        // NEGATIVE while charging, which used
                                        // to render as "N/A"). Normalize
                                        // against the charging flag and show
                                        // the signed value: + charging,
                                        // - discharging (same as the widget).
                                        if (currentLive.currentUA != -1L) {
                                            WidgetStats.formatCurrent(
                                                WidgetStats.normalizeCurrentUA(
                                                    currentLive.currentUA,
                                                    currentLive.charging,
                                                )
                                            )
                                        } else stringResource(strings.no_data)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    InfoItem(
                                        stringResource(strings.batt_voltage),
                                        if (currentLive.voltageUV > 0) {
                                            String.format(Locale.ENGLISH, "%.2f V", currentLive.voltageUV / 1_000_000.0)
                                        } else stringResource(strings.no_data)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    InfoItem(
                                        stringResource(strings.batt_power),
                                        if (currentLive.powerUW >= 0) {
                                            if (currentLive.powerUW >= 1_000_000) {
                                                String.format(Locale.ENGLISH, "%.2f W", currentLive.powerUW / 1_000_000.0)
                                            } else {
                                                "${currentLive.powerUW / 1000} mW"
                                            }
                                        } else stringResource(strings.no_data)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    val tempValue = currentLive.tempTenthsC
                                    InfoItem(
                                        stringResource(strings.temperature),
                                        if (tempValue >= 0) {
                                            formatTemperature(tempValue / 10, Settings.useImperialUnits)
                                        } else stringResource(strings.no_data)
                                    )
                                }
                            }

                            if (currentLive.status.isNotBlank()) {
                                InfoItem(stringResource(strings.status), currentLive.status)
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            InfoCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(stringResource(strings.batt_health))

                    if (currentLive == null || !currentLive.known || !currentLive.present) {
                        Text(
                            text = stringResource(strings.batt_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                InfoItem(
                                    stringResource(strings.batt_health),
                                    currentLive.health.ifBlank { stringResource(strings.no_data) }
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                InfoItem(
                                    stringResource(strings.batt_cycles),
                                    if (currentLive.cycleCount >= 0) {
                                        currentLive.cycleCount.toString()
                                    } else stringResource(strings.charge_cycles_unavailable)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                InfoItem(
                                    stringResource(strings.batt_charge_full),
                                    if (currentLive.chargeFullUAh > 0) {
                                        "${currentLive.chargeFullUAh / 1000} mAh"
                                    } else stringResource(strings.no_data)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                InfoItem(
                                    stringResource(strings.batt_design_capacity),
                                    if (currentLive.chargeFullDesignUAh > 0) {
                                        "${currentLive.chargeFullDesignUAh / 1000} mAh"
                                    } else stringResource(strings.no_data)
                                )
                            }
                        }

                        if (currentLive.chargeFullUAh > 0 && currentLive.chargeFullDesignUAh > 0) {
                            InfoItem(
                                stringResource(strings.batt_capacity_health),
                                String.format(
                                    Locale.ENGLISH, "%.1f%%",
                                    currentLive.chargeFullUAh * 100.0 / currentLive.chargeFullDesignUAh
                                ),
                                highlighted = true
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            InfoCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(stringResource(strings.batt_recording))
                    Text(
                        text = stringResource(strings.batt_recording_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(strings.batt_samples_stored, sampleCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Destructive: wipes every stored sample so the graphs
                    // restart from scratch (samples resume within a minute).
                    TextButton(onClick = { showResetConfirm = true }) {
                        Text(
                            text = stringResource(strings.batt_reset_history),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.padding(vertical = 16.dp))
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(strings.batt_reset_confirm_title)) },
            text = { Text(stringResource(strings.batt_reset_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    val dao = TaskManager.getBatteryDatabase(context).batterySampleDao()
                    scope.launch {
                        withContext(Dispatchers.IO) { dao.clearAll() }
                        sampleCount = 0
                        historyPoints = 0
                        historyGeneration++
                        // Blank the live windows too so the whole tab reads
                        // as "from scratch" (they refill within ~2 min).
                        liveLevelGraphHandler.reset()
                        liveCurrentGraphHandler.reset()
                        Toast.makeText(
                            context,
                            strings.batt_reset_done.getString(),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }) {
                    Text(
                        text = stringResource(strings.batt_reset_history),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(strings.cancel))
                }
            },
        )
    }
}
