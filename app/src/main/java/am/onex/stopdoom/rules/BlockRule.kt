package am.onex.stopdoom.rules

import kotlinx.serialization.Serializable

@Serializable
enum class BlockAction {
    /** Press Back until the matched node is gone. Leaves the rest of the app usable. */
    BACK_UNTIL_GONE,

    /** Go straight to the launcher. Blunt; use for whole-app rules. */
    HOME,

    /** Show the block screen and leave navigation alone. */
    OVERLAY_ONLY,

    /** Show the block screen, then back out from underneath it. The default. */
    OVERLAY_THEN_BACK,
}

/**
 * How to recognise a target screen.
 *
 * Groups are OR'd together by default: a node matching any of [anyViewIdContains],
 * [anyContentDescription] or [anyText] is enough. Set [requireAllGroups] to AND
 * the non-empty groups instead, which is how you tighten a rule that false-positives.
 *
 * All string comparisons are case-insensitive substring tests. An entry that
 * contains ":id/" is additionally treated as a fully-qualified view id and gets
 * a fast native lookup before any tree walk happens.
 */
@Serializable
data class MatchSpec(
    val anyViewIdContains: List<String> = emptyList(),
    val anyContentDescription: List<String> = emptyList(),
    val anyText: List<String> = emptyList(),
    val requireAllGroups: Boolean = false,
    /** How many distinct nodes must match before the rule fires. */
    val minMatchingNodes: Int = 1,
    /** A matching node must cover at least this fraction of the screen. See [areaFractionOf]. */
    val minAreaFraction: Float = 0f,
) {
    val isEmpty: Boolean
        get() = anyViewIdContains.isEmpty() && anyContentDescription.isEmpty() && anyText.isEmpty()

    /** Entries usable with `findAccessibilityNodeInfosByViewId`, which needs an exact id. */
    val fullyQualifiedViewIds: List<String>
        get() = anyViewIdContains.filter { it.contains(":id/") }
}

@Serializable
data class BlockRule(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    /** Packages this rule applies to. Empty means every package - only useful for web rules. */
    val packages: List<String> = emptyList(),
    val match: MatchSpec = MatchSpec(),
    /** Match on package alone, ignoring [match]. For "block TikTok entirely". */
    val wholeApp: Boolean = false,
    val action: BlockAction = BlockAction.OVERLAY_THEN_BACK,
    /** Seconds allowed per continuous visit. 0 blocks on sight. */
    val sessionBudgetSeconds: Int = 300,
    /** Seconds allowed per calendar day. 0 blocks on sight. */
    val dailyBudgetSeconds: Int = 900,
    /** Seconds the block screen stays un-dismissable. */
    val frictionSeconds: Int = 10,
    /** After a block, re-entering within this window blocks immediately. */
    val reblockCooldownSeconds: Int = 30,
    /** Free-text; survives round-trips so you can leave yourself notes in the JSON. */
    val note: String = "",
) {
    val blocksOnSight: Boolean get() = sessionBudgetSeconds <= 0 || dailyBudgetSeconds <= 0
}
