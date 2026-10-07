package com.lioravrahami.souschef.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lioravrahami.souschef.AppContainer
import com.lioravrahami.souschef.data.model.RatingAxis
import com.lioravrahami.souschef.data.model.Recipe
import com.lioravrahami.souschef.data.model.RecipeVersion
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.data.model.VersionOrigin
import com.lioravrahami.souschef.data.model.newId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A step being edited in the step editor. [index] is the step's position, or null for a new
 * step that is appended on Done. [step] is the step as it was when editing started.
 */
data class StepEdit(val index: Int?, val step: Step)

/** Shown when saving changed steps of an existing recipe: they become a new version. */
data class VersionPrompt(val suggestedName: String)

/** Everything the recipe editor screen shows. */
data class EditorUiState(
    val isNew: Boolean,
    val loading: Boolean = false,
    /** Set when the recipe to edit could not be loaded; the editor cannot be used. */
    val loadError: String? = null,
    val name: String = "",
    val notes: String = "",
    val customAxes: List<RatingAxis> = emptyList(),
    val steps: List<Step> = emptyList(),
    /** True when anything differs from what was loaded (or from an empty recipe). */
    val dirty: Boolean = false,
    /** Name of the version the steps started from (existing recipes only). */
    val baseVersionName: String? = null,
    val editing: StepEdit? = null,
    val versionPrompt: VersionPrompt? = null,
    val saving: Boolean = false,
    /** A problem to show the user (validation or a failed save). */
    val error: String? = null,
    /** Set once everything is stored; the screen then leaves with this recipe id. */
    val savedRecipeId: String? = null,
)

/**
 * State holder of the recipe editor. Creates a new recipe when [recipeId] is null; otherwise
 * edits that recipe with steps starting from [baseVersionId] (or the latest version).
 */
class RecipeEditorViewModel(
    private val container: AppContainer,
    private val recipeId: String?,
    private val baseVersionId: String?,
) : ViewModel() {
    private val repository get() = container.repository

    /** The parts of the state that make the editor "dirty" when changed. */
    private data class Content(
        val name: String,
        val notes: String,
        val customAxes: List<RatingAxis>,
        val steps: List<Step>,
    )

    private val _state = MutableStateFlow(EditorUiState(isNew = recipeId == null, loading = recipeId != null))
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private var recipe: Recipe? = null
    private var baseVersion: RecipeVersion? = null
    private var versionCount = 0
    private var initial = Content("", "", emptyList(), emptyList())

    init {
        if (recipeId != null) viewModelScope.launch { load(recipeId) }
    }

    private suspend fun load(id: String) {
        val details = try {
            repository.getDetails(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, loadError = "Could not open the recipe: ${e.message}") }
            return
        }
        if (details == null) {
            _state.update { it.copy(loading = false, loadError = "This recipe no longer exists.") }
            return
        }
        val base = baseVersionId?.let(details::version) ?: details.latestVersion()
        recipe = details.recipe
        baseVersion = base
        versionCount = details.versions.size
        initial = Content(details.recipe.name, details.recipe.notes, details.recipe.customAxes, base?.steps.orEmpty())
        _state.update {
            it.copy(
                loading = false,
                name = initial.name,
                notes = initial.notes,
                customAxes = initial.customAxes,
                steps = initial.steps,
                baseVersionName = base?.name,
                dirty = false,
            )
        }
    }

    private fun EditorUiState.content() = Content(name, notes, customAxes, steps)

    /** Applies an edit of the recipe content and recomputes [EditorUiState.dirty]. */
    private fun edit(block: (EditorUiState) -> EditorUiState) {
        _state.update { old -> block(old).let { it.copy(dirty = it.content() != initial) } }
    }

    /** Updates the recipe name as typed. */
    fun setName(name: String) = edit { it.copy(name = name) }

    /** Updates the free-text recipe notes. */
    fun setNotes(notes: String) = edit { it.copy(notes = notes) }

    /** Adds an empty custom rating axis to fill in. */
    fun addAxis() = edit { it.copy(customAxes = it.customAxes + RatingAxis(newId(), "", "")) }

    /** Sets both labels of the custom axis [id]. */
    fun updateAxis(id: String, lowLabel: String, highLabel: String) = edit { s ->
        s.copy(customAxes = s.customAxes.map { if (it.id == id) it.copy(lowLabel = lowLabel, highLabel = highLabel) else it })
    }

    /** Removes the custom axis [id]. */
    fun removeAxis(id: String) = edit { s -> s.copy(customAxes = s.customAxes.filterNot { it.id == id }) }

    /** Opens the text-step editor for a new step. */
    fun startAddText() = _state.update { it.copy(editing = StepEdit(null, Step.Text(""))) }

    /** Opens the wait-step editor for a new 5-minute wait the optimizer may change. */
    fun startAddWait() = _state.update {
        it.copy(editing = StepEdit(null, Step.Wait(label = "", seconds = EditorRules.NEW_WAIT_SECONDS, locked = false)))
    }

    /** Opens the matching editor for the step at [index]. */
    fun startEdit(index: Int) = _state.update { s ->
        s.steps.getOrNull(index)?.let { s.copy(editing = StepEdit(index, it)) } ?: s
    }

    /** Closes the step editor without changing the steps. */
    fun cancelEdit() = _state.update { it.copy(editing = null) }

    /** Stores the edited [step] (replacing the edited one, or appending a new one). */
    fun commitEdit(step: Step) = edit { s ->
        val target = s.editing ?: return@edit s
        val steps = when (val i = target.index) {
            null -> s.steps + step
            else -> s.steps.toMutableList().also { if (i in it.indices) it[i] = step }
        }
        s.copy(steps = steps, editing = null)
    }

    /** Moves the step at [index] by [delta] positions (-1 = up, +1 = down). */
    fun moveStep(index: Int, delta: Int) = edit { s ->
        val to = index + delta
        if (index !in s.steps.indices || to !in s.steps.indices) return@edit s
        val steps = s.steps.toMutableList()
        steps.add(to, steps.removeAt(index))
        s.copy(steps = steps)
    }

    /** Removes the step at [index]. */
    fun deleteStep(index: Int) = edit { s ->
        if (index !in s.steps.indices) s else s.copy(steps = s.steps.filterIndexed { i, _ -> i != index })
    }

    /** Appends the steps of a pasted recipe (see [EditorRules.parsePastedSteps]). */
    fun appendSteps(steps: List<Step>) = edit { it.copy(steps = it.steps + steps) }

    /** Dismisses the current error message. */
    fun clearError() = _state.update { it.copy(error = null) }

    /**
     * Validates and saves. New recipes are created right away. For an existing recipe whose
     * steps differ from the base version, a [VersionPrompt] is raised first; the save then
     * continues in [confirmVersion].
     */
    fun save() {
        val s = _state.value
        if (s.loading || s.saving || s.loadError != null) return
        EditorRules.validate(s.name, s.steps, s.customAxes)?.let { problem ->
            _state.update { it.copy(error = problem) }
            return
        }
        when {
            s.isNew -> persist(versionName = null, versionNote = "")
            !s.dirty -> _state.update { it.copy(savedRecipeId = recipeId) }
            s.steps != baseVersion?.steps ->
                _state.update { it.copy(versionPrompt = VersionPrompt(EditorRules.nextVersionName(versionCount))) }
            else -> persist(versionName = null, versionNote = "")
        }
    }

    /** Saves with the steps stored as a new version called [name] with an optional [note]. */
    fun confirmVersion(name: String, note: String) {
        if (_state.value.versionPrompt == null) return
        persist(versionName = name.trim(), versionNote = note.trim())
    }

    /** Closes the version-name prompt without saving. */
    fun dismissVersionPrompt() = _state.update { it.copy(versionPrompt = null) }

    private fun persist(versionName: String?, versionNote: String) {
        val s = _state.value.copy(versionPrompt = null, saving = true, error = null)
        _state.value = s
        viewModelScope.launch {
            try {
                val axes = EditorRules.cleanAxes(s.customAxes)
                val name = s.name.trim()
                val notes = s.notes.trim()
                val savedId = if (recipeId == null) {
                    repository.createRecipe(name, s.steps, axes, notes).id
                } else {
                    val current = repository.getRecipe(recipeId) ?: recipe
                        ?: error("This recipe no longer exists.")
                    val updated = current.copy(name = name, notes = notes, customAxes = axes)
                    if (updated != current) repository.saveRecipe(updated)
                    if (s.steps != baseVersion?.steps) {
                        repository.addVersion(
                            recipeId = recipeId,
                            steps = s.steps,
                            name = versionName,
                            parentVersionId = baseVersion?.id,
                            origin = VersionOrigin.MANUAL,
                            note = versionNote,
                        )
                    }
                    recipeId
                }
                initial = s.content()
                _state.update { it.copy(saving = false, dirty = false, savedRecipeId = savedId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = "Could not save: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }
}
