package com.lioravrahami.souschef.editor

import com.lioravrahami.souschef.data.model.RatingAxis
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.domain.recipe.StepParser
import com.lioravrahami.souschef.ui.editor.EditorRules
import com.lioravrahami.souschef.ui.editor.chipText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorRulesTest {
    private fun params(text: String) = StepParser.paramsInText(text)

    @Test
    fun pastedLinesBecomeStepsWithoutBullets() {
        val text = """
            - Boil 1.75[cups] water
            * Add 3[shakes] of salt

            1. Stir
            2) Wait a bit
            • Serve
            (3) Eat
            1.5[cups] flour stays a number
        """.trimIndent()
        assertEquals(
            listOf(
                "Boil 1.75[cups] water",
                "Add 3[shakes] of salt",
                "Stir",
                "Wait a bit",
                "Serve",
                "Eat",
                "1.5[cups] flour stays a number",
            ),
            EditorRules.parsePastedSteps(text).map { it.text },
        )
        assertTrue(EditorRules.parsePastedSteps("  \n\n - \n").isEmpty())
    }

    @Test
    fun validationNeedsNameAndAStepWithContent() {
        val step = Step.Text("Boil water")
        assertNotNull(EditorRules.validate(" ", listOf(step), emptyList()))
        assertNotNull(EditorRules.validate("Soup", emptyList(), emptyList()))
        assertNotNull(EditorRules.validate("Soup", listOf(Step.Text("  ")), emptyList()))
        assertNull(EditorRules.validate("Soup", listOf(step), emptyList()))
        assertNull(EditorRules.validate("Soup", listOf(Step.Wait(seconds = 60)), emptyList()))
        assertNotNull(EditorRules.validate("Soup", listOf(Step.Wait(seconds = 0)), emptyList()))
    }

    @Test
    fun halfFilledAxesBlockSavingAndEmptyOnesAreDropped() {
        val step = listOf(Step.Text("Boil water"))
        assertNotNull(EditorRules.validate("Soup", step, listOf(RatingAxis("a", "Too sour", " "))))
        assertNull(EditorRules.validate("Soup", step, listOf(RatingAxis("a", "", ""))))
        val cleaned = EditorRules.cleanAxes(
            listOf(RatingAxis("a", " Too sour ", "Too sweet"), RatingAxis("b", " ", "")),
        )
        assertEquals(listOf(RatingAxis("a", "Too sour", "Too sweet")), cleaned)
    }

    @Test
    fun contentOfSteps() {
        assertTrue(EditorRules.hasContent(Step.Text("x")))
        assertFalse(EditorRules.hasContent(Step.Text(" ")))
        assertTrue(EditorRules.hasContent(Step.Wait(label = "Rest", seconds = 0)))
        assertFalse(EditorRules.hasContent(Step.Wait(seconds = 0)))
    }

    @Test
    fun versionNamesCountUp() {
        assertEquals("v1", EditorRules.nextVersionName(0))
        assertEquals("v4", EditorRules.nextVersionName(3))
    }

    @Test
    fun locksStayWhenANumberIsEdited() {
        val before = params("Add 1.75[cups] water and 3[shakes] of salt")
        val after = params("Add 1.9[cups] water and 3[shakes] of salt")
        assertEquals(listOf(0, 1), EditorRules.remapLocks(before, after, listOf(0, 1)))
    }

    @Test
    fun locksFollowTheirNumberWhenOneIsInsertedBefore() {
        val before = params("Add 3[shakes] of salt")
        val after = params("Add 2[cups] water and 3[shakes] of salt")
        assertEquals(listOf(1), EditorRules.remapLocks(before, after, listOf(0)))
    }

    @Test
    fun locksOfRemovedNumbersAreDroppedAndLaterOnesShift() {
        val before = params("1[a] 2[b] 3[c]")
        val after = params("1[a] 3[c]")
        assertEquals(listOf(0, 1), EditorRules.remapLocks(before, after, listOf(0, 1, 2)))
        assertEquals(emptyList<Int>(), EditorRules.remapLocks(before, after, listOf(1)))
    }

    @Test
    fun outOfRangeLocksAreDropped() {
        val p = params("1[a] 2[b]")
        assertEquals(listOf(1), EditorRules.remapLocks(p, p, listOf(1, 5, -1)))
        assertEquals(emptyList<Int>(), EditorRules.remapLocks(p, emptyList(), listOf(0, 1)))
    }

    @Test
    fun chipsShowHowEachNumberWasUnderstood() {
        val chips = StepParser.params(listOf(Step.Text("Add 1 1/2[cups] water, 1,000[g] flour and 2[] eggs")))
            .map(::chipText)
        assertEquals(listOf("1.5 cups water", "1000 g flour", "2 eggs"), chips)
    }
}
