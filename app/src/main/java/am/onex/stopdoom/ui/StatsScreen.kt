package am.onex.stopdoom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import am.onex.stopdoom.overlay.formatDuration

@Composable
fun StatsScreen(
    state: UiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val remaining by viewModel.remaining.collectAsStateWithLifecycle()
    val totalToday = state.todayUsage.sumOf { it.seconds }
    val totalBlocks = state.todayUsage.sumOf { it.blocks }

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        item {
            SectionCard(
                title = "Today",
                subtitle = "${formatDuration(totalToday)} in tracked feeds, $totalBlocks blocks",
            ) {
                if (remaining != null) {
                    Text(
                        "On screen now: ${remaining!!.label}, " +
                            "${formatDuration(remaining!!.seconds)} left",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Hint("Nothing tracked is on screen.")
                }
            }
        }

        if (state.todayUsage.isEmpty()) {
            item {
                SectionCard(title = "No usage recorded yet") {
                    Hint(
                        "Time is only counted while a rule is matching - this is not a " +
                            "general app-usage tracker. So if you have opened Shorts or " +
                            "Reels and this is still empty, the selectors are not matching " +
                            "anything.",
                    )
                    Spacer(Modifier.height(8.dp))
                    Hint(
                        "Switch to developer mode on the Guard tab, open the Debug tab, and " +
                            "read \"Live match check\". It says which selector failed and " +
                            "why, which is faster than guessing at ids.",
                    )
                }
            }
        }

        items(state.todayUsage, key = { it.ruleId }) { usage ->
            val rule = state.rules.firstOrNull { it.id == usage.ruleId }
            SectionCard(
                title = rule?.label ?: usage.ruleId,
                subtitle = "${usage.opens} opens, ${usage.blocks} blocks",
            ) {
                Text(
                    formatDuration(usage.seconds),
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (rule != null && !rule.blocksOnSight) {
                    Spacer(Modifier.height(4.dp))
                    Hint("Daily allowance ${formatDuration(rule.dailyBudgetSeconds)}")
                }
            }
        }

        item {
            SectionCard(title = "Recent activity", subtitle = "Newest first") {
                if (state.events.isEmpty()) {
                    Hint("Nothing logged yet.")
                }
                state.events.take(40).forEach { event ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = formatTimestamp(event.at),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = buildString {
                                append(event.kind)
                                if (event.ruleId.isNotBlank()) append(" · ${event.ruleId}")
                                if (event.detail.isNotBlank()) append(" · ${event.detail}")
                            },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
