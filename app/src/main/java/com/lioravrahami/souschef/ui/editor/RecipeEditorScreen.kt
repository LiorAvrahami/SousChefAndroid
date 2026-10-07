package com.lioravrahami.souschef.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lioravrahami.souschef.AppContainer
import com.lioravrahami.souschef.data.model.DefaultAxes
import com.lioravrahami.souschef.data.model.RatingAxis
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.ui.TestTags
import com.lioravrahami.souschef.ui.components.BigButton
import com.lioravrahami.souschef.ui.components.BigOutlinedButton
import com.lioravrahami.souschef.ui.components.ScreenScaffold

/**
 * Creates a recipe ([recipeId] == null) or edits one. For an existing recipe the steps start
 * from [baseVersionId] (or the latest version); changed steps are saved as a new version.
 * Calls [onSaved] with the recipe id once everything is stored, [onCancel] when the user
 * leaves without saving.
 */
@Composable
fun RecipeEditorScreen(
    container: AppContainer,
    recipeId: String?,
    baseVersionId: String?,
    onSaved: (recipeId: String) -> Unit,
    onCancel: () -> Unit,
) {
    val vm: RecipeEditorViewModel = viewModel(
        key = "recipe-editor:${recipeId.orEmpty()}:${baseVersionId.orEmpty()}",
        factory = viewModelFactory {
            initializer { RecipeEditorViewModel(container, recipeId, baseVersionId, createSavedStateHandle()) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val latestOnSaved by rememberUpdatedState(onSaved)
    LaunchedEffect(state.savedRecipeId) {
        state.savedRecipeId?.let { latestOnSaved(it) }
    }
    // Hoisted so that the list keeps its position while a step is being edited.
    val formScroll = rememberScrollState()

    val editing = state.editing
    if (editing != null) {
        key(editing) {
            when (val step = editing.step) {
                is Step.Text -> TextStepEditor(step, isNew = editing.index == null, onDone = vm::commitEdit, onCancel = vm::cancelEdit)
                is Step.Wait -> WaitStepEditor(step, isNew = editing.index == null, onDone = vm::commitEdit, onCancel = vm::cancelEdit)
            }
        }
    } else {
        EditorForm(state = state, vm = vm, scroll = formScroll, onCancel = onCancel)
    }
}

@Composable
private fun EditorForm(
    state: EditorUiState,
    vm: RecipeEditorViewModel,
    scroll: ScrollState,
    onCancel: () -> Unit,
) {
    var showDiscard by rememberSaveable { mutableStateOf(false) }
    var showPaste by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by rememberSaveable { mutableStateOf<Int?>(null) }
    // While saving, leaving is blocked (back is consumed) so the save is never interrupted.
    val requestClose: () -> Unit = {
        if (state.saving) {
            // Ignored: the save finishes and then leaves by itself.
        } else if (state.dirty) {
            showDiscard = true
        } else {
            onCancel()
        }
    }
    BackHandler(enabled = state.dirty || state.saving) { if (!state.saving) showDiscard = true }

    ScreenScaffold(
        title = if (state.isNew) "New recipe" else "Edit recipe",
        onBack = requestClose,
        actions = {
            Button(
                onClick = vm::save,
                enabled = !state.loading && !state.saving && state.loadError == null,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .heightIn(min = 48.dp)
                    .testTag(TestTags.EDITOR_SAVE),
            ) {
                Text(if (state.saving) "Saving…" else "Save", style = MaterialTheme.typography.titleMedium)
            }
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(64.dp))
            }
            state.loadError != null -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Text(state.loadError, style = MaterialTheme.typography.titleLarge)
                BigButton("Back", onCancel)
            }
            else -> FormContent(
                state = state,
                vm = vm,
                scroll = scroll,
                padding = padding,
                onPaste = { showPaste = true },
                onDeleteStep = { index ->
                    val step = state.steps.getOrNull(index)
                    if (step != null && EditorRules.hasContent(step)) pendingDelete = index else vm.deleteStep(index)
                },
            )
        }
    }

    if (showDiscard) {
        DiscardChangesDialog(
            onDiscard = {
                showDiscard = false
                if (!state.saving) {
                    vm.markDiscarded()
                    onCancel()
                }
            },
            onKeepEditing = { showDiscard = false },
        )
    }
    if (showPaste) {
        PasteRecipeDialog(
            onAdd = { steps ->
                vm.appendSteps(steps)
                showPaste = false
            },
            onDismiss = { showPaste = false },
        )
    }
    pendingDelete?.let { index ->
        state.steps.getOrNull(index)?.let { step ->
            DeleteStepDialog(
                step = step,
                onDelete = {
                    vm.deleteStep(index)
                    pendingDelete = null
                },
                onDismiss = { pendingDelete = null },
            )
        }
    }
    state.versionPrompt?.let { prompt ->
        VersionNameDialog(
            suggestedName = prompt.suggestedName,
            onConfirm = vm::confirmVersion,
            onDismiss = vm::dismissVersionPrompt,
        )
    }
    state.error?.let { message ->
        MessageDialog(title = "Not saved yet", message = message, onDismiss = vm::clearError)
    }
    if (state.error == null) {
        state.notice?.let { message ->
            MessageDialog(title = "Unsaved changes restored", message = message, onDismiss = vm::clearNotice)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormContent(
    state: EditorUiState,
    vm: RecipeEditorViewModel,
    scroll: ScrollState,
    padding: PaddingValues,
    onPaste: () -> Unit,
    onDeleteStep: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
            .imePadding()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        EditorTextField(
            initialValue = state.name,
            onValueChange = vm::setName,
            label = "Recipe name",
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge,
            imeAction = ImeAction.Done,
            modifier = Modifier.testTag(TestTags.EDITOR_NAME),
        )

        SectionTitle("Steps")
        state.baseVersionName?.let {
            Text(
                text = "Starting from version $it. Changed steps are saved as a new version.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.steps.isEmpty()) {
            Text(
                text = "No steps yet. Add a step or a wait below, or paste a whole recipe.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        state.steps.forEachIndexed { index, step ->
            StepRow(
                number = index + 1,
                step = step,
                canMoveUp = index > 0,
                canMoveDown = index < state.steps.lastIndex,
                onEdit = { vm.startEdit(index) },
                onMoveUp = { vm.moveStep(index, -1) },
                onMoveDown = { vm.moveStep(index, +1) },
                onDelete = { onDeleteStep(index) },
            )
        }
        BigButton(
            text = "Add step",
            onClick = vm::startAddText,
            modifier = Modifier.testTag(TestTags.EDITOR_ADD_STEP),
        )
        BigOutlinedButton(
            text = "Add wait",
            onClick = vm::startAddWait,
            modifier = Modifier.testTag(TestTags.EDITOR_ADD_WAIT),
        )
        BigOutlinedButton(text = "Paste a recipe", onClick = onPaste)

        SectionTitle("Notes")
        EditorTextField(
            initialValue = state.notes,
            onValueChange = vm::setNotes,
            label = "Notes (optional)",
            minLines = 3,
        )

        SectionTitle("Rating after cooking")
        Text(
            text = "Each cooking gets an overall score from 0 to 10, plus these scales:",
            style = MaterialTheme.typography.bodyLarge,
        )
        DefaultAxes.all.forEach { axis ->
            Text(
                text = "${axis.lowLabel}  ↔  ${axis.highLabel}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        state.customAxes.forEach { axis ->
            key(axis.id) {
                CustomAxisEditor(
                    axis = axis,
                    onChange = { low, high -> vm.updateAxis(axis.id, low, high) },
                    onRemove = { vm.removeAxis(axis.id) },
                )
            }
        }
        BigOutlinedButton(text = "Add a rating scale", onClick = vm::addAxis)
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** Both ends of one custom rating scale, e.g. "Too sour" ↔ "Too sweet". */
@Composable
private fun CustomAxisEditor(
    axis: RatingAxis,
    onChange: (low: String, high: String) -> Unit,
    onRemove: () -> Unit,
) {
    var low by remember { mutableStateOf(axis.lowLabel) }
    var high by remember { mutableStateOf(axis.highLabel) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Custom scale", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onRemove, modifier = Modifier.size(56.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove this scale",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            OutlinedTextField(
                value = low,
                onValueChange = {
                    low = it
                    onChange(low, high)
                },
                label = { Text("One end, e.g. Too sour") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = high,
                onValueChange = {
                    high = it
                    onChange(low, high)
                },
                label = { Text("Other end, e.g. Too sweet") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Text field whose text lives in local Compose state (so typing is never delayed by a flow
 * round trip) and reports every change to [onValueChange]. [initialValue] is read once.
 */
@Composable
private fun EditorTextField(
    initialValue: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    minLines: Int = 1,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    imeAction: ImeAction = ImeAction.Default,
) {
    var value by remember { mutableStateOf(initialValue) }
    OutlinedTextField(
        value = value,
        onValueChange = {
            value = it
            onValueChange(it)
        },
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        textStyle = textStyle,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = imeAction),
        modifier = modifier.fillMaxWidth(),
    )
}
