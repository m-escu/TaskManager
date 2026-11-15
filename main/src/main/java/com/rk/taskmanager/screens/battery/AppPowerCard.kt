package com.rk.taskmanager.screens.battery

import android.content.Context
import android.os.Process
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rk.commons.strings
import com.rk.taskmanager.daemon.DaemonClient
import com.rk.taskmanager.daemon.isConnected
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** One row of the per-app battery card. */
data class AppPowerRow(
    val uid: Int,
    val label: String,
    val mAh: Double,
    val wakelockMs: Long,
)

/** "1 h 5 min" / "12 min" / "40 s" for wakelock totals. */
fun formatDurationShort(ms: Long): String {
    val s = ms / 1000
    return when {
        s >= 3600 -> String.format(Locale.US, "%d h %d min", s / 3600, (s % 3600) / 60)
        s >= 60 -> String.format(Locale.US, "%d min", s / 60)
        else -> String.format(Locale.US, "%d s", s)
    }
}

/** Human label for a uid: app label, or a well-known system uid name. */
private fun labelForUid(context: Context, uid: Int): String {
    when (uid) {
        Process.ROOT_UID -> return "root / kernel"
        Process.SYSTEM_UID -> return "Android System"
        1001 -> return "Radio"
        1002 -> return "Bluetooth"
        1013 -> return "Media"
        Process.SHELL_UID -> return "Shell"
    }
    val pm = context.packageManager
    val pkgs = runCatching { pm.getPackagesForUid(uid) }.getOrNull().orEmpty()
    val first = pkgs.firstOrNull() ?: return "uid $uid"
    val label = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(first, 0)).toString()
    }.getOrNull() ?: first
    return if (pkgs.size > 1) "$label (+${pkgs.size - 1})" else label
}

/**
 * Top apps by estimated battery use since the last full charge, plus their
 * partial-wakelock time (daemon APP_POWER, from batterystats). Hidden when
 * the connected daemon lacks the app_power cap.
 */
@Composable
fun AppPowerCard(limit: Int = 10) {
    if (!isConnected || !DaemonClient.hasCap("app_power")) return
    val context = LocalContext.current
    var rows by remember { mutableStateOf<List<AppPowerRow>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTick) {
        loading = true
        failed = false
        val response = DaemonClient.appPower()
        val arr = response?.optJSONArray("apps")
        if (response == null || arr == null || !response.optBoolean("available", false)) {
            failed = true
        } else {
            rows = withContext(Dispatchers.IO) {
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .filter { it.optDouble("mAh", 0.0) > 0.0 || it.optLong("wakelockMs", 0L) > 0L }
                    .sortedByDescending { it.optDouble("mAh", 0.0) }
                    .take(limit)
                    .map {
                        val uid = it.optInt("uid")
                        AppPowerRow(
                            uid = uid,
                            label = labelForUid(context, uid),
                            mAh = it.optDouble("mAh", 0.0),
                            wakelockMs = it.optLong("wakelockMs", 0L),
                        )
                    }
            }
        }
        loading = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(strings.batt_app_power),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.width(20.dp))
            } else {
                TextButton(onClick = { refreshTick++ }) { Text(stringResource(strings.refresh)) }
            }
        }
        Text(
            text = stringResource(strings.batt_app_power_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            failed -> Text(
                text = stringResource(strings.batt_app_power_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            rows.isNullOrEmpty() && !loading -> Text(
                text = stringResource(strings.batt_app_power_empty),
                style = MaterialTheme.typography.bodySmall,
            )
            else -> rows.orEmpty().forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = row.label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = String.format(Locale.US, "%.0f mAh", row.mAh),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(72.dp),
                    )
                    Text(
                        text = if (row.wakelockMs > 0) formatDurationShort(row.wakelockMs) else "—",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(76.dp),
                    )
                }
            }
        }
    }
}
