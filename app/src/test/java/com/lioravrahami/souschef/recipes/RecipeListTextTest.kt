package com.lioravrahami.souschef.recipes

import com.lioravrahami.souschef.data.model.Recipe
import com.lioravrahami.souschef.data.model.RecipeSummary
import com.lioravrahami.souschef.ui.recipes.RecipeListText
import org.junit.Assert.assertEquals
import org.junit.Test

class RecipeListTextTest {
    private fun summary(
        name: String = "Pasta",
        versions: Int = 1,
        trials: Int = 0,
        best: Double? = null,
    ) = RecipeSummary(Recipe(name = name), versions, trials, best, null)

    @Test
    fun statsLineFullSummary() {
        assertEquals("best 8.5 · 4 cookings · 2 versions", RecipeListText.statsLine(summary(versions = 2, trials = 4, best = 8.5)))
    }

    @Test
    fun statsLineSingular() {
        assertEquals("best 7 · 1 cooking · 1 version", RecipeListText.statsLine(summary(trials = 1, best = 7.0)))
    }

    @Test
    fun statsLineNeverCooked() {
        assertEquals("never cooked · 1 version", RecipeListText.statsLine(summary()))
        assertEquals("never cooked", RecipeListText.statsLine(summary(versions = 0)))
    }

    @Test
    fun statsLineOmitsUnknownBest() {
        assertEquals("3 cookings · 1 version", RecipeListText.statsLine(summary(trials = 3, best = null)))
    }

    @Test
    fun bannerText() {
        assertEquals("Cooking in progress — Pasta, step 3 of 7", RecipeListText.bannerText("Pasta", 2, 7))
        // The final "done" page is one past the last step.
        assertEquals("Cooking in progress — Pasta, step 7 of 7", RecipeListText.bannerText("Pasta", 7, 7))
        assertEquals("Cooking in progress — Pasta", RecipeListText.bannerText("Pasta", 2, null))
        assertEquals("Cooking in progress — step 1 of 4", RecipeListText.bannerText(null, -3, 4))
        assertEquals("Cooking in progress", RecipeListText.bannerText(" ", 0, 0))
    }

    @Test
    fun filterMatchesAllWordsIgnoringCase() {
        val list = listOf(summary("Tomato soup"), summary("Pasta al pomodoro"), summary("Soupe à l'oignon"))
        assertEquals(list, RecipeListText.filter(list, "  "))
        assertEquals(listOf("Tomato soup", "Soupe à l'oignon"), RecipeListText.filter(list, "SOUP").map { it.recipe.name })
        assertEquals(listOf("Tomato soup"), RecipeListText.filter(list, "soup tom").map { it.recipe.name })
        assertEquals(emptyList<String>(), RecipeListText.filter(list, "cake").map { it.recipe.name })
    }
}
