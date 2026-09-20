package com.rk.taskmanager.screens.battery

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.rk.commons.charts.ChartConfig
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

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

    val capacityProducer = remember { CartesianChartModelProducer() }
    val currentProducer = remember { CartesianChartModelProducer() }
    var historyPoints by remember { mutableStateOf(0) }

    // Live polling (request-id correlated; legacy daemons degrade gracefully).
    LaunchedEffect(Unit) {
        while (isActive) {
            live = parseBattery(DaemonClient.battery(timeoutMs = 2_500))
            delay(5_000)
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
                        // -1 means "unknown" — plot 0 instead of |−1| mA.
                        y = samples.map {
                            if (it.currentUA < 0) 0f else abs(it.currentUA / 1000f)
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
                Instant.ofEpochMinute(value.toLong()),
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

        UsageChart(
            modelProducer = capacityProducer,
            lineColors = listOf(MaterialTheme.colorScheme.primary),
            modifier = modifier.fillMaxWidth(),
            bottomAxisFormatter = timeAxisFormatter,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HISTORY_PERIODS_DAYS.forEach { days ->
                FilterChip(
                    selected = periodDays == days,
                    onClick = { periodDays = days },
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

        if (historyPoints < 2) {
            Text(
                text = stringResource(strings.batt_no_data_yet),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        // |current| over the selected period; the vendor sign convention is
        // not normalized by the daemon, so the magnitude is what's plottable.
        if (historyPoints >= 2) {
            Text(
                text = stringResource(strings.batt_chart_current),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
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
                                        if (currentLive.currentUA >= 0) {
                                            String.format(Locale.ENGLISH, "%.0f mA", currentLive.currentUA / 1000.0)
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
                }
            }
        }

        Spacer(modifier = Modifier.padding(vertical = 16.dp))
    }
}
