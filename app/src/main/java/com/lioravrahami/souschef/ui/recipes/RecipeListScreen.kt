package com.lioravrahami.souschef.ui.recipes

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lioravrahami.souschef.AppContainer
import com.lioravrahami.souschef.data.model.RecipeSummary
import com.lioravrahami.souschef.ui.TestTags
import com.lioravrahami.souschef.ui.components.BigButton
import com.lioravrahami.souschef.ui.components.BigOutlinedButton
import com.lioravrahami.souschef.ui.components.ScreenScaffold
import com.lioravrahami.souschef.ui.recipe.ConfirmDialog

/**
 * Home screen: the cooking-in-progress banner (if any), then one big card per recipe.
 * Tap a card to open the recipe, long-press it (or use its ⋮ button) to move it to the trash.
 */
@Composable
fun RecipeListScreen(
    container: AppContainer,
    onOpenRecipe: (recipeId: String) -> Unit,
    onNewRecipe: () -> Unit,
    onOpenSettings: () -> Unit,
    onResumeCooking: (trialId: String) -> Unit,
) {
    val vm: RecipeListViewModel = viewModel(
        factory = viewModelFactory { initializer { RecipeListViewModel(container) } },
    )
    val summaries by vm.summaries.collectAsStateWithLifecycle()
    val banner by vm.banner.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var trashCandidate by remember { mutableStateOf<RecipeSummary?>(null) }
    var abandonCandidate by remember { mutableStateOf<CookingBanner?>(null) }

    ScreenScaffold(
        title = "Sous Chef",
        actions = {
            IconButton(onClick = onOpenSettings, modifier = Modifier.testTag(TestTags.RECIPES_SETTINGS)) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewRecipe,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New recipe", style = MaterialTheme.typography.titleMedium) },
                modifier = Modifier.testTag(TestTags.RECIPES_NEW),
            )
        },
    ) { padding ->
        val list = summaries
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(64.dp))
            }
            list.isEmpty() -> Column(Modifier.padding(padding).fillMaxSize()) {
                banner?.let {
                    CookingBannerCard(
                        banner = it,
                        // dropUnlessResumed: a second tap during the navigation transition is ignored,
                        // so the back stack never gets the same screen twice.
                        onResume = dropUnlessResumed { onResumeCooking(it.trialId) },
                        onAbandon = { abandonCandidate = it },
                        modifier = Modifier.padding(16.dp),
                    )
                }
                EmptyState(onNewRecipe = onNewRecipe)
            }
            else -> {
                val shown = RecipeListText.filter(list, query)
                LazyColumn(
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    // Bottom padding keeps the last card clear of the floating button.
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    banner?.let { b ->
                        item(key = "banner") {
                            CookingBannerCard(
                                banner = b,
                                onResume = dropUnlessResumed { onResumeCooking(b.trialId) },
                                onAbandon = { abandonCandidate = b },
                            )
                        }
                    }
                    // Also shown while a query is set, so a filter left over from a longer list can be cleared.
                    if (RecipeListText.showSearch(list.size, query)) {
                        item(key = "search") { SearchField(query = query, onQueryChange = { query = it }) }
                    }
                    items(shown, key = { it.recipe.id }) { summary ->
                        RecipeCard(
                            summary = summary,
                            onOpen = dropUnlessResumed { onOpenRecipe(summary.recipe.id) },
                            onMoveToTrash = { trashCandidate = summary },
                        )
                    }
                    if (shown.isEmpty()) {
                        item(key = "no-match") {
                            Text(
                                "No recipe matches “${query.trim()}”.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    trashCandidate?.let { summary ->
        ConfirmDialog(
            title = "Move to trash?",
            message = "Move '${summary.recipe.name}' to the trash? You can restore it from Settings → Trash.",
            confirmLabel = "Move to trash",
            destructive = true,
            onConfirm = {
                vm.moveToTrash(summary.recipe.id)
                trashCandidate = null
            },
            onDismiss = { trashCandidate = null },
        )
    }
    abandonCandidate?.let { b ->
        ConfirmDialog(
            title = "Abandon cooking?",
            message = "Stop this cooking session without rating it? It will not count for the optimizer.",
            confirmLabel = "Abandon",
            destructive = true,
            onConfirm = {
                vm.abandon(b.trialId)
                abandonCandidate = null
            },
            onDismiss = { abandonCandidate = null },
        )
    }
}

/** The prominent "Cooking in progress" card with Resume / Abandon. */
@Composable
private fun CookingBannerCard(
    banner: CookingBanner,
    onResume: () -> Unit,
    onAbandon: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(banner.text, style = MaterialTheme.typography.titleLarge)
            BigButton(
                text = "Resume",
                onClick = onResume,
                modifier = Modifier.testTag(TestTags.RECIPES_RESUME),
            )
            BigOutlinedButton(text = "Abandon", onClick = onAbandon)
        }
    }
}

/** One recipe: name, stats and when it was last cooked. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecipeCard(
    summary: RecipeSummary,
    onOpen: () -> Unit,
    onMoveToTrash: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val shape = CardDefaults.shape
    Box {
        Card(
            shape = shape,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .combinedClickable(
                    onClickLabel = "Open recipe",
                    onLongClickLabel = "More options",
                    onClick = onOpen,
                    onLongClick = { menuOpen = true },
                )
                .testTag(TestTags.RECIPE_CARD),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp)
                    .padding(start = 20.dp, top = 16.dp, bottom = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        summary.recipe.name.ifBlank { "Untitled recipe" },
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        RecipeListText.statsLine(summary),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    summary.lastCookedAt?.let { at ->
                        Text(
                            "last cooked " + DateUtils.getRelativeTimeSpanString(
                                at,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options for ${summary.recipe.name}")
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Move to trash", style = MaterialTheme.typography.titleMedium) },
                onClick = {
                    menuOpen = false
                    onMoveToTrash()
                },
                modifier = Modifier.heightIn(min = 56.dp),
            )
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium,
        placeholder = { Text("Find a recipe", style = MaterialTheme.typography.titleMedium) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                }
            }
        },
    )
}

/** First-run explanation of the bracket format and a big "create" button. */
@Composable
private fun EmptyState(onNewRecipe: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("No recipes yet", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Write each step the way you would say it. Put the amounts and times you want to " +
                "fine-tune as a number followed by its unit in square brackets:",
            style = MaterialTheme.typography.bodyLarge,
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Add 1.75[cups] water and 1/2[tsp] salt", style = MaterialTheme.typography.titleMedium)
                Text("Bake for 20[min] at 180[°C]", style = MaterialTheme.typography.titleMedium)
            }
        }
        Text(
            "Sous Chef walks you through the recipe one big page at a time, runs reliable timers " +
                "for wait steps, and after each cooking asks how it went — then suggests small " +
                "tweaks to those numbers to make the next one better. Lock any number you never " +
                "want changed.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        BigButton(text = "Create your first recipe", onClick = onNewRecipe)
        Spacer(Modifier.height(96.dp))
    }
}
