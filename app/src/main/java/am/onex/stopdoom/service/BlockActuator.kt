package am.onex.stopdoom.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import am.onex.stopdoom.rules.BlockAction

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
    private val service: AccessibilityService,
    private val handler: Handler,
) {

    private var attemptsRemaining = 0
    private var inFlight = false

    fun perform(action: BlockAction, stillMatching: () -> Boolean, onFinished: () -> Unit = {}) {
        when (action) {
            BlockAction.OVERLAY_ONLY -> onFinished()

            BlockAction.HOME -> {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
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
            if (attemptsRemaining <= 0) {
                // Gave up: the app is not responding to Back. Home is the fallback
                // that always works, rather than leaving the feed on screen.
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                finish()
                return
            }
            if (!stillMatching()) {
                finish()
                return
            }
            attemptsRemaining--
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            handler.postDelayed(this, BACK_INTERVAL_MS)
        }
    }

    private fun finish() {
        inFlight = false
        attemptsRemaining = 0
        onFinished()
    }

    private companion object {
        const val MAX_BACK_ATTEMPTS = 5
        const val BACK_INTERVAL_MS = 350L
    }
}
