package am.onex.stopdoom.data

import android.content.ContentValues
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DayUsage(
    val ruleId: String,
    val dayKey: String,
    val seconds: Int,
    val blocks: Int,
    val opens: Int,
)

data class LoggedEvent(
    val at: Long,
    val ruleId: String,
    val kind: String,
    val detail: String,
)

/**
 * Per-rule time accounting, keyed by local calendar date.
 *
 * The day key is derived from the wall clock rather than reset by an alarm, so
 * there is no midnight job to miss and no drift if the phone is asleep at 00:00.
 */
class UsageStore(private val db: Db) {

    fun dayKey(atMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        LocalDate.ofInstant(Instant.ofEpochMilli(atMillis), zone).toString()

    fun secondsToday(ruleId: String, atMillis: Long): Int = seconds(ruleId, dayKey(atMillis))

    fun seconds(ruleId: String, dayKey: String): Int =
        db.readableDatabase.rawQuery(
            "SELECT seconds FROM usage WHERE rule_id = ? AND day_key = ?",
            arrayOf(ruleId, dayKey),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun addSeconds(ruleId: String, atMillis: Long, delta: Int) {
        if (delta <= 0) return
        bump(ruleId, dayKey(atMillis), secondsDelta = delta)
    }

    fun recordOpen(ruleId: String, atMillis: Long) =
        bump(ruleId, dayKey(atMillis), opensDelta = 1)

    fun recordBlock(ruleId: String, atMillis: Long) =
        bump(ruleId, dayKey(atMillis), blocksDelta = 1)

    private fun bump(
        ruleId: String,
        dayKey: String,
        secondsDelta: Int = 0,
        blocksDelta: Int = 0,
        opensDelta: Int = 0,
    ) {
        db.writableDatabase.run {
            // INSERT OR IGNORE then UPDATE is the portable upsert; ON CONFLICT DO UPDATE
            // needs SQLite 3.24+, which is present on API 34 but this is clearer.
            insertWithOnConflict(
                "usage",
                null,
                ContentValues().apply {
                    put("rule_id" to ruleId)
                    put("day_key" to dayKey)
                    put("seconds" to 0)
                    put("blocks" to 0)
                    put("opens" to 0)
                },
                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
            )
            execSQL(
                """
                UPDATE usage SET seconds = seconds + ?, blocks = blocks + ?, opens = opens + ?
                WHERE rule_id = ? AND day_key = ?
                """.trimIndent(),
                arrayOf<Any>(secondsDelta, blocksDelta, opensDelta, ruleId, dayKey),
            )
        }
    }

    fun usageForDay(dayKey: String): List<DayUsage> =
        db.readableDatabase.query(
            "usage",
            arrayOf("rule_id", "day_key", "seconds", "blocks", "opens"),
            "day_key = ?",
            arrayOf(dayKey),
            null,
            null,
            "seconds DESC",
        ).mapAll {
            DayUsage(it.getString(0), it.getString(1), it.getInt(2), it.getInt(3), it.getInt(4))
        }

    fun recentDays(limit: Int = 14): List<String> =
        db.readableDatabase.rawQuery(
            "SELECT DISTINCT day_key FROM usage ORDER BY day_key DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).mapAll { it.getString(0) }

    // --- re-block cooldown -------------------------------------------------

    fun blockedUntil(ruleId: String): Long =
        db.readableDatabase.rawQuery(
            "SELECT blocked_until FROM block_state WHERE rule_id = ?",
            arrayOf(ruleId),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    fun setBlockedUntil(ruleId: String, untilMillis: Long) {
        db.writableDatabase.insertWithOnConflict(
            "block_state",
            null,
            ContentValues().apply {
                put("rule_id" to ruleId)
                put("blocked_until" to untilMillis)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    // --- event log --------------------------------------------------------

    fun log(atMillis: Long, kind: String, ruleId: String = "", detail: String = "") {
        db.writableDatabase.insert(
            "event_log",
            null,
            ContentValues().apply {
                put("at" to atMillis)
                put("rule_id" to ruleId)
                put("kind" to kind)
                put("detail" to detail)
            },
        )
    }

    fun recentEvents(limit: Int = 100): List<LoggedEvent> =
        db.readableDatabase.query(
            "event_log",
            arrayOf("at", "rule_id", "kind", "detail"),
            null,
            null,
            null,
            null,
            "at DESC",
            limit.toString(),
        ).mapAll {
            LoggedEvent(it.getLong(0), it.getString(1), it.getString(2), it.getString(3))
        }

    fun trimEventLog(keep: Int = 2000) {
        db.writableDatabase.execSQL(
            "DELETE FROM event_log WHERE id NOT IN (SELECT id FROM event_log ORDER BY at DESC LIMIT ?)",
            arrayOf(keep),
        )
    }
}
