package com.lioravrahami.souschef.ui.recipe

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lioravrahami.souschef.data.model.RecipeVersion
import com.lioravrahami.souschef.data.model.VersionOrigin
import com.lioravrahami.souschef.ui.components.BigButton
import com.lioravrahami.souschef.ui.components.BigOutlinedButton

/**
 * One version of the recipe: name, origin, date, note and stats; tap to expand the written
 * steps and the actions (cook as written, edit as a new version, archive / unarchive).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VersionCard(
    version: RecipeVersion,
    cookings: Int,
    bestScore: Double?,
    onCookAsWritten: () -> Unit,
    onEdit: () -> Unit,
    onToggleArchive: () -> Unit,
) {
    var expanded by rememberSaveable(version.id) { mutableStateOf(false) }
    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = if (version.archived) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        border = if (version.archived) CardDefaults.outlinedCardBorder() else null,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    version.name.ifBlank { "Unnamed version" },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LabelBadge(
                    RecipeDetailText.originLabel(version.origin),
                    container = if (version.origin == VersionOrigin.AI) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    content = if (version.origin == VersionOrigin.AI) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                )
                if (version.archived) {
                    LabelBadge(
                        "archived",
                        container = MaterialTheme.colorScheme.outline,
                        content = MaterialTheme.colorScheme.surface,
                    )
                }
                Text(
                    RecipeDetailText.formatDate(version.createdAt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            Text(
                RecipeDetailText.versionStats(cookings, bestScore),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (version.note.isNotBlank()) {
                Text(
                    version.note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                if (version.steps.isEmpty()) {
                    Text("No steps.", style = MaterialTheme.typography.bodyLarge)
                } else {
                    StepList(steps = version.steps, values = null, modifier = Modifier.padding(vertical = 8.dp))
                }
                BigButton(text = "Cook as written", onClick = onCookAsWritten, enabled = version.steps.isNotEmpty())
                BigOutlinedButton(text = "Edit as new version", onClick = onEdit)
                TextButton(
                    onClick = onToggleArchive,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                ) {
                    Text(if (version.archived) "Unarchive" else "Archive", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
