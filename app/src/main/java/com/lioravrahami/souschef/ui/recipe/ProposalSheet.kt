package com.lioravrahami.souschef.ui.recipe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.lioravrahami.souschef.ui.TestTags
import com.lioravrahami.souschef.ui.components.BigButton
import com.lioravrahami.souschef.ui.components.BigOutlinedButton

/**
 * Bottom sheet shown before any cooking starts: what will be cooked and why, with
 * "Cook this", "Another suggestion" (optimizers only) and "Cancel".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProposalSheet(
    state: ProposalState.Ready,
    onCook: () -> Unit,
    onAnother: () -> Unit,
    onCancel: () -> Unit,
) {
    val cook = state.cook
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ProposalBody(cook)
            }
            HorizontalDivider()
            BigButton(
                text = if (state.starting) "Starting…" else "Cook this",
                onClick = onCook,
                enabled = !state.starting,
                modifier = Modifier.testTag(TestTags.PROPOSAL_COOK),
            )
            if (cook.source.canReroll) {
                BigOutlinedButton(
                    text = "Another suggestion",
                    onClick = onAnother,
                    enabled = !state.starting,
                    modifier = Modifier.testTag(TestTags.PROPOSAL_ANOTHER),
                )
            }
            TextButton(
                onClick = onCancel,
                enabled = !state.starting,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag(TestTags.PROPOSAL_CANCEL),
            ) {
                Text("Cancel", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun ProposalBody(cook: PendingCook) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(cook.source.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f, fill = false))
        if (cook.newVersion != null) {
            LabelBadge(
                "New version",
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
    Text(
        listOfNotNull("Version “${cook.versionName}”", cook.basedOn).joinToString(" · "),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    cook.summary?.takeIf { it.isNotBlank() }?.let {
        Text(it, style = MaterialTheme.typography.titleLarge)
    }

    if (cook.newVersion != null) {
        SheetHeading("New steps")
        StepList(steps = cook.steps, values = null)
    } else {
        SheetHeading("This time")
        if (cook.changes.isEmpty()) {
            Text(RecipeDetailText.AS_WRITTEN, style = MaterialTheme.typography.titleLarge)
        } else {
            cook.changes.forEach { line ->
                Text(
                    "• $line",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (cook.rationale.isNotBlank()) {
        SheetHeading("Why")
        Text(cook.rationale, style = MaterialTheme.typography.bodyLarge)
    }

    if (cook.newVersion == null && cook.steps.isNotEmpty()) {
        var showSteps by rememberSaveable { mutableStateOf(false) }
        TextButton(onClick = { showSteps = !showSteps }, modifier = Modifier.heightIn(min = 56.dp)) {
            Text(if (showSteps) "Hide all steps" else "Show all steps", style = MaterialTheme.typography.titleMedium)
        }
        if (showSteps) StepList(steps = cook.steps, values = cook.values)
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(top = 4.dp),
    )
}
