package am.onex.stopdoom.time

import am.onex.stopdoom.data.UsageStore
import am.onex.stopdoom.rules.BlockRule

sealed interface BudgetVerdict {
    /** Still inside the budget. [remainingSeconds] is the smaller of session and daily. */
    data class Allowed(val remainingSeconds: Int) : BudgetVerdict

    data class Blocked(val reason: Reason, val usedTodaySeconds: Int) : BudgetVerdict

    enum class Reason { NO_BUDGET, SESSION_EXHAUSTED, DAILY_EXHAUSTED, REBLOCK_COOLDOWN }
}

/**
 * Per-rule time accounting for the currently visible target.
 *
 * Owns exactly one active target at a time, which matches reality: the
 * accessibility service only ever reports one foreground screen. Unflushed
 * seconds are held in memory and written every [FLUSH_INTERVAL_SECONDS] so a
 * process death costs at most that much, not the whole session.
 */
class UsageTracker(
    private val usage: UsageStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private data class Active(
        val ruleId: String,
        val startedAt: Long,
        var sessionSeconds: Int = 0,
        var unflushedSeconds: Int = 0,
    )

    private var active: Active? = null

    val activeRuleId: String? get() = active?.ruleId

    /**
     * Call when the visible target changes. Flushes the previous target's time.
     * Returns true if this is a newly entered target.
     */
    fun onTargetChanged(ruleId: String?): Boolean {
        val previous = active
        if (previous?.ruleId == ruleId) return false
        if (previous != null) flush(previous)
        active = ruleId?.let { Active(it, clock()) }
        if (ruleId != null) usage.recordOpen(ruleId, clock())
        return ruleId != null
    }

    /** Call roughly once a second while [rule] is on screen. */
    fun tick(rule: BlockRule, elapsedSeconds: Int = 1): BudgetVerdict {
        val state = active
        if (state == null || state.ruleId != rule.id) {
            onTargetChanged(rule.id)
            return check(rule)
        }
        state.sessionSeconds += elapsedSeconds
        state.unflushedSeconds += elapsedSeconds
        if (state.unflushedSeconds >= FLUSH_INTERVAL_SECONDS) flush(state)
        return check(rule)
    }

    /**
     * Budget decision without advancing the clock. Reads the in-memory session
     * counter plus whatever is already persisted for today, adding the unflushed
     * remainder so a pending write cannot buy extra seconds.
     */
    fun check(rule: BlockRule): BudgetVerdict {
        val now = clock()

        if (now < usage.blockedUntil(rule.id)) {
            return BudgetVerdict.Blocked(
                BudgetVerdict.Reason.REBLOCK_COOLDOWN,
                usedToday(rule.id, now),
            )
        }
        if (rule.blocksOnSight) {
            return BudgetVerdict.Blocked(BudgetVerdict.Reason.NO_BUDGET, usedToday(rule.id, now))
        }

        val state = active?.takeIf { it.ruleId == rule.id }
        val sessionUsed = state?.sessionSeconds ?: 0
        val dailyUsed = usedToday(rule.id, now)

        if (sessionUsed >= rule.sessionBudgetSeconds) {
            return BudgetVerdict.Blocked(BudgetVerdict.Reason.SESSION_EXHAUSTED, dailyUsed)
        }
        if (dailyUsed >= rule.dailyBudgetSeconds) {
            return BudgetVerdict.Blocked(BudgetVerdict.Reason.DAILY_EXHAUSTED, dailyUsed)
        }

        val remaining = minOf(
            rule.sessionBudgetSeconds - sessionUsed,
            rule.dailyBudgetSeconds - dailyUsed,
        )
        return BudgetVerdict.Allowed(remaining.coerceAtLeast(0))
    }

    /** Records the block and arms the re-block cooldown so re-entry is instant. */
    fun onBlocked(rule: BlockRule) {
        val now = clock()
        active?.let { flush(it) }
        usage.recordBlock(rule.id, now)
        usage.setBlockedUntil(rule.id, now + rule.reblockCooldownSeconds * 1000L)
        // Drop the session so leaving and returning after the cooldown starts clean;
        // the daily budget is what stops that from being a free reset.
        active = null
    }

    fun usedToday(ruleId: String, nowMillis: Long = clock()): Int {
        val persisted = usage.secondsToday(ruleId, nowMillis)
        val pendingWrite = active?.takeIf { it.ruleId == ruleId }?.unflushedSeconds ?: 0
        return persisted + pendingWrite
    }

    fun flushNow() {
        active?.let { flush(it) }
    }

    private fun flush(state: Active) {
        if (state.unflushedSeconds <= 0) return
        usage.addSeconds(state.ruleId, clock(), state.unflushedSeconds)
        state.unflushedSeconds = 0
    }

    private companion object {
        const val FLUSH_INTERVAL_SECONDS = 10
    }
}
