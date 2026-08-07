package am.onex.stopdoom.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DoomGuardTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    DoomGuardApp()
                }
            }
        }
    }
}

// Core icons only. material-icons-extended is roughly 30MB of vectors for the
// five glyphs this app actually shows.
private enum class Tab(val label: String, val icon: ImageVector) {
    SETUP("Setup", Icons.Filled.CheckCircle),
    RULES("Rules", Icons.AutoMirrored.Filled.List),
    STATS("Stats", Icons.Filled.DateRange),
    GUARD("Guard", Icons.Filled.Lock),
    DEBUG("Debug", Icons.Filled.Build),
}

/**
 * Five tabs held in a plain state variable rather than a NavHost. With no deep
 * links, no arguments and no back stack worth preserving, Navigation would be more
 * setup than the thing it replaces.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DoomGuardApp(viewModel: MainViewModel = viewModel()) {
    var tab by remember { mutableStateOf(Tab.SETUP) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Permissions and cooldowns both change outside the app, so re-read on resume.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    val now = System.currentTimeMillis()
    val offReason = when {
        !state.settings.protectionEnabled -> "All protection is switched off"
        state.settings.maintenanceActiveAt(now) -> "Maintenance mode - nothing is blocked"
        else -> null
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text(tab.label) })
                // Being switched off is the one state worth carrying onto every tab:
                // the rules list looks identical either way, and a rule that quietly
                // is not running is the failure this app cannot afford.
                if (offReason != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { tab = Tab.GUARD },
                    ) {
                        Text(
                            text = "$offReason - tap to fix",
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp, horizontal = 16.dp),
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { padding ->
        val inner = Modifier.padding(padding)
        when (tab) {
            Tab.SETUP -> SetupScreen(inner)
            Tab.RULES -> RulesScreen(state, viewModel, inner)
            Tab.STATS -> StatsScreen(state, viewModel, inner)
            Tab.GUARD -> GuardScreen(state, viewModel, inner)
            Tab.DEBUG -> DebugScreen(state, viewModel, inner)
        }
    }
}
