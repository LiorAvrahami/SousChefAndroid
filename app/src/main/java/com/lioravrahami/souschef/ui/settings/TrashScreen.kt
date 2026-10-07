package com.lioravrahami.souschef.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lioravrahami.souschef.AppContainer
import com.lioravrahami.souschef.data.model.Recipe
import com.lioravrahami.souschef.ui.components.BigButton
import com.lioravrahami.souschef.ui.components.ScreenScaffold
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the typed-confirmation dialog is about to delete forever. */
private sealed interface PendingDelete {
    data class One(val recipeId: String, val name: String) : PendingDelete
    data object All : PendingDelete
}

/**
 * Lists recipes in the trash. Each can be restored or deleted forever; "Empty trash"
 * deletes all of them. Permanent deletion always requires typing "delete".
 */
@Composable
fun TrashScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val repository = container.repository
    val trash by repository.observeTrash().collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<PendingDelete?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    // Refresh "deleted 5 minutes ago" texts while the screen is open.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }

    fun launchAction(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e.toString()
            }
        }
    }

    ScreenScaffold(title = "Trash", onBack = onBack) { inner ->
        val recipes = trash?.sortedByDescending { it.deletedAt ?: 0L }
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize(),
        ) {
            when {
                recipes == null -> Unit
                recipes.isEmpty() -> EmptyTrash(Modifier.weight(1f))
                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        item {
                            HelpText("Recipes stay here until you delete them forever. Restoring brings back every version and cooking.")
                        }
                        items(recipes, key = { it.id }) { recipe ->
                            TrashedRecipeCard(
                                recipe = recipe,
                                now = now,
                                onRestore = { launchAction { repository.restoreFromTrash(recipe.id) } },
                                onDeleteForever = { pending = PendingDelete.One(recipe.id, recipe.name) },
                            )
                        }
                    }
                    HorizontalDivider()
                    BigButton(
                        text = "Empty trash (${recipes.size})",
                        onClick = { pending = PendingDelete.All },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    when (val p = pending) {
        is PendingDelete.One -> TypedDeleteDialog(
            title = "Delete \"${p.name}\" forever?",
            message = "The recipe, all its versions and all its cookings will be erased from this phone.",
            confirmLabel = "Delete forever",
            onConfirm = {
                pending = null
                launchAction { repository.purge(p.recipeId) }
            },
            onDismiss = { pending = null },
        )
        PendingDelete.All -> TypedDeleteDialog(
            title = "Empty the trash?",
            message = "Every recipe in the trash (${trash?.size ?: 0}), with all versions and cookings, will be erased from this phone.",
            confirmLabel = "Empty trash",
            onConfirm = {
                pending = null
                launchAction { repository.emptyTrash() }
            },
            onDismiss = { pending = null },
        )
        null -> Unit
    }

    error?.let { message ->
        MessageDialog(title = "Something went wrong", message = message, onDismiss = { error = null })
    }
}

@Composable
private fun EmptyTrash(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            "The trash is empty.\nDeleted recipes show up here until you delete them forever.",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrashedRecipeCard(
    recipe: Recipe,
    now: Long,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(recipe.name, style = MaterialTheme.typography.headlineSmall)
            recipe.deletedAt?.let {
                Text(
                    "Deleted ${relativeTime(it, now)}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BigButton(text = "Restore", onClick = onRestore)
            OutlinedButton(
                onClick = onDeleteForever,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp),
            ) {
                Text("Delete forever", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
