package com.lioravrahami.souschef.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.domain.recipe.ChangeSummary
import com.lioravrahami.souschef.domain.recipe.ParamSpec
import com.lioravrahami.souschef.domain.recipe.StepParser
import com.lioravrahami.souschef.ui.TestTags
import com.lioravrahami.souschef.ui.components.BigButton
import com.lioravrahami.souschef.ui.components.BigOutlinedButton
import com.lioravrahami.souschef.ui.components.ScreenScaffold

/**
 * Full-screen editor of a text step: a big multiline field and one chip per detected
 * parameter ("1.75 cups water") that toggles whether the optimizer may change it.
 *
 * @param initial the step as it was when editing started (empty for a new step).
 * @param isNew true when the step will be appended; the field then gets focus right away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TextStepEditor(
    initial: Step.Text,
    isNew: Boolean,
    onDone: (Step.Text) -> Unit,
    onCancel: () -> Unit,
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial.text, TextRange(initial.text.length)))
    }
    var locks by rememberSaveable { mutableStateOf(initial.locked.distinct().sorted()) }
    val params = remember(value.text) { StepParser.params(listOf(Step.Text(value.text))) }
    val result = Step.Text(value.text.trim(), locks.filter { it < params.size })
    val changed = value.text != initial.text || locks != initial.locked.distinct().sorted()

    StepEditorFrame(
        title = if (isNew) "New step" else "Edit step",
        changed = changed,
        onCancel = onCancel,
        doneEnabled = result.text.isNotBlank(),
        doneTag = TestTags.STEP_DONE,
        onDone = { onDone(result) },
    ) {
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { if (isNew) focusRequester.requestFocus() }
        OutlinedTextField(
            value = value,
            onValueChange = { new ->
                if (new.text != value.text) {
                    locks = EditorRules.remapLocks(
                        old = StepParser.paramsInText(value.text),
                        new = StepParser.paramsInText(new.text),
                        locks = locks,
                    )
                }
                value = new
            },
            label = { Text("What to do") },
            textStyle = MaterialTheme.typography.titleLarge,
            minLines = 4,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .testTag(TestTags.STEP_TEXT_FIELD),
        )
        Text(
            text = "Write numbers the optimizer may tweak as number[unit], e.g. 1.75[cups] water. " +
                "Tap a number below to lock it.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (params.isEmpty()) {
            Text(
                text = "No numbers in brackets yet: nothing here will be tweaked.",
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            Text("Numbers in this step", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                params.forEach { p ->
                    val locked = p.indexInStep in locks
                    FilterChip(
                        selected = locked,
                        onClick = {
                            locks = if (locked) locks - p.indexInStep else (locks + p.indexInStep).sorted()
                        },
                        label = {
                            Text(
                                text = chipText(p) + if (locked) " · locked" else "",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        },
                        leadingIcon = if (locked) {
                            { Icon(Icons.Default.Lock, contentDescription = null, Modifier.heightIn(max = FilterChipDefaults.IconSize)) }
                        } else {
                            null
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
    }
}

/** "1.75 cups water": how the parser understood a number, with what it measures. */
internal fun chipText(p: ParamSpec): String =
    listOf(ChangeSummary.valueText(p, p.baseValue), p.name).filter { it.isNotBlank() }.joinToString(" ")

/**
 * Full-screen editor of a wait step: optional label, the [EggTimerDial] and whether the
 * optimizer may change the time.
 */
@Composable
internal fun WaitStepEditor(
    initial: Step.Wait,
    isNew: Boolean,
    onDone: (Step.Wait) -> Unit,
    onCancel: () -> Unit,
) {
    var label by rememberSaveable { mutableStateOf(initial.label) }
    var seconds by rememberSaveable { mutableIntStateOf(initial.seconds) }
    var mayChange by rememberSaveable { mutableStateOf(!initial.locked) }
    val result = Step.Wait(label = label.trim(), seconds = seconds, locked = !mayChange)

    StepEditorFrame(
        title = if (isNew) "New wait" else "Edit wait",
        changed = result != initial,
        onCancel = onCancel,
        doneEnabled = seconds > 0,
        doneTag = TestTags.WAIT_DONE,
        onDone = { onDone(result) },
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("What are we waiting for? (optional)") },
            placeholder = { Text("Let the dough rise") },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.WAIT_LABEL_FIELD),
        )
        Text(
            text = "Tap the dial at the time you want, or drag around it.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EggTimerDial(seconds = seconds, onSecondsChange = { seconds = it })
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = mayChange, onValueChange = { mayChange = it }, role = Role.Switch)
                .padding(vertical = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Optimizer may change this time", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (mayChange) "It may try a bit shorter or longer." else "Locked: always exactly this long.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = mayChange, onCheckedChange = null)
        }
    }
}

/**
 * Shared frame of the step editors: top bar, scrolling content, and Cancel / Done at the
 * bottom. Leaving with unsaved changes (back arrow, system back, Cancel) asks first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepEditorFrame(
    title: String,
    changed: Boolean,
    onCancel: () -> Unit,
    doneEnabled: Boolean,
    doneTag: String,
    onDone: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val requestCancel: () -> Unit = {
        if (changed) {
            confirmDiscard = true
        } else {
            onCancel()
        }
    }
    BackHandler(onBack = requestCancel)

    ScreenScaffold(title = title, onBack = requestCancel) { padding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigOutlinedButton(text = "Cancel", onClick = requestCancel, modifier = Modifier.weight(1f))
                BigButton(
                    text = "Done",
                    onClick = onDone,
                    enabled = doneEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(doneTag),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDiscard) {
        DiscardChangesDialog(
            title = "Discard this step's changes?",
            onDiscard = {
                confirmDiscard = false
                onCancel()
            },
            onKeepEditing = { confirmDiscard = false },
        )
    }
}
