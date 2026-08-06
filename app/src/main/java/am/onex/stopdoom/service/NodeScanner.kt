package am.onex.stopdoom.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import am.onex.stopdoom.rules.ScreenSnapshot
import am.onex.stopdoom.rules.SnapNode

/**
 * Turns a live accessibility tree into a [ScreenSnapshot].
 *
 * Bounded on purpose. This runs on every content-change event in a scrolling
 * video feed, so an unbounded recursion would walk thousands of nodes many times
 * a second - visible jank and a real battery cost. The caps below keep a scan in
 * the sub-millisecond range at the cost of missing nodes buried very deep, which
 * no rule needs.
 */
class NodeScanner(
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
    private val maxNodes: Int = DEFAULT_MAX_NODES,
) {

    fun snapshot(
        root: AccessibilityNodeInfo?,
        packageName: String,
        screenWidth: Int,
        screenHeight: Int,
    ): ScreenSnapshot {
        if (root == null) {
            return ScreenSnapshot(packageName, screenWidth, screenHeight)
        }
        val nodes = ArrayList<SnapNode>(64)
        val truncated = walk(root, 0, nodes)
        return ScreenSnapshot(
            packageName = packageName,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            nodes = nodes,
            truncated = truncated,
        )
    }

    /** Returns true if a cap was hit and the result is partial. */
    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
        out: MutableList<SnapNode>,
    ): Boolean {
        if (out.size >= maxNodes) return true
        out.add(node.toSnapNode(depth))
        if (depth >= maxDepth) return true

        var truncated = false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (walk(child, depth + 1, out)) truncated = true
            if (out.size >= maxNodes) return true
        }
        return truncated
    }

    /**
     * Exact-id lookup, which the framework answers natively without a walk.
     * Much cheaper than traversing, so rules that name a fully-qualified id get
     * checked this way first.
     */
    fun matchesAnyViewId(
        root: AccessibilityNodeInfo?,
        fullyQualifiedIds: List<String>,
        minAreaFraction: Float,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        if (root == null || fullyQualifiedIds.isEmpty()) return false
        val screenArea = screenWidth.toLong() * screenHeight.toLong()
        for (id in fullyQualifiedIds) {
            val found = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull()
            if (found.isNullOrEmpty()) continue
            if (minAreaFraction <= 0f || screenArea <= 0L) return true
            val bounds = Rect()
            for (node in found) {
                node.getBoundsInScreen(bounds)
                val area = bounds.width().toLong() * bounds.height().toLong()
                if (area.toFloat() / screenArea.toFloat() >= minAreaFraction) return true
            }
        }
        return false
    }

    private fun AccessibilityNodeInfo.toSnapNode(depth: Int): SnapNode {
        val bounds = Rect()
        getBoundsInScreen(bounds)
        return SnapNode(
            viewId = viewIdResourceName,
            className = className?.toString(),
            text = text?.toString(),
            desc = contentDescription?.toString(),
            depth = depth,
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
        )
    }

    companion object {
        const val DEFAULT_MAX_DEPTH = 12
        const val DEFAULT_MAX_NODES = 400

        /** The dumper wants the whole tree, so it uses far looser caps. */
        fun forDumping() = NodeScanner(maxDepth = 40, maxNodes = 4000)
    }
}
