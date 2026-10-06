package com.lioravrahami.souschef.ui.recipe

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.lioravrahami.souschef.AppContainer

// PLACEHOLDER — replaced by the real implementation.
@Composable
fun RecipeDetailScreen(
    container: AppContainer,
    recipeId: String,
    onBack: () -> Unit,
    onNewVersion: (recipeId: String, baseVersionId: String?) -> Unit,
    onStartCooking: (trialId: String) -> Unit,
) {
    Text("Recipe $recipeId (placeholder)")
}
