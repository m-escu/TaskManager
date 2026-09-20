package com.rk.taskmanager.screens.net

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.rk.commons.charts.ChartConfig
import com.rk.commons.charts.GraphDataHandler
import com.rk.commons.charts.UsageChart
import com.rk.commons.getString
import com.rk.commons.settings.Settings
import com.rk.commons.strings
import com.rk.commons.ui.InfoCard
import com.rk.commons.ui.InfoItem
import com.rk.commons.ui.SectionHeader
import com.rk.commons.utils.FormatUtils
import com.rk.taskmanager.R
import com.rk.taskmanager.daemon.DaemonServer
import com.rk.taskmanager.navControllerRef
import com.rk.taskmanager.screens.drawableTobitMap
import com.rk.taskmanager.screens.selectedscreen
import com.rk.taskmanager.settings.SettingsRoutes
import com.rk.taskmanager.settings.WorkingMode
import com.rk.taskmanager.shizuku.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

val netGraphHandler = GraphDataHandler(seriesCount = 2)

private val NET_PERIOD_DAYS = intArrayOf(1, 7, 30)
private const val MAX_APP_ROWS = 50
private const val SU_TIMEOUT_SECONDS = 15L

/** Sampling window of the live per-app rate mode. */
private const val LIVE_RATE_SAMPLE_MS = 3_000L

/** The cumulative per-uid anchor is rebased after this long to bound query cost. */
private const val LIVE_RATE_REBASE_MS = 10L * 60 * 1000

private const val TAG = "NetScreen"

/** One-line summary of the last per-app query, surfaced when the list is empty. */
var lastPerAppDiag: String? = null
    private set

/** One app (or uid) row of the per-app traffic list. */
data class AppNetUsage(
    val uid: Int,
    val packageName: String?,
    val label: String,
    val rxBytes: Long,
    val txBytes: Long,
    val icon: Drawable?,
)

/** True when the app holds the PACKAGE_USAGE_STATS appop (needed for per-app traffic). */
fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        ?: return false
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        android.os.Process.myUid(),
        context.packageName,
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

/** Result of one privileged exec attempt: exit code + merged output, or null on failure. */
private typealias ExecAttempt = Pair<Int, String>?

/**
 * Runs [argv] through Shizuku (adb-level shell identity — the same identity
 * `adb shell appops set` uses, which is sufficient for appop changes even
 * without root). Null when Shizuku is unavailable or the exec throws.
 */
private suspend fun shizukuRun(argv: Array<String?>): ExecAttempt {
    return try {
        if (!ShizukuShell.isShizukuRunning()) {
            Log.d(TAG, "shizuku exec skipped (binder not alive)")
            null
        } else {
            val (exit, out) = ShizukuShell.newProcess(argv, arrayOf(), "/")
            exit to out.trim()
        }
    } catch (e: Exception) {
        Log.d(TAG, "shizuku exec ${argv.filterNotNull().joinToString(" ")} threw: ${e.message}")
        null
    }
}

/**
 * Runs [command] through `su` with a hard timeout — some su managers hang
 * waiting for an approval prompt the user never sees, and this must never
 * freeze the screen coroutine. Null on exec failure / timeout.
 */
private fun suRun(command: String): ExecAttempt {
    return try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(SU_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exit = if (finished) process.waitFor() else -1
        exit to output
    } catch (e: Exception) {
        Log.d(TAG, "su exec `$command` threw: ${e.message}")
        null
    }
}

/**
 * Self-grant attempt (fork roadmap: "appops self-grant"). The op is granted
 * through the SAME privileged channel the daemon already uses, so whatever
 * mode the user started the daemon in keeps working here:
 *  - ROOT mode -> `su -c cmd appops set ...` (APatch/Magisk/KernelSU)
 *  - SHIZUKU mode -> Shizuku exec as adb-shell identity, which holds
 *    MANAGE_APP_OPS_MODES even without root
 * The other channel is tried afterwards as a fallback (e.g. an APatch user
 * running the daemon via Shizuku can still approve the su prompt).
 *
 * The Usage access toggle in system settings flips the appop registered as
 * OPSTR_GET_USAGE_STATS, i.e. the namespaced string "android:get_usage_stats"
 * (short name "GET_USAGE_STATS"). The app's own check below uses the very same
 * constant, and `appops set` rejects anything else with "Unknown operation"
 * — the historical "android:package_usage_stats" spelling is NOT an appop
 * name (it is the *permission* name), so it is kept only as a last-ditch
 * exotic-OEM fallback. Every variant is retried until the appop flips.
 */
suspend fun grantUsageAccessViaRoot(context: Context): Boolean = withContext(Dispatchers.IO) {
    if (hasUsageAccess(context)) return@withContext true
    val pkg = context.packageName
    val opNames = listOf(
        "android:get_usage_stats",       // OPSTR_GET_USAGE_STATS — what Settings flips
        "GET_USAGE_STATS",               // legacy short name accepted by the appops shell
        "android:package_usage_stats",   // permission spellings — harmless if rejected
        "PACKAGE_USAGE_STATS",
    )

    val shizukuCmd: suspend (String) -> ExecAttempt = { op ->
        shizukuRun(arrayOf<String?>("cmd", "appops", "set", "--user", "0", pkg, op, "allow"))
    }
    val suCmd: suspend (String) -> ExecAttempt = { op ->
        suRun("cmd appops set --user 0 $pkg $op allow")
    }

    val attempts: List<suspend (String) -> ExecAttempt> = when (Settings.workingMode) {
        WorkingMode.SHIZUKU.id -> listOf(shizukuCmd, suCmd)
        else -> listOf(suCmd, shizukuCmd)
    }

    for (attempt in attempts) {
        for (op in opNames) {
            val (exit, output) = attempt(op) ?: continue
            Log.d(TAG, "grant attempt op=$op exit=$exit out=${output.take(200)}")
            if (exit == 0 && hasUsageAccess(context)) return@withContext true
        }
    }
    // The appop write can propagate asynchronously on some builds; re-check
    // a few times before giving up.
    repeat(3) {
        delay(300)
        if (hasUsageAccess(context)) return@withContext true
    }
    Log.w(TAG, "usage access grant failed (mode=${Settings.workingMode})")
    false
}

/** Aggregates Wi-Fi + mobile + Ethernet buckets per uid; null when access is missing. */
suspend fun queryPerAppUsage(
    context: Context,
    sinceMs: Long,
): Map<Int, LongArray>? = withContext(Dispatchers.IO) {
    if (!hasUsageAccess(context)) {
        Log.w(TAG, "per-app query skipped: usage access appop not granted")
        return@withContext null
    }
    val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
    if (nsm == null) {
        Log.w(TAG, "per-app query skipped: NetworkStatsManager unavailable")
        lastPerAppDiag = "NetworkStatsManager unavailable"
        return@withContext null
    }
    val end = System.currentTimeMillis()
    val perUid = HashMap<Int, LongArray>(256)
    val diag = StringBuilder()

    // The int-type querySummary (public API since 23, still present on
    // current builds) builds the matching NetworkTemplate INSIDE the
    // framework, so no reflection is needed at all. The NetworkTemplate
    // overloads are @SystemApi(MODULE_LIBRARIES) since T — reflection-blocked
    // for regular apps — which is exactly the NoSuchMethodException the
    // diagnostic line used to report. subscriberId = null means "all
    // networks" of that type (documented behaviour, allowed with usage
    // access). Each transport is queried independently.
    val transports = listOf(
        "wifi" to ConnectivityManager.TYPE_WIFI,
        "mobile" to ConnectivityManager.TYPE_MOBILE,
        "ethernet" to ConnectivityManager.TYPE_ETHERNET,
    )
    diag.append("types=").append(transports.size)
    for ((label, type) in transports) {
        var stats: NetworkStats? = null
        try {
            stats = nsm.querySummary(type, null, sinceMs, end)
            if (stats == null) {
                diag.append("; ").append(label).append(":null")
                continue
            }
            val bucket = NetworkStats.Bucket()
            var buckets = 0
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                buckets++
                if (bucket.rxBytes <= 0 && bucket.txBytes <= 0) continue
                val agg = perUid.getOrPut(bucket.uid) { LongArray(2) }
                agg[0] += bucket.rxBytes
                agg[1] += bucket.txBytes
            }
            diag.append("; ").append(label).append(":").append(buckets).append("b")
            Log.i(TAG, "per-app $label: $buckets buckets, ${perUid.size} uids cumulative")
        } catch (e: Exception) {
            // This transport may not exist on the device — the others still count.
            diag.append("; ").append(label).append(":err(").append(e.javaClass.simpleName).append(")")
            Log.w(TAG, "per-app $label failed: $e")
        } finally {
            try {
                stats?.close()
            } catch (_: Exception) {
            }
        }
    }
    lastPerAppDiag = diag.toString().take(240)
    Log.i(TAG, "per-app query done: ${perUid.size} uids | $lastPerAppDiag")
    perUid
}

private fun toAppUsages(
    context: Context,
    perUid: Map<Int, LongArray>,
): List<AppNetUsage> {
    val pm = context.packageManager
    return perUid.mapNotNull { (uid, bytes) ->
        val packages = pm.getPackagesForUid(uid)
        val packageName = packages?.firstOrNull()
        val label = try {
            if (packageName != null) {
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            } else "uid $uid"
        } catch (_: Exception) {
            packageName ?: "uid $uid"
        }
        val icon = try {
            if (packageName != null) pm.getApplicationIcon(packageName) else null
        } catch (_: Exception) {
            null
        }
        AppNetUsage(uid, packageName, label, bytes[0], bytes[1], icon)
    }.sortedByDescending { it.rxBytes + it.txBytes }
}

/**
 * Network screen (fork roadmap): live per-interface rates from the daemon's
 * NET_PING, plus per-app traffic totals from NetworkStatsManager. Per-app
 * data needs the PACKAGE_USAGE_STATS appop — self-granted via root when
 * available, otherwise the system settings page is offered. The app itself
 * still holds no INTERNET permission; this screen only reads accounting data.
 */
@Composable
fun NetScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var ifaces by remember { mutableStateOf(listOf("wlan0")) }
    var selectedIface by rememberSaveable { mutableStateOf(Settings.selectedNetInterface) }
    var downloadBps by remember { mutableStateOf(-1.0) }
    var uploadBps by remember { mutableStateOf(-1.0) }

    var usageGranted by remember { mutableStateOf(hasUsageAccess(context)) }
    var periodDays by rememberSaveable { mutableIntStateOf(1) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf<List<AppNetUsage>>(emptyList()) }

    // Per-app display mode: live transfer rates (default — the user opens
    // the tab to see what is transferring NOW) or period totals. Forced off
    // when Usage access is missing; the user's choice survives via
    // rememberSaveable while the process is alive.
    var liveMode by rememberSaveable { mutableStateOf(true) }
    var rates by remember { mutableStateOf<List<AppNetUsage>>(emptyList()) }

    // The appop can only be granted while this screen is paused (either in
    // Settings -> Usage access, or via the su/Shizuku prompt which suspends
    // the activity), so re-check on every resume and refresh the totals.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = hasUsageAccess(context)
                if (granted != usageGranted) usageGranted = granted
                refreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        ifaces = try {
            File("/sys/class/net").listFiles()?.map { it.name }?.sorted() ?: listOf("wlan0")
        } catch (_: Exception) {
            listOf("wlan0")
        }
        if (selectedIface !in ifaces) selectedIface = ifaces.firstOrNull() ?: "wlan0"
    }

    // Live rates via request-id correlated NET_PING.
    LaunchedEffect(selectedIface) {
        if (selectedIface.isEmpty()) return@LaunchedEffect
        while (isActive) {
            val response = DaemonServer.request(
                JSONObject().put("cmd", "NET_PING").put("interface", selectedIface),
                timeoutMs = 2_000,
            )
            if (response != null && response.has("rxBytesPerSec")) {
                downloadBps = response.optDouble("rxBytesPerSec", 0.0)
                uploadBps = response.optDouble("txBytesPerSec", 0.0)
                netGraphHandler.update(
                    (downloadBps / 1024.0).toInt(),
                    (uploadBps / 1024.0).toInt(),
                ) {
                    // Same gate the CPU/RAM/GPU charts use. The old
                    // `selectedscreen == 3` compared against the BOTTOM nav
                    // (0 = resources, 1 = processes) — it could never be 3,
                    // so the chart never received a single transaction and
                    // stayed a flat zero line. NetScreen is only composed
                    // while its tab (currentResource == 3) is visible, so the
                    // resource-tab half of the gate is already implied.
                    selectedscreen.intValue == 0 &&
                        navControllerRef.get()?.currentDestination?.route == SettingsRoutes.Home.route
                }
            }
            delay(1000)
        }
    }

    // Per-app totals for the selected period (also re-run after resume / grant,
    // and after leaving live mode so the totals are fresh).
    LaunchedEffect(periodDays, usageGranted, refreshTick, liveMode) {
        if (!usageGranted || liveMode) {
            apps = emptyList()
            if (!usageGranted) liveMode = false
            return@LaunchedEffect
        }
        val since = System.currentTimeMillis() - periodDays * 24L * 3600 * 1000
        val perUid = queryPerAppUsage(context, since)
        apps = if (perUid == null) emptyList() else toAppUsages(context, perUid)
    }

    // Live per-app rates: NetworkStatsManager only exposes cumulative
    // counters, so the rate is the DIFF of two consecutive snapshots taken
    // from the same anchor. A fixed anchor keeps consecutive snapshots
    // comparable even when the framework hands out coarse buckets — any
    // bytes that land between snapshots always show up in the next diff.
    LaunchedEffect(usageGranted, liveMode) {
        if (!usageGranted || !liveMode) {
            rates = emptyList()
            return@LaunchedEffect
        }
        var anchor = System.currentTimeMillis() - LIVE_RATE_SAMPLE_MS
        val baseline = queryPerAppUsage(context, anchor)
        if (baseline == null) {
            // queryPerAppUsage returns null only when usage access vanished —
            // drop out of live mode instead of spinning on failed queries.
            liveMode = false
            return@LaunchedEffect
        }
        // Non-null type on purpose: a nullable loop-carried var would lose
        // its null-check smart cast on every loop iteration.
        var prev: Map<Int, LongArray> = baseline
        var prevStamp = System.currentTimeMillis()
        while (isActive) {
            delay(LIVE_RATE_SAMPLE_MS)
            val cur = queryPerAppUsage(context, anchor)
            if (cur == null) {
                liveMode = false
                break
            }
            val now = System.currentTimeMillis()
            val elapsedSec = ((now - prevStamp) / 1000.0).coerceAtLeast(0.5)
            prevStamp = now

            // Per-uid delta since the previous snapshot, converted to bytes/s.
            val delta = HashMap<Int, LongArray>(cur.size)
            for ((uid, curBytes) in cur) {
                val prevBytes = prev[uid]
                val rx = (curBytes[0] - (prevBytes?.get(0) ?: 0L)).coerceAtLeast(0L)
                val tx = (curBytes[1] - (prevBytes?.get(1) ?: 0L)).coerceAtLeast(0L)
                if (rx > 0L || tx > 0L) {
                    delta[uid] = longArrayOf(
                        (rx / elapsedSec).toLong(),
                        (tx / elapsedSec).toLong(),
                    )
                }
            }
            rates = toAppUsages(context, delta)

            // Rebase the anchor periodically so each query never walks the
            // whole history; the next snapshot becomes the new baseline.
            prev = cur
            if (now - anchor > LIVE_RATE_REBASE_MS) {
                anchor = now
            }
        }
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        UsageChart(
            modelProducer = netGraphHandler.modelProducer,
            lineColors = listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.tertiary,
            ),
            modifier = modifier.fillMaxWidth(),
            // Rates are KB/s values, not percentages — dynamic range,
            // plain labels (the shared default would clip above 100).
            rangeProvider = ChartConfig.AutoRangeProvider,
            valueFormatter = ChartConfig.PlainStartAxisValueFormatter,
            markerValueFormatter = ChartConfig.PlainMarkerValueFormatter,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HorizontalDivider()

            InfoCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(stringResource(strings.net_live_rates))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ifaces.forEach { iface ->
                            FilterChip(
                                selected = selectedIface == iface,
                                onClick = {
                                    selectedIface = iface
                                    Settings.selectedNetInterface = iface
                                },
                                label = { Text(iface) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            InfoItem(
                                stringResource(strings.net_download),
                                if (downloadBps >= 0) FormatUtils.formatBytes(downloadBps.toLong()) + "/s" else stringResource(strings.no_data),
                                highlighted = true
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            InfoItem(
                                stringResource(strings.net_upload),
                                if (uploadBps >= 0) FormatUtils.formatBytes(uploadBps.toLong()) + "/s" else stringResource(strings.no_data),
                                highlighted = true
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            InfoCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(stringResource(strings.net_per_app))

                    if (!usageGranted) {
                        Text(
                            text = stringResource(strings.net_usage_access_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row {
                            TextButton(onClick = {
                                scope.launch {
                                    usageGranted = grantUsageAccessViaRoot(context)
                                    if (!usageGranted) {
                                        Toast.makeText(
                                            context,
                                            strings.net_grant_failed.getString(),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }) {
                                Text(stringResource(strings.net_grant_via_root))
                            }
                            TextButton(onClick = {
                                try {
                                    context.startActivity(
                                        Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                } catch (_: Exception) {
                                }
                            }) {
                                Text(stringResource(strings.net_open_settings))
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = liveMode,
                                onClick = { liveMode = true },
                                label = { Text(stringResource(strings.net_live_chip)) }
                            )
                            NET_PERIOD_DAYS.forEach { days ->
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

                        Text(
                            text = if (liveMode) {
                                stringResource(strings.net_live_hint)
                            } else {
                                stringResource(strings.net_totals_hint)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (liveMode) {
                            if (rates.isEmpty()) {
                                Text(
                                    text = stringResource(strings.net_no_active),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                rates.take(MAX_APP_ROWS).forEach { usage ->
                                    AppNetRow(usage, liveRate = true)
                                }
                            }
                        } else if (apps.isEmpty()) {
                            Text(
                                text = stringResource(strings.net_no_data),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            lastPerAppDiag?.takeIf { it.isNotBlank() }?.let { diag ->
                                Text(
                                    text = stringResource(strings.net_diag_prefix, diag),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            apps.take(MAX_APP_ROWS).forEach { usage ->
                                AppNetRow(usage)
                            }
                            if (apps.size > MAX_APP_ROWS) {
                                Text(
                                    text = stringResource(strings.net_showing_top, MAX_APP_ROWS),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.padding(vertical = 16.dp))
    }
}

@Composable
fun AppNetRow(usage: AppNetUsage, liveRate: Boolean = false) {
    val iconBitmap: ImageBitmap? = remember(usage.icon) {
        usage.icon?.let { drawableTobitMap(it)?.asImageBitmap() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
        } else {
            Icon(
                painter = painterResource(id = R.drawable.ic_android_black_24dp),
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.padding(start = 10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = usage.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1
            )
            Text(
                text = if (liveRate) {
                    // rx/tx already hold bytes-per-second in live mode.
                    stringResource(
                        strings.net_app_rate,
                        FormatUtils.formatBytes(usage.rxBytes),
                        FormatUtils.formatBytes(usage.txBytes),
                    )
                } else {
                    stringResource(
                        strings.net_app_traffic,
                        FormatUtils.formatBytes(usage.rxBytes),
                        FormatUtils.formatBytes(usage.txBytes),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
