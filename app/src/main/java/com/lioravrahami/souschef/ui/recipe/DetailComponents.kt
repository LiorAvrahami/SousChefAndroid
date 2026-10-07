package com.lioravrahami.souschef.ui.recipe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.domain.recipe.ParamSpec
import com.lioravrahami.souschef.domain.recipe.StepParser

/** A yes/no dialog with large buttons; [destructive] paints the confirm button in the error colour. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissLabel: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = { Text(message, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = 56.dp),
                colors = if (destructive) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(confirmLabel, style = MaterialTheme.typography.titleMedium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(dismissLabel, style = MaterialTheme.typography.titleMedium)
            }
        },
    )
}

/** An informational dialog with a single "OK" button. */
@Composable
fun InfoDialog(title: String?, message: String, onDismiss: () -> Unit, isError: Boolean = false) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = title?.let {
            {
                Text(
                    it,
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (isError) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
        },
        text = { Text(message, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) {
                Text("OK", style = MaterialTheme.typography.titleMedium)
            }
        },
    )
}

/** The score of a cooking in a big coloured pill: ≥ 8 tertiary, ≥ 5 neutral, else error container. */
@Composable
fun ScoreChip(score: Double?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (score?.let(RecipeDetailText::scoreBucket)) {
        ScoreBucket.HIGH -> colors.tertiary to colors.onTertiary
        ScoreBucket.MEDIUM -> colors.surfaceVariant to colors.onSurfaceVariant
        ScoreBucket.LOW -> colors.errorContainer to colors.onErrorContainer
        null -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = container,
        contentColor = content,
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 72.dp)
                .heightIn(min = 56.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                score?.let(RecipeDetailText::formatScore) ?: "–",
                style = MaterialTheme.typography.headlineSmall,
            )
        }
    }
}

/** A small rounded label such as "AI", "archived" or "New version". */
@Composable
fun LabelBadge(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(8.dp), color = container, contentColor = content) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * Numbered steps rendered with [values] (null = as written). Values that differ from the
 * written ones are shown in bold in the primary colour.
 */
@Composable
fun StepList(steps: List<Step>, values: List<Double>?, modifier: Modifier = Modifier) {
    val params = StepParser.params(steps)
    // Values that do not fit the steps are ignored rather than shown misplaced.
    val usable = values?.takeIf { it.size == params.size }
    val highlight = SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEachIndexed { index, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${index + 1}.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 32.dp),
                )
                Text(stepText(step, index, params, usable, highlight), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private fun stepText(
    step: Step,
    index: Int,
    params: List<ParamSpec>,
    values: List<Double>?,
    highlight: SpanStyle,
): AnnotatedString = when (step) {
    is Step.Text -> buildAnnotatedString {
        StepParser.segments(step, index, params, values).forEach { segment ->
            if (segment.changed) withStyle(highlight) { append(segment.text) } else append(segment.text)
        }
    }
    is Step.Wait -> {
        val text = "⏲ " + StepParser.render(step, index, params, values)
        val changed = values != null && StepParser.waitSeconds(step, index, params, values) != step.seconds
        if (changed) {
            buildAnnotatedString { withStyle(highlight) { append(text) } }
        } else {
            AnnotatedString(text)
        }
    }
}
