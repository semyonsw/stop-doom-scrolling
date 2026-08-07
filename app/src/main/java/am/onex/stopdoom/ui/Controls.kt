package am.onex.stopdoom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import am.onex.stopdoom.overlay.formatDuration
import kotlin.math.roundToInt

/**
 * The form controls the rule editor is built from.
 *
 * They exist because every rule field is a number with a meaning - seconds, a count,
 * a fraction of the screen - and a bare text box makes all of them look the same.
 * Each control here shows the value in the unit it is actually measured in, and
 * offers the handful of settings that are worth choosing directly.
 */

/** Title on the left, whatever the field needs on the right, help underneath. */
@Composable
fun FieldHeader(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    help: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    if (help != null) {
        Spacer(Modifier.height(2.dp))
        Hint(help)
    }
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    help: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (help != null) {
                Spacer(Modifier.height(2.dp))
                Hint(help)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * Seconds, entered the way you actually think about them.
 *
 * The presets carry the intent - "block on sight" is 0, "a couple of minutes" is 120 -
 * and the stepper is there for the values in between. Zero is spelled out as its
 * consequence rather than as a number, because 0 and 1 second read the same on a
 * dial and mean completely different things here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DurationField(
    title: String,
    seconds: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    help: String? = null,
    presets: List<Int> = listOf(0, 60, 300, 600, 1800, 3600),
    zeroLabel: String = "Off",
    step: Int = 30,
) {
    Column(modifier.fillMaxWidth()) {
        FieldHeader(
            title = title,
            value = if (seconds <= 0) zeroLabel else formatDuration(seconds),
            help = help,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { preset ->
                FilterChip(
                    selected = seconds == preset,
                    onClick = { onChange(preset) },
                    label = {
                        Text(if (preset <= 0) zeroLabel else formatDuration(preset))
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        NumberStepper(
            value = seconds,
            onChange = onChange,
            step = step,
            min = 0,
            max = 24 * 3600,
            unit = "seconds",
        )
    }
}

/** Small whole numbers, where a slider would be imprecise and a keyboard overkill. */
@Composable
fun CountField(
    title: String,
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    help: String? = null,
    min: Int = 0,
    max: Int = 99,
) {
    Column(modifier.fillMaxWidth()) {
        FieldHeader(title = title, value = value.toString(), help = help)
        Spacer(Modifier.height(8.dp))
        NumberStepper(value = value, onChange = onChange, step = 1, min = min, max = max)
    }
}

/**
 * A fraction of the screen, shown as a percentage.
 *
 * This is the control that stops a rule from matching a nav-bar button, so the
 * label spells out what the number gates rather than just printing it.
 */
@Composable
fun FractionField(
    title: String,
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    help: String? = null,
) {
    Column(modifier.fillMaxWidth()) {
        FieldHeader(
            title = title,
            value = "${(value * 100).roundToInt()}% of the screen",
            help = help,
        )
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = { onChange((it * 100).roundToInt() / 100f) },
            valueRange = 0f..1f,
            steps = 19,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Hint("Any size")
            Hint("Full screen only")
        }
    }
}

/**
 * A list of strings edited as chips.
 *
 * Used for packages and for every matcher group. Typing a value and adding it is
 * two taps; the JSON equivalent was punctuation you had to get right.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagListField(
    title: String,
    items: List<String>,
    onChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    help: String? = null,
    placeholder: String = "",
    emptyText: String = "Nothing yet",
    trailingAction: (@Composable () -> Unit)? = null,
) {
    var draft by remember { mutableStateOf("") }

    fun commit() {
        val entry = draft.trim()
        if (entry.isNotEmpty() && entry !in items) onChange(items + entry)
        draft = ""
    }

    Column(modifier.fillMaxWidth()) {
        FieldHeader(title = title, value = if (items.isEmpty()) null else "${items.size}", help = help)
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) {
            Hint(emptyText)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { item ->
                    InputChip(
                        selected = false,
                        onClick = { onChange(items - item) },
                        label = { Text(item) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = "Remove $item",
                                modifier = Modifier.size(InputChipDefaults.AvatarSize),
                            )
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f),
            )
            FilledTonalIconButton(onClick = ::commit, enabled = draft.isNotBlank()) {
                Icon(Icons.Filled.Add, contentDescription = "Add")
            }
            trailingAction?.invoke()
        }
    }
}

/** One choice out of a few, laid out as chips so every option stays visible. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceField(
    title: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    labelOf: (T) -> String,
    modifier: Modifier = Modifier,
    help: String? = null,
    describeSelected: ((T) -> String)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        FieldHeader(title = title, help = help)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(labelOf(option)) },
                )
            }
        }
        if (describeSelected != null) {
            Spacer(Modifier.height(6.dp))
            Hint(describeSelected(selected))
        }
    }
}

/** `[−] 300 [+]`, with the number editable directly for anything the steps miss. */
@Composable
fun NumberStepper(
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    step: Int = 1,
    min: Int = 0,
    max: Int = Int.MAX_VALUE,
    unit: String? = null,
) {
    // Typing "" or "1" on the way to "10" must not be clobbered by the value coming
    // back through recomposition, so the field owns its text while it is being edited.
    var text by remember(value) { mutableStateOf(value.toString()) }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedIconButton(
            onClick = { onChange((value - step).coerceAtLeast(min)) },
            enabled = value > min,
        ) {
            Text("−", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                val digits = raw.filter(Char::isDigit).take(6)
                text = digits
                onChange((digits.toIntOrNull() ?: min).coerceIn(min, max))
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.width(110.dp),
        )
        OutlinedIconButton(
            onClick = { onChange((value + step).coerceAtMost(max)) },
            enabled = value < max,
        ) {
            Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        if (unit != null) Hint(unit)
    }
}

/** A tinted strip for the one thing on a screen the user needs to notice. */
@Composable
fun Callout(
    text: String,
    modifier: Modifier = Modifier,
    tone: CalloutTone = CalloutTone.Info,
) {
    val container = when (tone) {
        CalloutTone.Info -> MaterialTheme.colorScheme.surfaceVariant
        CalloutTone.Warn -> MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
        CalloutTone.Good -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    }
    val content = when (tone) {
        CalloutTone.Info -> MaterialTheme.colorScheme.onSurfaceVariant
        CalloutTone.Warn -> MaterialTheme.colorScheme.error
        CalloutTone.Good -> MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = container,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = content,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

enum class CalloutTone { Info, Warn, Good }

/** A hairline between fields inside one card. */
@Composable
fun FieldDivider(modifier: Modifier = Modifier) {
    Spacer(modifier.height(20.dp))
}
