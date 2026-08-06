package am.onex.stopdoom.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File

/**
 * The selector-discovery tool.
 *
 * This is what makes the guessed view ids in the bundled rules fixable. YouTube's
 * internal ids are undocumented and change between builds, so they have to be read
 * off the running app rather than assumed.
 */
@Composable
fun DebugScreen(
    state: UiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copy: (String, String) -> Unit = { label, value ->
        scope.launch {
            clipboard.setClipEntry(
                ClipEntry(android.content.ClipData.newPlainText(label, value)),
            )
        }
    }
    var expanded by remember { mutableStateOf<File?>(null) }
    var hostsText by remember { mutableStateOf("") }
    var domainText by remember { mutableStateOf("") }

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        item {
            SectionCard(title = "Find the real view ids", subtitle = "Dump the live screen") {
                Text(
                    "Tap Dump, then switch to the app and open the exact screen you want to " +
                        "block. Five seconds later the whole view tree is written to a file " +
                        "and every distinct view id is printed to logcat under the tag " +
                        "DoomGuard/Dump. Paste the ones that look right into the rule's " +
                        "anyViewIdContains list.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.armDump() }) { Text("Dump in 5s") }
                    if (state.dumps.isNotEmpty()) {
                        TextButton(onClick = { viewModel.deleteDumps() }) { Text("Delete all") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Hint(
                    "Dump a screen you do NOT want blocked too - the YouTube home feed, for " +
                        "instance - and check the ids do not overlap. That is how you avoid " +
                        "a rule that blocks the whole app.",
                )
            }
        }

        if (state.dumps.isEmpty()) {
            item {
                SectionCard(title = "No dumps yet") {
                    Hint("Dumps land in the app's private files directory.")
                }
            }
        }

        items(state.dumps, key = { it.absolutePath }) { file ->
            val ids = remember(file.absolutePath) { viewModel.viewIdsOf(file) }
            SectionCard(
                title = file.name.removePrefix("dump-").removeSuffix(".json"),
                subtitle = "${ids.size} distinct view ids",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        expanded = if (expanded == file) null else file
                    }) { Text(if (expanded == file) "Hide ids" else "Show ids") }
                    TextButton(onClick = {
                        copy("view ids", ids.joinToString("\n"))
                    }) { Text("Copy ids") }
                    TextButton(onClick = {
                        copy("node dump", viewModel.readDump(file))
                    }) { Text("Copy JSON") }
                }
                if (expanded == file) {
                    Spacer(Modifier.height(8.dp))
                    ids.forEach { id ->
                        Text(
                            text = id,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "Blocklist",
                subtitle = "${state.blocklistSize} domains loaded",
            ) {
                Text(
                    "The bundled seed list is short on purpose. Paste a public list here - " +
                        "hosts format or one domain per line, both work.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = hostsText,
                    onValueChange = { hostsText = it },
                    label = { Text("Paste hosts / domain list") },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        viewModel.importHosts(hostsText)
                        hostsText = ""
                    },
                    enabled = hostsText.isNotBlank(),
                ) { Text("Import") }

                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = domainText,
                    onValueChange = { domainText = it },
                    label = { Text("Single domain") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.addBlockedDomain(domainText)
                            domainText = ""
                        },
                        enabled = domainText.isNotBlank(),
                    ) { Text("Block") }
                    OutlinedButton(
                        onClick = {
                            viewModel.addAllowedDomain(domainText)
                            domainText = ""
                        },
                        enabled = domainText.isNotBlank(),
                    ) { Text("Allow") }
                }
                Spacer(Modifier.height(8.dp))
                Hint(
                    "One entry covers subdomains. Allow entries win over block entries, and " +
                        "are not protected by the cooldown.",
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
