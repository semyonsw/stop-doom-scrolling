package am.onex.stopdoom.data

import android.content.ContentValues
import android.content.Context
import am.onex.stopdoom.rules.BlockRule
import am.onex.stopdoom.rules.RuleJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Rules live in SQLite as whole JSON blobs keyed by id.
 *
 * Storing the serialised rule rather than exploding it into columns is deliberate:
 * the editor is a JSON text field, so a round-trip through columns would only add
 * a lossy translation step and force a schema change every time a match option is added.
 */
class RuleStore(private val context: Context, private val db: Db) {

    private val _rules = MutableStateFlow<List<BlockRule>>(emptyList())
    val rules: StateFlow<List<BlockRule>> = _rules

    /** Seeds from assets on first run, then loads whatever is in the database. */
    fun load(): List<BlockRule> {
        if (count() == 0) {
            seedFromAssets()
        }
        val loaded = readAll()
        _rules.value = loaded
        return loaded
    }

    fun readAll(): List<BlockRule> =
        db.readableDatabase
            .query("rules", arrayOf("json"), null, null, null, null, "ordinal ASC")
            .mapAll { it.getString(0) }
            .mapNotNull { json -> RuleJson.decodeOne(json).getOrNull() }

    fun replaceAll(rules: List<BlockRule>) {
        db.writableDatabase.run {
            beginTransaction()
            try {
                delete("rules", null, null)
                rules.forEachIndexed { index, rule ->
                    insert("rules", null, rule.toValues(index))
                }
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
        _rules.value = rules
    }

    fun upsert(rule: BlockRule) {
        val existing = readAll()
        val index = existing.indexOfFirst { it.id == rule.id }
        val updated = if (index >= 0) {
            existing.toMutableList().also { it[index] = rule }
        } else {
            existing + rule
        }
        replaceAll(updated)
    }

    fun delete(ruleId: String) {
        replaceAll(readAll().filterNot { it.id == ruleId })
    }

    fun resetToDefaults() {
        db.writableDatabase.delete("rules", null, null)
        seedFromAssets()
        _rules.value = readAll()
    }

    private fun count(): Int =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM rules", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    private fun seedFromAssets() {
        val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        val seeded = RuleJson.decode(text).getOrElse {
            // A broken bundled asset is a build error, not a runtime condition worth
            // recovering from quietly - but crashing the accessibility service would
            // be worse, so start empty and let the UI show zero rules.
            emptyList()
        }
        db.writableDatabase.run {
            beginTransaction()
            try {
                seeded.forEachIndexed { index, rule -> insert("rules", null, rule.toValues(index)) }
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
    }

    private fun BlockRule.toValues(ordinal: Int) = ContentValues().apply {
        put("id" to id)
        put("ordinal" to ordinal)
        put("json" to RuleJson.encodeOne(this@toValues))
    }

    companion object {
        const val ASSET_NAME = "default_rules.json"
    }
}
