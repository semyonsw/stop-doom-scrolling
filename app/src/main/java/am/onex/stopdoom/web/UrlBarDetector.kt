package am.onex.stopdoom.web

import am.onex.stopdoom.rules.ScreenSnapshot
import am.onex.stopdoom.vpn.Blocklist

/**
 * Reads the browser address bar out of a screen snapshot and decides whether the
 * page should be blocked.
 *
 * This exists because DNS filtering has two blind spots that matter in practice:
 * DNS-over-HTTPS, where the browser resolves names outside the tunnel entirely,
 * and search queries, which are just HTTPS traffic to a search engine and never
 * produce a lookup for anything blockable.
 */
object UrlBarDetector {

    /** Address-bar view ids per browser, most common first. */
    private val URL_BAR_IDS: Map<String, List<String>> = mapOf(
        "com.android.chrome" to listOf(
            "com.android.chrome:id/url_bar",
            "com.android.chrome:id/search_box_text",
        ),
        "com.chrome.beta" to listOf("com.chrome.beta:id/url_bar"),
        "com.chrome.dev" to listOf("com.chrome.dev:id/url_bar"),
        "com.sec.android.app.sbrowser" to listOf(
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.sec.android.app.sbrowser:id/sbrowser_url_bar",
        ),
        "org.mozilla.firefox" to listOf(
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "org.mozilla.firefox:id/mozac_browser_toolbar_edit_url_view",
        ),
        "com.microsoft.emmx" to listOf("com.microsoft.emmx:id/url_bar"),
        "com.brave.browser" to listOf("com.brave.browser:id/url_bar"),
        "com.opera.browser" to listOf("com.opera.browser:id/url_field"),
        "com.duckduckgo.mobile.android" to listOf(
            "com.duckduckgo.mobile.android:id/omnibarTextInput",
        ),
    )

    val supportedPackages: Set<String> get() = URL_BAR_IDS.keys

    fun isBrowser(packageName: String): Boolean = packageName in URL_BAR_IDS

    /** The raw address-bar text, or null if this screen has no recognisable one. */
    fun extractBarText(snapshot: ScreenSnapshot): String? {
        val ids = URL_BAR_IDS[snapshot.packageName] ?: return null
        for (id in ids) {
            val node = snapshot.nodes.firstOrNull { it.viewId == id } ?: continue
            val value = node.text?.takeIf { it.isNotBlank() }
                ?: node.desc?.takeIf { it.isNotBlank() }
            if (value != null) return value.trim()
        }
        return null
    }

    sealed interface Verdict {
        data object Allowed : Verdict
        data class BlockedDomain(val host: String) : Verdict
        data class BlockedKeyword(val keyword: String) : Verdict
    }

    fun evaluate(
        snapshot: ScreenSnapshot,
        list: Blocklist.Snapshot,
        keywordsEnabled: Boolean,
    ): Verdict {
        val barText = extractBarText(snapshot) ?: return Verdict.Allowed
        return evaluateText(barText, list, keywordsEnabled)
    }

    fun evaluateText(
        barText: String,
        list: Blocklist.Snapshot,
        keywordsEnabled: Boolean,
    ): Verdict {
        val host = hostOf(barText)
        if (host != null && list.blocksHost(host)) return Verdict.BlockedDomain(host)

        if (keywordsEnabled) {
            // Match against the whole bar so a typed search is caught before it is
            // even submitted, not only once it becomes a results URL.
            list.matchesKeyword(barText)?.let { return Verdict.BlockedKeyword(it) }
        }
        return Verdict.Allowed
    }

    /**
     * Best-effort host extraction from whatever the omnibox shows, which may be a
     * full URL, a bare host, a host with the scheme hidden, or a search phrase.
     */
    fun hostOf(barText: String): String? {
        var value = barText.trim().lowercase()
        if (value.isEmpty()) return null

        value = value.substringBefore(' ')
        value = value.removePrefix("https://").removePrefix("http://")
        value = value.substringBefore('/').substringBefore('?').substringBefore('#')
        value = value.substringAfterLast('@')
        value = value.substringBefore(':')
        value = value.trimEnd('.')

        // A search phrase has no dot; treating it as a host would be meaningless.
        if (!value.contains('.')) return null
        if (value.any { it.isWhitespace() }) return null
        return value.takeIf { it.isNotEmpty() }
    }
}
