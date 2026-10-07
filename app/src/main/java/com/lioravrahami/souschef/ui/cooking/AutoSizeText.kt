package com.lioravrahami.souschef.ui.cooking

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/** Font-size search used by [AutoSizeText]; pure so it can be unit-tested. */
object AutoSize {
    /** Each shrink step multiplies the font size by this factor. */
    const val SHRINK_FACTOR = 0.9f

    /**
     * The next smaller font size to try after [currentSp] overflowed, never below [minSp];
     * null when [currentSp] is already the minimum and nothing smaller may be tried.
     */
    fun nextSize(currentSp: Float, minSp: Float, factor: Float = SHRINK_FACTOR): Float? {
        if (currentSp <= minSp + 0.01f) return null
        return (currentSp * factor).coerceAtLeast(minSp)
    }

    /** Every size [AutoSizeText] may try, from [maxSp] down to [minSp]. */
    fun candidates(maxSp: Float, minSp: Float, factor: Float = SHRINK_FACTOR): List<Float> =
        generateSequence(maxSp.coerceAtLeast(minSp)) { nextSize(it, minSp, factor) }.toList()

    /** Line height for [fontSp] keeping the line-height / font-size ratio of the original style. */
    fun lineHeightFor(fontSp: Float, styleFontSp: Float, styleLineHeightSp: Float): Float =
        if (styleFontSp <= 0f || styleLineHeightSp <= 0f) fontSp * 1.2f else fontSp * styleLineHeightSp / styleFontSp
}

/**
 * Text that is as big as possible while still fitting its bounds: it starts at [maxStyle]
 * (`displaySmall` by default) and shrinks step by step via `onTextLayout` overflow checks
 * down to the font size of [minStyle] (`titleLarge`). Only the final size is drawn, so the
 * shrinking never flickers. If the text still overflows at the minimum it becomes vertically
 * scrollable instead of being cut off.
 *
 * The composable fills the available width and height; give it bounded constraints (for
 * example `Modifier.weight(1f)` inside a Column).
 */
@Composable
fun AutoSizeText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    maxStyle: TextStyle = MaterialTheme.typography.displaySmall,
    minStyle: TextStyle = MaterialTheme.typography.titleLarge,
    color: Color = MaterialTheme.colorScheme.onBackground,
    textAlign: TextAlign = TextAlign.Center,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val maxSp = maxStyle.fontSize.spOr(44f)
        val minSp = minStyle.fontSize.spOr(26f).coerceAtMost(maxSp)
        val ratioFont = maxStyle.fontSize.spOr(maxSp)
        val ratioLine = maxStyle.lineHeight.spOr(maxSp * 1.2f)
        // Restart the search whenever the text or the available space changes.
        var fontSp by remember(text, maxWidth, maxHeight, maxSp, minSp) { mutableFloatStateOf(maxSp) }
        var settled by remember(text, maxWidth, maxHeight, maxSp, minSp) { mutableStateOf(false) }
        var overflowAtMin by remember(text, maxWidth, maxHeight, maxSp, minSp) { mutableStateOf(false) }

        val style = maxStyle.copy(
            fontSize = fontSp.sp,
            lineHeight = AutoSize.lineHeightFor(fontSp, ratioFont, ratioLine).sp,
            color = color,
            textAlign = textAlign,
        )

        if (overflowAtMin) {
            Box(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(text = text, style = style, modifier = Modifier.fillMaxWidth())
            }
        } else {
            Text(
                text = text,
                style = style,
                overflow = TextOverflow.Clip,
                softWrap = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .drawWithContent { if (settled) drawContent() },
                onTextLayout = { result ->
                    if (result.hasVisualOverflow) {
                        val next = AutoSize.nextSize(fontSp, minSp)
                        if (next != null) {
                            fontSp = next
                        } else {
                            overflowAtMin = true
                            settled = true
                        }
                    } else {
                        settled = true
                    }
                },
            )
        }
    }
}

private fun TextUnit.spOr(fallback: Float): Float = if (isSpecified && isSp) value else fallback
