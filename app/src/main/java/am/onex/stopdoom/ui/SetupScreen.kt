package am.onex.stopdoom.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import am.onex.stopdoom.vpn.DnsFilterVpnService

/**
 * The permission checklist. Re-checks on every resume, because every one of these
 * is granted in a different Settings screen and the only reliable signal that it
 * worked is looking again when the user comes back.
 */
@Composable
fun SetupScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var statuses by remember { mutableStateOf(readStatuses(context)) }

    LifecycleResumeEffect(Unit) {
        statuses = readStatuses(context)
        onPauseOrDispose { }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { statuses = readStatuses(context) }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            DnsFilterVpnService.start(context)
        }
        statuses = readStatuses(context)
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { statuses = readStatuses(context) }

    val remaining = statuses.count { !it.value && it.key.required }

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        item {
            SectionCard(
                title = if (remaining == 0) "Setup complete" else "$remaining required step(s) left",
                subtitle = "Each of these is granted in system settings, not here.",
            ) {
                Hint(
                    "Anything unchecked is a hole in the protection. The service in " +
                        "particular is not optional - without it nothing is watched at all.",
                )
            }
        }

        items(SetupStep.entries.toList()) { step ->
            val satisfied = statuses[step] == true
            SectionCard(
                title = step.title,
                subtitle = if (step.required) null else "Optional",
                trailing = {
                    Icon(
                        imageVector = if (satisfied) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                        contentDescription = if (satisfied) "granted" else "not granted",
                        tint = when {
                            satisfied -> MaterialTheme.colorScheme.primary
                            step.required -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
            ) {
                Text(step.why, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            when (step) {
                                SetupStep.NOTIFICATIONS -> notificationLauncher.launch(
                                    android.Manifest.permission.POST_NOTIFICATIONS,
                                )

                                SetupStep.VPN_CONSENT -> {
                                    val prepare = android.net.VpnService.prepare(context)
                                    if (prepare != null) {
                                        vpnLauncher.launch(prepare)
                                    } else {
                                        DnsFilterVpnService.start(context)
                                        statuses = readStatuses(context)
                                    }
                                }

                                else -> step.settingsIntent(context)?.let {
                                    settingsLauncher.launch(it)
                                }
                            }
                        },
                        enabled = !satisfied || step == SetupStep.VPN_CONSENT,
                    ) {
                        Text(
                            when {
                                step == SetupStep.VPN_CONSENT && satisfied -> "Restart filter"
                                satisfied -> "Granted"
                                else -> "Open settings"
                            },
                        )
                    }
                    if (step == SetupStep.VPN_CONSENT && satisfied) {
                        OutlinedButton(onClick = { DnsFilterVpnService.stop(context) }) {
                            Text("Stop filter")
                        }
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "One UI steps with no shortcut",
                subtitle = "Samsung gives no intent for these; they have to be done by hand.",
            ) {
                ONE_UI_MANUAL_STEPS.forEach {
                    Text(
                        text = "•  $it",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Hint(
                    "Battery sleep is the single most common reason a blocker quietly " +
                        "stops working on a Samsung phone.",
                )
            }
        }

        item {
            SectionCard(title = "Turning it off again") {
                Text(
                    "Revoking the accessibility service disables all blocking immediately " +
                        "and no cooldown applies to it - the system owns that switch, not " +
                        "this app. The uninstall lock and the change cooldown are what make " +
                        "that a deliberate act rather than a reflex.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun readStatuses(context: Context): Map<SetupStep, Boolean> =
    SetupStep.entries.associateWith { it.isSatisfied(context) }
