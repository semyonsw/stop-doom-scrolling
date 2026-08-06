package am.onex.stopdoom.rules

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * Rules are edited as raw JSON in the app, so parsing has to be forgiving about
 * unknown keys (forward compatibility) and strict about reporting errors back to
 * the editor instead of throwing into a service thread.
 */
object RuleJson {

    @OptIn(ExperimentalSerializationApi::class)
    val format: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        isLenient = true
    }

    fun encode(rules: List<BlockRule>): String = format.encodeToString(rules)

    fun encodeOne(rule: BlockRule): String = format.encodeToString(rule)

    fun decodeOne(text: String): Result<BlockRule> = runCatching {
        format.decodeFromString<BlockRule>(text)
    }

    /** [Result.failure] carries the parser message verbatim for display in the editor. */
    fun decode(text: String): Result<List<BlockRule>> = runCatching {
        val parsed = format.decodeFromString<List<BlockRule>>(text)
        val duplicates = parsed.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate rule ids: ${duplicates.joinToString()}" }
        require(parsed.none { it.id.isBlank() }) { "Every rule needs a non-blank id" }
        parsed
    }
}
