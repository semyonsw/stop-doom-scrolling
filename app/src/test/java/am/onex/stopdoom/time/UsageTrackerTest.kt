package am.onex.stopdoom.time

import am.onex.stopdoom.data.Db
import am.onex.stopdoom.data.UsageStore
import am.onex.stopdoom.rules.BlockRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Runs against the real SQLite store under Robolectric rather than a hand-written
 * fake, so the upsert SQL and the day-key logic are covered too.
 */
// A plain Application on purpose: booting the real one would start WorkManager and
// the accessibility plumbing, none of which this test needs to exercise SQLite.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class UsageTrackerTest {

    private lateinit var store: UsageStore
    private var now = 1_700_000_000_000L // fixed instant; tests advance it explicitly

    private val rule = BlockRule(
        id = "yt-shorts",
        label = "Shorts",
        packages = listOf("com.google.android.youtube"),
        sessionBudgetSeconds = 10,
        dailyBudgetSeconds = 25,
        reblockCooldownSeconds = 30,
    )

    private fun tracker() = UsageTracker(store) { now }

    @Before
    fun setUp() {
        val db = Db(RuntimeEnvironment.getApplication())
        store = UsageStore(db)
    }

    @Test
    fun `allows until the session budget runs out`() {
        val tracker = tracker()
        tracker.onTargetChanged(rule.id)

        repeat(9) {
            now += 1_000
            assertTrue(tracker.tick(rule) is BudgetVerdict.Allowed)
        }
        now += 1_000
        val verdict = tracker.tick(rule)
        assertTrue(verdict is BudgetVerdict.Blocked)
        assertEquals(
            BudgetVerdict.Reason.SESSION_EXHAUSTED,
            (verdict as BudgetVerdict.Blocked).reason,
        )
    }

    @Test
    fun `remaining seconds counts down`() {
        val tracker = tracker()
        tracker.onTargetChanged(rule.id)
        now += 1_000
        val verdict = tracker.tick(rule)
        assertEquals(9, (verdict as BudgetVerdict.Allowed).remainingSeconds)
    }

    @Test
    fun `zero budget blocks on sight`() {
        val instant = rule.copy(sessionBudgetSeconds = 0, dailyBudgetSeconds = 0)
        val verdict = tracker().check(instant)
        assertTrue(verdict is BudgetVerdict.Blocked)
        assertEquals(BudgetVerdict.Reason.NO_BUDGET, (verdict as BudgetVerdict.Blocked).reason)
    }

    /** Leaving and coming back must not hand out a fresh session immediately. */
    @Test
    fun `reblock cooldown blocks a quick return`() {
        val tracker = tracker()
        tracker.onTargetChanged(rule.id)
        tracker.onBlocked(rule)

        now += 5_000
        val verdict = tracker.check(rule)
        assertTrue(verdict is BudgetVerdict.Blocked)
        assertEquals(
            BudgetVerdict.Reason.REBLOCK_COOLDOWN,
            (verdict as BudgetVerdict.Blocked).reason,
        )
    }

    @Test
    fun `after the cooldown a new session is allowed`() {
        val tracker = tracker()
        tracker.onTargetChanged(rule.id)
        now += 3_000
        tracker.tick(rule)
        tracker.onBlocked(rule)

        now += 31_000
        assertTrue(tracker.check(rule) is BudgetVerdict.Allowed)
    }

    /** The daily budget is what stops the session reset from being a free refill. */
    @Test
    fun `daily budget survives session resets`() {
        val tracker = tracker()

        repeat(3) {
            tracker.onTargetChanged(null)
            tracker.onTargetChanged(rule.id)
            repeat(9) {
                now += 1_000
                tracker.tick(rule)
            }
            tracker.flushNow()
            now += 31_000 // wait out the reblock cooldown
        }

        val verdict = tracker.check(rule)
        assertTrue("expected daily exhaustion, got $verdict", verdict is BudgetVerdict.Blocked)
        assertEquals(
            BudgetVerdict.Reason.DAILY_EXHAUSTED,
            (verdict as BudgetVerdict.Blocked).reason,
        )
    }

    @Test
    fun `unflushed seconds still count toward the budget`() {
        val tracker = tracker()
        tracker.onTargetChanged(rule.id)
        // Fewer ticks than the 10s flush interval, so nothing has been persisted yet.
        repeat(3) {
            now += 1_000
            tracker.tick(rule)
        }
        assertEquals(0, store.secondsToday(rule.id, now))
        assertEquals(3, tracker.usedToday(rule.id, now))
    }

    @Test
    fun `switching targets flushes the previous one`() {
        val tracker = tracker()
        tracker.onTargetChanged(rule.id)
        repeat(4) {
            now += 1_000
            tracker.tick(rule)
        }
        tracker.onTargetChanged("something-else")
        assertEquals(4, store.secondsToday(rule.id, now))
    }

    @Test
    fun `usage is keyed by calendar day`() {
        val tracker = tracker()
        tracker.onTargetChanged(rule.id)
        repeat(5) {
            now += 1_000
            tracker.tick(rule)
        }
        tracker.flushNow()
        val firstDay = store.secondsToday(rule.id, now)
        assertEquals(5, firstDay)

        now += 24L * 60 * 60 * 1000
        assertEquals(0, store.secondsToday(rule.id, now))
    }

    @Test
    fun `opens and blocks are counted`() {
        val tracker = tracker()
        tracker.onTargetChanged(rule.id)
        tracker.onBlocked(rule)

        val today = store.usageForDay(store.dayKey(now)).first { it.ruleId == rule.id }
        assertEquals(1, today.opens)
        assertEquals(1, today.blocks)
    }
}
