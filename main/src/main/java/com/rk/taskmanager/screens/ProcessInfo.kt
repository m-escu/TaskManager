package com.rk.taskmanager.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.rk.components.SettingsToggle
import com.rk.components.TextCard
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.components.compose.preferences.base.PreferenceTemplate
import com.rk.taskmanager.ProcessUiModel
import com.rk.taskmanager.ProcessViewModel
import com.rk.taskmanager.TaskManager
import com.rk.taskmanager.daemon.DaemonClient
import com.rk.taskmanager.daemon.DaemonServer
import com.rk.taskmanager.daemon.KillAction
import com.rk.commons.getString
import com.rk.taskmanager.settings.SettingsRoutes
import com.rk.commons.strings
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

fun elapsedFromStartTime(startTimeTicks: Long): String {
    val processStartMillis = startTimeToMillis(startTimeTicks)
    val now = System.currentTimeMillis()
    val elapsedSeconds = (now - processStartMillis) / 1000

    val h = TimeUnit.SECONDS.toHours(elapsedSeconds)
    val m = TimeUnit.SECONDS.toMinutes(elapsedSeconds) % 60
    val s = elapsedSeconds % 60

    return String.format("%02d:%02d:%02d", h, m, s)
}

fun startTimeToMillis(startTimeTicks: Long): Long {
    val ticksPerSecond = sysconf() // custom helper below
    val bootTimeMillis = System.currentTimeMillis() - SystemClock.elapsedRealtime()
    val processStartMillis = bootTimeMillis + (startTimeTicks * 1000 / ticksPerSecond)
    return processStartMillis
}

fun sysconf(): Long {
    return Os.sysconf(OsConstants._SC_CLK_TCK)
}

/**
 * Formats raw CPU ticks (utime+stime from /proc/<pid>/stat) as cumulative
 * processor time. Independent of wall time: a multi-threaded process can
 * easily accumulate more CPU seconds than it has been alive.
 */fun formatCpuTicks(ticks: Long): String {
    if (ticks <= 0) return "\u2014"
    val ticksPerSecond = try {
        sysconf()
    } catch (_: Exception) {
        100L
    }
    val totalSeconds = ticks / ticksPerSecond
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format(Locale.ENGLISH, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ENGLISH, "%02d:%02d", m, s)
}

/** Human-readable size for KB values coming from /proc (RSS, PSS, ...). */
fun formatSizeKb(kb: Long): String {
    return if (kb >= 1000) {
        val mb = kb / 1024f
        String.format(Locale.US, "%.2f MB", mb)
    } else {
        "$kb KB"
    }
}

fun isAppInstalled(context: Context, packageName: String): Boolean {
    return try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}

fun getApkNameFromPackage(context: Context, packageName: String): String? {
    return try {
        context.packageManager.getApplicationLabel(
            context.packageManager.getApplicationInfo(
                packageName,
                PackageManager.GET_META_DATA
            )
        ).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}

fun isSystemApp(context: Context, packageName: String): Boolean {
    return try {
        val packageManager = context.packageManager
        val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
        (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    } catch (e: PackageManager.NameNotFoundException) {
        // Package not found
        false
    }
}


fun getAppIcon(context: Context, packageName: String): Drawable? {
    return try {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(packageName, 0)
        pm.getApplicationIcon(appInfo)
    } catch (e: PackageManager.NameNotFoundException) {
        null // App not found
    }
}

fun drawableTobitMap(drawable: Drawable?): Bitmap? {
    return drawable?.let {
        if (it is BitmapDrawable) {
            it.bitmap
        } else {
            val width = maxOf(1, it.intrinsicWidth)
            val height = maxOf(1, it.intrinsicHeight)
            val bitmap = createBitmap(width, height)
            val canvas = Canvas(bitmap)
            it.setBounds(0, 0, canvas.width, canvas.height)
            it.draw(canvas)
            bitmap
        }
    }
}

fun getAppIconBitmap(context: Context, packageName: String): Bitmap? {
    val drawable = getAppIcon(context, packageName)
    return drawableTobitMap(drawable)
}


val procByPid = mutableStateMapOf<Int, WeakReference<ProcessUiModel?>?>()


@OptIn(
    ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class,
    DelicateCoroutinesApi::class
)
@Composable
fun ProcessInfo(
    modifier: Modifier = Modifier,
    navController: NavController,
    viewModel: ProcessViewModel,
    proc: ProcessUiModel
) {
    var showKillDialog by remember { mutableStateOf<ProcessUiModel?>(null) }

    val username = remember { mutableStateOf(strings.unknown.getString()) }
    val scope = rememberCoroutineScope()
    val cpuUsage = remember { mutableIntStateOf(-1) }

    LaunchedEffect(proc) {
        username.value = getUsernameFromUid(proc?.proc?.uid!!) ?: proc?.proc?.uid.toString()
    }


    Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
        TopAppBar(title = {
            Text(stringResource(strings.proc_info))
        }, navigationIcon = {
            IconButton(onClick = {
                navController.popBackStack()
            }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Default.ArrowBack,
                    contentDescription = stringResource(strings.go_back)
                )
            }
        })
    }) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding), contentAlignment = Alignment.Center) {
            Column(modifier.verticalScroll(rememberScrollState())) {
                PreferenceGroup {
                    val enabled = proc!!.proc.pid > 1 && proc!!.killed.value.not() && proc!!.proc.cmdLine != "zygote" && proc!!.proc.cmdLine != "zygote64"
                    val interactionSource = remember { MutableInteractionSource() }
                    PreferenceTemplate(
                        modifier = modifier
                            .combinedClickable(
                                enabled = enabled,
                                indication = ripple(),
                                interactionSource = interactionSource,
                                onClick = {
                                    showKillDialog = proc
                                }
                            ),
                        contentModifier = Modifier
                            .fillMaxHeight()
                            .padding(vertical = 16.dp)
                            .padding(start = 16.dp),
                        title = {
                            Text(
                                fontWeight = FontWeight.Bold,
                                text =
                                    if (proc!!.killing.value) {
                                        stringResource(
                                            if (proc.isApp) {
                                                strings.stopping
                                            } else {
                                                strings.killing
                                            }
                                        )
                                    } else {
                                        if (proc!!.killed.value!!) {
                                            stringResource(
                                                if (proc.isApp) {
                                                    strings.killed
                                                } else {
                                                    strings.stopped
                                                }
                                            )
                                        } else {
                                            stringResource(strings.kill)
                                        }
                                    },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        description = { Text(stringResource(strings.kill_proc)) },
                        enabled = enabled,
                        applyPaddings = false,
                        endWidget = null,
                        startWidget = {
                            if (proc!!.killing.value) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .padding(start = 16.dp)
                                        .alpha(if (enabled) 1f else 0.3f),
                                )
                            } else {
                                if (proc!!.killed.value) {
                                    Icon(
                                        modifier = Modifier
                                            .padding(start = 16.dp)
                                            .alpha(if (enabled) 1f else 0.3f),
                                        imageVector = Icons.Outlined.Check,
                                        contentDescription = null
                                    )
                                }else{
                                    Icon(
                                        modifier = Modifier
                                            .padding(start = 16.dp)
                                            .alpha(if (enabled) 1f else 0.3f),
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = null
                                    )
                                }

                            }
                        }
                    )

                    SettingsToggle(
                        label = if (proc.isPinned.value) stringResource(strings.unpin) else stringResource(strings.pin),
                        description = if (proc.isPinned.value) stringResource(strings.unpin_desc) else stringResource(strings.pin_desc),
                        default = proc.isPinned.value,
                        isEnabled = true,
                        showSwitch = true,
                        sideEffect = {
                            viewModel.togglePin(proc)
                        }
                    )
                }

                PreferenceGroup {
                    var name by remember { mutableStateOf(strings.loading.getString()) }


                    LaunchedEffect(proc) {
                        name = getApkNameFromPackage(
                            TaskManager.requireContext(),
                            proc!!.proc.cmdLine
                        ) ?: proc!!.proc.name
                    }

                    TextCard(text = stringResource(strings.name), description = name.trim())
                    TextCard(text = stringResource(strings.pid), description = proc!!.proc.pid.toString())
                    TextCard(
                        text = stringResource(
                            if (proc.isApp) {
                                strings.str_package
                            } else {
                                strings.command
                            }
                        ),
                        description = proc!!.proc.cmdLine.ifEmpty {
                            stringResource(strings.no_cmd)
                        }
                    )
                    TextCard(text = stringResource(strings.user), description = username.value)


                    // Request-id correlated per-process CPU polling: only the
                    // response carrying our id updates this screen (no
                    // cross-talk with other concurrent viewers).
                    LaunchedEffect(proc) {
                        while (isActive) {
                            val response = DaemonServer.request(
                                JSONObject()
                                    .put("cmd", "PING_PID_CPU")
                                    .put("pid", proc!!.proc.pid),
                                timeoutMs = 2_000,
                            )
                            if (response != null) {
                                cpuUsage.intValue = response.optInt("usage", -1)
                            }
                            delay(1000)
                        }
                    }

                    TextCard(
                        text = stringResource(strings.cpu_usage),
                        description = (if (cpuUsage.intValue == -1) {
                            proc!!.proc.cpuUsage.roundToInt().toString()
                        } else {
                            cpuUsage.intValue
                        }).toString() + "% (${strings.estimated.getString()})"
                    )

                    TextCard(
                        text = stringResource(strings.cpu_time),
                        description = formatCpuTicks(proc!!.proc.cpuTimeTicks)
                    )

                    // Lifetime-average load: the cumulative CPU time spread
                    // over the process's whole lifetime — the stable
                    // counterpart to the spiky instantaneous reading above.
                    TextCard(
                        text = stringResource(strings.avg_cpu_usage),
                        description = if (proc!!.proc.avgCpuPercent >= 0f) {
                            String.format(Locale.ENGLISH, "%.1f%%", proc!!.proc.avgCpuPercent) +
                                " (${strings.avg_cpu_since_start.getString()})"
                        } else {
                            strings.no_data.getString()
                        }
                    )
                    TextCard(
                        text = stringResource(strings.is_foreground),
                        description = proc!!.proc.isForeground.toString()
                    )

                    TextCard(
                        text = stringResource(strings.ram_usage),
                        description = formatSizeKb(proc!!.proc.memoryUsageKb)
                    )

                    if (proc!!.proc.residentSetSizeKb != proc!!.proc.memoryUsageKb) {
                        TextCard(
                            text = stringResource(strings.actual_ram_usage),
                            description = formatSizeKb(proc!!.proc.residentSetSizeKb)
                        )
                    }


                    TextCard(
                        text = stringResource(strings.niceness),
                        description = "${proc!!.proc.nice}"
                    )

                    TextCard(
                        text = stringResource(strings.status),
                        description = proc!!.proc.state
                    )

                    TextCard(
                        text = stringResource(strings.threads),
                        description = proc!!.proc.threads.toString()
                    )

                    TextCard(
                        text = stringResource(strings.start_time),
                        description = DateFormat.getDateTimeInstance().format(
                            Date(startTimeToMillis(proc!!.proc.startTime))
                        )
                    )

                    var elapsed by remember { mutableStateOf("") }

                    val startTimeTicks = proc!!.proc.startTime
                    LaunchedEffect(startTimeTicks) {
                        while (isActive) {
                            elapsed = elapsedFromStartTime(startTimeTicks)
                            delay(1000)
                        }
                    }

                    TextCard(
                        text = stringResource(strings.elapsed_time),
                        description = elapsed
                    )

                    if (proc!!.proc.executablePath != "null") {
                        TextCard(
                            text = stringResource(strings.exec_path),
                            description = proc!!.proc.executablePath
                        )
                    }

                    if (proc!!.proc.parentPid != 0) {

                        val text = stringResource(strings.parent_pid)
                        val description = proc!!.proc.parentPid.toString()
                        SettingsToggle(
                            label = text,
                            description = description,
                            default = false,
                            showSwitch = false,
                            onLongClick = {
                                val clipboard = TaskManager.requireContext()
                                    .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText(text, description)
                                clipboard.setPrimaryClip(clip)

                                Toast.makeText(
                                    TaskManager.requireContext(),
                                    strings.copied.getString(),
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            endWidget = {
                                Icon(
                                    modifier = Modifier.padding(end = 16.dp),
                                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                    contentDescription = null
                                )
                            },
                            sideEffect = {
                                scope.launch(Dispatchers.IO) {
                                    val parent = viewModel.uiProcesses.value.find {
                                        it.proc.pid == proc!!.proc.parentPid
                                    }

                                    withContext(Dispatchers.Main){
                                        if (parent != null){
                                            navController.navigate(
                                                SettingsRoutes.ProcessInfo.createRoute(
                                                    parent
                                                )
                                            )
                                        }else{
                                            Toast.makeText(TaskManager.requireContext(), strings.parent_not_found.getString(), Toast.LENGTH_SHORT).show()
                                        }
                                    }

                                }

                            })

                    }

                    val context = LocalContext.current
                    if (proc.isApp){
                        SettingsToggle(
                            label = stringResource(strings.app_info),
                            description = stringResource(strings.app_info_desc),
                            default = false,
                            showSwitch = false,
                            endWidget = {
                                Icon(
                                    modifier = Modifier.padding(end = 16.dp),
                                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                    contentDescription = null
                                )
                            },
                            sideEffect = {
                                val packageName = proc!!.proc.cmdLine
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = "package:$packageName".toUri()
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            })
                    }


                }


                // PSS breakdown (fork decision: PSS/RSS toggle target). Polled
                // via the typed DaemonClient: responses are matched by request
                // id, and daemons without the pss_ping cap (ERROR answer or
                // missing field) degrade to an "unavailable" row set.
                val pssState = remember(proc!!.proc.pid) { mutableStateOf<PssState>(PssState.Loading) }

                LaunchedEffect(proc!!.proc.pid) {
                    while (isActive) {
                        val response = DaemonClient.pss(proc.proc.pid, timeoutMs = 2_000)
                        pssState.value = when {
                            response == null -> PssState.Unavailable
                            response.optBoolean("available", false) -> PssState.Ready(
                                pssKb = response.optLong("pssKb", -1),
                                anonKb = response.optLong("pssAnonKb", -1),
                                fileKb = response.optLong("pssFileKb", -1),
                                swapKb = response.optLong("swapPssKb", -1),
                                privateKb = response.optLong("privateKb", -1),
                            )
                            else -> PssState.Unavailable
                        }
                        delay(3_000)
                    }
                }

                PreferenceGroup(heading = stringResource(strings.pss_details)) {
                    when (val state = pssState.value) {
                        PssState.Loading -> TextCard(
                            text = stringResource(strings.pss_total),
                            description = stringResource(strings.loading)
                        )
                        PssState.Unavailable -> TextCard(
                            text = stringResource(strings.pss_unavailable),
                            description = null,
                            selection = true,
                            copyDesOnLong = false
                        )
                        is PssState.Ready -> {
                            TextCard(text = stringResource(strings.pss_total), description = formatSizeKb(state.pssKb))
                            if (state.anonKb >= 0) TextCard(text = stringResource(strings.pss_anon), description = formatSizeKb(state.anonKb))
                            if (state.fileKb >= 0) TextCard(text = stringResource(strings.pss_file), description = formatSizeKb(state.fileKb))
                            if (state.swapKb >= 0) TextCard(text = stringResource(strings.pss_swap), description = formatSizeKb(state.swapKb))
                            if (state.privateKb >= 0) TextCard(text = stringResource(strings.pss_private), description = formatSizeKb(state.privateKb))
                        }
                    }
                }


                if (proc?.isApp == true) {
                    val descriptionState by produceState<DescriptionState>(initialValue = DescriptionState.Loading, key1 = proc?.proc?.cmdLine) {
                        val db = TaskManager.getDatabase(TaskManager.requireContext())
                        val desc = withContext(Dispatchers.IO) {
                            db.appDao().getDescription(proc!!.proc.cmdLine)
                        }

                        value = if (desc.isNullOrBlank()) {
                            DescriptionState.Empty
                        } else {
                            DescriptionState.Success(desc)
                        }
                    }

                    PreferenceGroup(heading = stringResource(strings.debloater_info)) {
                        when (descriptionState) {
                            is DescriptionState.Loading -> TextCard(text = stringResource(strings.loading), description = null, selection = true, copyDesOnLong = false)
                            is DescriptionState.Success -> TextCard(text = null, description = (descriptionState as DescriptionState.Success).text, selection = true,copyDesOnLong = false)
                            is DescriptionState.Empty -> TextCard(text = null, description = stringResource(strings.no_info_debloater), selection = true,copyDesOnLong = false)
                        }
                    }
                }




                Spacer(modifier = Modifier.padding(16.dp))
            }
        }

    }

    if (showKillDialog != null) {
        val target = showKillDialog
        // Kill policy (fork14): "Confirm stop" is the MASTER switch for the
        // dialog (previously the dialog only appeared in Ask mode, so the
        // toggle looked dead). With it on, the dialog offers the configured
        // default action; with it off, the default action runs immediately
        // (Ask resolves to Terminate). System apps ALWAYS confirm.
        val alwaysAsk = target?.isSystemApp == true
        if (alwaysAsk || com.rk.commons.settings.Settings.confirmkill) {
            KillConfirmDialog(
                processName = target?.name ?: "",
                forceAsk = alwaysAsk,
                onDismiss = { showKillDialog = null },
                onConfirm = { action ->
                    val killTarget = showKillDialog
                    showKillDialog = null
                    viewModel.viewModelScope.launch {
                        killTarget?.killWithUiState(action)
                    }
                },
            )
        } else {
            LaunchedEffect(Unit) {
                val killTarget = showKillDialog
                showKillDialog = null
                viewModel.viewModelScope.launch {
                    killTarget?.killWithUiState(
                        KillAction.fromId(com.rk.commons.settings.Settings.defaultKillAction).resolve()
                    )
                }
            }
        }
    }
}


suspend fun getUsernameFromUid(uid: Int): String? = withContext(Dispatchers.IO) {
    return@withContext try {
        val process = ProcessBuilder("id", "-nu", uid.toString()).start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        reader.readLine()?.trim()
    } catch (e: Exception) {
        null
    }
}

sealed class DescriptionState {
    object Loading : DescriptionState()
    data class Success(val text: String) : DescriptionState()
    object Empty : DescriptionState()
}

/** PSS polling state for the ProcessInfo memory-details group. */
sealed class PssState {
    data object Loading : PssState()
    data object Unavailable : PssState()
    data class Ready(
        val pssKb: Long,
        val anonKb: Long,
        val fileKb: Long,
        val swapKb: Long,
        val privateKb: Long,
    ) : PssState()
}
