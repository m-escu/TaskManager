package com.rk.taskmanager.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.rk.components.SettingsToggle
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.components.compose.preferences.base.PreferenceLayout
import com.rk.components.compose.preferences.base.PreferenceTemplate
import com.rk.taskmanager.BuildConfig
import com.rk.commons.strings
import com.rk.taskmanager.R

private const val FORK_REPO_URL = "https://github.com/m-escu/TaskManager"
private const val FORK_AUTHOR_URL = "https://github.com/m-escu"
private const val UPSTREAM_AUTHOR_URL = "https://github.com/RohitKushvaha01"

@Composable
fun About(modifier: Modifier = Modifier) {
    val packageInfo = LocalContext.current.packageManager.getPackageInfo(LocalContext.current.packageName, 0)
    val versionName = packageInfo.versionName
    val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
    val context = LocalContext.current

    PreferenceLayout(label = stringResource(strings.about), backArrowVisible = true) {
        PreferenceGroup(heading = stringResource(strings.developer)) {
            contributorRow(
                label = "m-escu",
                description = stringResource(strings.fork_maintainer),
                url = FORK_AUTHOR_URL,
            )
            contributorRow(
                label = "RohitKushvaha01",
                description = stringResource(strings.original_author),
                url = UPSTREAM_AUTHOR_URL,
            )
        }

        PreferenceGroup(heading = stringResource(strings.project)) {
            contributorRow(
                label = stringResource(strings.source_code),
                description = FORK_REPO_URL.removePrefix("https://"),
                url = FORK_REPO_URL,
            )
        }

        PreferenceGroup(heading = stringResource(strings.build_info)) {
            PreferenceTemplate(
                modifier =
                    Modifier.combinedClickable(
                        enabled = true,
                        onClick = {},
                        onLongClick = { copyToClipboard(context, versionName ?: "unknown") },
                    ),
                title = {
                    Text(text = stringResource(strings.version), style = MaterialTheme.typography.titleMedium)
                },
                description = { Text(text = versionName ?: "unknown", style = MaterialTheme.typography.titleSmall) },
            )

            PreferenceTemplate(
                modifier =
                    Modifier.combinedClickable(
                        enabled = true,
                        onClick = {},
                        onLongClick = { copyToClipboard(context,versionCode.toString()) },
                    ),
                title = {
                    Text(text = stringResource(strings.version_code), style = MaterialTheme.typography.titleMedium)
                },
                description = { Text(text = versionCode.toString(), style = MaterialTheme.typography.titleSmall) },
            )

            PreferenceTemplate(
                modifier =
                    Modifier.combinedClickable(
                        enabled = true,
                        onClick = {},
                        onLongClick = { copyToClipboard(context,BuildConfig.GIT_SHORT_COMMIT_HASH) },
                    ),
                title = {
                    Text(text = stringResource(strings.git_hash), style = MaterialTheme.typography.titleMedium)
                },
                description = {
                    Text(text = BuildConfig.GIT_SHORT_COMMIT_HASH, style = MaterialTheme.typography.titleSmall)
                },
            )

            PreferenceTemplate(
                modifier =
                    Modifier.combinedClickable(
                        enabled = true,
                        onClick = {},
                        onLongClick = { copyToClipboard(context,BuildConfig.GIT_SHORT_COMMIT_HASH) },
                    ),
                title = {
                    Text(text = stringResource(strings.build_type), style = MaterialTheme.typography.titleMedium)
                },
                description = {
                    // No bridge/Pro in this fork — it is the open-source community build.
                    Text(text = stringResource(strings.community), style = MaterialTheme.typography.titleSmall)
                },
            )

        }
    }
}

/**
 * A tappable row that opens a GitHub profile/repo in the browser. Fully
 * offline in the app itself: the avatar is a local vector (this fork has no
 * INTERNET permission by design), only the external browser goes online.
 */
@Composable
private fun contributorRow(label: String, description: String, url: String) {
    val context = LocalContext.current
    SettingsToggle(
        label = label,
        description = description,
        default = false,
        sideEffect = {
            val intent = Intent(Intent.ACTION_VIEW).apply { data = Uri.parse(url) }
            runCatching { context.startActivity(intent) }
        },
        showSwitch = false,
        startWidget = {
            Box(
                modifier =
                    Modifier.padding(start = 16.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.github),
                    contentDescription = "GitHub",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        endWidget = {
            Icon(
                modifier = Modifier.padding(16.dp),
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
            )
        },
    )
}


private fun copyToClipboard(context: Context,text: String, showToast: Boolean = true) {
    copyToClipboard(context,label = "TaskManager", text, showToast = showToast)
}


private fun copyToClipboard(context: Context,label: String, text: String, showToast: Boolean = true) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
    if (showToast) {
        Toast.makeText(context, context.getString(strings.copied_to_clipboard), Toast.LENGTH_SHORT).show()
    }
}
