package am.onex.stopdoom.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Covers the queue against real SQLite, in particular [PendingChangeStore.makeAllDue]:
 * switching the cooldown off has to release what is already waiting, or the one thing
 * you turned it off to avoid - a two hour wait - still happens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PendingChangeStoreTest {

    private lateinit var store: PendingChangeStore
    private val now = 1_700_000_000_000L
    private val twoHours = 2 * 60 * 60 * 1000L

    @Before
    fun setUp() {
        store = PendingChangeStore(Db(RuntimeEnvironment.getApplication()))
    }

    private fun enqueue(targetId: String, effectiveAt: Long) = store.enqueue(
        kind = PendingChange.Kind.TOGGLE,
        payload = "false",
        description = "Turn off $targetId",
        createdAt = now,
        effectiveAt = effectiveAt,
        targetId = targetId,
    )

    @Test
    fun `a queued change is not due before its time`() {
        enqueue("web_blocking", now + twoHours)
        assertTrue(store.due(now).isEmpty())
        assertEquals(1, store.due(now + twoHours).size)
    }

    @Test
    fun `makeAllDue releases everything that was waiting`() {
        enqueue("web_blocking", now + twoHours)
        enqueue("keyword_blocking", now + twoHours * 2)

        assertEquals(2, store.makeAllDue(now))
        assertEquals(2, store.due(now).size)
    }

    @Test
    fun `makeAllDue on an empty queue changes nothing`() {
        assertEquals(0, store.makeAllDue(now))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `cancelling by target removes only that target`() {
        enqueue("web_blocking", now + twoHours)
        enqueue("keyword_blocking", now + twoHours)

        store.cancelAllFor("web_blocking")

        assertEquals(listOf("keyword_blocking"), store.all().map { it.targetId })
    }
}
