package am.onex.stopdoom.guard

import am.onex.stopdoom.data.PendingChange
import am.onex.stopdoom.data.PendingChangeStore
import am.onex.stopdoom.data.RuleStore
import am.onex.stopdoom.data.Settings
import am.onex.stopdoom.data.UsageStore
import am.onex.stopdoom.rules.BlockRule
import am.onex.stopdoom.rules.RuleJson

sealed interface ChangeOutcome {
    /** Applied immediately, because it made things stricter or left them equal. */
    data object AppliedNow : ChangeOutcome

    /** Queued; [effectiveAt] is when it will actually take effect. */
    data class Deferred(val effectiveAt: Long, val pendingId: Long) : ChangeOutcome
}

/** Settings that can be weakened, and which value counts as the weaker one. */
enum class GuardedToggle(val key: String, val looseningValue: Boolean, val label: String) {
    /**
     * The master switch. It goes through the same gate as everything else on
     * purpose: a one-tap "off" that applied instantly would make every other
     * cooldown in the app decorative.
     */
    PROTECTION_ENABLED("protection_enabled", false, "all protection"),
    WEB_BLOCKING("web_blocking", false, "website blocking"),
    URL_BAR_BLOCKING("url_bar_blocking", false, "browser URL checking"),
    KEYWORD_BLOCKING("keyword_blocking", false, "keyword blocking"),
    WATCHDOG_AGGRESSIVE("watchdog_aggressive", false, "aggressive settings guard"),
    ;

    companion object {
        fun parse(key: String): GuardedToggle? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The single write path for anything that can weaken protection.
 *
 * Tightening applies instantly, loosening is queued with a delay. Nothing else in
 * the app writes rules or settings directly - if it did, the cooldown would be
 * advisory rather than enforced.
 */
class CooldownGate(
    private val rules: RuleStore,
    private val pending: PendingChangeStore,
    private val usage: UsageStore,
    private val settings: Settings,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Zero when the cooldown is switched off, which is what makes the testing
     * escape hatch work.
     *
     * A weakening still goes through the queue rather than around it - it is just
     * due the moment it lands, so the next drain applies it. Keeping one path means
     * the logging, the supersede-on-tighten rule and the pending list all behave
     * exactly as they do normally, and switching the cooldown back on cannot leave
     * a change stranded in a state the queue has never seen.
     */
    private fun cooldownMillis(): Long =
        if (!settings.current.cooldownEnabled) 0L
        else settings.current.cooldownMinutes.toLong() * 60_000L

    fun requestRuleUpsert(updated: BlockRule): ChangeOutcome {
        val existing = rules.readAll().firstOrNull { it.id == updated.id }
        // A brand new rule can only add coverage, so it is never a loosening.
        if (existing == null || !isLoosening(existing, updated)) {
            rules.upsert(updated)
            // Tightening supersedes any queued weakening of the same rule.
            pending.cancelAllFor(updated.id)
            usage.log(clock(), KIND_APPLIED, updated.id, "rule tightened or added")
            return ChangeOutcome.AppliedNow
        }
        return defer(
            kind = PendingChange.Kind.RULE_UPSERT,
            targetId = updated.id,
            payload = RuleJson.encodeOne(updated),
            description = "Weaken \"${updated.label}\"",
        )
    }

    /** Deleting a rule always removes coverage, so it always waits. */
    fun requestRuleDelete(ruleId: String): ChangeOutcome {
        val existing = rules.readAll().firstOrNull { it.id == ruleId }
            ?: return ChangeOutcome.AppliedNow
        return defer(
            kind = PendingChange.Kind.RULE_DELETE,
            targetId = ruleId,
            payload = ruleId,
            description = "Delete \"${existing.label}\"",
        )
    }

    /**
     * Maintenance mode goes through the cooldown too, or it would be a one-tap
     * bypass for everything else. It still has to exist: without an escape hatch
     * you cannot work on the app that is blocking you.
     */
    fun requestMaintenance(minutes: Int): ChangeOutcome = defer(
        kind = PendingChange.Kind.MAINTENANCE,
        targetId = TARGET_MAINTENANCE,
        payload = minutes.toString(),
        description = "$minutes minutes of maintenance mode",
    )

    suspend fun requestCooldownChange(newMinutes: Int): ChangeOutcome {
        if (newMinutes >= settings.current.cooldownMinutes) {
            settings.setCooldownMinutes(newMinutes)
            usage.log(clock(), KIND_APPLIED, TARGET_COOLDOWN, "cooldown raised to ${newMinutes}m")
            return ChangeOutcome.AppliedNow
        }
        return defer(
            kind = PendingChange.Kind.COOLDOWN_MINUTES,
            targetId = TARGET_COOLDOWN,
            payload = newMinutes.toString(),
            description = "Shorten cooldown to $newMinutes minutes",
        )
    }

    suspend fun requestToggle(toggle: GuardedToggle, value: Boolean): ChangeOutcome {
        if (value != toggle.looseningValue) {
            applyToggle(toggle, value)
            usage.log(clock(), KIND_APPLIED, toggle.key, "enabled")
            return ChangeOutcome.AppliedNow
        }
        return defer(
            kind = PendingChange.Kind.TOGGLE,
            targetId = toggle.key,
            payload = value.toString(),
            description = "Turn off ${toggle.label}",
        )
    }

    /** Used by both the instant path above and the worker that drains the queue. */
    suspend fun applyToggle(toggle: GuardedToggle, value: Boolean) = when (toggle) {
        GuardedToggle.PROTECTION_ENABLED -> settings.setProtectionEnabled(value)
        GuardedToggle.WEB_BLOCKING -> settings.setWebBlocking(value)
        GuardedToggle.URL_BAR_BLOCKING -> settings.setUrlBarBlocking(value)
        GuardedToggle.KEYWORD_BLOCKING -> settings.setKeywordBlocking(value)
        GuardedToggle.WATCHDOG_AGGRESSIVE -> settings.setWatchdogAggressive(value)
    }

    /** Cancelling a queued weakening moves back toward the stricter state, so it is free. */
    fun cancel(pendingId: Long) {
        pending.remove(pendingId)
        usage.log(clock(), KIND_CANCELLED, detail = "pending #$pendingId")
    }

    private fun defer(
        kind: PendingChange.Kind,
        targetId: String,
        payload: String,
        description: String,
    ): ChangeOutcome {
        val now = clock()
        // Re-requesting the same weakening must not restart the clock - otherwise
        // tapping Save twice would push it further out - and must not shorten it.
        // Keeping the earliest existing request does both.
        val queued = pending.all().firstOrNull { it.targetId == targetId && it.kind == kind }
        if (queued != null) return ChangeOutcome.Deferred(queued.effectiveAt, queued.id)

        val effectiveAt = now + cooldownMillis()
        val id = pending.enqueue(
            kind = kind,
            payload = payload,
            description = description,
            createdAt = now,
            effectiveAt = effectiveAt,
            targetId = targetId,
        )
        usage.log(now, KIND_DEFERRED, targetId, description)
        return ChangeOutcome.Deferred(effectiveAt, id)
    }

    companion object {
        const val TARGET_MAINTENANCE = "maintenance"
        const val TARGET_COOLDOWN = "cooldown"
        const val KIND_DEFERRED = "change_deferred"
        const val KIND_APPLIED = "change_applied"
        const val KIND_CANCELLED = "change_cancelled"
    }
}
