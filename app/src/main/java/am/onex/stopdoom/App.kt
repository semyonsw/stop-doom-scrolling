package am.onex.stopdoom

import android.app.Application
import android.content.Context
import am.onex.stopdoom.data.Db
import am.onex.stopdoom.data.PendingChangeStore
import am.onex.stopdoom.data.RuleStore
import am.onex.stopdoom.data.Settings
import am.onex.stopdoom.data.UsageStore
import am.onex.stopdoom.debug.NodeTreeDumper
import am.onex.stopdoom.guard.ApplyPendingWorker
import am.onex.stopdoom.guard.CooldownGate
import am.onex.stopdoom.guard.ServiceWatchdog
import am.onex.stopdoom.guard.applyDuePendingChanges
import am.onex.stopdoom.overlay.OverlayManager
import am.onex.stopdoom.rules.BlockRule
import am.onex.stopdoom.rules.RuleEngine
import am.onex.stopdoom.time.UsageTracker
import am.onex.stopdoom.vpn.Blocklist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}

/**
 * Hand-rolled dependency container.
 *
 * A DI framework would add annotation processing to a build that otherwise has
 * none, for a graph of roughly ten singletons that never vary by build type.
 * Constructing them here is shorter and keeps the wiring readable in one place.
 */
class AppContainer(private val context: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val db = Db(context)
    val settings = Settings(context)
    val usage = UsageStore(db)
    val rules = RuleStore(context, db)
    val pending = PendingChangeStore(db)
    val blocklist = Blocklist(context)
    val engine = RuleEngine()
    val tracker = UsageTracker(usage)
    val overlay = OverlayManager(context)
    val dumper = NodeTreeDumper(context)
    val cooldownGate = CooldownGate(rules, pending, usage, settings)

    private val _remaining = MutableStateFlow<RemainingBudget?>(null)

    /** What the currently visible target has left, for the live tile in the UI. */
    val remaining: StateFlow<RemainingBudget?> = _remaining

    private val _accessibilityConnected = MutableStateFlow(false)
    val accessibilityConnected: StateFlow<Boolean> = _accessibilityConnected

    fun start() {
        settings.keepFresh(scope)
        scope.launch {
            engine.replaceRules(rules.load())
            blocklist.load()
            applyDuePendingChangesSafely()
        }
        ApplyPendingWorker.schedule(context)
        ServiceWatchdog.schedule(context)
    }

    fun reloadRules() {
        engine.replaceRules(rules.readAll())
    }

    fun onAccessibilityServiceConnected() {
        _accessibilityConnected.value = true
        reloadRules()
        usage.log(System.currentTimeMillis(), "a11y_connected")
    }

    fun onAccessibilityServiceDisconnected() {
        _accessibilityConnected.value = false
        usage.log(System.currentTimeMillis(), "a11y_disconnected")
    }

    fun publishRemaining(rule: BlockRule?, seconds: Int) {
        _remaining.value = rule?.let { RemainingBudget(it.id, it.label, seconds) }
    }

    fun onDomainBlocked(host: String) {
        usage.log(System.currentTimeMillis(), "dns_blocked", detail = host)
    }

    /** Drains anything whose cooldown elapsed while the app was not running. */
    suspend fun applyDuePendingChangesSafely() {
        runCatching { applyDuePendingChanges() }
    }
}

data class RemainingBudget(val ruleId: String, val label: String, val seconds: Int)
