package am.onex.stopdoom.service

import android.os.Handler
import android.os.Looper
import am.onex.stopdoom.rules.BlockAction
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The retry sequence, which is easy to get wrong in a way nothing surfaces.
 *
 * Both failure modes are silent on a phone: stopping while the feed is still up looks
 * like the rule never matched, and pressing Back past the point it worked looks like
 * a random trip to the launcher.
 */
// Only a Looper is needed here; the plain Application avoids booting the real one,
// and the pinned sdk keeps Robolectric off a platform it has no image for.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BlockActuatorTest {

    private class Recorder : GlobalActions {
        val calls = mutableListOf<String>()
        override fun back() { calls.add("back") }
        override fun home() { calls.add("home") }
    }

    private val recorder = Recorder()
    private val handler = Handler(Looper.getMainLooper())
    private val actuator = BlockActuator(recorder, handler)

    /** Drains everything the sequence could possibly have scheduled. */
    private fun runSequence() {
        shadowOf(Looper.getMainLooper()).idleFor(
            java.time.Duration.ofMillis(BlockActuator.BACK_INTERVAL_MS * (BlockActuator.MAX_BACK_ATTEMPTS + 2)),
        )
    }

    @Test
    fun `stops as soon as the target is gone`() {
        var onScreen = true
        actuator.perform(BlockAction.OVERLAY_THEN_BACK, stillMatching = { onScreen })

        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("back"), recorder.calls)

        onScreen = false
        runSequence()
        assertEquals("one Back was enough", listOf("back"), recorder.calls)
    }

    /**
     * The regression that mattered: a Back that worked on the final attempt used to
     * be followed by Home anyway, because the attempt counter was checked first.
     */
    @Test
    fun `does not go home when the last back works`() {
        var backsSeen = 0
        actuator.perform(
            BlockAction.OVERLAY_THEN_BACK,
            stillMatching = { backsSeen < BlockActuator.MAX_BACK_ATTEMPTS },
        )
        repeat(BlockActuator.MAX_BACK_ATTEMPTS + 2) {
            shadowOf(Looper.getMainLooper()).idle()
            backsSeen = recorder.calls.count { call -> call == "back" }
            shadowOf(Looper.getMainLooper()).idleFor(
                java.time.Duration.ofMillis(BlockActuator.BACK_INTERVAL_MS),
            )
        }

        assertEquals(BlockActuator.MAX_BACK_ATTEMPTS, recorder.calls.count { it == "back" })
        assertEquals("home is the give-up path, not the finish line", 0, recorder.calls.count { it == "home" })
    }

    @Test
    fun `falls back to home when back is ignored`() {
        actuator.perform(BlockAction.OVERLAY_THEN_BACK, stillMatching = { true })
        runSequence()

        assertEquals(BlockActuator.MAX_BACK_ATTEMPTS, recorder.calls.count { it == "back" })
        assertEquals(listOf("home"), recorder.calls.takeLast(1))
    }

    @Test
    fun `never presses back for an overlay-only rule`() {
        actuator.perform(BlockAction.OVERLAY_ONLY, stillMatching = { true })
        runSequence()
        assertEquals(emptyList<String>(), recorder.calls)
    }

    @Test
    fun `home action goes straight home`() {
        actuator.perform(BlockAction.HOME, stillMatching = { true })
        runSequence()
        assertEquals(listOf("home"), recorder.calls)
    }

    @Test
    fun `cancel stops a sequence in flight`() {
        actuator.perform(BlockAction.OVERLAY_THEN_BACK, stillMatching = { true })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, recorder.calls.size)

        actuator.cancel()
        runSequence()
        assertEquals("nothing after the cancel", 1, recorder.calls.size)
    }

    @Test
    fun `a second request does not start a parallel sequence`() {
        actuator.perform(BlockAction.OVERLAY_THEN_BACK, stillMatching = { true })
        actuator.perform(BlockAction.OVERLAY_THEN_BACK, stillMatching = { true })
        runSequence()

        assertEquals(BlockActuator.MAX_BACK_ATTEMPTS, recorder.calls.count { it == "back" })
    }

    @Test
    fun `reports completion once the target clears`() {
        var finished = false
        actuator.perform(
            BlockAction.OVERLAY_THEN_BACK,
            stillMatching = { false },
            onFinished = { finished = true },
        )
        runSequence()

        assertEquals(emptyList<String>(), recorder.calls)
        assertEquals(true, finished)
    }
}
