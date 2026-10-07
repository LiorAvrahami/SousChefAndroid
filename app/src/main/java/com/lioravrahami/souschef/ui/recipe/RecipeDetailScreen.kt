package com.lioravrahami.souschef.ui.recipe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lioravrahami.souschef.AppContainer
import com.lioravrahami.souschef.data.model.RecipeDetails
import com.lioravrahami.souschef.data.model.Trial
import com.lioravrahami.souschef.ui.TestTags
import com.lioravrahami.souschef.ui.components.BigButton
import com.lioravrahami.souschef.ui.components.BigOutlinedButton
import com.lioravrahami.souschef.ui.components.ScreenScaffold

/**
 * One recipe: the two ways to start cooking (best so far / explore), its versions, the
 * history of cookings and the recipe notes. Every cooking starts through the proposal sheet.
 */
@Composable
fun RecipeDetailScreen(
    container: AppContainer,
    recipeId: String,
    onBack: () -> Unit,
    onNewVersion: (recipeId: String, baseVersionId: String?) -> Unit,
    onStartCooking: (trialId: String) -> Unit,
) {
    val vm: RecipeDetailViewModel = viewModel(
        key = "recipe-detail:$recipeId",
        factory = viewModelFactory { initializer { RecipeDetailViewModel(container, recipeId) } },
    )
    val content by vm.content.collectAsStateWithLifecycle()
    val inProgress by vm.inProgress.collectAsStateWithLifecycle()
    val proposal by vm.proposal.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val settingsTick by container.settings.changes.collectAsStateWithLifecycle()

    val latestOnStartCooking by rememberUpdatedState(onStartCooking)
    val latestOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is DetailEvent.StartCooking -> latestOnStartCooking(event.trialId)
                DetailEvent.Closed -> latestOnBack()
            }
        }
    }

    var showChooser by rememberSaveable { mutableStateOf(false) }
    var confirmTrash by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val details = (content as? DetailContent.Loaded)?.details

    ScreenScaffold(
        title = details?.recipe?.name ?: "Recipe",
        onBack = onBack,
        actions = {
            if (details != null) {
                IconButton(
                    onClick = { onNewVersion(recipeId, details.latestVersion()?.id) },
                    modifier = Modifier.testTag(TestTags.RECIPE_EDIT),
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit recipe")
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Move to trash", style = MaterialTheme.typography.titleMedium) },
                            onClick = {
                                menuOpen = false
                                confirmTrash = true
                            },
                            modifier = Modifier.heightIn(min = 56.dp),
                        )
                    }
                }
            }
        },
    ) { padding ->
        when (val c = content) {
            DetailContent.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(64.dp))
            }
            DetailContent.NotFound -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Text("Recipe not found", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "It may have been moved to the trash. You can restore it from Settings → Trash.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                BigButton(text = "Back", onClick = onBack)
            }
            is DetailContent.Loaded -> DetailBody(
                details = c.details,
                inProgress = inProgress,
                modifier = Modifier.padding(padding),
                vm = vm,
                onExplore = { showChooser = true },
                onResume = onStartCooking,
                onNewVersion = { versionId -> onNewVersion(recipeId, versionId) },
            )
        }
    }

    if (showChooser) {
        val hasApiKey = remember(settingsTick) { container.settings.hasApiKey }
        ExploreChooserDialog(
            hasApiKey = hasApiKey,
            onClassical = {
                showChooser = false
                vm.exploreClassical()
            },
            onAi = {
                showChooser = false
                vm.exploreAi()
            },
            onDismiss = { showChooser = false },
        )
    }

    when (val p = proposal) {
        ProposalState.Idle -> Unit
        ProposalState.Loading -> AiLoadingDialog(onCancel = vm::cancelLoading)
        is ProposalState.Message -> InfoDialog(p.title, p.text, onDismiss = vm::dismissProposal, isError = p.isError)
        is ProposalState.Ready -> {
            ProposalSheet(
                state = p,
                onCook = vm::cook,
                onAnother = vm::anotherSuggestion,
                onCancel = vm::dismissProposal,
            )
            if (p.confirmAbandon) {
                ConfirmDialog(
                    title = "Cooking in progress",
                    message = "A cooking session is in progress. Starting a new one abandons it.",
                    confirmLabel = "Continue",
                    onConfirm = vm::confirmAbandonAndCook,
                    onDismiss = vm::dismissAbandonWarning,
                    destructive = true,
                )
            }
        }
    }

    notice?.let { InfoDialog(title = null, message = it, onDismiss = vm::dismissNotice) }

    if (confirmTrash && details != null) {
        ConfirmDialog(
            title = "Move to trash?",
            message = "Move '${details.recipe.name}' to the trash? You can restore it from Settings → Trash.",
            confirmLabel = "Move to trash",
            destructive = true,
            onConfirm = {
                confirmTrash = false
                vm.moveToTrash()
            },
            onDismiss = { confirmTrash = false },
        )
    }
}

@Composable
private fun DetailBody(
    details: RecipeDetails,
    inProgress: Trial?,
    vm: RecipeDetailViewModel,
    onExplore: () -> Unit,
    onResume: (trialId: String) -> Unit,
    onNewVersion: (versionId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (inProgress != null && inProgress.recipeId == details.recipe.id) {
            ResumeCard(onResume = { onResume(inProgress.id) })
        }
        if (details.versions.isEmpty()) {
            NoVersionsCard(onEdit = { onNewVersion(null) })
        } else {
            StartCookingCard(details = details, onCookBest = vm::cookBest, onExplore = onExplore, vm = vm)
            VersionsSection(details = details, vm = vm, onNewVersion = onNewVersion)
        }
        HistorySection(details = details, onMakeVersion = vm::makeVersionFromTrial)
        if (details.recipe.notes.isNotBlank()) {
            Section(title = "Notes") {
                Text(details.recipe.notes.trim(), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ResumeCard(onResume: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("You are cooking this recipe right now.", style = MaterialTheme.typography.titleLarge)
            BigButton(text = "Resume cooking", onClick = onResume)
        }
    }
}

@Composable
private fun NoVersionsCard(onEdit: () -> Unit) {
    Section(title = "No steps saved") {
        Text(
            "This recipe has no version with steps — its data may be incomplete. Open the editor to write the steps again.",
            style = MaterialTheme.typography.bodyLarge,
        )
        BigButton(text = "Edit recipe", onClick = onEdit)
    }
}

@Composable
private fun StartCookingCard(
    details: RecipeDetails,
    onCookBest: () -> Unit,
    onExplore: () -> Unit,
    vm: RecipeDetailViewModel,
) {
    val subtitle = remember(details) {
        val best = runCatching { vm.bestProposal(details) }.getOrNull()
        val trial = best?.let { RecipeDetailText.bestTrialFor(details, it) }
        RecipeDetailText.bestSubtitle(trial, details.doneTrials.isNotEmpty(), RecipeDetailText::formatDate)
    }
    Section(title = "Start cooking") {
        BigButton(
            text = "Cook the best so far",
            onClick = onCookBest,
            modifier = Modifier
                .heightIn(min = 80.dp)
                .testTag(TestTags.COOK_BEST),
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
        )
        BigOutlinedButton(
            text = "Explore — try a tweak",
            onClick = onExplore,
            modifier = Modifier
                .heightIn(min = 80.dp)
                .testTag(TestTags.COOK_EXPLORE),
        )
        Text(
            "Gather information for the optimizer",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun VersionsSection(
    details: RecipeDetails,
    vm: RecipeDetailViewModel,
    onNewVersion: (versionId: String?) -> Unit,
) {
    SectionTitle("Versions")
    RecipeDetailText.versionsNewestFirst(details).forEach { version ->
        key(version.id) {
            val done = details.trialsOf(version.id)
            VersionCard(
                version = version,
                cookings = done.size,
                bestScore = done.mapNotNull { it.overallScore }.maxOrNull(),
                onCookAsWritten = { vm.cookAsWritten(version) },
                onEdit = { onNewVersion(version.id) },
                onToggleArchive = { vm.toggleArchive(version) },
            )
        }
    }
}

@Composable
private fun HistorySection(details: RecipeDetails, onMakeVersion: (Trial) -> Unit) {
    val history = RecipeDetailText.history(details)
    val abandoned = RecipeDetailText.abandonedCount(details)
    if (history.isEmpty() && abandoned == 0) return
    SectionTitle("History")
    if (history.isEmpty()) {
        Text(
            "No finished cookings yet.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    history.forEach { trial ->
        key(trial.id) {
            TrialCard(
                trial = trial,
                version = details.version(trial.versionId),
                axes = details.axes,
                onMakeVersion = { onMakeVersion(trial) },
            )
        }
    }
    if (abandoned > 0) {
        Text(
            "$abandoned abandoned",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
}

/** A titled card in the style of the settings screen. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}
