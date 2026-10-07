package com.lioravrahami.souschef.ui.rating

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lioravrahami.souschef.AppContainer
import com.lioravrahami.souschef.data.model.RatingAxis
import com.lioravrahami.souschef.data.model.TrialStatus
import com.lioravrahami.souschef.ui.TestTags
import com.lioravrahami.souschef.ui.components.BigButton
import com.lioravrahami.souschef.ui.components.ScreenScaffold
import java.text.DateFormat
import java.util.Date

/**
 * "How did it go?" — the rating form shown after a cooking: overall score, one slider per
 * rating axis, and free-text notes. Saving marks the trial DONE so the optimizers learn from it.
 *
 * @param onDone called with the recipe id after the rating was saved or the cooking discarded.
 */
@Composable
fun RatingScreen(
    container: AppContainer,
    trialId: String,
    onDone: (recipeId: String) -> Unit,
) {
    val viewModel: RatingViewModel = viewModel(
        key = "rating-$trialId",
        factory = viewModelFactory { initializer { RatingViewModel(container, trialId) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var askOnBack by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    val ready = state as? RatingUiState.Ready
    BackHandler(enabled = ready != null && !viewModel.leaving) { askOnBack = true }

    ScreenScaffold(
        title = "How did it go?",
        onBack = {
            if (ready != null) askOnBack = true else backDispatcher?.onBackPressed()
        },
    ) { padding ->
        val current = state
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                viewModel.leaving || current is RatingUiState.Loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                current is RatingUiState.Missing -> Message(
                    text = "This cooking no longer exists.",
                    button = "Back",
                    onClick = { current.recipeId?.let(onDone) ?: backDispatcher?.onBackPressed() },
                )
                current is RatingUiState.Closed -> Message(
                    text = if (current.status == TrialStatus.DONE) {
                        "This cooking was already rated" +
                            (current.score?.let { " (${RatingLogic.formatScore(it)} / 10)" } ?: "") + "."
                    } else {
                        "This cooking was discarded."
                    },
                    button = "Back to the recipe",
                    onClick = { onDone(current.recipeId) },
                )
                current is RatingUiState.Ready -> RatingForm(
                    state = current,
                    viewModel = viewModel,
                    onSave = { viewModel.save(current.axes, current.trial.recipeId, onDone) },
                    onDiscard = { confirmDiscard = true },
                )
            }
        }
    }

    if (askOnBack && ready != null) {
        AlertDialog(
            onDismissRequest = { askOnBack = false },
            title = { Text("Save the rating first?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "Without a rating this cooking can't help the optimizer.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        askOnBack = false
                        viewModel.save(ready.axes, ready.trial.recipeId, onDone)
                    },
                    modifier = Modifier.heightIn(min = 56.dp),
                ) { Text("Save", style = MaterialTheme.typography.titleMedium) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { askOnBack = false }, modifier = Modifier.heightIn(min = 56.dp)) {
                        Text("Cancel", style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(
                        onClick = {
                            askOnBack = false
                            viewModel.discard(ready.trial.recipeId, onDone)
                        },
                        modifier = Modifier.heightIn(min = 56.dp),
                    ) {
                        Text("Discard", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
    }

    if (confirmDiscard && ready != null) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this cooking?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "It stays in the history as discarded and won't be used by the optimizer.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        viewModel.discard(ready.trial.recipeId, onDone)
                    },
                    modifier = Modifier.heightIn(min = 56.dp),
                ) {
                    Text("Discard", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text("Keep", style = MaterialTheme.typography.titleMedium)
                }
            },
        )
    }
}

@Composable
private fun RatingForm(
    state: RatingUiState.Ready,
    viewModel: RatingViewModel,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    val trial = state.trial
    val differences = state.version?.let { RatingLogic.differences(it.steps, trial.values) }
    val cookedAt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(trial.finishedAt ?: trial.createdAt))
    val subtitle = listOfNotNull(cookedAt, RatingLogic.modeLabel(trial.mode), state.version?.name).joinToString(" · ")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(state.details.recipe.name, style = MaterialTheme.typography.headlineMedium)
        Text(subtitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (differences != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("This time", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    differences.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }

        // ---- overall score
        Text("Overall", style = MaterialTheme.typography.titleLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(RatingLogic.formatScore(viewModel.score), style = MaterialTheme.typography.displayLarge)
            Text(
                " / 10",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        Slider(
            value = viewModel.score.toFloat(),
            onValueChange = viewModel::onScoreChange,
            valueRange = RatingLogic.MIN_SCORE..RatingLogic.MAX_SCORE,
            steps = RatingLogic.SCORE_SLIDER_STEPS,
            enabled = !viewModel.leaving,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag(TestTags.RATING_OVERALL),
        )

        HorizontalDivider()

        state.axes.forEach { axis ->
            AxisRow(
                axis = axis,
                value = viewModel.axisValues[axis.id] ?: 0.0,
                enabled = !viewModel.leaving,
                onChange = { viewModel.onAxisChange(axis.id, it) },
            )
        }

        HorizontalDivider()

        OutlinedTextField(
            value = viewModel.notes,
            onValueChange = { viewModel.notes = it },
            label = { Text("What would you change next time?") },
            textStyle = MaterialTheme.typography.bodyLarge,
            minLines = 3,
            enabled = !viewModel.leaving,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.RATING_NOTES),
        )

        BigButton(
            text = "Save rating",
            onClick = onSave,
            enabled = !viewModel.leaving,
            modifier = Modifier
                .heightIn(min = 80.dp)
                .testTag(TestTags.RATING_SAVE),
        )
        TextButton(
            onClick = onDiscard,
            enabled = !viewModel.leaving,
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Discard this cooking",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** One axis: low label on the left, high label on the right, a 5-position slider and a caption. */
@Composable
private fun AxisRow(axis: RatingAxis, value: Double, enabled: Boolean, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                axis.lowLabel,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                axis.highLabel,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = onChange,
            valueRange = RatingLogic.AXIS_MIN..RatingLogic.AXIS_MAX,
            steps = RatingLogic.AXIS_SLIDER_STEPS,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        )
        Text(
            RatingLogic.axisCaption(axis, value),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (value == 0.0) FontWeight.Normal else FontWeight.Bold,
            color = if (value == 0.0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Message(text: String, button: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        BigButton(text = button, onClick = onClick)
    }
}
