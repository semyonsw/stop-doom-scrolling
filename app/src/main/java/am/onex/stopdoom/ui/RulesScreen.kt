package am.onex.stopdoom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
 * The rule list, and the three ways in to editing one.
 *
 * The form is the front door. Raw JSON is still here because selectors are
 * sometimes pasted wholesale out of a screen dump, and going through a rebuild
 * for each attempt would make fixing a rule against a new app version unworkable -
 * but nothing routine needs it now.
 */
private sealed interface RuleEditorMode {
    data object List : RuleEditorMode

    /** [rule] null means a new one. */
    data class Form(val rule: BlockRule?) : RuleEditorMode

    /** [bulk] edits the whole list at once rather than a single rule. */
    data class Json(val bulk: Boolean) : RuleEditorMode
}

@Composable
fun RulesScreen(
    state: UiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    var mode by remember { mutableStateOf<RuleEditorMode>(RuleEditorMode.List) }
    var editorText by remember { mutableStateOf("") }

    when (val current = mode) {
        is RuleEditorMode.Form -> {
            RuleEditorScreen(
                original = current.rule,
                existingIds = state.rules.map { it.id },
                developerMode = state.settings.developerMode,
                onSave = {
                    viewModel.saveRule(it)
                    mode = RuleEditorMode.List
                },
                onCancel = { mode = RuleEditorMode.List },
                onEditJson = {
                    editorText = RuleJson.encodeOne(it)
                    mode = RuleEditorMode.Json(bulk = false)
                },
                modifier = modifier,
            )
            return
        }

        is RuleEditorMode.Json -> {
            JsonEditor(
                bulk = current.bulk,
                text = editorText,
                onTextChange = { editorText = it },
                onSave = {
                    if (current.bulk) {
                        viewModel.saveAllRulesJson(editorText)
                    } else {
                        viewModel.saveRuleJson(editorText)
                    }
                    mode = RuleEditorMode.List
                },
                onCancel = { mode = RuleEditorMode.List },
                modifier = modifier,
            )
            return
        }

        RuleEditorMode.List -> Unit
    }

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        item {
            val active = state.rules.count { it.enabled }
            SectionCard(
                title = "Rules",
                subtitle = "$active of ${state.rules.size} active",
            ) {
                Hint(
                    "Order matters: the first matching rule wins, so keep narrow rules " +
                        "above broad ones.",
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { mode = RuleEditorMode.Form(null) }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("New rule")
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.settings.developerMode) {
                        OutlinedButton(onClick = {
                            editorText = viewModel.exportRulesJson()
                            mode = RuleEditorMode.Json(bulk = true)
                        }) { Text("Edit all as JSON") }
                    }
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
                Text(rule.limitsSummary(), style = MaterialTheme.typography.bodyMedium)
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
                    Button(onClick = { mode = RuleEditorMode.Form(rule) }) { Text("Edit") }
                    TextButton(onClick = { viewModel.deleteRule(rule.id) }) { Text("Delete") }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun JsonEditor(
    bulk: Boolean,
    text: String,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        item {
            SectionCard(
                title = if (bulk) "All rules" else "Raw JSON",
                subtitle = "Advanced",
            ) {
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
