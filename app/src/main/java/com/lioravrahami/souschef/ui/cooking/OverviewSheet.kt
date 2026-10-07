package com.lioravrahami.souschef.ui.cooking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.ui.components.BigButton
import com.lioravrahami.souschef.ui.components.BigOutlinedButton

/**
 * The holistic view of the recipe, opened by swiping down or with the "Overview" button:
 * every step rendered with this trial's values, the current one highlighted. Tapping a
 * step jumps there. Also the place to finish early or leave the session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewSheet(
    session: CookingSession,
    currentPage: Int,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onJumpTo: (page: Int) -> Unit,
    onFinishAndRate: () -> Unit,
    onExitWithoutRating: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = session.recipeName,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 2,
            )
            Text(
                text = "Version: ${session.version.name}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (session.changes.isNotEmpty()) {
                Text(
                    text = "This time: " + session.changes.joinToString("; ") { it.text },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }

            val listState = rememberLazyListState()
            LaunchedEffect(Unit) {
                if (currentPage in session.steps.indices) listState.scrollToItem((currentPage - 1).coerceAtLeast(0))
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(session.steps) { index, step ->
                    OverviewStepRow(
                        number = index + 1,
                        text = session.render(index),
                        isWait = step is Step.Wait,
                        isCurrent = index == currentPage,
                        onClick = { onJumpTo(index) },
                    )
                }
            }

            BigButton(text = "Keep cooking", onClick = onDismiss)
            BigOutlinedButton(text = "Finish & rate", onClick = onFinishAndRate)
            TextButton(
                onClick = onExitWithoutRating,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            ) {
                Text(
                    "Exit without rating",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun OverviewStepRow(
    number: Int,
    text: String,
    isWait: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val container = when {
        isCurrent -> MaterialTheme.colorScheme.primaryContainer
        isWait -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
        isWait -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$number.",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                modifier = Modifier.width(48.dp),
            )
            Text(
                text = if (isCurrent) "$text  (now)" else text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}
