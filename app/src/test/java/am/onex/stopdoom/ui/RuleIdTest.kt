package am.onex.stopdoom.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ids are derived from the name now that rules are created through a form rather
 * than typed as JSON. The id is what the store upserts on and what usage history
 * is keyed by, so a collision would silently merge two rules' statistics.
 */
class RuleIdTest {

    @Test
    fun `a name becomes a slug`() {
        assertEquals("youtube-shorts", uniqueId("YouTube Shorts", emptyList()))
    }

    @Test
    fun `punctuation collapses rather than repeating`() {
        assertEquals("facebook-reels", uniqueId("Facebook / Reels!!", emptyList()))
    }

    @Test
    fun `a name with nothing usable still yields an id`() {
        assertEquals("rule", uniqueId("???", emptyList()))
    }

    @Test
    fun `a taken id gets a suffix rather than overwriting`() {
        assertEquals("tiktok-2", uniqueId("TikTok", listOf("tiktok")))
        assertEquals("tiktok-3", uniqueId("TikTok", listOf("tiktok", "tiktok-2")))
    }
}
