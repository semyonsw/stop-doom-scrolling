package am.onex.stopdoom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
            SectionCard(title = "Protection switches") {
                GuardedToggle.entries.forEach { toggle ->
                    val value = when (toggle) {
                        GuardedToggle.WEB_BLOCKING -> state.settings.webBlockingEnabled
                        GuardedToggle.URL_BAR_BLOCKING -> state.settings.urlBarBlockingEnabled
                        GuardedToggle.KEYWORD_BLOCKING -> state.settings.keywordBlockingEnabled
                        GuardedToggle.WATCHDOG_AGGRESSIVE -> state.settings.watchdogAggressive
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                toggle.label.replaceFirstChar(Char::uppercase),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Hint(toggle.explain())
                        }
                        Switch(
                            checked = value,
                            onCheckedChange = { viewModel.setToggle(toggle, it) },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Hint("Turning any of these off is a weakening and waits for the cooldown.")
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
    GuardedToggle.WEB_BLOCKING -> "Master switch for the DNS filter and URL checks."
    GuardedToggle.URL_BAR_BLOCKING -> "Reads the browser address bar; catches DoH bypasses."
    GuardedToggle.KEYWORD_BLOCKING -> "Matches typed searches, which DNS never sees."
    GuardedToggle.WATCHDOG_AGGRESSIVE ->
        "Backs you out of Settings pages that name DoomGuard, so reaching the accessibility " +
            "switch costs something. Stands aside for a minute after three tries, so it is " +
            "friction rather than a lock. Annoying while developing."
}
