package com.lioravrahami.souschef.ui.cooking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.domain.recipe.StepParser
import com.lioravrahami.souschef.ui.TestTags
import com.lioravrahami.souschef.ui.components.BigButton

/**
 * Page of a text step: the instruction, huge and centred, with this trial's amounts
 * highlighted (changed amounts are also underlined), a line listing what is different this
 * time, and big "Next" / small "Back" buttons in addition to swiping.
 */
@Composable
fun StepPage(
    session: CookingSession,
    stepIndex: Int,
    step: Step.Text,
    onNext: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val highlight = MaterialTheme.colorScheme.primary
    val text = remember(step, stepIndex, session.params, session.values, highlight) {
        stepAnnotatedString(step, stepIndex, session, highlight)
    }
    val changeLine = remember(session.changes, stepIndex) {
        CookingTexts.changeLine(session.changesForStep(stepIndex))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AutoSizeText(
            text = text,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
        if (changeLine != null) {
            Text(
                text = changeLine,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (onBack != null) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier
                        .heightIn(min = 64.dp)
                        .width(112.dp),
                ) { Text("Back", style = MaterialTheme.typography.titleLarge) }
            }
            BigButton(
                text = "Next",
                onClick = onNext,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 80.dp)
                    .testTag(TestTags.COOK_NEXT),
            )
        }
    }
}

/**
 * The step text with parameter segments in [highlight] and bold; segments whose value
 * differs from the version's written value are also underlined.
 */
internal fun stepAnnotatedString(
    step: Step.Text,
    stepIndex: Int,
    session: CookingSession,
    highlight: Color,
): AnnotatedString = buildAnnotatedString {
    StepParser.segments(step, stepIndex, session.params, session.values).forEach { segment ->
        if (segment.paramIndex == null) {
            append(segment.text)
        } else {
            val style = SpanStyle(
                color = highlight,
                fontWeight = FontWeight.ExtraBold,
                textDecoration = if (segment.changed) TextDecoration.Underline else null,
            )
            withStyle(style) { append(segment.text) }
        }
    }
}
