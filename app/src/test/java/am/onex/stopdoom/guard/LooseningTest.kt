package am.onex.stopdoom.guard

import am.onex.stopdoom.rules.BlockAction
import am.onex.stopdoom.rules.BlockRule
import am.onex.stopdoom.rules.MatchSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cooldown is only as good as this predicate. A loosening misclassified as a
 * tightening applies instantly and quietly defeats the whole mechanism, so the
 * cases here are deliberately exhaustive.
 */
class LooseningTest {

    private val base = BlockRule(
        id = "yt-shorts",
        label = "YouTube Shorts",
        packages = listOf("com.google.android.youtube"),
        match = MatchSpec(
            anyViewIdContains = listOf("reel_recycler"),
            minMatchingNodes = 1,
            minAreaFraction = 0.35f,
        ),
        action = BlockAction.OVERLAY_THEN_BACK,
        sessionBudgetSeconds = 300,
        dailyBudgetSeconds = 900,
        frictionSeconds = 10,
        reblockCooldownSeconds = 30,
    )

    @Test
    fun `no change is not a loosening`() {
        assertFalse(isLoosening(base, base.copy()))
    }

    @Test
    fun `disabling is a loosening`() {
        assertTrue(isLoosening(base, base.copy(enabled = false)))
    }

    @Test
    fun `enabling is not`() {
        assertFalse(isLoosening(base.copy(enabled = false), base))
    }

    @Test
    fun `raising a budget loosens, lowering does not`() {
        assertTrue(isLoosening(base, base.copy(sessionBudgetSeconds = 600)))
        assertTrue(isLoosening(base, base.copy(dailyBudgetSeconds = 1800)))
        assertFalse(isLoosening(base, base.copy(sessionBudgetSeconds = 60)))
        assertFalse(isLoosening(base, base.copy(dailyBudgetSeconds = 60)))
    }

    @Test
    fun `shortening the friction wait loosens`() {
        assertTrue(isLoosening(base, base.copy(frictionSeconds = 2)))
        assertFalse(isLoosening(base, base.copy(frictionSeconds = 30)))
    }

    @Test
    fun `shortening the reblock cooldown loosens`() {
        assertTrue(isLoosening(base, base.copy(reblockCooldownSeconds = 5)))
        assertFalse(isLoosening(base, base.copy(reblockCooldownSeconds = 120)))
    }

    @Test
    fun `weakening the action loosens`() {
        assertTrue(isLoosening(base, base.copy(action = BlockAction.OVERLAY_ONLY)))
        assertTrue(isLoosening(base, base.copy(action = BlockAction.BACK_UNTIL_GONE)))
        assertFalse(isLoosening(base, base.copy(action = BlockAction.HOME)))
    }

    @Test
    fun `dropping a watched package loosens`() {
        val twoPackages = base.copy(packages = listOf("com.google.android.youtube", "com.x"))
        assertTrue(isLoosening(twoPackages, base))
        assertFalse(isLoosening(base, twoPackages))
    }

    @Test
    fun `removing a matcher loosens, adding one does not`() {
        val narrowed = base.copy(match = base.match.copy(anyViewIdContains = emptyList()))
        assertTrue(isLoosening(base, narrowed))

        val widened = base.copy(
            match = base.match.copy(anyViewIdContains = listOf("reel_recycler", "shorts_")),
        )
        assertFalse(isLoosening(base, widened))
    }

    @Test
    fun `raising the match thresholds loosens because the rule fires less`() {
        assertTrue(isLoosening(base, base.copy(match = base.match.copy(minMatchingNodes = 3))))
        assertTrue(isLoosening(base, base.copy(match = base.match.copy(minAreaFraction = 0.9f))))
        assertFalse(isLoosening(base, base.copy(match = base.match.copy(minAreaFraction = 0.1f))))
    }

    @Test
    fun `switching to requireAllGroups loosens`() {
        assertTrue(isLoosening(base, base.copy(match = base.match.copy(requireAllGroups = true))))
        assertFalse(
            isLoosening(
                base.copy(match = base.match.copy(requireAllGroups = true)),
                base,
            ),
        )
    }

    @Test
    fun `turning a section rule into a whole-app rule is not a loosening`() {
        assertFalse(isLoosening(base, base.copy(wholeApp = true)))
        assertTrue(isLoosening(base.copy(wholeApp = true), base))
    }

    @Test
    fun `notes and labels are cosmetic`() {
        assertFalse(isLoosening(base, base.copy(label = "Renamed", note = "some note")))
    }
}
