package com.lioravrahami.souschef.ui.recipe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.lioravrahami.souschef.ui.TestTags
import com.lioravrahami.souschef.ui.components.BigButton
import com.lioravrahami.souschef.ui.components.BigOutlinedButton

/**
 * "Explore — try a tweak" chooser: a classical tweak, or an AI suggestion (disabled with a
 * hint until an OpenRouter key is set).
 */
@Composable
fun ExploreChooserDialog(
    hasApiKey: Boolean,
    onClassical: () -> Unit,
    onAi: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Try a tweak", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Gather information for the optimizer: cook a variation and rate it afterwards.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                BigButton(
                    text = "Classical tweak",
                    onClick = onClassical,
                    modifier = Modifier.testTag(TestTags.EXPLORE_CLASSICAL),
                )
                Text(
                    "Nudges amounts and times based on your overall scores.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BigOutlinedButton(
                    text = "AI suggestion",
                    onClick = onAi,
                    enabled = hasApiKey,
                    modifier = Modifier.testTag(TestTags.EXPLORE_AI),
                )
                Text(
                    if (hasApiKey) {
                        "An AI reads every version, score and note, suggests one change, and a second AI checks it isn't too much."
                    } else {
                        "Add an OpenRouter key in Settings"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasApiKey) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) {
                Text("Cancel", style = MaterialTheme.typography.titleMedium)
            }
        },
    )
}

/** Shown while the AI thinks; "Cancel" stops the request without an error. */
@Composable
fun AiLoadingDialog(onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("AI suggestion", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                CircularProgressIndicator(Modifier.size(48.dp))
                Text("Asking the AI… this takes a few seconds", style = MaterialTheme.typography.bodyLarge)
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 56.dp)) {
                Text("Cancel", style = MaterialTheme.typography.titleMedium)
            }
        },
    )
}
