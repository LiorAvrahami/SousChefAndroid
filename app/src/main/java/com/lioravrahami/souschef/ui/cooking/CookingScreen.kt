package com.lioravrahami.souschef.ui.cooking

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.lioravrahami.souschef.AppContainer

// PLACEHOLDER — replaced by the real implementation.
@Composable
fun CookingScreen(
    container: AppContainer,
    trialId: String,
    onFinished: (trialId: String) -> Unit,
    onExit: (recipeId: String) -> Unit,
) {
    Text("Cooking $trialId (placeholder)")
}
