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

    val groupResults = snapshot.groupHits(match).map { it.passing }
    if (groupResults.isEmpty()) return false

    val threshold = match.minMatchingNodes.coerceAtLeast(1)
    return if (match.requireAllGroups) {
        groupResults.all { it >= threshold }
    } else {
        groupResults.any { it >= threshold }
    }
}

/**
 * What one matcher group found, in enough detail to explain a miss.
 *
 * [raw] counts nodes whose text matched a needle before the area floor is applied
 * and [passing] counts those that also cleared it. Keeping both apart is the whole
 * point: "your selector is wrong" and "your selector is right but minAreaFraction
 * is too high" look identical from a boolean, and they need opposite fixes.
 */
data class GroupHits(
    val name: String,
    val raw: Int,
    val passing: Int,
    /** Largest fraction of the screen any raw hit covered, for sizing the floor. */
    val largestFraction: Float,
)

/**
 * Why a rule did or did not fire against a snapshot.
 *
 * Pure and driven by exactly the same counting as [matches], so the explanation
 * cannot drift away from the behaviour it explains.
 */
data class RuleProbe(
    val ruleId: String,
    val label: String,
    val matched: Boolean,
    val groups: List<GroupHits>,
    val verdict: String,
)

fun BlockRule.probe(snapshot: ScreenSnapshot): RuleProbe {
    val groups = if (wholeApp) emptyList() else snapshot.groupHits(match)
    val matched = matches(snapshot)
    val threshold = match.minMatchingNodes.coerceAtLeast(1)
    val floorPercent = (match.minAreaFraction * 100).toInt()

    val verdict = when {
        !enabled -> "Rule is switched off."
        packages.isNotEmpty() && snapshot.packageName !in packages ->
            "Does not apply to ${snapshot.packageName}."

        wholeApp -> "Whole-app rule, so every screen in this app matches."
        match.isEmpty -> "No selectors set, so this rule can never match."
        matched -> "Matches."

        groups.all { it.raw == 0 } -> {
            val ids = snapshot.nodes.count { it.viewId != null }
            "None of the selectors were found. The scan saw ${snapshot.nodes.size} " +
                "elements, $ids of them with a view id" +
                if (snapshot.truncated) {
                    ", and it hit its size cap - the element you want may be deeper " +
                        "than the scan goes."
                } else {
                    ". Dump this screen and compare the real ids."
                }
        }

        groups.any { it.raw > 0 && it.passing == 0 } -> {
            val best = groups.filter { it.raw > 0 }.maxOf { it.largestFraction }
            "Found the selector, but the biggest match covers ${(best * 100).toInt()}% " +
                "of the screen and the rule needs $floorPercent%. Lower the minimum size."
        }

        groups.any { it.passing in 1 until threshold } ->
            "Found ${groups.maxOf { it.passing }} matching element(s), but the rule " +
                "needs $threshold. Lower the element count."

        match.requireAllGroups ->
            "Some groups matched and some did not, and this rule requires all of them."

        else -> "No group reached the threshold."
    }

    return RuleProbe(id, label, matched, groups, verdict)
}

/** The non-empty matcher groups, counted. Shared by [matches] and [probe]. */
private fun ScreenSnapshot.groupHits(spec: MatchSpec): List<GroupHits> = buildList {
    if (spec.anyViewIdContains.isNotEmpty()) {
        add(hits("View ids", spec, spec.anyViewIdContains) { it.viewId })
    }
    if (spec.anyContentDescription.isNotEmpty()) {
        add(hits("Content descriptions", spec, spec.anyContentDescription) { it.desc })
    }
    if (spec.anyText.isNotEmpty()) {
        add(hits("On-screen text", spec, spec.anyText) { it.text })
    }
}

private inline fun ScreenSnapshot.hits(
    name: String,
    spec: MatchSpec,
    needles: List<String>,
    field: (SnapNode) -> String?,
): GroupHits {
    var raw = 0
    var passing = 0
    var largest = 0f
    for (node in nodes) {
        val value = field(node) ?: continue
        if (needles.none { value.contains(it, ignoreCase = true) }) continue
        raw++
        val fraction = node.areaFractionOf(this)
        if (fraction > largest) largest = fraction
        if (spec.minAreaFraction <= 0f || fraction >= spec.minAreaFraction) passing++
    }
    return GroupHits(name, raw, passing, largest)
}
