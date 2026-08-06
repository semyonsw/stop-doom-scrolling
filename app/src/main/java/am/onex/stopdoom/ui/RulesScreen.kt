package am.onex.stopdoom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import am.onex.stopdoom.overlay.formatDuration
import am.onex.stopdoom.rules.BlockRule
import am.onex.stopdoom.rules.RuleJson

/**
 * Rule list plus a raw JSON editor.
 *
 * The editor is the important half. Selectors have to be fixed against whatever
 * the installed YouTube build actually renders, and going through a rebuild for
 * each attempt would make that unworkable - so rules are text you can paste.
 */
@Composable
fun RulesScreen(
    state: UiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<BlockRule?>(null) }
    var editorText by remember { mutableStateOf("") }
    var bulkMode by remember { mutableStateOf(false) }

    val current = editing
    if (current != null || bulkMode) {
        RuleEditor(
            titleText = if (bulkMode) "All rules" else current?.label.orEmpty(),
            text = editorText,
            onTextChange = { editorText = it },
            onSave = {
                if (bulkMode) viewModel.saveAllRulesJson(editorText)
                else viewModel.saveRuleJson(editorText)
                editing = null
                bulkMode = false
            },
            onCancel = {
                editing = null
                bulkMode = false
            },
            modifier = modifier,
        )
        return
    }

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        item {
            SectionCard(
                title = "Rules",
                subtitle = "${state.rules.count { it.enabled }} of ${state.rules.size} active",
            ) {
                Hint(
                    "Order matters: the first matching rule wins, so keep narrow rules " +
                        "above broad ones.",
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        editorText = viewModel.exportRulesJson()
                        bulkMode = true
                    }) { Text("Edit all as JSON") }
                    TextButton(onClick = { viewModel.resetRulesToDefaults() }) {
                        Text("Reset to defaults")
                    }
                }
            }
        }

        items(state.rules, key = { it.id }) { rule ->
            val used = state.todayUsage.firstOrNull { it.ruleId == rule.id }
            SectionCard(
                title = rule.label,
                subtitle = rule.describeTarget(),
                trailing = {
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = { viewModel.setRuleEnabled(rule, it) },
                    )
                },
            ) {
                Text(rule.describeLimits(), style = MaterialTheme.typography.bodyMedium)
                if (used != null) {
                    Spacer(Modifier.height(4.dp))
                    Hint(
                        "Today: ${formatDuration(used.seconds)}, " +
                            "${used.opens} opens, ${used.blocks} blocks",
                    )
                }
                if (rule.note.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Hint(rule.note)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        editorText = RuleJson.encodeOne(rule)
                        editing = rule
                    }) { Text("Edit") }
                    TextButton(onClick = { viewModel.deleteRule(rule.id) }) { Text("Delete") }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun RuleEditor(
    titleText: String,
    text: String,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        item {
            SectionCard(title = titleText, subtitle = "Raw JSON") {
                Hint(
                    "Any entry containing \":id/\" is treated as an exact view id and gets " +
                        "the fast native lookup. Everything else is a case-insensitive " +
                        "substring match. minAreaFraction is what keeps a nav-bar button " +
                        "from matching a full-screen player.",
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth().height(420.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    ),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSave) { Text("Save") }
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
                Spacer(Modifier.height(8.dp))
                Hint("Weakening a rule is queued for the cooldown. Tightening saves at once.")
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun BlockRule.describeTarget(): String = when {
    wholeApp -> "Whole app: ${packages.joinToString()}"
    packages.isEmpty() -> "Any app"
    else -> packages.joinToString()
}

private fun BlockRule.describeLimits(): String = when {
    blocksOnSight -> "Blocked on sight, ${frictionSeconds}s wait"
    else -> "${formatDuration(sessionBudgetSeconds)} per visit, " +
        "${formatDuration(dailyBudgetSeconds)} per day, ${frictionSeconds}s wait"
}
