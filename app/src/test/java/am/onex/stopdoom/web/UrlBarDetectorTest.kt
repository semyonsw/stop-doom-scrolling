package am.onex.stopdoom.web

import am.onex.stopdoom.rules.ScreenSnapshot
import am.onex.stopdoom.rules.SnapNode
import am.onex.stopdoom.vpn.Blocklist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlBarDetectorTest {

    private val list = Blocklist.Snapshot(
        domains = setOf("pornhub.com", "example-blocked.net"),
        allowed = setOf("safe.example-blocked.net"),
        keywords = listOf("porn", "xxx"),
    )

    private fun chrome(barText: String) = ScreenSnapshot(
        packageName = "com.android.chrome",
        screenWidth = 1080,
        screenHeight = 2340,
        nodes = listOf(SnapNode(viewId = "com.android.chrome:id/url_bar", text = barText)),
    )

    @Test
    fun `blocks a listed domain`() {
        val verdict = UrlBarDetector.evaluate(chrome("https://www.pornhub.com/video"), list, true)
        assertTrue(verdict is UrlBarDetector.Verdict.BlockedDomain)
    }

    @Test
    fun `allows an ordinary site`() {
        assertEquals(
            UrlBarDetector.Verdict.Allowed,
            UrlBarDetector.evaluate(chrome("https://kotlinlang.org/docs"), list, true),
        )
    }

    @Test
    fun `subdomains inherit the block`() {
        assertTrue(list.blocksHost("cdn.media.pornhub.com"))
    }

    @Test
    fun `allowlist beats the blocklist for that exact host`() {
        assertTrue(list.blocksHost("other.example-blocked.net"))
        assertTrue(!list.blocksHost("safe.example-blocked.net"))
    }

    @Test
    fun `catches a typed search that never produces a lookup`() {
        val verdict = UrlBarDetector.evaluate(chrome("free porn videos"), list, true)
        assertTrue(verdict is UrlBarDetector.Verdict.BlockedKeyword)
    }

    @Test
    fun `keyword matching can be switched off`() {
        assertEquals(
            UrlBarDetector.Verdict.Allowed,
            UrlBarDetector.evaluate(chrome("free porn videos"), list, false),
        )
    }

    @Test
    fun `unknown package yields no bar text`() {
        val snap = ScreenSnapshot(
            "com.google.android.youtube", 1080, 2340,
            listOf(SnapNode(viewId = "com.android.chrome:id/url_bar", text = "pornhub.com")),
        )
        assertNull(UrlBarDetector.extractBarText(snap))
        assertEquals(UrlBarDetector.Verdict.Allowed, UrlBarDetector.evaluate(snap, list, true))
    }

    @Test
    fun `host extraction strips scheme path query and port`() {
        assertEquals("example.com", UrlBarDetector.hostOf("https://example.com/a/b?c=1#d"))
        assertEquals("example.com", UrlBarDetector.hostOf("example.com:8443"))
        assertEquals("example.com", UrlBarDetector.hostOf("http://user@example.com/"))
        assertEquals("example.com", UrlBarDetector.hostOf("  EXAMPLE.com.  "))
    }

    @Test
    fun `a search phrase is not treated as a host`() {
        assertNull(UrlBarDetector.hostOf("how to stop scrolling"))
        assertNull(UrlBarDetector.hostOf("kotlin"))
        assertNull(UrlBarDetector.hostOf(""))
    }

    @Test
    fun `samsung internet bar is recognised`() {
        val snap = ScreenSnapshot(
            "com.sec.android.app.sbrowser", 1080, 2340,
            listOf(
                SnapNode(
                    viewId = "com.sec.android.app.sbrowser:id/location_bar_edit_text",
                    text = "pornhub.com",
                ),
            ),
        )
        assertTrue(UrlBarDetector.evaluate(snap, list, true) is UrlBarDetector.Verdict.BlockedDomain)
    }

    @Test
    fun `empty blocklist blocks nothing`() {
        val empty = Blocklist.Snapshot(emptySet(), emptySet(), emptyList())
        assertEquals(
            UrlBarDetector.Verdict.Allowed,
            UrlBarDetector.evaluate(chrome("https://pornhub.com"), empty, true),
        )
    }
}
