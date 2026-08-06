package am.onex.stopdoom.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detection logic tested without a device.
 *
 * A [ScreenSnapshot] is exactly what the on-device dumper writes, so a real dump
 * pasted into one of these fixtures becomes a regression test the moment YouTube
 * renames something.
 */
class RuleEngineTest {

    private val screenW = 1080
    private val screenH = 2340

    private fun snapshot(pkg: String, vararg nodes: SnapNode) =
        ScreenSnapshot(pkg, screenW, screenH, nodes.toList())

    /** A node covering most of the screen, like a full-screen video player. */
    private fun fullScreen(viewId: String? = null, desc: String? = null, text: String? = null) =
        SnapNode(
            viewId = viewId, desc = desc, text = text,
            left = 0, top = 0, right = screenW, bottom = screenH,
        )

    /** A small node, like a bottom-nav tab button. */
    private fun tabButton(viewId: String? = null, desc: String? = null, text: String? = null) =
        SnapNode(
            viewId = viewId, desc = desc, text = text,
            left = 216, top = 2200, right = 432, bottom = 2300,
        )

    private val shortsRule = BlockRule(
        id = "yt-shorts",
        label = "YouTube Shorts",
        packages = listOf("com.google.android.youtube"),
        match = MatchSpec(
            anyViewIdContains = listOf("reel_recycler", "reel_player_page"),
            minAreaFraction = 0.35f,
        ),
    )

    @Test
    fun `matches shorts player by view id`() {
        val snap = snapshot(
            "com.google.android.youtube",
            fullScreen(viewId = "com.google.android.youtube:id/reel_recycler"),
        )
        assertTrue(shortsRule.matches(snap))
    }

    @Test
    fun `ignores other packages`() {
        val snap = snapshot(
            "com.android.chrome",
            fullScreen(viewId = "com.google.android.youtube:id/reel_recycler"),
        )
        assertFalse(shortsRule.matches(snap))
    }

    @Test
    fun `disabled rule never matches`() {
        val snap = snapshot(
            "com.google.android.youtube",
            fullScreen(viewId = "com.google.android.youtube:id/reel_recycler"),
        )
        assertFalse(shortsRule.copy(enabled = false).matches(snap))
    }

    /**
     * The trap this whole design exists to avoid: "Shorts" appears on the bottom-nav
     * tab of the ordinary home feed. Matching it there would block all of YouTube.
     */
    @Test
    fun `description rule does not fire on the nav tab`() {
        val descRule = BlockRule(
            id = "desc",
            label = "desc",
            packages = listOf("com.google.android.youtube"),
            match = MatchSpec(anyContentDescription = listOf("Shorts"), minAreaFraction = 0.5f),
        )
        val homeFeed = snapshot(
            "com.google.android.youtube",
            tabButton(desc = "Shorts"),
            fullScreen(viewId = "com.google.android.youtube:id/results"),
        )
        assertFalse(descRule.matches(homeFeed))

        val shortsPlayer = snapshot(
            "com.google.android.youtube",
            tabButton(desc = "Shorts"),
            fullScreen(desc = "Shorts video player"),
        )
        assertTrue(descRule.matches(shortsPlayer))
    }

    @Test
    fun `without an area floor the nav tab does match`() {
        val loose = BlockRule(
            id = "loose",
            label = "loose",
            packages = listOf("com.google.android.youtube"),
            match = MatchSpec(anyContentDescription = listOf("Shorts")),
        )
        assertTrue(loose.matches(snapshot("com.google.android.youtube", tabButton(desc = "Shorts"))))
    }

    @Test
    fun `whole app rule ignores the tree entirely`() {
        val tiktok = BlockRule(
            id = "tiktok",
            label = "TikTok",
            packages = listOf("com.zhiliaoapp.musically"),
            wholeApp = true,
        )
        assertTrue(tiktok.matches(snapshot("com.zhiliaoapp.musically")))
    }

    @Test
    fun `empty match spec never fires`() {
        val empty = BlockRule(id = "e", label = "e", packages = listOf("com.x"))
        assertFalse(empty.matches(snapshot("com.x", fullScreen(viewId = "com.x:id/anything"))))
    }

    @Test
    fun `requireAllGroups needs every group to hit`() {
        val strict = BlockRule(
            id = "strict",
            label = "strict",
            packages = listOf("com.facebook.katana"),
            match = MatchSpec(
                anyContentDescription = listOf("Reels"),
                anyText = listOf("Reels"),
                requireAllGroups = true,
            ),
        )
        assertFalse(strict.matches(snapshot("com.facebook.katana", fullScreen(desc = "Reels"))))
        assertTrue(
            strict.matches(
                snapshot(
                    "com.facebook.katana",
                    fullScreen(desc = "Reels"),
                    fullScreen(text = "Reels"),
                ),
            ),
        )
    }

    @Test
    fun `minMatchingNodes raises the bar`() {
        val rule = BlockRule(
            id = "n",
            label = "n",
            packages = listOf("com.x"),
            match = MatchSpec(anyText = listOf("Reel"), minMatchingNodes = 2),
        )
        assertFalse(rule.matches(snapshot("com.x", fullScreen(text = "Reel"))))
        assertTrue(rule.matches(snapshot("com.x", fullScreen(text = "Reel"), fullScreen(text = "Reel"))))
    }

    @Test
    fun `matching is case insensitive`() {
        val snap = snapshot(
            "com.google.android.youtube",
            fullScreen(viewId = "com.google.android.youtube:id/REEL_RECYCLER"),
        )
        assertTrue(shortsRule.matches(snap))
    }

    @Test
    fun `engine returns first match in declaration order`() {
        val narrow = shortsRule
        val broad = BlockRule(
            id = "yt-all",
            label = "YouTube",
            packages = listOf("com.google.android.youtube"),
            wholeApp = true,
        )
        val engine = RuleEngine(listOf(narrow, broad))
        val snap = snapshot(
            "com.google.android.youtube",
            fullScreen(viewId = "com.google.android.youtube:id/reel_recycler"),
        )
        assertEquals("yt-shorts", engine.firstMatch(snap)?.id)
        assertEquals(2, engine.evaluate(snap).size)
    }

    @Test
    fun `candidatesFor filters by package before any matching`() {
        val engine = RuleEngine(listOf(shortsRule))
        assertTrue(engine.candidatesFor("com.instagram.android").isEmpty())
        assertEquals(1, engine.candidatesFor("com.google.android.youtube").size)
        assertNull(engine.firstMatch(snapshot("com.instagram.android")))
    }

    @Test
    fun `fullyQualifiedViewIds only picks entries usable for exact lookup`() {
        val spec = MatchSpec(
            anyViewIdContains = listOf("com.google.android.youtube:id/reel_recycler", "shorts_"),
        )
        assertEquals(listOf("com.google.android.youtube:id/reel_recycler"), spec.fullyQualifiedViewIds)
    }
}
