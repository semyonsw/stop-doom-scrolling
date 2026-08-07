package am.onex.stopdoom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import am.onex.stopdoom.guard.GuardedToggle
import am.onex.stopdoom.guard.ServiceWatchdog

/**
 * Everything that decides how hard the app is to get around, in one place, so the
 * trade-offs are visible rather than scattered through settings.
 */
@Composable
fun GuardScreen(
    state: UiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var cooldownText by remember(state.settings.cooldownMinutes) {
        mutableStateOf(state.settings.cooldownMinutes.toString())
    }
    val now = System.currentTimeMillis()
    val maintenanceActive = state.settings.maintenanceActiveAt(now)

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        item { MasterSwitchCard(state, viewModel, now) }

        if (maintenanceActive) {
            item {
                SectionCard(
                    title = "Maintenance mode is on",
                    subtitle = "Ends ${formatRelativeFuture(state.settings.maintenanceUntil, now)}",
                ) {
                    Text(
                        "Nothing is being blocked right now.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        item {
            SectionCard(
                title = "Change cooldown",
                subtitle = "Currently ${state.settings.cooldownMinutes} minutes",
            ) {
                Text(
                    "The load-bearing part. Anything that weakens a limit waits this long; " +
                        "anything that strengthens one applies at once. Long enough to " +
                        "outlast an urge is the right setting - two hours is a good start.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = cooldownText,
                        onValueChange = { cooldownText = it.filter(Char::isDigit).take(4) },
                        label = { Text("Minutes") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = {
                        cooldownText.toIntOrNull()?.let { viewModel.setCooldownMinutes(it) }
                    }) { Text("Set") }
                }
                Spacer(Modifier.height(8.dp))
                Hint("Raising it is instant. Lowering it waits out the current cooldown first.")
            }
        }

        item {
            SectionCard(
                title = "Maintenance mode",
                subtitle = "Temporarily disables everything",
            ) {
                Text(
                    "You are the developer of the thing blocking you, so an escape hatch " +
                        "has to exist or you would end up uninstalling it to get work done. " +
                        "It goes through the cooldown like any other weakening.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60).forEach { minutes ->
                        OutlinedButton(onClick = { viewModel.requestMaintenance(minutes) }) {
                            Text("${minutes}m")
                        }
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "Individual switches",
                subtitle = "Each part of the protection on its own",
            ) {
                // The master switch has its own card at the top; listing it again here
                // would give the same setting two controls that disagree while a
                // switch-off is queued.
                GuardedToggle.entries
                    .filter { it != GuardedToggle.PROTECTION_ENABLED }
                    .forEach { toggle ->
                        val value = when (toggle) {
                            GuardedToggle.PROTECTION_ENABLED -> state.settings.protectionEnabled
                            GuardedToggle.WEB_BLOCKING -> state.settings.webBlockingEnabled
                            GuardedToggle.URL_BAR_BLOCKING -> state.settings.urlBarBlockingEnabled
                            GuardedToggle.KEYWORD_BLOCKING -> state.settings.keywordBlockingEnabled
                            GuardedToggle.WATCHDOG_AGGRESSIVE -> state.settings.watchdogAggressive
                        }
                        SwitchRow(
                            title = toggle.label.replaceFirstChar(Char::uppercase),
                            checked = value,
                            onCheckedChange = { viewModel.setToggle(toggle, it) },
                            help = toggle.explain(),
                            enabled = state.settings.protectionEnabled,
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                Hint(
                    if (state.settings.protectionEnabled) {
                        "Turning any of these off is a weakening and waits for the cooldown."
                    } else {
                        "Greyed out because all protection is switched off above."
                    },
                )
            }
        }

        item {
            val a11y = ServiceWatchdog.isAccessibilityEnabled(context)
            val privateDns = ServiceWatchdog.privateDnsDefeatsFilter(context)
            SectionCard(title = "Health") {
                StatusLine("Accessibility service", if (a11y) "running" else "OFF", !a11y)
                StatusLine(
                    "Private DNS",
                    if (privateDns) "bypassing the filter" else "not interfering",
                    privateDns,
                )
                StatusLine("Blocklist", "${state.blocklistSize} domains", false)
                if (privateDns) {
                    Spacer(Modifier.height(8.dp))
                    Hint(
                        "Private DNS sends lookups over TLS straight past the tunnel, so " +
                            "domain blocking cannot see them. Browser URL checking still " +
                            "works. Set Private DNS to Off or Automatic to restore it.",
                    )
                }
            }
        }

        if (state.pending.isNotEmpty()) {
            item {
                SectionCard(
                    title = "Waiting on cooldown",
                    subtitle = "${state.pending.size} queued",
                ) {
                    Hint("These apply automatically. Cancelling one is always allowed.")
                }
            }
            items(state.pending, key = { it.id }) { change ->
                SectionCard(
                    title = change.description,
                    subtitle = "Applies ${formatRelativeFuture(change.effectiveAt, now)}",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { viewModel.cancelPending(change.id) }) {
                            Text("Cancel")
                        }
                    }
                }
            }
            item {
                SectionCard(title = "") {
                    OutlinedButton(onClick = { viewModel.applyDueNow() }) {
                        Text("Apply anything already due")
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * The one switch that turns everything off.
 *
 * It goes through the cooldown like every other weakening, which means the switch
 * cannot simply follow the finger: tapping it off leaves the state on and queues a
 * change. Showing the queued change in the same card is what stops that reading as
 * a broken control - the alternative, a switch that springs back with no
 * explanation, is exactly how you end up disabling the accessibility service
 * instead.
 */
@Composable
private fun MasterSwitchCard(
    state: UiState,
    viewModel: MainViewModel,
    now: Long,
) {
    val on = state.settings.protectionEnabled
    val queuedOff = state.pending.firstOrNull {
        it.targetId == GuardedToggle.PROTECTION_ENABLED.key
    }
    var confirming by remember { mutableStateOf(false) }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Switch everything off?") },
            text = {
                Text(
                    "Every rule, the website filter and the browser URL check all stop. " +
                        "This is a weakening, so it will not happen now - it is queued for " +
                        "the ${state.settings.cooldownMinutes} minute cooldown, and you can " +
                        "cancel it at any point before then.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setToggle(GuardedToggle.PROTECTION_ENABLED, false)
                    confirming = false
                }) { Text("Queue it") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Keep it on") }
            },
        )
    }

    SectionCard(
        title = "All protection",
        subtitle = if (on) "On - rules are being enforced" else "OFF - nothing is blocked",
        trailing = {
            Switch(
                checked = on,
                onCheckedChange = { wanted ->
                    if (wanted) {
                        viewModel.setToggle(GuardedToggle.PROTECTION_ENABLED, true)
                    } else {
                        confirming = true
                    }
                },
            )
        },
    ) {
        Text(
            "The single switch for everything below. Turning it back on is instant; " +
                "turning it off waits out the cooldown.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!on) {
            Spacer(Modifier.height(12.dp))
            Callout(
                "Nothing is being blocked right now. Feeds, websites and searches are " +
                    "all open.",
                tone = CalloutTone.Warn,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { viewModel.setToggle(GuardedToggle.PROTECTION_ENABLED, true) }) {
                Text("Turn protection back on")
            }
        } else if (queuedOff != null) {
            Spacer(Modifier.height(12.dp))
            Callout(
                "Switching off ${formatRelativeFuture(queuedOff.effectiveAt, now)}. " +
                    "The switch stays on until then.",
                tone = CalloutTone.Warn,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { viewModel.cancelPending(queuedOff.id) }) {
                Text("Cancel that")
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, warn: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
    Spacer(Modifier.height(6.dp))
}

private fun GuardedToggle.explain(): String = when (this) {
    GuardedToggle.PROTECTION_ENABLED -> "Everything, in one switch."
    GuardedToggle.WEB_BLOCKING -> "Covers the DNS filter and the URL checks together."
    GuardedToggle.URL_BAR_BLOCKING -> "Reads the browser address bar; catches DoH bypasses."
    GuardedToggle.KEYWORD_BLOCKING -> "Matches typed searches, which DNS never sees."
    GuardedToggle.WATCHDOG_AGGRESSIVE ->
        "Backs you out of Settings pages that name DoomGuard, so reaching the accessibility " +
            "switch costs something. Stands aside for a minute after three tries, so it is " +
            "friction rather than a lock. Annoying while developing."
}
