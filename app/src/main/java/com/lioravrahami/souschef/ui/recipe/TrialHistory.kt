package com.lioravrahami.souschef.ui.recipe

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lioravrahami.souschef.data.model.RatingAxis
import com.lioravrahami.souschef.data.model.RecipeVersion
import com.lioravrahami.souschef.data.model.Trial
import com.lioravrahami.souschef.ui.components.BigOutlinedButton

/**
 * One finished cooking: date, score chip, how it was started, what was different and the
 * feedback. Tap to expand the full steps with those values and "Make this a version".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrialCard(
    trial: Trial,
    version: RecipeVersion?,
    axes: List<RatingAxis>,
    onMakeVersion: () -> Unit,
) {
    var expanded by rememberSaveable(trial.id) { mutableStateOf(false) }
    val fits = RecipeDetailText.matchesVersion(version, trial.values)
    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ScoreChip(trial.overallScore)
                Column(Modifier.weight(1f)) {
                    Text(
                        RecipeDetailText.formatDate(trial.finishedAt ?: trial.createdAt),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        listOfNotNull(
                            RecipeDetailText.modeLabel(trial.mode),
                            version?.name?.let { "version “$it”" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                RecipeDetailText.trialChangeLine(version, trial.values),
                style = MaterialTheme.typography.bodyLarge,
                color = if (fits) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            val chips = RecipeDetailText.axisChips(axes, trial.axes)
            if (chips.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    chips.forEach { LabelBadge(it) }
                }
            }
            if (trial.notes.isNotBlank()) {
                Text("“${trial.notes.trim()}”", style = MaterialTheme.typography.bodyLarge)
            }
            if (expanded) {
                if (trial.rationale.isNotBlank()) {
                    Text(
                        trial.rationale,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (version != null && fits) {
                    StepList(steps = version.steps, values = trial.values, modifier = Modifier.padding(vertical = 8.dp))
                    BigOutlinedButton(text = "Make this a version", onClick = onMakeVersion)
                } else {
                    Text(
                        if (version == null) {
                            "The version of this cooking no longer exists."
                        } else {
                            "The values of this cooking don't match its version's steps."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}
