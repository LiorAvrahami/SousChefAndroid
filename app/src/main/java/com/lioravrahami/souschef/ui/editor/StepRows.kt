package com.lioravrahami.souschef.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.domain.recipe.StepParser

private const val LOCK_ICON = "lock"

/**
 * One step of the recipe in the editor's list: its number, its readable content and the
 * row actions (edit, move up, move down, delete). Tapping the card also edits the step.
 */
@Composable
internal fun StepRow(
    number: Int,
    step: Step,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(onClick = onEdit, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "$number.",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.widthIn(min = 40.dp),
                )
                StepContent(step, Modifier.weight(1f).padding(end = 8.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RowAction(Icons.Default.Edit, "Edit step $number", onEdit)
                RowAction(Icons.Default.KeyboardArrowUp, "Move step $number up", onMoveUp, enabled = canMoveUp)
                RowAction(Icons.Default.KeyboardArrowDown, "Move step $number down", onMoveDown, enabled = canMoveDown)
                RowAction(Icons.Default.Delete, "Delete step $number", onDelete, tinted = true)
            }
        }
    }
}

@Composable
private fun RowAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tinted: Boolean = false,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(56.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(32.dp),
            tint = if (tinted && enabled) MaterialTheme.colorScheme.error else LocalContentColor.current,
        )
    }
}

/**
 * Readable rendering of a step: text steps with their parameters highlighted (and a lock on
 * locked ones), wait steps as "⏱ label — duration".
 */
@Composable
internal fun StepContent(step: Step, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val highlight = SpanStyle(
        fontWeight = FontWeight.Bold,
        color = colors.onPrimaryContainer,
        background = colors.primaryContainer,
    )
    val text: AnnotatedString = remember(step, highlight) {
        when (step) {
            is Step.Text -> buildAnnotatedString {
                if (step.text.isBlank()) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("(empty step)") }
                    return@buildAnnotatedString
                }
                val params = StepParser.params(listOf(step))
                StepParser.segments(step, 0, params, values = null).forEach { segment ->
                    val index = segment.paramIndex
                    if (index == null) {
                        append(segment.text)
                    } else {
                        withStyle(highlight) { append(segment.text) }
                        if (params.getOrNull(index)?.locked == true) appendInlineContent(LOCK_ICON, "(locked)")
                    }
                }
            }
            is Step.Wait -> buildAnnotatedString {
                append("⏱ ")
                append(step.label.ifBlank { "Wait" })
                append(" — ")
                withStyle(highlight) { append(StepParser.formatDuration(step.seconds)) }
                if (step.locked) appendInlineContent(LOCK_ICON, "(locked)")
            }
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier,
        inlineContent = mapOf(
            LOCK_ICON to InlineTextContent(Placeholder(1.1.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "locked",
                    tint = colors.primary,
                    modifier = Modifier.fillMaxSize(),
                )
            },
        ),
    )
}
