package am.onex.stopdoom.guard

import am.onex.stopdoom.rules.ScreenSnapshot

/**
 * Backs you out of this app's own pages inside system Settings.
 *
 * The accessibility toggle is the one switch that turns everything off at once, and
 * the system owns it - no cooldown can be applied to it. What can be done is to make
 * reaching it cost something, which is what this does when the aggressive guard is on.
 *
 * It stands down on purpose. After [MAX_BOUNCES] attempts in quick succession it
 * stops interfering for [STAND_DOWN_MS], so the switch is always reachable by someone
 * who genuinely means it. That is the same bargain the rest of the app makes: friction
 * that outlasts an impulse, not a lock. A guard with no way out would eventually be
 * escaped with a factory reset instead, which is strictly worse.
 */
class SettingsGuard(private val clock: () -> Long = System::currentTimeMillis) {

    private var bounces = 0
    private var lastBounceAt = 0L
    private var standDownUntil = 0L

    /** True when this screen should be backed out of right now. */
    fun shouldBounce(onOwnSettingsScreen: Boolean): Boolean {
        val now = clock()
        if (!onOwnSettingsScreen) return false
        if (now < standDownUntil) return false

        val sinceLast = now - lastBounceAt
        if (sinceLast > RESET_AFTER_MS) bounces = 0
        lastBounceAt = now

        // Several scans of one screen are a single attempt. The scan loop runs
        // multiple times a second, so counting every pass would spend the whole
        // allowance inside a second and hand over the stand-down for free.
        if (sinceLast > ATTEMPT_DEBOUNCE_MS) {
            bounces++
            if (bounces >= MAX_BOUNCES) {
                standDownUntil = now + STAND_DOWN_MS
                bounces = 0
            }
        }
        return true
    }

    /** Seconds until the guard stops standing aside, or 0 when it is active. */
    fun standDownSecondsLeft(): Int =
        ((standDownUntil - clock()) / 1_000L).coerceAtLeast(0L).toInt()

    companion object {
        const val MAX_BOUNCES = 3
        const val STAND_DOWN_MS = 60_000L

        /** Attempts further apart than this are a new run, not a continued one. */
        const val RESET_AFTER_MS = 15_000L

        /** Bounces closer together than this are the same attempt seen twice. */
        const val ATTEMPT_DEBOUNCE_MS = 2_000L

        /** One UI splits Settings across two packages depending on the screen. */
        val SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.samsung.android.settings",
        )

        /**
         * Whether a Settings screen is showing this app.
         *
         * Matching the label rather than a screen title is deliberate: the pages
         * worth guarding - the accessibility service detail page, app info, device
         * admin - have nothing in common except that they name the app.
         */
        fun isOwnSettingsScreen(snapshot: ScreenSnapshot, appLabel: String): Boolean {
            if (snapshot.packageName !in SETTINGS_PACKAGES) return false
            if (appLabel.isBlank()) return false
            return snapshot.nodes.any { node ->
                node.text?.contains(appLabel, ignoreCase = true) == true ||
                    node.desc?.contains(appLabel, ignoreCase = true) == true
            }
        }
    }
}
