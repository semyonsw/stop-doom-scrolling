package am.onex.stopdoom.service

import android.accessibilityservice.AccessibilityService
import android.content.res.Resources
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import am.onex.stopdoom.App
import am.onex.stopdoom.AppContainer
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

    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var main: Handler

    @Volatile
    private var lastScanAt = 0L

    @Volatile
    private var currentRuleId: String? = null

    private var tickScheduled = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        container = (application as App).container
        main = Handler(mainLooper)
        actuator = BlockActuator(this, main)

        workerThread = HandlerThread("doomguard-scan").apply { start() }
        worker = Handler(workerThread.looper)

        container.onAccessibilityServiceConnected()
        Log.i(TAG, "connected; ${container.engine.rules.size} rules loaded")
    }

    override fun onDestroy() {
        actuator.cancel()
        container.tracker.flushNow()
        if (::workerThread.isInitialized) workerThread.quitSafely()
        container.onAccessibilityServiceDisconnected()
        super.onDestroy()
    }

    override fun onInterrupt() {
        actuator.cancel()
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
        if (settings.maintenanceActiveAt(now)) {
            clearActive()
            return
        }

        val candidates = container.engine.candidatesFor(packageName)
        val isBrowser = settings.urlBarBlockingEnabled && UrlBarDetector.isBrowser(packageName)
        val wantsDump = container.dumper.isArmed(now)

        if (candidates.isEmpty() && !isBrowser && !wantsDump) {
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

        val needsSnapshot = wantsDump || isBrowser ||
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
                stillMatching = { true },
                onFinished = {},
            )
        }
        return true
    }

    private fun onTargetVisible(rule: BlockRule, now: Long) {
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
                stillMatching = { currentRuleId == null },
            )
        }
    }

    private fun clearActive() {
        if (currentRuleId != null) {
            currentRuleId = null
            container.tracker.onTargetChanged(null)
            container.publishRemaining(null, 0)
        }
        main.post { container.overlay.hideIfShowing() }
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
    }
}

/** A rule that cannot be answered by the exact-id fast path needs the tree. */
private val BlockRule.needsTreeWalk: Boolean
    get() = !wholeApp && (
        match.anyContentDescription.isNotEmpty() ||
            match.anyText.isNotEmpty() ||
            match.anyViewIdContains.any { !it.contains(":id/") }
        )
