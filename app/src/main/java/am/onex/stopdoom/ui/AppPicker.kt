package am.onex.stopdoom.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(val packageName: String, val label: String)

/**
 * Pick a package from the launcher instead of typing it.
 *
 * Package names are the one field in a rule with no forgiving spelling: a typo in
 * `com.zhiliaoapp.musically` produces a rule that matches nothing and looks exactly
 * like a broken matcher. Choosing from the installed list removes the failure mode.
 */
@Composable
fun AppPickerDialog(
    alreadyChosen: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { launchableApps(context) }
    }

    val loaded = apps
    val visible = remember(loaded, query) {
        val needle = query.trim().lowercase()
        loaded.orEmpty().filter {
            needle.isEmpty() ||
                it.label.lowercase().contains(needle) ||
                it.packageName.lowercase().contains(needle)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Choose an app") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search installed apps") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                when {
                    loaded == null -> Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    visible.isEmpty() -> Hint("Nothing matches \"$query\".")

                    else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(visible, key = { it.packageName }) { app ->
                            AppRow(
                                app = app,
                                chosen = app.packageName in alreadyChosen,
                                onClick = { onPick(app.packageName) },
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun AppRow(app: InstalledApp, chosen: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val icon = remember(app.packageName) { loadIcon(context, app.packageName) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !chosen, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(36.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp),
                    ),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (chosen) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Already added",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Launcher entries only. The full package list on a Samsung device is several
 * hundred system components that no rule would ever name.
 */
private fun launchableApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return runCatching {
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())
}

/** Rasterised small: these are list thumbnails, not the launcher's own icons. */
private fun loadIcon(context: Context, packageName: String): ImageBitmap? = runCatching {
    val size = (36 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    context.packageManager.getApplicationIcon(packageName).toBitmap(size, size).asImageBitmap()
}.getOrNull()
