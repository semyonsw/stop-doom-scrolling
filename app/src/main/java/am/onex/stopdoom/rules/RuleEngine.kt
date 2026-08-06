package am.onex.stopdoom.rules

/**
 * Decides which rules a screen matches. Pure: no Android types, no clock, no I/O.
 */
class RuleEngine(rules: List<BlockRule> = emptyList()) {

    @Volatile
    var rules: List<BlockRule> = rules
        private set

    fun replaceRules(newRules: List<BlockRule>) {
        rules = newRules
    }

    /** Enabled rules that could apply to [packageName], cheapest pre-filter there is. */
    fun candidatesFor(packageName: String): List<BlockRule> =
        rules.filter { it.enabled && (it.packages.isEmpty() || packageName in it.packages) }

    /**
     * Every rule the snapshot matches, in declaration order.
     *
     * Order matters: the service acts on the first result, so put narrow rules
     * ahead of broad ones in the JSON.
     */
    fun evaluate(snapshot: ScreenSnapshot): List<BlockRule> =
        candidatesFor(snapshot.packageName).filter { it.matches(snapshot) }

    fun firstMatch(snapshot: ScreenSnapshot): BlockRule? =
        candidatesFor(snapshot.packageName).firstOrNull { it.matches(snapshot) }
}

fun BlockRule.matches(snapshot: ScreenSnapshot): Boolean {
    if (!enabled) return false
    if (packages.isNotEmpty() && snapshot.packageName !in packages) return false
    if (wholeApp) return true
    if (match.isEmpty) return false

    val groupResults = buildList {
        if (match.anyViewIdContains.isNotEmpty()) {
            add(snapshot.countMatching(match, match.anyViewIdContains) { it.viewId })
        }
        if (match.anyContentDescription.isNotEmpty()) {
            add(snapshot.countMatching(match, match.anyContentDescription) { it.desc })
        }
        if (match.anyText.isNotEmpty()) {
            add(snapshot.countMatching(match, match.anyText) { it.text })
        }
    }
    if (groupResults.isEmpty()) return false

    val threshold = match.minMatchingNodes.coerceAtLeast(1)
    return if (match.requireAllGroups) {
        groupResults.all { it >= threshold }
    } else {
        groupResults.any { it >= threshold }
    }
}

private inline fun ScreenSnapshot.countMatching(
    spec: MatchSpec,
    needles: List<String>,
    field: (SnapNode) -> String?,
): Int = nodes.count { node ->
    val value = field(node)
    value != null &&
        needles.any { value.contains(it, ignoreCase = true) } &&
        (spec.minAreaFraction <= 0f || node.areaFractionOf(this) >= spec.minAreaFraction)
}
