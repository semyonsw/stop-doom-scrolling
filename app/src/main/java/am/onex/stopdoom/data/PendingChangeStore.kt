package am.onex.stopdoom.data

import android.content.ContentValues

/**
 * A change that has been requested but is not allowed to take effect yet.
 *
 * This is the whole point of the cooldown: the decision to weaken a limit is made
 * by the you of right now, but only applied by the you of two hours from now.
 */
data class PendingChange(
    val id: Long,
    val kind: Kind,
    val targetId: String,
    val payload: String,
    val description: String,
    val createdAt: Long,
    val effectiveAt: Long,
) {
    enum class Kind {
        /** [payload] is a serialised BlockRule to upsert. */
        RULE_UPSERT,

        /** [targetId] is the rule id to delete. */
        RULE_DELETE,

        /** [payload] is the number of minutes of maintenance mode to grant. */
        MAINTENANCE,

        /** [payload] is the new global cooldown in minutes. */
        COOLDOWN_MINUTES,

        /** [payload] is "true"/"false" for a named toggle in [targetId]. */
        TOGGLE,
        ;

        companion object {
            fun parse(raw: String): Kind? = entries.firstOrNull { it.name == raw }
        }
    }
}

class PendingChangeStore(private val db: Db) {

    fun enqueue(
        kind: PendingChange.Kind,
        payload: String,
        description: String,
        createdAt: Long,
        effectiveAt: Long,
        targetId: String = "",
    ): Long = db.writableDatabase.insert(
        "pending_change",
        null,
        ContentValues().apply {
            put("kind" to kind.name)
            put("target_id" to targetId)
            put("payload" to payload)
            put("description" to description)
            put("created_at" to createdAt)
            put("effective_at" to effectiveAt)
        },
    )

    fun all(): List<PendingChange> = query(null, null)

    fun due(nowMillis: Long): List<PendingChange> =
        query("effective_at <= ?", arrayOf(nowMillis.toString()))

    fun nextDueAt(): Long? =
        db.readableDatabase.rawQuery(
            "SELECT MIN(effective_at) FROM pending_change",
            null,
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    fun remove(id: Long) {
        db.writableDatabase.delete("pending_change", "id = ?", arrayOf(id.toString()))
    }

    /**
     * Brings the whole queue forward, for when the cooldown is switched off.
     *
     * Without this, turning the cooldown off would only affect changes made after
     * the switch, and anything already waiting would sit there for its original
     * two hours - which is exactly the case you were trying to get out of.
     */
    fun makeAllDue(nowMillis: Long): Int = db.writableDatabase.update(
        "pending_change",
        ContentValues().apply { put("effective_at" to nowMillis) },
        null,
        null,
    )

    /**
     * Cancelling a pending change is always allowed: it moves you back toward the
     * stricter state, and tightening never waits.
     */
    fun cancelAllFor(targetId: String) {
        db.writableDatabase.delete("pending_change", "target_id = ?", arrayOf(targetId))
    }

    private fun query(where: String?, args: Array<String>?): List<PendingChange> =
        db.readableDatabase.query(
            "pending_change",
            arrayOf("id", "kind", "target_id", "payload", "description", "created_at", "effective_at"),
            where,
            args,
            null,
            null,
            "effective_at ASC",
        ).mapAll { c ->
            PendingChange(
                id = c.getLong(0),
                kind = PendingChange.Kind.parse(c.getString(1)) ?: PendingChange.Kind.TOGGLE,
                targetId = c.getString(2),
                payload = c.getString(3),
                description = c.getString(4),
                createdAt = c.getLong(5),
                effectiveAt = c.getLong(6),
            )
        }
}
