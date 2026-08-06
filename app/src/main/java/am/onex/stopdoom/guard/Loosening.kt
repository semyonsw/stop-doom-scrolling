package am.onex.stopdoom.guard

import am.onex.stopdoom.rules.BlockAction
import am.onex.stopdoom.rules.BlockRule

/**
 * Does [updated] make [current] easier to get around?
 *
 * Pure and deliberately conservative: anything that is not clearly a tightening
 * counts as a loosening and goes through the cooldown. Getting this wrong in the
 * permissive direction is the one bug that quietly defeats the entire app, so the
 * comparison errs toward waiting.
 */
fun isLoosening(current: BlockRule, updated: BlockRule): Boolean {
    if (current.enabled && !updated.enabled) return true
    if (updated.sessionBudgetSeconds > current.sessionBudgetSeconds) return true
    if (updated.dailyBudgetSeconds > current.dailyBudgetSeconds) return true
    if (updated.frictionSeconds < current.frictionSeconds) return true
    if (updated.reblockCooldownSeconds < current.reblockCooldownSeconds) return true
    if (current.wholeApp && !updated.wholeApp) return true
    if (actionStrength(updated.action) < actionStrength(current.action)) return true

    // Narrowing what the rule watches is a loosening: dropping a package or a
    // matcher means screens that used to be caught no longer are.
    if (!updated.packages.containsAll(current.packages)) return true
    if (!updated.match.anyViewIdContains.containsAll(current.match.anyViewIdContains)) return true
    if (!updated.match.anyContentDescription.containsAll(current.match.anyContentDescription)) return true
    if (!updated.match.anyText.containsAll(current.match.anyText)) return true

    // Both of these make the matcher fire less often.
    if (updated.match.minMatchingNodes > current.match.minMatchingNodes) return true
    if (updated.match.minAreaFraction > current.match.minAreaFraction) return true
    if (!current.match.requireAllGroups && updated.match.requireAllGroups) return true

    return false
}

/** Higher means harder to get past. */
private fun actionStrength(action: BlockAction): Int = when (action) {
    BlockAction.OVERLAY_ONLY -> 0
    BlockAction.BACK_UNTIL_GONE -> 1
    BlockAction.OVERLAY_THEN_BACK -> 2
    BlockAction.HOME -> 3
}
