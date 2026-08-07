package am.onex.stopdoom.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import am.onex.stopdoom.rules.BlockAction

/**
 * The two system gestures a block can make.
 *
 * Behind an interface only so the retry sequence below can be tested. Getting that
 * sequence wrong is silent: it either gives up while the feed is still up, or walks
 * the user out to the launcher when a single Back would have done.
 */
interface GlobalActions {
    fun back()
    fun home()

    companion object {
        fun of(service: AccessibilityService) = object : GlobalActions {
            override fun back() {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }

            override fun home() {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            }
        }
    }
}

/**
 * Carries out a block.
 *
 * Back-pressing rather than going Home is the default because it does what you
 * actually asked for: leave the Shorts section, keep the rest of YouTube. It is
 * retried a few times because one Back often only dismisses a player chrome layer
 * rather than the feed itself - but it is capped, since pressing Back forever in
 * an app that ignores it would walk the user out of everything.
 */
class BlockActuator(
    private val actions: GlobalActions,
    private val handler: Handler,
) {

    private var attemptsRemaining = 0
    private var inFlight = false

    fun perform(action: BlockAction, stillMatching: () -> Boolean, onFinished: () -> Unit = {}) {
        when (action) {
            BlockAction.OVERLAY_ONLY -> onFinished()

            BlockAction.HOME -> {
                actions.home()
                onFinished()
            }

            BlockAction.BACK_UNTIL_GONE, BlockAction.OVERLAY_THEN_BACK -> {
                startBackSequence(stillMatching, onFinished)
            }
        }
    }

    fun cancel() {
        attemptsRemaining = 0
        inFlight = false
        handler.removeCallbacks(backStep)
    }

    private fun startBackSequence(stillMatching: () -> Boolean, onFinished: () -> Unit) {
        if (inFlight) return
        inFlight = true
        attemptsRemaining = MAX_BACK_ATTEMPTS
        this.stillMatching = stillMatching
        this.onFinished = onFinished
        handler.post(backStep)
    }

    private var stillMatching: () -> Boolean = { false }
    private var onFinished: () -> Unit = {}

    private val backStep = object : Runnable {
        override fun run() {
            // Order matters: ask whether the target is gone before deciding the
            // attempts ran out, or a Back that worked on the last try would still
            // be followed by a trip to the launcher.
            if (!stillMatching()) {
                finish()
                return
            }
            if (attemptsRemaining <= 0) {
                // Gave up: the app is not responding to Back. Home is the fallback
                // that always works, rather than leaving the feed on screen.
                actions.home()
                finish()
                return
            }
            attemptsRemaining--
            actions.back()
            handler.postDelayed(this, BACK_INTERVAL_MS)
        }
    }

    private fun finish() {
        inFlight = false
        attemptsRemaining = 0
        onFinished()
    }

    companion object {
        const val MAX_BACK_ATTEMPTS = 5
        const val BACK_INTERVAL_MS = 350L
    }
}
