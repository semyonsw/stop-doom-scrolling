package am.onex.stopdoom.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "doomguard")

data class SettingsSnapshot(
    /** The master switch. False means nothing is blocked, whatever the rules say. */
    val protectionEnabled: Boolean,
    /**
     * Reveals the selector editing, the JSON editors and the Debug tab.
     *
     * Purely what the UI shows, with one exception that matters: the cooldown
     * switch below only exists in developer mode, which makes this the door to
     * the app's only instant bypass. Off is the setting to live in.
     */
    val developerMode: Boolean,
    /**
     * Whether the change cooldown is enforced at all.
     *
     * False makes every weakening apply on the spot, which is the only way to
     * test a change without waiting out a real cooldown. It is not protected by
     * the gate it disables - that would make it useless for the one job it has -
     * so it is deliberately loud everywhere it is on.
     */
    val cooldownEnabled: Boolean,
    val cooldownMinutes: Int,
    val maintenanceUntil: Long,
    val webBlockingEnabled: Boolean,
    val urlBarBlockingEnabled: Boolean,
    val keywordBlockingEnabled: Boolean,
    val watchdogAggressive: Boolean,
    val onboardingComplete: Boolean,
    val upstreamDns: String,
    val replacementActivities: List<String>,
) {
    fun maintenanceActiveAt(nowMillis: Long): Boolean = nowMillis < maintenanceUntil

    /**
     * The one question every blocker asks before acting.
     *
     * Both ways of switching everything off answer it, so a new blocking path can
     * only get this wrong by not calling it at all.
     */
    fun blockingActiveAt(nowMillis: Long): Boolean =
        protectionEnabled && !maintenanceActiveAt(nowMillis)
}

class Settings(private val context: Context) {

    val flow: Flow<SettingsSnapshot> = context.dataStore.data.map { it.toSnapshot() }

    /**
     * Last known settings, readable without suspending.
     *
     * The accessibility service runs its scan on a plain Handler thread and is on a
     * ~200ms budget, so it cannot suspend or block on DataStore there. [keepFresh]
     * mirrors the flow into this field instead; the service always reads a value
     * that is at most one edit stale, which is fine for cooldowns and toggles.
     */
    @Volatile
    var current: SettingsSnapshot = DEFAULTS
        private set

    /** Call once from a long-lived scope. Returns the collecting job. */
    fun keepFresh(scope: CoroutineScope): Job = scope.launch {
        flow.collect { current = it }
    }

    /** One-shot read for callers that can suspend. */
    suspend fun read(): SettingsSnapshot = flow.first().also { current = it }

    suspend fun setProtectionEnabled(value: Boolean) = edit { it[PROTECTION_ENABLED] = value }

    suspend fun setDeveloperMode(value: Boolean) = edit { it[DEVELOPER_MODE] = value }

    suspend fun setCooldownEnabled(value: Boolean) = edit { it[COOLDOWN_ENABLED] = value }

    suspend fun setCooldownMinutes(value: Int) = edit { it[COOLDOWN_MINUTES] = value.coerceAtLeast(0) }

    suspend fun setMaintenanceUntil(untilMillis: Long) = edit { it[MAINTENANCE_UNTIL] = untilMillis }

    suspend fun setWebBlocking(enabled: Boolean) = edit { it[WEB_BLOCKING] = enabled }

    suspend fun setUrlBarBlocking(enabled: Boolean) = edit { it[URL_BAR_BLOCKING] = enabled }

    suspend fun setKeywordBlocking(enabled: Boolean) = edit { it[KEYWORD_BLOCKING] = enabled }

    suspend fun setWatchdogAggressive(enabled: Boolean) = edit { it[WATCHDOG_AGGRESSIVE] = enabled }

    suspend fun setOnboardingComplete(value: Boolean) = edit { it[ONBOARDING_COMPLETE] = value }

    suspend fun setUpstreamDns(value: String) = edit { it[UPSTREAM_DNS] = value }

    suspend fun setReplacementActivities(items: List<String>) =
        edit { it[REPLACEMENTS] = items.joinToString("\n") }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private fun Preferences.toSnapshot() = SettingsSnapshot(
        protectionEnabled = this[PROTECTION_ENABLED] ?: DEFAULTS.protectionEnabled,
        developerMode = this[DEVELOPER_MODE] ?: DEFAULTS.developerMode,
        cooldownEnabled = this[COOLDOWN_ENABLED] ?: DEFAULTS.cooldownEnabled,
        cooldownMinutes = this[COOLDOWN_MINUTES] ?: DEFAULTS.cooldownMinutes,
        maintenanceUntil = this[MAINTENANCE_UNTIL] ?: DEFAULTS.maintenanceUntil,
        webBlockingEnabled = this[WEB_BLOCKING] ?: DEFAULTS.webBlockingEnabled,
        urlBarBlockingEnabled = this[URL_BAR_BLOCKING] ?: DEFAULTS.urlBarBlockingEnabled,
        keywordBlockingEnabled = this[KEYWORD_BLOCKING] ?: DEFAULTS.keywordBlockingEnabled,
        watchdogAggressive = this[WATCHDOG_AGGRESSIVE] ?: DEFAULTS.watchdogAggressive,
        onboardingComplete = this[ONBOARDING_COMPLETE] ?: DEFAULTS.onboardingComplete,
        upstreamDns = this[UPSTREAM_DNS] ?: DEFAULTS.upstreamDns,
        replacementActivities = (this[REPLACEMENTS] ?: DEFAULTS.replacementActivities.joinToString("\n"))
            .lines().map { it.trim() }.filter { it.isNotEmpty() },
    )

    companion object {
        private val PROTECTION_ENABLED = booleanPreferencesKey("protection_enabled")
        private val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
        private val COOLDOWN_ENABLED = booleanPreferencesKey("cooldown_enabled")
        private val COOLDOWN_MINUTES = intPreferencesKey("cooldown_minutes")
        private val MAINTENANCE_UNTIL = longPreferencesKey("maintenance_until")
        private val WEB_BLOCKING = booleanPreferencesKey("web_blocking")
        private val URL_BAR_BLOCKING = booleanPreferencesKey("url_bar_blocking")
        private val KEYWORD_BLOCKING = booleanPreferencesKey("keyword_blocking")
        private val WATCHDOG_AGGRESSIVE = booleanPreferencesKey("watchdog_aggressive")
        private val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        private val UPSTREAM_DNS = stringPreferencesKey("upstream_dns")
        private val REPLACEMENTS = stringPreferencesKey("replacement_activities")

        val DEFAULTS = SettingsSnapshot(
            protectionEnabled = true,
            // Both off by default: the selector fields are noise until a rule stops
            // matching, and the cooldown is the whole mechanism.
            developerMode = false,
            cooldownEnabled = true,
            cooldownMinutes = 120,
            maintenanceUntil = 0L,
            webBlockingEnabled = true,
            urlBarBlockingEnabled = true,
            keywordBlockingEnabled = true,
            // Off by default: backing out of the system Settings app is effective but
            // disorienting, and you need to be able to reach your own toggles while
            // developing. Turn it on once the rules have settled.
            watchdogAggressive = false,
            onboardingComplete = false,
            upstreamDns = "1.1.1.1",
            replacementActivities = listOf(
                "Open the lesson and read one page",
                "Write two lines of code",
                "10 minute walk outside",
            ),
        )
    }
}
