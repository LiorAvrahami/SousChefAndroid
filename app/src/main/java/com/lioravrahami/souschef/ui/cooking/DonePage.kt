package com.lioravrahami.souschef.ui.cooking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lioravrahami.souschef.ui.TestTags
import com.lioravrahami.souschef.ui.components.BigButton
import com.lioravrahami.souschef.ui.components.BigOutlinedButton

/** The last page of the pager: finish and rate, or leave without rating. */
@Composable
fun DonePage(
    onRate: () -> Unit,
    onExitWithoutRating: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = "All done!",
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Taste it, then tell Sous Chef how it went so the next cooking gets better.",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        BigButton(
            text = "Rate this cooking",
            onClick = onRate,
            modifier = Modifier
                .heightIn(min = 88.dp)
                .testTag(TestTags.COOK_FINISH_RATE),
        )
        BigOutlinedButton(
            text = "Exit without rating",
            onClick = onExitWithoutRating,
            modifier = Modifier.testTag(TestTags.COOK_EXIT),
        )
        if (onBack != null) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) {
                Text("Back to the last step", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/**
 * Confirmation before discarding a cooking session. Exiting marks the trial as aborted:
 * it is kept for history but never used by the optimizer.
 */
@Composable
fun ExitWithoutRatingDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exit without rating?", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Text(
                "This cooking will be discarded and won't help the optimizer.",
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 56.dp)) {
                Text("Exit", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) {
                Text("Keep cooking", style = MaterialTheme.typography.titleMedium)
            }
        },
    )
}
