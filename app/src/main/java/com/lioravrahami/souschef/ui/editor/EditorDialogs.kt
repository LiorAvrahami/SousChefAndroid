package com.lioravrahami.souschef.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.domain.recipe.StepParser
import com.lioravrahami.souschef.ui.TestTags

/** Big text for dialog bodies. */
@Composable
private fun DialogText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun DialogAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, destructive: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = if (destructive) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
        modifier = modifier.heightIn(min = 56.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun DialogDismiss(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 56.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** "Discard changes?" with Discard / Keep editing. */
@Composable
internal fun DiscardChangesDialog(
    title: String = "Discard changes?",
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(title) },
        text = { DialogText("What you changed here will be lost.") },
        confirmButton = { DialogAction("Discard", onDiscard, destructive = true) },
        dismissButton = { DialogDismiss("Keep editing", onKeepEditing) },
    )
}

/** Confirms deleting a step that has content, showing what it says. */
@Composable
internal fun DeleteStepDialog(step: Step, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val preview = remember(step) { StepParser.render(step, 0, StepParser.params(listOf(step)), null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this step?") },
        text = { DialogText(preview) },
        confirmButton = { DialogAction("Delete", onDelete, destructive = true) },
        dismissButton = { DialogDismiss("Keep it", onDismiss) },
    )
}

/** A short message, e.g. why the recipe cannot be saved yet. */
@Composable
internal fun MessageDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { DialogText(message) },
        confirmButton = { DialogAction("OK", onDismiss) },
    )
}

/**
 * Quick entry: paste or type a whole recipe; every non-blank line becomes a text step
 * (see [EditorRules.parsePastedSteps]).
 */
@Composable
internal fun PasteRecipeDialog(onAdd: (List<Step.Text>) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val steps = remember(text) { EditorRules.parsePastedSteps(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste a recipe") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DialogText("One step per line. Bullets and numbering are removed.")
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("- Boil 1.75[cups] water\n- Add 3[shakes] of salt") },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    minLines = 6,
                    maxLines = 12,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            DialogAction(
                text = when (steps.size) {
                    0 -> "Add steps"
                    1 -> "Add 1 step"
                    else -> "Add ${steps.size} steps"
                },
                onClick = { onAdd(steps) },
                enabled = steps.isNotEmpty(),
            )
        },
        dismissButton = { DialogDismiss("Cancel", onDismiss) },
    )
}

/**
 * Asked when the steps of an existing recipe changed: they are saved as a new version with
 * this (prefilled) name and an optional note.
 */
@Composable
internal fun VersionNameDialog(
    suggestedName: String,
    onConfirm: (name: String, note: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(suggestedName) }
    var note by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save as a new version") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DialogText("The steps changed, so they are kept as a new version. Earlier cookings stay with their own version.")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Version name") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("What changed? (optional)") },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            DialogAction(
                text = "Save version",
                onClick = { onConfirm(name.ifBlank { suggestedName }, note) },
                modifier = Modifier.testTag(TestTags.VERSION_NAME_CONFIRM),
            )
        },
        dismissButton = { DialogDismiss("Back", onDismiss) },
    )
}
