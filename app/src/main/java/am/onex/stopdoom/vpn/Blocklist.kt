package am.onex.stopdoom.vpn

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Domain and keyword matching, shared by the DNS filter and the browser URL check.
 *
 * Held as a single immutable snapshot swapped atomically, so the packet loop never
 * takes a lock and a reload mid-stream cannot produce a half-updated list.
 */
class Blocklist(private val context: Context) {

    class Snapshot(
        val domains: Set<String>,
        val allowed: Set<String>,
        val keywords: List<String>,
    ) {
        /** Matches the host and every parent, so one entry covers all subdomains. */
        fun blocksHost(host: String): Boolean {
            val clean = host.trim().trimEnd('.').lowercase()
            if (clean.isEmpty()) return false
            var candidate = clean
            while (true) {
                if (candidate in allowed) return false
                if (candidate in domains) return true
                val dot = candidate.indexOf('.')
                if (dot < 0) return false
                candidate = candidate.substring(dot + 1)
            }
        }

        fun matchesKeyword(text: String): String? {
            if (keywords.isEmpty()) return null
            val haystack = text.lowercase()
            return keywords.firstOrNull { haystack.contains(it) }
        }

        val size: Int get() = domains.size
    }

    private val snapshot = AtomicReference(Snapshot(emptySet(), emptySet(), emptyList()))

    fun current(): Snapshot = snapshot.get()

    fun load() {
        val domains = HashSet<String>(4096)
        readAsset(ASSET_DOMAINS) { domains.add(it) }
        userFile(FILE_EXTRA_DOMAINS).takeIf { it.exists() }
            ?.let { file -> readLines(file.readLines()) { domains.add(it) } }

        // Known DNS-over-HTTPS endpoints. Blocking the resolver's own hostname is
        // what stops a browser from quietly routing around the filter; it is not
        // airtight, since a client can hardcode an IP, but it closes the easy path.
        readAsset(ASSET_DOH) { domains.add(it) }

        val allowed = HashSet<String>()
        userFile(FILE_ALLOWLIST).takeIf { it.exists() }
            ?.let { file -> readLines(file.readLines()) { allowed.add(it) } }

        val keywords = mutableListOf<String>()
        readAsset(ASSET_KEYWORDS) { keywords.add(it) }
        userFile(FILE_EXTRA_KEYWORDS).takeIf { it.exists() }
            ?.let { file -> readLines(file.readLines()) { keywords.add(it) } }

        snapshot.set(Snapshot(domains, allowed, keywords))
    }

    /** Appends imported hosts-format text to the user list, then reloads. */
    fun importHostsText(text: String) {
        val file = userFile(FILE_EXTRA_DOMAINS)
        val existing = if (file.exists()) file.readLines().toMutableSet() else mutableSetOf()
        readLines(text.lines()) { existing.add(it) }
        file.parentFile?.mkdirs()
        file.writeText(existing.sorted().joinToString("\n"))
        load()
    }

    fun addAllowed(domain: String) = appendTo(FILE_ALLOWLIST, domain)

    fun addBlocked(domain: String) = appendTo(FILE_EXTRA_DOMAINS, domain)

    fun addKeyword(keyword: String) = appendTo(FILE_EXTRA_KEYWORDS, keyword)

    private fun appendTo(name: String, value: String) {
        val cleaned = value.trim().lowercase()
        if (cleaned.isEmpty()) return
        val file = userFile(name)
        file.parentFile?.mkdirs()
        val lines = if (file.exists()) file.readLines().toMutableSet() else mutableSetOf()
        lines.add(cleaned)
        file.writeText(lines.sorted().joinToString("\n"))
        load()
    }

    fun userListText(name: String): String =
        userFile(name).takeIf { it.exists() }?.readText().orEmpty()

    private fun userFile(name: String) = File(File(context.filesDir, "lists"), name)

    private inline fun readAsset(name: String, add: (String) -> Unit) {
        runCatching {
            context.assets.open(name).bufferedReader().use { reader ->
                readLines(reader.readLines(), add)
            }
        }
    }

    /** Accepts both plain domain lists and `0.0.0.0 domain` hosts files. */
    private inline fun readLines(lines: List<String>, add: (String) -> Unit) {
        for (raw in lines) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val token = line.split(Regex("\\s+")).let { parts ->
                if (parts.size >= 2 && parts[0] in HOSTS_PREFIXES) parts[1] else parts[0]
            }
            val host = token.trimEnd('.').lowercase()
            if (host.isNotEmpty() && host != "localhost") add(host)
        }
    }

    companion object {
        const val ASSET_DOMAINS = "blocklist_domains.txt"
        const val ASSET_DOH = "blocklist_doh.txt"
        const val ASSET_KEYWORDS = "blocklist_keywords.txt"

        const val FILE_EXTRA_DOMAINS = "extra_domains.txt"
        const val FILE_EXTRA_KEYWORDS = "extra_keywords.txt"
        const val FILE_ALLOWLIST = "allowlist.txt"

        private val HOSTS_PREFIXES = setOf("0.0.0.0", "127.0.0.1", "::1", "::")
    }
}
