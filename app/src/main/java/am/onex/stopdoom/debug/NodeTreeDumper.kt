package am.onex.stopdoom.debug

import android.content.Context
import android.util.Log
import am.onex.stopdoom.rules.ScreenSnapshot
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the live view tree to a file so real view ids can be discovered on the
 * device instead of guessed at from here.
 *
 * This is what makes the selectors in default_rules.json fixable without a
 * rebuild: dump the screen, read the ids, paste them into the rule editor. The
 * dumps double as unit-test fixtures, since a [ScreenSnapshot] is exactly what
 * the rule engine consumes.
 */
class NodeTreeDumper(private val context: Context) {

    @Volatile
    private var armedUntil: Long = 0L

    /** Arms the dumper; the next scan within the window writes a file. */
    fun arm(delayMillis: Long = 5_000L, windowMillis: Long = 20_000L) {
        armedUntil = System.currentTimeMillis() + delayMillis + windowMillis
        armedFrom = System.currentTimeMillis() + delayMillis
    }

    @Volatile
    private var armedFrom: Long = 0L

    fun isArmed(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis in armedFrom..armedUntil

    fun disarm() {
        armedUntil = 0L
        armedFrom = 0L
    }

    fun dump(snapshot: ScreenSnapshot): File? = runCatching {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "dump-${snapshot.packageName}-$stamp.json")
        file.writeText(JSON.encodeToString(snapshot))
        disarm()

        Log.i(
            TAG,
            "dumped ${snapshot.nodes.size} nodes from ${snapshot.packageName} " +
                "(truncated=${snapshot.truncated}) -> ${file.absolutePath}",
        )
        // The distinct view ids are the whole point, so put them straight in logcat
        // where they can be read over adb without pulling the file.
        snapshot.nodes.mapNotNull { it.viewId }.distinct().sorted().forEach {
            Log.i(TAG, "  viewId: $it")
        }
        file
    }.onFailure { Log.w(TAG, "dump failed", it) }.getOrNull()

    fun listDumps(): List<File> =
        File(context.filesDir, DIR).listFiles()
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun deleteAll() {
        File(context.filesDir, DIR).listFiles()?.forEach { it.delete() }
    }

    /** Distinct view ids in a saved dump, which is what you paste into a rule. */
    fun viewIdsOf(file: File): List<String> = runCatching {
        JSON.decodeFromString<ScreenSnapshot>(file.readText())
            .nodes.mapNotNull { it.viewId }.distinct().sorted()
    }.getOrDefault(emptyList())

    companion object {
        const val TAG = "DoomGuard/Dump"
        private const val DIR = "dumps"
        private val JSON = Json { prettyPrint = true; ignoreUnknownKeys = true }
    }
}
