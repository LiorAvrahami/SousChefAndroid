package com.lioravrahami.souschef

import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.domain.recipe.StepParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepParserTest {
    private val steps = listOf(
        Step.Text("Add 1.75[cups] water and 3 [shakes] of salt", locked = listOf(1)),
        Step.Wait(label = "Microwave", seconds = 900),
        Step.Text("Stir in 1,5[spoons] of soup powder"),
        Step.Text("No numbers here"),
    )

    @Test
    fun extractsParametersInOrder() {
        val params = StepParser.params(steps)
        assertEquals(4, params.size)
        assertEquals(1.75, params[0].baseValue, 1e-9)
        assertEquals("cups", params[0].unit)
        assertFalse(params[0].locked)
        assertEquals(3.0, params[1].baseValue, 1e-9)
        assertEquals("shakes", params[1].unit)
        assertTrue(params[1].locked)
        assertTrue(params[2].isWait)
        assertEquals(900.0, params[2].baseValue, 1e-9)
        assertEquals(1.5, params[3].baseValue, 1e-9)
        assertEquals(listOf(0, 0, 1, 2), params.map { it.stepIndex })
    }

    @Test
    fun rendersWithSubstitutedValues() {
        val params = StepParser.params(steps)
        val values = listOf(2.0, 3.0, 600.0, 1.25)
        assertEquals("Add 2 cups water and 3 shakes of salt", StepParser.render(steps[0], 0, params, values))
        assertEquals("Microwave — 10 min", StepParser.render(steps[1], 1, params, values))
        assertEquals("Stir in 1.25 spoons of soup powder", StepParser.render(steps[2], 2, params, values))
        assertEquals("No numbers here", StepParser.render(steps[3], 3, params, values))
    }

    @Test
    fun rendersBaseValuesWhenNull() {
        val params = StepParser.params(steps)
        assertEquals("Add 1.75 cups water and 3 shakes of salt", StepParser.render(steps[0], 0, params, null))
        assertEquals("Microwave — 15 min", StepParser.render(steps[1], 1, params, null))
    }

    @Test
    fun segmentsFlagChangedValues() {
        val params = StepParser.params(steps)
        val segs = StepParser.segments(steps[0] as Step.Text, 0, params, listOf(2.0, 3.0, 900.0, 1.5))
        val paramSegs = segs.filter { it.paramIndex != null }
        assertEquals(2, paramSegs.size)
        assertTrue(paramSegs[0].changed)
        assertFalse(paramSegs[1].changed)
    }

    @Test
    fun applyValuesWritesNumbersBack() {
        val applied = StepParser.applyValues(steps, listOf(2.0, 3.0, 600.0, 1.25))
        // Whitespace between the number and the unit is normalized away.
        assertEquals("Add 2[cups] water and 3[shakes] of salt", (applied[0] as Step.Text).text)
        assertEquals(600, (applied[1] as Step.Wait).seconds)
        assertEquals("Stir in 1.25[spoons] of soup powder", (applied[2] as Step.Text).text)
        assertEquals(listOf(1), (applied[0] as Step.Text).locked)
    }

    @Test
    fun formatting() {
        assertEquals("2", StepParser.formatValue(2.0))
        assertEquals("1.75", StepParser.formatValue(1.75))
        assertEquals("0.5", StepParser.formatValue(0.5))
        assertEquals("1 h 05 min", StepParser.formatDuration(3900))
        assertEquals("12 min 30 s", StepParser.formatDuration(750))
        assertEquals("45 s", StepParser.formatDuration(45))
        assertEquals("2 h", StepParser.formatDuration(7200))
        assertEquals("12:30", StepParser.formatClock(750))
        assertEquals("1:05:00", StepParser.formatClock(3900))
        assertEquals("0:05", StepParser.formatClock(5))
    }

    @Test
    fun parsesFractionsMixedNumbersAndThousands() {
        fun values(text: String) = StepParser.paramsInText(text).map { it.first }
        assertEquals(listOf(0.5), values("Add 1/2[cup] milk"))
        assertEquals(listOf(1.5), values("Add 1 1/2[cups] flour"))
        assertEquals(listOf(2.25, 3.0), values("2 1/4[tsp] yeast, then 3[eggs]"))
        assertEquals(listOf(1000.0), values("1,000[g] potatoes"))
        assertEquals(listOf(12500.5), values("12,500.5[g] of something huge"))
        assertEquals(listOf(1.5), values("1,5[l] water"))
        assertEquals(listOf(1.75), values("1,75[l] water"))
        // "step 3: ..." must not swallow the step number into a mixed number
        assertEquals(listOf(0.5), values("step 3: add 1/2[cup]"))
        // a typo'd zero denominator never produces infinity
        assertEquals(listOf(1.0), values("1/0[cup]"))
        // numbers without brackets are plain text
        assertEquals(emptyList<Double>(), values("Bake at 180 degrees for 20 minutes"))
    }

    @Test
    fun fractionsRenderAsDecimalsAndRoundTrip() {
        val steps = listOf(Step.Text("Add 1 1/2[cups] flour and 1,000[g] water"))
        val params = StepParser.params(steps)
        assertEquals(listOf(1.5, 1000.0), params.map { it.baseValue })
        assertEquals("Add 1.5 cups flour and 1000 g water", StepParser.render(steps[0], 0, params, null))
        val applied = StepParser.applyValues(steps, listOf(1.75, 900.0))
        assertEquals("Add 1.75[cups] flour and 900[g] water", (applied[0] as Step.Text).text)
        assertEquals(listOf(1.75, 900.0), StepParser.baseValues(applied))
    }

    @Test
    fun roundingKeepsSensiblePrecision() {
        assertEquals(1.23, StepParser.round(1.2345), 1e-9)
        assertEquals(12.3, StepParser.round(12.345), 1e-9)
        assertEquals(123.0, StepParser.round(123.45), 1e-9)
    }
}
