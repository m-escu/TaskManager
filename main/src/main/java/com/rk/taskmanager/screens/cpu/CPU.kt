package com.rk.taskmanager.screens.cpu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rk.commons.charts.GraphDataHandler
import com.rk.commons.charts.UsageChart
import com.rk.commons.ui.FrequencyInfo
import com.rk.commons.ui.InfoCard
import com.rk.commons.ui.InfoItem
import com.rk.commons.ui.SectionHeader
import com.rk.commons.utils.CpuInfoReader
import com.rk.commons.utils.formatTemperature
import com.rk.components.SettingsToggle
import com.rk.taskmanager.ProcessViewModel
import com.rk.taskmanager.daemon.DaemonClient
import com.rk.taskmanager.daemon.DaemonServer
import com.rk.taskmanager.navControllerRef
import com.rk.taskmanager.screens.selectedscreen
import com.rk.taskmanager.settings.SettingsRoutes
import com.rk.taskmanager.settings.useImperialUnits
import com.rk.commons.strings
import com.rk.commons.getString
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import org.json.JSONObject

import java.util.Locale

val cpuGraphHandler = GraphDataHandler(seriesCount = 1)

/** One CPU core as reported by CORE_PING (usage %, frequencies in KHz). */
data class CoreStat(
    val index: Int,
    val online: Boolean,
    val usage: Int,
    val freqKHz: Long,
    val maxFreqKHz: Long,
)

private val _cpuUsage = MutableStateFlow(0)
val cpuUsage = _cpuUsage.asStateFlow()

fun setCpuUsage(value: Int) {
    _cpuUsage.value = value
}

suspend fun updateCpuGraph(usage: Int) {
    setCpuUsage(usage)
    cpuGraphHandler.update(usage) {
        selectedscreen.intValue == 0 && navControllerRef.get()?.currentDestination?.route == SettingsRoutes.Home.route
    }
}

@Composable
fun CPU(modifier: Modifier = Modifier, viewModel: ProcessViewModel) {
    LaunchedEffect(Unit) {
        cpuGraphHandler.refresh()
    }

    var temperature by remember { mutableStateOf(strings.no_data.getString()) }
    var uptime by remember { mutableStateOf("") }
    var cpuInfo by remember { mutableStateOf<CpuInfoReader.CpuInfo?>(null) }
    var cores by remember { mutableStateOf<List<CoreStat>>(emptyList()) }

    // Request-id correlated polling: only responses carrying our request id
    // can update this screen (the legacy type-matching consumer is gone).
    LaunchedEffect(Unit) {
        while (isActive) {
            val response = DaemonServer.request(
                JSONObject().put("cmd", "CTEMP_PING"),
                timeoutMs = 2_000,
            )
            val temp = response?.optInt("temp", -1)
            if (temp != null && temp > 0) {
                temperature = temp.toString()
            }
            uptime = CpuInfoReader.getUptimeFormatted()
            cpuInfo = CpuInfoReader.read()
            delay(2000)
        }
    }

    // Per-core usage + frequencies (requires the core_ping daemon cap; on
    // older daemons the response has no "cores" array and the card hides).
    LaunchedEffect(Unit) {
        while (isActive) {
            val response = DaemonClient.cores(timeoutMs = 2_000)
            val arr = response?.optJSONArray("cores")
            if (arr != null) {
                cores = (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    CoreStat(
                        index = obj.optInt("index", i),
                        online = obj.optBoolean("online", true),
                        usage = obj.optInt("usage", 0),
                        freqKHz = obj.optLong("freqKHz", -1),
                        maxFreqKHz = obj.optLong("maxFreqKHz", -1),
                    )
                }
            }
            delay(2000)
        }
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        UsageChart(
            modelProducer = cpuGraphHandler.modelProducer,
            lineColors = listOf(MaterialTheme.colorScheme.primary),
            modifier = modifier
        )

        val usage by cpuUsage.collectAsState()

        SettingsToggle(
            description = stringResource(strings.cpu_usage_label, if (usage < 0) stringResource(strings.no_data) else "$usage%"),
            showSwitch = false,
            default = false
        )

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
                    SectionHeader(stringResource(strings.processor_info))

                    InfoItem(label = stringResource(strings.soc), value = cpuInfo?.soc ?: stringResource(strings.no_data), highlighted = true)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            InfoItem(stringResource(strings.architecture), cpuInfo?.arch ?: stringResource(strings.no_data))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            InfoItem(stringResource(strings.abi), cpuInfo?.abi ?: stringResource(strings.no_data))
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            InfoItem(stringResource(strings.cores), cpuInfo?.cores.toString())
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            InfoItem(stringResource(strings.governor), cpuInfo?.governor ?: stringResource(strings.no_data))
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val temperatureValue = temperature.toIntOrNull()
                            InfoItem(
                                stringResource(strings.temperature),
                                if (temperatureValue != null) {
                                    "${formatTemperature(temperatureValue, useImperialUnits)} (${stringResource(strings.estimated)})"
                                } else temperature
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            InfoCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(stringResource(strings.system_stats))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            InfoItem(stringResource(strings.procs), viewModel.procCount.collectAsState().value.toString())
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            InfoItem(stringResource(strings.threads), viewModel.threadCount.collectAsState().value.toString())
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            InfoItem(stringResource(strings.uptime), uptime)
                        }
                    }
                }
            }

            HorizontalDivider()

            if (cores.isNotEmpty()) {
                InfoCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionHeader(stringResource(strings.per_core_usage))
                        cores.forEach { core ->
                            CoreRow(core)
                        }
                    }
                }

                HorizontalDivider()
            }

            if (cpuInfo?.clusters?.isNotEmpty() == true) {
                cpuInfo?.clusters?.forEach { cluster ->
                    ClusterCard(cluster)
                }
            }
        }

        Spacer(modifier = Modifier.padding(vertical = 16.dp))
    }
}

/** One row of the per-core card: name, usage bar, usage %, current frequency. */
@Composable
fun CoreRow(core: CoreStat) {
    val freqText = when {
        !core.online -> stringResource(strings.core_offline)
        core.freqKHz > 0 -> String.format(Locale.ENGLISH, "%.2f GHz", core.freqKHz / 1_000_000.0)
        else -> "\u2014"
    }
    val usageText = if (core.online) "${core.usage}%" else "\u2014"

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "CPU${core.index}",
            style = MaterialTheme.typography.labelMedium,
            color = if (core.online) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            },
            modifier = Modifier.width(48.dp),
            maxLines = 1,
        )
        LinearProgressIndicator(
            progress = { core.usage.coerceIn(0, 100) / 100f },
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
        Text(
            text = usageText,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp),
            maxLines = 1,
        )
        Text(
            text = freqText,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.End,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(86.dp),
            maxLines = 1,
        )
    }
}

@Composable
fun ClusterCard(cluster: CpuInfoReader.CpuCluster) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = cluster.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = stringResource(strings.cores_count, cluster.cores),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            FrequencyInfo(label = stringResource(strings.min), value = cluster.minFreq ?: "—", modifier = Modifier.weight(1f))
            cluster.currentFreq?.let { freq ->
                FrequencyInfo(label = stringResource(strings.current), value = freq, modifier = Modifier.weight(1f))
            }
            FrequencyInfo(label = stringResource(strings.max), value = cluster.maxFreq ?: "—", modifier = Modifier.weight(1f))
        }
    }
}