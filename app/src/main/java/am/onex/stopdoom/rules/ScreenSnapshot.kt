package am.onex.stopdoom.rules

import kotlinx.serialization.Serializable

/**
 * A flattened, Android-free picture of what is currently on screen.
 *
 * Everything downstream of this - rule matching, URL extraction, budget
 * decisions - works on this type rather than on [android.view.accessibility.AccessibilityNodeInfo].
 * That is what makes detection testable off-device: a snapshot dumped from the
 * phone by the debug tool is the exact same shape as a test fixture.
 */
@Serializable
data class ScreenSnapshot(
    val packageName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val nodes: List<SnapNode> = emptyList(),
    /** True when the traversal hit its depth or node cap, so [nodes] is partial. */
    val truncated: Boolean = false,
)

@Serializable
data class SnapNode(
    val viewId: String? = null,
    val className: String? = null,
    val text: String? = null,
    val desc: String? = null,
    val depth: Int = 0,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

/**
 * Fraction of the screen this node covers, 0f..1f.
 *
 * This is the discriminator that keeps "Shorts" from matching the Shorts *tab
 * button* on the YouTube home feed: the tab is a small node, the Shorts player
 * fills the screen. Without it, a content-description rule blocks the home
 * screen the moment you open YouTube.
 */
fun SnapNode.areaFractionOf(snapshot: ScreenSnapshot): Float {
    val screenArea = snapshot.screenWidth.toLong() * snapshot.screenHeight.toLong()
    if (screenArea <= 0L) return 0f
    return (width.toLong() * height.toLong()).toFloat() / screenArea.toFloat()
}
