package am.onex.stopdoom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import am.onex.stopdoom.guard.isLoosening
import am.onex.stopdoom.overlay.formatDuration
import am.onex.stopdoom.rules.BlockAction
import am.onex.stopdoom.rules.BlockRule
import am.onex.stopdoom.rules.MatchSpec

/**
 * The rule editor, as a form.
 *
 * Every field a rule has is a real control here, because the JSON editor it replaces
 * asked you to remember both the key names and the units. The raw editor is still
 * one tap away - selectors sometimes have to be pasted from a dump - but nothing
 * routine needs it any more.
 *
 * The loosening check runs live against the rule as it was when the screen opened,
 * so the cooldown is visible while you are still deciding rather than as a surprise
 * after saving. It calls the same pure function the gate itself uses.
 */
@Composable
fun RuleEditorScreen(
    original: BlockRule?,
    existingIds: List<String>,
    onSave: (BlockRule) -> Unit,
    onCancel: () -> Unit,
    onEditJson: (BlockRule) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember(original) { mutableStateOf(original ?: newRule()) }
    var showAppPicker by remember { mutableStateOf(false) }

    val isNew = original == null
    val id = if (isNew) uniqueId(draft.label, existingIds) else draft.id
    val loosens = original != null && isLoosening(original, draft)
    val problem = draft.problem()

    fun edit(block: BlockRule.() -> BlockRule) {
        draft = draft.block()
    }

    fun editMatch(block: MatchSpec.() -> MatchSpec) {
        draft = draft.copy(match = draft.match.block())
    }

    if (showAppPicker) {
        AppPickerDialog(
            alreadyChosen = draft.packages,
            onPick = { picked ->
                if (picked !in draft.packages) edit { copy(packages = packages + picked) }
            },
            onDismiss = { showAppPicker = false },
        )
    }

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        item {
            SectionCard(
                title = if (isNew) "New rule" else draft.label.ifBlank { "Rule" },
                subtitle = if (isNew) "Not saved yet" else id,
            ) {
                OutlinedTextField(
                    value = draft.label,
                    onValueChange = { edit { copy(label = it) } },
                    label = { Text("Name") },
                    placeholder = { Text("YouTube Shorts") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Hint(
                    if (isNew) {
                        "Shown on the block screen. Saved with the id \"$id\"."
                    } else {
                        "Shown on the block screen."
                    },
                )
                FieldDivider()
                SwitchRow(
                    title = "Rule is active",
                    checked = draft.enabled,
                    onCheckedChange = { edit { copy(enabled = it) } },
                    help = "Off means this rule is kept but never fires.",
                )
                Spacer(Modifier.height(16.dp))
                Callout(draft.summary(), tone = CalloutTone.Info)
                if (loosens) {
                    Spacer(Modifier.height(8.dp))
                    Callout(
                        "This weakens the rule, so saving queues it for the change cooldown " +
                            "instead of applying now.",
                        tone = CalloutTone.Warn,
                    )
                }
                if (problem != null) {
                    Spacer(Modifier.height(8.dp))
                    Callout(problem, tone = CalloutTone.Warn)
                }
            }
        }

        item {
            SectionCard(title = "Which apps", subtitle = "Where this rule is allowed to fire") {
                TagListField(
                    title = "Packages",
                    items = draft.packages,
                    onChange = { edit { copy(packages = it) } },
                    placeholder = "com.example.app",
                    emptyText = "Empty means every app. Only useful for rules that match a URL.",
                    help = "Pick from the installed list rather than typing - a typo here " +
                        "looks exactly like a broken matcher.",
                    trailingAction = {
                        OutlinedButton(onClick = { showAppPicker = true }) { Text("Browse") }
                    },
                )
                FieldDivider()
                SwitchRow(
                    title = "Block the whole app",
                    checked = draft.wholeApp,
                    onCheckedChange = { edit { copy(wholeApp = it) } },
                    help = "Ignores everything below and treats any screen in these apps as " +
                        "the target. The honest fallback when a feed cannot be detected.",
                )
            }
        }

        if (!draft.wholeApp) {
            item {
                SectionCard(
                    title = "What to look for",
                    subtitle = "How the feed is recognised on screen",
                ) {
                    Hint(
                        "An entry containing \":id/\" is matched as an exact view id and skips " +
                            "the tree walk entirely. Everything else is a case-insensitive " +
                            "substring match. Use the Debug tab to dump a live screen and see " +
                            "what your build of the app actually renders.",
                    )
                    FieldDivider()
                    TagListField(
                        title = "View ids",
                        items = draft.match.anyViewIdContains,
                        onChange = { editMatch { copy(anyViewIdContains = it) } },
                        placeholder = "reel_recycler",
                        emptyText = "No view ids. This is usually the most reliable matcher.",
                    )
                    FieldDivider()
                    TagListField(
                        title = "Content descriptions",
                        items = draft.match.anyContentDescription,
                        onChange = { editMatch { copy(anyContentDescription = it) } },
                        placeholder = "Shorts",
                        emptyText = "No content descriptions.",
                    )
                    FieldDivider()
                    TagListField(
                        title = "On-screen text",
                        items = draft.match.anyText,
                        onChange = { editMatch { copy(anyText = it) } },
                        placeholder = "youtube.com/shorts",
                        emptyText = "No text matchers.",
                        help = "Also how a browser URL is caught, since the address bar is text.",
                    )
                }
            }

            item {
                SectionCard(
                    title = "How strict the match is",
                    subtitle = "The dials that stop false positives",
                ) {
                    ChoiceField(
                        title = "Combine the groups",
                        options = listOf(false, true),
                        selected = draft.match.requireAllGroups,
                        onSelect = { editMatch { copy(requireAllGroups = it) } },
                        labelOf = { if (it) "All must match" else "Any may match" },
                        describeSelected = {
                            if (it) {
                                "Every group you filled in has to match. Use this to tighten a " +
                                    "rule that fires on the wrong screen."
                            } else {
                                "One group matching is enough. The usual setting."
                            }
                        },
                    )
                    FieldDivider()
                    CountField(
                        title = "Matching elements needed",
                        value = draft.match.minMatchingNodes,
                        onChange = { editMatch { copy(minMatchingNodes = it) } },
                        min = 1,
                        max = 20,
                        help = "How many separate nodes on screen have to match before the " +
                            "rule fires. Raise it when one stray element is triggering it.",
                    )
                    FieldDivider()
                    FractionField(
                        title = "Minimum size of a match",
                        value = draft.match.minAreaFraction,
                        onChange = { editMatch { copy(minAreaFraction = it) } },
                        help = "A matching element must cover at least this much of the screen. " +
                            "This is what keeps the word \"Shorts\" on a nav-bar button from " +
                            "blocking all of YouTube - around 35% is a good floor for a player.",
                    )
                }
            }
        }

        item {
            SectionCard(title = "Time limits", subtitle = draft.limitsSummary()) {
                SwitchRow(
                    title = "Block on sight",
                    checked = draft.blocksOnSight,
                    onCheckedChange = { onSight ->
                        edit {
                            if (onSight) {
                                copy(sessionBudgetSeconds = 0, dailyBudgetSeconds = 0)
                            } else {
                                copy(
                                    sessionBudgetSeconds = DEFAULT_SESSION_SECONDS,
                                    dailyBudgetSeconds = DEFAULT_DAILY_SECONDS,
                                )
                            }
                        }
                    },
                    help = "No time at all. The right setting for an app that is nothing but " +
                        "the feed.",
                )
                if (!draft.blocksOnSight) {
                    FieldDivider()
                    DurationField(
                        title = "Per visit",
                        seconds = draft.sessionBudgetSeconds,
                        onChange = { edit { copy(sessionBudgetSeconds = it) } },
                        presets = listOf(0, 60, 120, 300, 600, 900),
                        zeroLabel = "On sight",
                        help = "The clock starts when the feed appears and resets when you " +
                            "leave it.",
                    )
                    FieldDivider()
                    DurationField(
                        title = "Per day",
                        seconds = draft.dailyBudgetSeconds,
                        onChange = { edit { copy(dailyBudgetSeconds = it) } },
                        presets = listOf(0, 300, 900, 1800, 3600, 7200),
                        zeroLabel = "On sight",
                        step = 300,
                        help = "Total across the whole calendar day. Resets at midnight.",
                    )
                }
            }
        }

        item {
            SectionCard(title = "What happens when it blocks") {
                ChoiceField(
                    title = "Action",
                    options = BlockAction.entries.sortedBy { it.ordering },
                    selected = draft.action,
                    onSelect = { edit { copy(action = it) } },
                    labelOf = { it.shortLabel() },
                    describeSelected = { it.explain() },
                )
                FieldDivider()
                DurationField(
                    title = "Forced wait on the block screen",
                    seconds = draft.frictionSeconds,
                    onChange = { edit { copy(frictionSeconds = it) } },
                    presets = listOf(0, 5, 10, 20, 30, 60),
                    zeroLabel = "None",
                    step = 5,
                    help = "How long the Okay button stays disabled. The pause is most of " +
                        "what actually works.",
                )
                FieldDivider()
                DurationField(
                    title = "Re-entry cooldown",
                    seconds = draft.reblockCooldownSeconds,
                    onChange = { edit { copy(reblockCooldownSeconds = it) } },
                    presets = listOf(0, 30, 60, 300, 600),
                    zeroLabel = "None",
                    help = "Coming back within this window is blocked immediately, with no " +
                        "fresh budget. Stops the instant second attempt.",
                )
            }
        }

        item {
            SectionCard(title = "Note", subtitle = "For your own reference") {
                OutlinedTextField(
                    value = draft.note,
                    onValueChange = { edit { copy(note = it) } },
                    placeholder = { Text("Why this rule looks the way it does") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item {
            SectionCard(title = "") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onSave(draft.copy(id = id)) },
                        enabled = draft.label.isNotBlank(),
                    ) { Text(if (isNew) "Create rule" else "Save") }
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                    TextButton(onClick = { onEditJson(draft.copy(id = id)) }) { Text("JSON") }
                }
                Spacer(Modifier.height(8.dp))
                Hint(
                    "Tightening a rule saves at once. Weakening one is queued for the change " +
                        "cooldown on the Guard tab.",
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

// --- descriptions -------------------------------------------------------------
//
// These read the draft rather than the saved rule, so the summary on screen always
// describes what Save would actually produce.

fun BlockRule.summary(): String {
    val where = when {
        packages.isEmpty() -> "In any app"
        packages.size == 1 -> "In ${packages.first().shortPackage()}"
        else -> "In ${packages.size} apps"
    }
    val what = when {
        wholeApp -> "every screen"
        match.isEmpty -> "nothing (no matchers set)"
        else -> "screens matching your selectors"
    }
    val limit = if (blocksOnSight) {
        "blocked on sight"
    } else {
        "${formatDuration(sessionBudgetSeconds)} per visit and " +
            "${formatDuration(dailyBudgetSeconds)} per day"
    }
    return "$where, $what: $limit. Then ${action.shortLabel().lowercase()}."
}

fun BlockRule.limitsSummary(): String =
    if (blocksOnSight) {
        "Blocked on sight"
    } else {
        "${formatDuration(sessionBudgetSeconds)} per visit, " +
            "${formatDuration(dailyBudgetSeconds)} per day"
    }

fun BlockRule.describeTarget(): String = when {
    wholeApp -> "Whole app: ${packages.joinToString { it.shortPackage() }}"
    packages.isEmpty() -> "Any app"
    else -> packages.joinToString { it.shortPackage() }
}

/** The one thing that would make a saved rule silently do nothing. */
private fun BlockRule.problem(): String? = when {
    label.isBlank() -> "Give the rule a name before saving."
    !wholeApp && match.isEmpty ->
        "No matchers set, so this rule can never fire. Add a view id, a content " +
            "description or some text - or turn on \"Block the whole app\"."

    else -> null
}

private fun String.shortPackage(): String = substringAfterLast('.')

private fun BlockAction.shortLabel(): String = when (this) {
    BlockAction.OVERLAY_THEN_BACK -> "Block screen, then back out"
    BlockAction.BACK_UNTIL_GONE -> "Back out silently"
    BlockAction.OVERLAY_ONLY -> "Block screen only"
    BlockAction.HOME -> "Send to home screen"
}

private fun BlockAction.explain(): String = when (this) {
    BlockAction.OVERLAY_THEN_BACK ->
        "Shows the block screen and backs out from underneath it. The default, and the " +
            "right answer nearly always."

    BlockAction.BACK_UNTIL_GONE ->
        "Presses Back until the feed is gone, with no block screen. Quiet, but nothing " +
            "explains what happened."

    BlockAction.OVERLAY_ONLY ->
        "Shows the block screen and leaves you exactly where you are. The weakest option: " +
            "dismissing it puts you back in the feed."

    BlockAction.HOME ->
        "Goes straight to the launcher. Blunt - it closes the rest of the app too - so " +
            "it suits whole-app rules."
}

/** Display order, weakest first, which is not the declaration order. */
private val BlockAction.ordering: Int
    get() = when (this) {
        BlockAction.OVERLAY_THEN_BACK -> 0
        BlockAction.BACK_UNTIL_GONE -> 1
        BlockAction.OVERLAY_ONLY -> 2
        BlockAction.HOME -> 3
    }

private const val DEFAULT_SESSION_SECONDS = 300
private const val DEFAULT_DAILY_SECONDS = 900

private fun newRule() = BlockRule(
    id = "",
    label = "",
    enabled = true,
    sessionBudgetSeconds = DEFAULT_SESSION_SECONDS,
    dailyBudgetSeconds = DEFAULT_DAILY_SECONDS,
)

/**
 * Ids are derived from the name rather than typed.
 *
 * The id is the key the store upserts on and the key usage history is recorded
 * against, so it is not something to leave to a free-text field - reusing one by
 * accident would silently merge two rules' statistics.
 */
fun uniqueId(label: String, taken: List<String>): String {
    val base = label.lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .trim('-')
        .replace(Regex("-+"), "-")
        .ifBlank { "rule" }
    if (base !in taken) return base
    var n = 2
    while ("$base-$n" in taken) n++
    return "$base-$n"
}
