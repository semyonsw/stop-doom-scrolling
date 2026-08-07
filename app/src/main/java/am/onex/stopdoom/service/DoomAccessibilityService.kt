package am.onex.stopdoom.service

import android.accessibilityservice.AccessibilityService
import android.content.res.Resources
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import am.onex.stopdoom.App
import am.onex.stopdoom.AppContainer
import am.onex.stopdoom.R
import am.onex.stopdoom.guard.SettingsGuard
import am.onex.stopdoom.rules.BlockAction
import am.onex.stopdoom.rules.BlockRule
import am.onex.stopdoom.rules.ScreenSnapshot
import am.onex.stopdoom.time.BudgetVerdict
import am.onex.stopdoom.web.UrlBarDetector

/**
 * The one component that knows what is on screen.
 *
 * Everything is driven from here: section detection, time budgets, the browser URL
 * check and the block overlay. Running it as a single service rather than several
 * means one tree scan serves every consumer.
 */
class DoomAccessibilityService : AccessibilityService() {

    private lateinit var container: AppContainer
    private lateinit var actuator: BlockActuator

    private val scanner = NodeScanner()
    private val dumpScanner = NodeScanner.forDumping()
    private val settingsGuard = SettingsGuard()

    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var main: Handler

    private val appLabel: String by lazy { getString(R.string.app_name) }

    @Volatile
    private var lastScanAt = 0L

    /** The target the budget clock is running against. Cleared the moment it blocks. */
    @Volatile
    private var currentRuleId: String? = null

    /**
     * What the most recent scan actually saw, which is a different question.
     *
     * [currentRuleId] is reset by a block so the next entry is logged as a fresh one,
     * so it cannot answer "is the feed still up?" - and that is exactly what the Back
     * loop has to keep asking. Holding the two separately is what stops the actuator
     * from either giving up early or walking the user out to the launcher.
     */
    @Volatile
    private var visibleTarget: String? = null

    private var tickScheduled = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        container = (application as App).container
        main = Handler(mainLooper)
        actuator = BlockActuator(GlobalActions.of(this), main)

        workerThread = HandlerThread("doomguard-scan").apply { start() }
        worker = Handler(workerThread.looper)

        container.onAccessibilityServiceConnected()
        Log.i(TAG, "connected; ${container.engine.rules.size} rules loaded")
    }

    override fun onDestroy() {
        // The overlay lives in the application's window manager, so it would outlive
        // this service and leave an undismissable screen behind if it were showing.
        if (::container.isInitialized) {
            if (::main.isInitialized) main.post { container.overlay.hideIfShowing() }
            container.tracker.flushNow()
            container.onAccessibilityServiceDisconnected()
        }
        if (::actuator.isInitialized) actuator.cancel()
        if (::workerThread.isInitialized) workerThread.quitSafely()
        super.onDestroy()
    }

    override fun onInterrupt() {
        if (::actuator.isInitialized) actuator.cancel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName == this.packageName) return

        val now = System.currentTimeMillis()
        val forced = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        // Throttle content-change events: a scrolling feed fires these continuously
        // and re-scanning on each one is pure waste. Window changes always go through.
        if (!forced && now - lastScanAt < SCAN_THROTTLE_MS) return
        lastScanAt = now

        worker.post { evaluate(packageName, now) }
    }

    private fun evaluate(packageName: String, now: Long) {
        val settings = container.settings.current
        if (!settings.blockingActiveAt(now)) {
            clearActive()
            // Being switched off - by the master switch or by maintenance - is the one
            // case where a block screen already on show is wrong, so this is the only
            // path that takes it down early.
            main.post { container.overlay.hideIfShowing() }
            return
        }

        val candidates = container.engine.candidatesFor(packageName)
        val isBrowser = settings.urlBarBlockingEnabled && UrlBarDetector.isBrowser(packageName)
        val wantsDump = container.dumper.isArmed(now)
        val guardsSettings = settings.watchdogAggressive &&
            packageName in SettingsGuard.SETTINGS_PACKAGES

        if (candidates.isEmpty() && !isBrowser && !wantsDump && !guardsSettings) {
            clearActive()
            return
        }

        val root = rootInActiveWindow
        val metrics = Resources.getSystem().displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        // Fast path: rules naming an exact view id are answered natively, no walk.
        val fastMatch = candidates.firstOrNull { rule ->
            !rule.wholeApp && rule.match.fullyQualifiedViewIds.isNotEmpty() &&
                scanner.matchesAnyViewId(
                    root,
                    rule.match.fullyQualifiedViewIds,
                    rule.match.minAreaFraction,
                    width,
                    height,
                )
        }

        val needsSnapshot = wantsDump || isBrowser || guardsSettings ||
            (fastMatch == null && candidates.any { it.needsTreeWalk })

        val snapshot: ScreenSnapshot? = if (needsSnapshot) {
            val walker = if (wantsDump) dumpScanner else scanner
            walker.snapshot(root, packageName, width, height)
        } else {
            null
        }

        if (wantsDump && snapshot != null) {
            container.dumper.dump(snapshot)
        }

        if (guardsSettings && snapshot != null && guardOwnSettings(snapshot, now)) return

        if (isBrowser && snapshot != null && checkBrowser(snapshot, now)) return

        val matched = fastMatch
            ?: candidates.firstOrNull { it.wholeApp }
            ?: snapshot?.let { container.engine.firstMatch(it) }

        if (matched == null) {
            clearActive()
            return
        }

        onTargetVisible(matched, now)
    }

    /** Returns true when the screen was bounced, so nothing else should run. */
    private fun guardOwnSettings(snapshot: ScreenSnapshot, now: Long): Boolean {
        val own = SettingsGuard.isOwnSettingsScreen(snapshot, appLabel)
        if (!settingsGuard.shouldBounce(own)) return false

        // No feed is on screen here, so nothing should still be counting against a
        // budget or driving a Back sequence of its own.
        clearActive()
        container.usage.log(now, "settings_guard_bounce")
        main.post { performGlobalAction(GLOBAL_ACTION_BACK) }
        return true
    }

    /** Returns true when the browser check blocked, so section rules are skipped. */
    private fun checkBrowser(snapshot: ScreenSnapshot, now: Long): Boolean {
        val settings = container.settings.current
        if (!settings.webBlockingEnabled) return false

        val verdict = UrlBarDetector.evaluate(
            snapshot,
            container.blocklist.current(),
            settings.keywordBlockingEnabled,
        )
        val reason = when (verdict) {
            is UrlBarDetector.Verdict.BlockedDomain -> "Blocked site: ${verdict.host}"
            is UrlBarDetector.Verdict.BlockedKeyword -> "Blocked search: \"${verdict.keyword}\""
            UrlBarDetector.Verdict.Allowed -> return false
        }

        container.usage.log(now, "web_blocked", detail = reason)
        visibleTarget = WEB_TARGET
        main.post {
            container.overlay.show(
                title = "Not this",
                reason = reason,
                frictionSeconds = WEB_BLOCK_FRICTION_SECONDS,
                usedTodaySeconds = null,
                replacements = settings.replacementActivities,
                onDismiss = { actuator.cancel() },
            )
            actuator.perform(
                BlockAction.OVERLAY_THEN_BACK,
                stillMatching = { visibleTarget == WEB_TARGET },
                onFinished = {},
            )
        }
        return true
    }

    private fun onTargetVisible(rule: BlockRule, now: Long) {
        visibleTarget = rule.id
        if (currentRuleId != rule.id) {
            currentRuleId = rule.id
            container.tracker.onTargetChanged(rule.id)
            container.usage.log(now, "target_entered", rule.id)
        }
        scheduleTick()
        applyVerdict(rule, container.tracker.check(rule))
    }

    private fun applyVerdict(rule: BlockRule, verdict: BudgetVerdict) {
        when (verdict) {
            is BudgetVerdict.Allowed -> container.publishRemaining(rule, verdict.remainingSeconds)
            is BudgetVerdict.Blocked -> block(rule, verdict)
        }
    }

    private fun block(rule: BlockRule, verdict: BudgetVerdict.Blocked) {
        container.tracker.onBlocked(rule)
        currentRuleId = null
        container.usage.log(
            System.currentTimeMillis(),
            "blocked",
            rule.id,
            verdict.reason.name,
        )

        val settings = container.settings.current
        val showsOverlay = rule.action == BlockAction.OVERLAY_ONLY ||
            rule.action == BlockAction.OVERLAY_THEN_BACK

        main.post {
            if (showsOverlay) {
                container.overlay.show(
                    title = rule.label,
                    reason = verdict.reason.humanReason(),
                    frictionSeconds = rule.frictionSeconds,
                    usedTodaySeconds = verdict.usedTodaySeconds,
                    replacements = settings.replacementActivities,
                    onDismiss = { actuator.cancel() },
                )
            }
            actuator.perform(
                rule.action,
                stillMatching = { visibleTarget == rule.id },
            )
        }
    }

    /**
     * Nothing worth blocking is on screen.
     *
     * Note what this does not do: take the block screen down. Backing out of the feed
     * lands here within a couple of hundred milliseconds, so hiding here would flash
     * the screen away before it could be read - and the enforced pause is the part
     * that actually works. It goes when it is dismissed, or on its own timer.
     */
    private fun clearActive() {
        visibleTarget = null
        if (currentRuleId != null) {
            currentRuleId = null
            container.tracker.onTargetChanged(null)
            container.publishRemaining(null, 0)
        }
    }

    /** One-second budget tick, alive only while a target is on screen. */
    private fun scheduleTick() {
        if (tickScheduled) return
        tickScheduled = true
        worker.postDelayed(tickRunnable, 1_000L)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            tickScheduled = false
            val ruleId = currentRuleId ?: return
            val rule = container.engine.rules.firstOrNull { it.id == ruleId } ?: return
            applyVerdict(rule, container.tracker.tick(rule))
            if (currentRuleId != null) {
                tickScheduled = true
                worker.postDelayed(this, 1_000L)
            }
        }
    }

    private fun BudgetVerdict.Reason.humanReason(): String = when (this) {
        BudgetVerdict.Reason.NO_BUDGET -> "This one is blocked outright."
        BudgetVerdict.Reason.SESSION_EXHAUSTED -> "Session limit reached."
        BudgetVerdict.Reason.DAILY_EXHAUSTED -> "Daily limit reached."
        BudgetVerdict.Reason.REBLOCK_COOLDOWN -> "You were just here. Still blocked."
    }

    private companion object {
        const val TAG = "DoomGuard/A11y"
        const val SCAN_THROTTLE_MS = 200L
        const val WEB_BLOCK_FRICTION_SECONDS = 8

        /** Stands in for a rule id in [visibleTarget]; no rule can be called this. */
        const val WEB_TARGET = " web"
    }
}

/** A rule that cannot be answered by the exact-id fast path needs the tree. */
private val BlockRule.needsTreeWalk: Boolean
    get() = !wholeApp && (
        match.anyContentDescription.isNotEmpty() ||
            match.anyText.isNotEmpty() ||
            match.anyViewIdContains.any { !it.contains(":id/") }
        )
