package am.onex.stopdoom.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Plain SQLiteOpenHelper rather than Room.
 *
 * Room would drag in KSP and a Kotlin/KSP version pin for four small tables and
 * a handful of queries. This is less machinery for the same result, and it keeps
 * the build free of annotation processing.
 */
class Db(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE rules (
                id      TEXT PRIMARY KEY NOT NULL,
                ordinal INTEGER NOT NULL,
                json    TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE usage (
                rule_id  TEXT NOT NULL,
                day_key  TEXT NOT NULL,
                seconds  INTEGER NOT NULL DEFAULT 0,
                blocks   INTEGER NOT NULL DEFAULT 0,
                opens    INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (rule_id, day_key)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE block_state (
                rule_id       TEXT PRIMARY KEY NOT NULL,
                blocked_until INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE pending_change (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                kind         TEXT NOT NULL,
                target_id    TEXT NOT NULL DEFAULT '',
                payload      TEXT NOT NULL,
                description  TEXT NOT NULL DEFAULT '',
                created_at   INTEGER NOT NULL,
                effective_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE event_log (
                id      INTEGER PRIMARY KEY AUTOINCREMENT,
                at      INTEGER NOT NULL,
                rule_id TEXT NOT NULL DEFAULT '',
                kind    TEXT NOT NULL,
                detail  TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_event_log_at ON event_log(at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Single-device personal app with no migration history worth preserving.
        // If the schema changes, rules are re-seeded from assets and history is dropped.
        db.execSQL("DROP TABLE IF EXISTS rules")
        db.execSQL("DROP TABLE IF EXISTS usage")
        db.execSQL("DROP TABLE IF EXISTS block_state")
        db.execSQL("DROP TABLE IF EXISTS pending_change")
        db.execSQL("DROP TABLE IF EXISTS event_log")
        onCreate(db)
    }

    companion object {
        const val NAME = "doomguard.db"
        const val VERSION = 1
    }
}

internal fun ContentValues.put(pair: Pair<String, Any?>) {
    val (key, value) = pair
    when (value) {
        null -> putNull(key)
        is String -> put(key, value)
        is Int -> put(key, value)
        is Long -> put(key, value)
        is Boolean -> put(key, if (value) 1 else 0)
        else -> put(key, value.toString())
    }
}

internal inline fun <T> Cursor.mapAll(row: (Cursor) -> T): List<T> = use {
    val out = ArrayList<T>(count.coerceAtLeast(0))
    while (moveToNext()) out.add(row(this))
    out
}
