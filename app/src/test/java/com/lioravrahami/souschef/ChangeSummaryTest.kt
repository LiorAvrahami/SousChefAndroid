package com.lioravrahami.souschef

import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.domain.recipe.ChangeSummary
import com.lioravrahami.souschef.domain.recipe.StepParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeSummaryTest {
    private val steps = listOf(
        Step.Text("Add 1.75[cups] water and 3[shakes] of salt"),
        Step.Wait(label = "Microwave", seconds = 900),
        Step.Text("Bake at 180[°C] for 20[min], then add 2[] eggs"),
        Step.Wait(seconds = 60),
    )

    @Test
    fun parameterNamesComeFromTheWordsAfterTheUnit() {
        val names = StepParser.params(steps).map { it.name }
        assertEquals(listOf("water", "salt", "Microwave", "", "", "eggs", ""), names)
        val labels = StepParser.params(steps).map { it.label }
        assertEquals(listOf("water", "salt", "Microwave", "°C", "min", "eggs", "wait"), labels)
    }

    @Test
    fun namesStopAtClauseBreaksAndDropLeadingOf() {
        fun name(text: String) = StepParser.params(listOf(Step.Text(text))).first().name
        assertEquals("olive oil", name("Add 2[tbsp] olive oil to the pan"))
        assertEquals("soup-powder", name("1[spoon] of soup-powder"))
        assertEquals("all-purpose flour", name("1 1/2[cups] all-purpose flour, sifted"))
        assertEquals("", name("15[minuets] in the microwave"))
        assertEquals("very finely chopped", name("3[cloves] very finely chopped garlic cloves please"))
        assertEquals("", name("Stir for 5[min]"))
    }

    @Test
    fun describesChangesAgainstBaseValues() {
        val to = listOf(1.9, 3.0, 780.0, 170.0, 22.0, 3.0, 60.0)
        assertEquals(
            listOf(
                "water: 1.75 → 1.9 cups",
                "Microwave: 15 min → 13 min",
                "180 → 170 °C",
                "20 → 22 min",
                "eggs: 2 → 3",
            ),
            ChangeSummary.describe(steps, null, to),
        )
    }

    @Test
    fun describesChangesBetweenTwoVectors() {
        val from = listOf(2.0, 3.0, 900.0, 180.0, 20.0, 2.0, 60.0)
        val to = listOf(2.0, 4.0, 900.0, 180.0, 20.0, 2.0, 90.0)
        assertEquals(
            listOf("salt: 3 → 4 shakes", "wait: 1 min → 1 min 30 s"),
            ChangeSummary.describe(steps, from, to),
        )
        assertEquals(listOf(1, 6), ChangeSummary.changedIndices(from, to))
    }

    @Test
    fun unchangedAndMismatchedVectors() {
        val base = StepParser.baseValues(steps)
        assertTrue(ChangeSummary.changes(steps, null, base).isEmpty())
        assertEquals("as written", ChangeSummary.oneLine(steps, null, base))
        // A value vector of the wrong length is never described (and never crashes).
        assertTrue(ChangeSummary.changes(steps, null, listOf(1.0, 2.0)).isEmpty())
        // Sub-second differences of a wait are not a change.
        val almost = base.toMutableList().also { it[2] = 900.4 }
        assertTrue(ChangeSummary.changes(steps, null, almost).isEmpty())
    }

    @Test
    fun valueText() {
        val params = StepParser.params(steps)
        assertEquals("1.75 cups", ChangeSummary.valueText(params[0], 1.75))
        assertEquals("15 min", ChangeSummary.valueText(params[2], 900.0))
        assertEquals("3", ChangeSummary.valueText(params[5], 3.0))
    }
}
