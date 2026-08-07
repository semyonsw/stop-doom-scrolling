package am.onex.stopdoom.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The master switch and maintenance mode both answer one question, and every
 * blocking path asks it through [SettingsSnapshot.blockingActiveAt]. A blocker that
 * checked only maintenance would keep running with the master switch off, which is
 * the exact failure this consolidation exists to prevent.
 */
class SettingsSnapshotTest {

    private val now = 1_000_000L
    private val base = Settings.DEFAULTS

    @Test
    fun `defaults have protection on`() {
        assertTrue(base.protectionEnabled)
        assertTrue(base.blockingActiveAt(now))
    }

    @Test
    fun `defaults are user mode with the cooldown enforced`() {
        assertFalse(base.developerMode)
        assertTrue(base.cooldownEnabled)
    }

    @Test
    fun `the developer switches do not affect whether blocking runs`() {
        // Developer mode reveals editing surfaces and the cooldown governs how fast a
        // change lands. Neither is an answer to "should this screen be blocked".
        assertTrue(base.copy(developerMode = true, cooldownEnabled = false).blockingActiveAt(now))
    }

    @Test
    fun `the master switch stops blocking on its own`() {
        assertFalse(base.copy(protectionEnabled = false).blockingActiveAt(now))
    }

    @Test
    fun `maintenance stops blocking while it lasts`() {
        val active = base.copy(maintenanceUntil = now + 60_000L)
        assertFalse(active.blockingActiveAt(now))
        assertTrue(active.blockingActiveAt(now + 61_000L))
    }

    @Test
    fun `maintenance ending does not re-enable a switched-off app`() {
        val both = base.copy(protectionEnabled = false, maintenanceUntil = now + 60_000L)
        assertFalse(both.blockingActiveAt(now + 61_000L))
    }
}
