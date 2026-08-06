package am.onex.stopdoom.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuleJsonTest {

    @Test
    fun `round trips a rule`() {
        val rule = BlockRule(
            id = "x",
            label = "X",
            packages = listOf("com.x"),
            match = MatchSpec(anyViewIdContains = listOf("a"), minAreaFraction = 0.4f),
        )
        val decoded = RuleJson.decodeOne(RuleJson.encodeOne(rule)).getOrThrow()
        assertEquals(rule, decoded)
    }

    @Test
    fun `unknown keys are tolerated so old rules survive a schema change`() {
        val json = """{"id":"x","label":"X","somethingNew":true}"""
        assertTrue(RuleJson.decodeOne(json).isSuccess)
    }

    @Test
    fun `duplicate ids are rejected with a readable message`() {
        val json = """[{"id":"a","label":"A"},{"id":"a","label":"B"}]"""
        val result = RuleJson.decode(json)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Duplicate"))
    }

    @Test
    fun `blank ids are rejected`() {
        assertTrue(RuleJson.decode("""[{"id":"","label":"A"}]""").isFailure)
    }

    @Test
    fun `malformed json fails instead of throwing`() {
        val result = RuleJson.decode("{ not json")
        assertTrue(result.isFailure)
    }

    /**
     * The bundled asset ships as the app's starting configuration, so a typo in it
     * would leave a fresh install with no rules at all and no obvious cause.
     */
    @Test
    fun `bundled default rules asset parses`() {
        val asset = File("src/main/assets/default_rules.json")
        assertTrue("default_rules.json not found at ${asset.absolutePath}", asset.exists())

        val rules = RuleJson.decode(asset.readText()).getOrThrow()
        assertTrue(rules.isNotEmpty())
        assertTrue(rules.all { it.id.isNotBlank() })
        assertTrue(rules.all { it.wholeApp || !it.match.isEmpty || it.packages.isNotEmpty() })

        // Every enabled section rule needs something to match on, or it silently
        // never fires and looks like a detection bug.
        rules.filter { it.enabled && !it.wholeApp }.forEach {
            assertTrue("rule ${it.id} has no matchers", !it.match.isEmpty)
        }
    }
}
