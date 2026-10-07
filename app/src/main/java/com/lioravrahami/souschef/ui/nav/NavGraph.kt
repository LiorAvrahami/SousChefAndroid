package com.lioravrahami.souschef.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lioravrahami.souschef.AppContainer
import com.lioravrahami.souschef.ui.cooking.CookingScreen
import com.lioravrahami.souschef.ui.editor.RecipeEditorScreen
import com.lioravrahami.souschef.ui.rating.RatingScreen
import com.lioravrahami.souschef.ui.recipe.RecipeDetailScreen
import com.lioravrahami.souschef.ui.recipes.RecipeListScreen
import com.lioravrahami.souschef.ui.settings.SettingsScreen
import com.lioravrahami.souschef.ui.settings.TrashScreen

object Routes {
    const val RECIPES = "recipes"
    const val RECIPE = "recipe/{recipeId}"
    const val EDITOR = "editor?recipeId={recipeId}&versionId={versionId}"
    const val COOK = "cook/{trialId}"
    const val RATE = "rate/{trialId}"
    const val SETTINGS = "settings"
    const val TRASH = "trash"

    fun recipe(recipeId: String) = "recipe/$recipeId"
    fun editor(recipeId: String? = null, versionId: String? = null) =
        "editor?recipeId=${recipeId ?: ""}&versionId=${versionId ?: ""}"
    fun cook(trialId: String) = "cook/$trialId"
    fun rate(trialId: String) = "rate/$trialId"
}

@Composable
fun SousChefNavGraph(
    container: AppContainer,
    pendingTrialId: String?,
    onPendingTrialConsumed: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    LaunchedEffect(pendingTrialId) {
        if (pendingTrialId != null) {
            navController.navigate(Routes.cook(pendingTrialId)) { launchSingleTop = true }
            onPendingTrialConsumed()
        }
    }

    /**
     * Shows [recipeId] after leaving the screen whose route pattern is [leaving]: that screen
     * is popped, and if the recipe screen is now on top it is reused. Nothing else on the
     * back stack is touched, so an editor with unsaved changes that happened to be open
     * underneath (for example when a timer notification opened the cooking screen on top
     * of it) is never discarded.
     */
    fun showRecipeAfter(leaving: String, recipeId: String) {
        navController.navigate(Routes.recipe(recipeId)) {
            popUpTo(leaving) { inclusive = true }
            launchSingleTop = true
        }
    }

    NavHost(navController = navController, startDestination = Routes.RECIPES) {
        composable(Routes.RECIPES) {
            RecipeListScreen(
                container = container,
                onOpenRecipe = { navController.navigate(Routes.recipe(it)) { launchSingleTop = true } },
                onNewRecipe = { navController.navigate(Routes.editor()) { launchSingleTop = true } },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                onResumeCooking = { navController.navigate(Routes.cook(it)) { launchSingleTop = true } },
            )
        }
        composable(
            Routes.RECIPE,
            arguments = listOf(navArgument("recipeId") { type = NavType.StringType }),
        ) { entry ->
            val recipeId = entry.arguments?.getString("recipeId").orEmpty()
            RecipeDetailScreen(
                container = container,
                recipeId = recipeId,
                onBack = { navController.popBackStack() },
                onNewVersion = { rId, vId -> navController.navigate(Routes.editor(rId, vId)) { launchSingleTop = true } },
                onStartCooking = { trialId -> navController.navigate(Routes.cook(trialId)) { launchSingleTop = true } },
            )
        }
        composable(
            Routes.EDITOR,
            arguments = listOf(
                navArgument("recipeId") { type = NavType.StringType; defaultValue = "" },
                navArgument("versionId") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val recipeId = entry.arguments?.getString("recipeId")?.takeIf { it.isNotBlank() }
            val versionId = entry.arguments?.getString("versionId")?.takeIf { it.isNotBlank() }
            RecipeEditorScreen(
                container = container,
                recipeId = recipeId,
                baseVersionId = versionId,
                onSaved = { savedRecipeId -> showRecipeAfter(Routes.EDITOR, savedRecipeId) },
                onCancel = { navController.popBackStack() },
            )
        }
        composable(
            Routes.COOK,
            arguments = listOf(navArgument("trialId") { type = NavType.StringType }),
        ) { entry ->
            val trialId = entry.arguments?.getString("trialId").orEmpty()
            CookingScreen(
                container = container,
                trialId = trialId,
                onFinished = { tId ->
                    navController.navigate(Routes.rate(tId)) {
                        popUpTo(Routes.COOK) { inclusive = true }
                    }
                },
                onExit = { recipeId -> showRecipeAfter(Routes.COOK, recipeId) },
            )
        }
        composable(
            Routes.RATE,
            arguments = listOf(navArgument("trialId") { type = NavType.StringType }),
        ) { entry ->
            val trialId = entry.arguments?.getString("trialId").orEmpty()
            RatingScreen(
                container = container,
                trialId = trialId,
                onDone = { recipeId -> showRecipeAfter(Routes.RATE, recipeId) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                container = container,
                onBack = { navController.popBackStack() },
                onOpenTrash = { navController.navigate(Routes.TRASH) { launchSingleTop = true } },
            )
        }
        composable(Routes.TRASH) {
            TrashScreen(
                container = container,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

@Suppress("unused")
private fun NavHostController.goHome() {
    navigate(Routes.RECIPES) {
        popUpTo(graph.findStartDestination().id) { inclusive = false }
        launchSingleTop = true
    }
}
