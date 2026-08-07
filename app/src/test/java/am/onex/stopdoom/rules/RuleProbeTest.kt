package am.onex.stopdoom.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe is what turns "it doesn't work" into a specific fix.
 *
 * A rule that never fires is otherwise silent - identical, from the Stats tab, to a
 * service that never ran - and the four ways a selector can be wrong need four
 * different corrections. These tests pin each one to the sentence it produces, and
 * check the probe agrees with [matches] rather than reimplementing it.
 */
class RuleProbeTest {

    private val screenW = 1080
    private val screenH = 2340

    private fun snapshot(pkg: String, vararg nodes: SnapNode, truncated: Boolean = false) =
        ScreenSnapshot(pkg, screenW, screenH, nodes.toList(), truncated)

    private fun fullScreen(viewId: String? = null, desc: String? = null) = SnapNode(
        viewId = viewId, desc = desc,
        left = 0, top = 0, right = screenW, bottom = screenH,
    )

    private fun tabButton(viewId: String? = null, desc: String? = null) = SnapNode(
        viewId = viewId, desc = desc,
        left = 216, top = 2200, right = 432, bottom = 2300,
    )

    private val rule = BlockRule(
        id = "yt-shorts",
        label = "YouTube Shorts",
        packages = listOf("com.google.android.youtube"),
        match = MatchSpec(
            anyViewIdContains = listOf("reel_recycler"),
            minAreaFraction = 0.35f,
        ),
    )

    @Test
    fun `a match is reported as a match`() {
        val snap = snapshot(
            "com.google.android.youtube",
            fullScreen(viewId = "com.google.android.youtube:id/reel_recycler"),
        )
        val probe = rule.probe(snap)

        assertTrue(probe.matched)
        assertEquals(rule.matches(snap), probe.matched)
        assertEquals("Matches.", probe.verdict)
    }

    @Test
    fun `a selector that is nowhere on screen says so`() {
        val snap = snapshot(
            "com.google.android.youtube",
            fullScreen(viewId = "com.google.android.youtube:id/watch_player"),
        )
        val probe = rule.probe(snap)

        assertFalse(probe.matched)
        assertTrue(probe.verdict.startsWith("None of the selectors were found"))
        assertEquals(0, probe.groups.single().raw)
    }

    @Test
    fun `a truncated scan is called out rather than blamed on the selector`() {
        val snap = snapshot(
            "com.google.android.youtube",
            fullScreen(viewId = "com.google.android.youtube:id/watch_player"),
            truncated = true,
        )
        assertTrue(rule.probe(snap).verdict.contains("size cap"))
    }

    @Test
    fun `a selector found but too small blames the area floor`() {
        // The exact case minAreaFraction exists for, seen from the other side: the
        // nav-bar button matched, and the rule was right to reject it - but the user
        // needs to be told which of the two knobs is responsible.
        val snap = snapshot(
            "com.google.android.youtube",
            tabButton(viewId = "com.google.android.youtube:id/reel_recycler"),
        )
        val probe = rule.probe(snap)

        assertFalse(probe.matched)
        assertTrue(probe.verdict.contains("Found the selector"))
        assertTrue(probe.verdict.contains("needs 35%"))
        assertEquals(1, probe.groups.single().raw)
        assertEquals(0, probe.groups.single().passing)
    }

    @Test
    fun `too few matching elements blames the element count`() {
        val strict = rule.copy(match = rule.match.copy(minMatchingNodes = 3))
        val snap = snapshot(
            "com.google.android.youtube",
            fullScreen(viewId = "com.google.android.youtube:id/reel_recycler"),
        )
        val probe = strict.probe(snap)

        assertFalse(probe.matched)
        assertTrue(probe.verdict.contains("needs 3"))
    }

    @Test
    fun `a rule for another app says so instead of blaming selectors`() {
        val snap = snapshot("com.instagram.android", fullScreen(viewId = "clips_viewer"))
        assertEquals("Does not apply to com.instagram.android.", rule.probe(snap).verdict)
    }

    @Test
    fun `a disabled rule reports being off`() {
        val snap = snapshot(
            "com.google.android.youtube",
            fullScreen(viewId = "com.google.android.youtube:id/reel_recycler"),
        )
        assertEquals("Rule is switched off.", rule.copy(enabled = false).probe(snap).verdict)
    }

    @Test
    fun `a rule with no selectors says it can never match`() {
        val empty = rule.copy(match = MatchSpec())
        val snap = snapshot("com.google.android.youtube", fullScreen(viewId = "anything"))
        assertTrue(empty.probe(snap).verdict.contains("never match"))
    }

    @Test
    fun `a whole-app rule reports matching everything`() {
        val whole = rule.copy(wholeApp = true)
        val snap = snapshot("com.google.android.youtube", fullScreen(viewId = "anything"))
        val probe = whole.probe(snap)

        assertTrue(probe.matched)
        assertTrue(probe.verdict.contains("Whole-app"))
    }

    @Test
    fun `the largest fraction is reported so the floor can be sized`() {
        val snap = snapshot(
            "com.google.android.youtube",
            tabButton(viewId = "com.google.android.youtube:id/reel_recycler"),
            fullScreen(viewId = "com.google.android.youtube:id/reel_recycler_2"),
        )
        val group = rule.probe(snap).groups.single()

        assertEquals(2, group.raw)
        // The full-screen node is the larger of the two, so that is what gets shown.
        assertEquals(1.0f, group.largestFraction, 0.001f)
    }

    @Test
    fun `requireAllGroups with a partial match names that as the reason`() {
        val both = rule.copy(
            match = rule.match.copy(
                anyContentDescription = listOf("Shorts"),
                requireAllGroups = true,
            ),
        )
        val snap = snapshot(
            "com.google.android.youtube",
            fullScreen(viewId = "com.google.android.youtube:id/reel_recycler"),
        )
        val probe = both.probe(snap)

        assertFalse(probe.matched)
        assertTrue(probe.verdict.contains("requires all of them"))
    }
}
