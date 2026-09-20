package com.rk.taskmanager.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rk.commons.getString
import com.rk.commons.settings.Settings
import com.rk.commons.strings
import com.rk.components.XedDialog
import com.rk.taskmanager.ProcessUiModel
import com.rk.taskmanager.TaskManager
import com.rk.taskmanager.daemon.DaemonClient
import com.rk.taskmanager.daemon.KillAction
import kotlinx.coroutines.delay

/**
 * Confirmation dialog for stopping a process, honoring the fork's kill
 * policy ([Settings.defaultKillAction]):
 *
 *  - ASK (default): shows both "Terminate" (SIGTERM -> SIGKILL after the
 *    grace period) and "Force kill" (immediate SIGKILL).
 *  - TERMINATE / FORCE: shows a single button for the configured action.
 *  - [forceAsk] overrides the policy with the ASK-style chooser; used for
 *    system apps, which are always confirmed before termination.
 *
 * [onConfirm] receives the concrete action chosen by the user.
 */
@Composable
fun KillConfirmDialog(
    processName: String,
    onDismiss: () -> Unit,
    onConfirm: (KillAction) -> Unit,
    forceAsk: Boolean = false,
) {
    XedDialog(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {

            Text(
                text = stringResource(strings.terminate),
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.padding(vertical = 8.dp))

            Text(text = stringResource(strings.terminate_confirm, processName))

            Spacer(modifier = Modifier.padding(vertical = 16.dp))

            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(strings.cancel))
                }

                Spacer(modifier = Modifier.width(8.dp))

                when (if (forceAsk) KillAction.ASK else KillAction.fromId(Settings.defaultKillAction)) {
                    KillAction.ASK -> {
                        TextButton(onClick = { onConfirm(KillAction.TERMINATE) }) {
                            Text(stringResource(strings.kill_action_terminate))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = { onConfirm(KillAction.FORCE) }) {
                            Text(
                                text = stringResource(strings.force_kill),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    KillAction.FORCE -> {
                        TextButton(onClick = { onConfirm(KillAction.FORCE) }) {
                            Text(
                                text = stringResource(strings.force_kill),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    else -> {
                        TextButton(onClick = { onConfirm(KillAction.TERMINATE) }) {
                            Text(
                                text = stringResource(strings.kill_action_terminate),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Runs the kill through [DaemonClient] while driving the shared
 * killing/killed UI states of the process row, and toasts on failure.
 *
 * Replaces the old racy `daemon_messages.first { KILL_RESULT }` wait with
 * request-id correlated responses.
 */
suspend fun ProcessUiModel.killWithUiState(action: KillAction): Boolean {
    killing.value = true
    val ok = runCatching { DaemonClient.kill(proc, action) }.getOrDefault(false)
    delay(300)
    killing.value = false
    killed.value = ok
    if (!ok) {
        runCatching {
            Toast.makeText(
                TaskManager.requireContext(),
                strings.kill_failed.getString(),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    return ok
}
