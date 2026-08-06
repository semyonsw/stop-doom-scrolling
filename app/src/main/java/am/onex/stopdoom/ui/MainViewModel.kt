package am.onex.stopdoom.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import am.onex.stopdoom.App
import am.onex.stopdoom.data.DayUsage
import am.onex.stopdoom.data.LoggedEvent
import am.onex.stopdoom.data.PendingChange
import am.onex.stopdoom.data.SettingsSnapshot
import am.onex.stopdoom.guard.ChangeOutcome
import am.onex.stopdoom.guard.GuardedToggle
import am.onex.stopdoom.guard.applyDuePendingChanges
import am.onex.stopdoom.rules.BlockRule
import am.onex.stopdoom.rules.RuleJson
import am.onex.stopdoom.vpn.Blocklist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UiState(
    val rules: List<BlockRule> = emptyList(),
    val settings: SettingsSnapshot = am.onex.stopdoom.data.Settings.DEFAULTS,
    val pending: List<PendingChange> = emptyList(),
    val todayUsage: List<DayUsage> = emptyList(),
    val events: List<LoggedEvent> = emptyList(),
    val dumps: List<File> = emptyList(),
    val blocklistSize: Int = 0,
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as App).container

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    val remaining = container.remaining

    init {
        viewModelScope.launch {
            container.settings.flow.collect { snapshot ->
                _state.value = _state.value.copy(settings = snapshot)
            }
        }
        refresh()
    }

    /** Also drains any cooldown that expired while the app was closed. */
    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        container.applyDuePendingChangesSafely()
        val now = System.currentTimeMillis()
        _state.value = _state.value.copy(
            rules = container.rules.readAll(),
            pending = container.pending.all(),
            todayUsage = container.usage.usageForDay(container.usage.dayKey(now)),
            events = container.usage.recentEvents(60),
            dumps = container.dumper.listDumps(),
            blocklistSize = container.blocklist.current().size,
        )
    }

    private fun note(text: String) {
        _state.value = _state.value.copy(message = text)
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun describe(outcome: ChangeOutcome, appliedText: String): String = when (outcome) {
        ChangeOutcome.AppliedNow -> appliedText
        is ChangeOutcome.Deferred -> {
            val minutes = ((outcome.effectiveAt - System.currentTimeMillis()) / 60_000L)
                .coerceAtLeast(0L)
            "Queued. This weakens a limit, so it takes effect in about $minutes min."
        }
    }

    // --- rules ------------------------------------------------------------

    fun saveRuleJson(json: String) = viewModelScope.launch(Dispatchers.IO) {
        val parsed = RuleJson.decodeOne(json)
        val rule = parsed.getOrElse {
            note("Could not parse: ${it.message}")
            return@launch
        }
        val outcome = container.cooldownGate.requestRuleUpsert(rule)
        container.reloadRules()
        note(describe(outcome, "Saved."))
        refresh()
    }

    fun saveAllRulesJson(json: String) = viewModelScope.launch(Dispatchers.IO) {
        val parsed = RuleJson.decode(json).getOrElse {
            note("Could not parse: ${it.message}")
            return@launch
        }
        // Routed one at a time so each rule gets its own loosening check; a bulk
        // replace would be an easy way to weaken everything at once.
        var deferred = 0
        parsed.forEach {
            if (container.cooldownGate.requestRuleUpsert(it) is ChangeOutcome.Deferred) deferred++
        }
        container.reloadRules()
        note(if (deferred == 0) "Saved ${parsed.size} rules." else "$deferred queued for cooldown.")
        refresh()
    }

    fun setRuleEnabled(rule: BlockRule, enabled: Boolean) =
        saveRule(rule.copy(enabled = enabled))

    fun saveRule(rule: BlockRule) = viewModelScope.launch(Dispatchers.IO) {
        val outcome = container.cooldownGate.requestRuleUpsert(rule)
        container.reloadRules()
        note(describe(outcome, "Saved."))
        refresh()
    }

    fun deleteRule(ruleId: String) = viewModelScope.launch(Dispatchers.IO) {
        val outcome = container.cooldownGate.requestRuleDelete(ruleId)
        container.reloadRules()
        note(describe(outcome, "Deleted."))
        refresh()
    }

    fun resetRulesToDefaults() = viewModelScope.launch(Dispatchers.IO) {
        container.rules.resetToDefaults()
        container.reloadRules()
        note("Rules reset to the bundled defaults.")
        refresh()
    }

    fun exportRulesJson(): String = RuleJson.encode(_state.value.rules)

    // --- guard ------------------------------------------------------------

    fun requestMaintenance(minutes: Int) = viewModelScope.launch(Dispatchers.IO) {
        val outcome = container.cooldownGate.requestMaintenance(minutes)
        note(describe(outcome, "Maintenance mode on."))
        refresh()
    }

    fun setCooldownMinutes(minutes: Int) = viewModelScope.launch(Dispatchers.IO) {
        val outcome = container.cooldownGate.requestCooldownChange(minutes)
        note(describe(outcome, "Cooldown is now $minutes min."))
        refresh()
    }

    fun setToggle(toggle: GuardedToggle, value: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        val outcome = container.cooldownGate.requestToggle(toggle, value)
        note(describe(outcome, "Updated."))
        refresh()
    }

    fun cancelPending(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        container.cooldownGate.cancel(id)
        note("Cancelled.")
        refresh()
    }

    fun applyDueNow() = viewModelScope.launch(Dispatchers.IO) {
        val applied = container.applyDuePendingChanges()
        container.reloadRules()
        note(if (applied.isEmpty()) "Nothing is due yet." else "Applied ${applied.size}.")
        refresh()
    }

    // --- debug ------------------------------------------------------------

    fun armDump() {
        container.dumper.arm()
        note("Dumping in 5s. Open the screen you want to inspect now.")
    }

    fun viewIdsOf(file: File): List<String> = container.dumper.viewIdsOf(file)

    fun deleteDumps() = viewModelScope.launch(Dispatchers.IO) {
        container.dumper.deleteAll()
        note("Dumps deleted.")
        refresh()
    }

    fun readDump(file: File): String = runCatching { file.readText() }.getOrDefault("")

    // --- blocklist --------------------------------------------------------

    fun importHosts(text: String) = viewModelScope.launch(Dispatchers.IO) {
        container.blocklist.importHostsText(text)
        note("Blocklist now has ${container.blocklist.current().size} domains.")
        refresh()
    }

    fun addBlockedDomain(domain: String) = viewModelScope.launch(Dispatchers.IO) {
        container.blocklist.addBlocked(domain)
        note("Blocking $domain.")
        refresh()
    }

    fun addAllowedDomain(domain: String) = viewModelScope.launch(Dispatchers.IO) {
        // Allowing a domain is a weakening, but the blocklist is a file rather than a
        // rule, so it is not covered by the cooldown. Flagged here rather than hidden.
        container.blocklist.addAllowed(domain)
        note("Allowed $domain. Note: allowlist edits are not cooldown-protected.")
        refresh()
    }

    fun userList(name: String): String = container.blocklist.userListText(name)

    suspend fun reloadBlocklist() = withContext(Dispatchers.IO) {
        container.blocklist.load()
    }

    companion object {
        val ALLOWLIST_FILE = Blocklist.FILE_ALLOWLIST
    }
}
