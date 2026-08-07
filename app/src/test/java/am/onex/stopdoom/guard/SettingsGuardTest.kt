package am.onex.stopdoom.guard

import am.onex.stopdoom.rules.ScreenSnapshot
import am.onex.stopdoom.rules.SnapNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsGuardTest {

    private var now = 1_000_000L
    private val guard = SettingsGuard { now }

    private fun snapshot(pkg: String, vararg nodes: SnapNode) =
        ScreenSnapshot(pkg, 1080, 2340, nodes.toList())

    // --- screen recognition ------------------------------------------------

    @Test
    fun `recognises the app's own page in settings`() {
        val snap = snapshot(
            "com.android.settings",
            SnapNode(text = "DoomGuard blocker"),
            SnapNode(text = "Use service"),
        )
        assertTrue(SettingsGuard.isOwnSettingsScreen(snap, "DoomGuard"))
    }

    @Test
    fun `matches on content description too`() {
        val snap = snapshot("com.android.settings", SnapNode(desc = "doomguard, switch, on"))
        assertTrue(SettingsGuard.isOwnSettingsScreen(snap, "DoomGuard"))
    }

    @Test
    fun `leaves unrelated settings screens alone`() {
        val snap = snapshot(
            "com.android.settings",
            SnapNode(text = "Wi-Fi"),
            SnapNode(text = "Bluetooth"),
        )
        assertFalse(SettingsGuard.isOwnSettingsScreen(snap, "DoomGuard"))
    }

    /** The same words in some other app are not the Settings page worth guarding. */
    @Test
    fun `only fires inside the settings packages`() {
        val snap = snapshot("com.google.android.youtube", SnapNode(text = "DoomGuard"))
        assertFalse(SettingsGuard.isOwnSettingsScreen(snap, "DoomGuard"))
    }

    @Test
    fun `a blank label never matches`() {
        val snap = snapshot("com.android.settings", SnapNode(text = "anything"))
        assertFalse(SettingsGuard.isOwnSettingsScreen(snap, ""))
    }

    // --- the stand-down bargain -------------------------------------------

    /** One deliberate attempt: walk back in, get bounced, repeat. */
    private fun attempt(): Boolean {
        now += SettingsGuard.ATTEMPT_DEBOUNCE_MS + 1
        return guard.shouldBounce(true)
    }

    @Test
    fun `bounces the first few attempts`() {
        assertTrue(attempt())
        assertTrue(attempt())
        assertTrue(attempt())
    }

    /**
     * The property the whole design rests on: a determined user always gets through.
     * A guard with no way out would be escaped by a factory reset instead.
     */
    @Test
    fun `stands aside after the third attempt`() {
        repeat(SettingsGuard.MAX_BOUNCES) { assertTrue(attempt()) }
        assertFalse(attempt())
        assertFalse(attempt())
    }

    /**
     * The scan loop sees the same screen many times a second. If each pass spent an
     * attempt, the stand-down would arrive within a second and the guard would be
     * worth nothing.
     */
    @Test
    fun `repeated scans of one screen are a single attempt`() {
        repeat(50) {
            now += 100L
            assertTrue(guard.shouldBounce(true))
        }
        assertEquals(0, guard.standDownSecondsLeft())
    }

    @Test
    fun `resumes once the stand-down elapses`() {
        repeat(SettingsGuard.MAX_BOUNCES) { attempt() }
        assertFalse(attempt())

        now += SettingsGuard.STAND_DOWN_MS + 1
        assertTrue(guard.shouldBounce(true))
    }

    @Test
    fun `reports how long it is standing aside`() {
        repeat(SettingsGuard.MAX_BOUNCES) { attempt() }
        assertEquals(60, guard.standDownSecondsLeft())

        now += 30_000L
        assertEquals(30, guard.standDownSecondsLeft())
    }

    @Test
    fun `never bounces a screen it was not asked about`() {
        assertFalse(guard.shouldBounce(false))
        assertFalse(guard.shouldBounce(false))
        assertFalse(guard.shouldBounce(false))
        assertFalse(guard.shouldBounce(false))
    }

    /** Attempts spread out over time are separate runs, not one long one. */
    @Test
    fun `forgets old attempts`() {
        assertTrue(attempt())
        assertTrue(attempt())

        now += SettingsGuard.RESET_AFTER_MS + 1

        repeat(SettingsGuard.MAX_BOUNCES) { assertTrue(attempt()) }
        assertFalse(attempt())
    }

    @Test
    fun `covers the samsung settings package`() {
        val snap = snapshot("com.samsung.android.settings", SnapNode(text = "DoomGuard"))
        assertTrue(SettingsGuard.isOwnSettingsScreen(snap, "DoomGuard"))
    }
}
