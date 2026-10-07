package com.lioravrahami.souschef.ui.editor

import com.lioravrahami.souschef.data.model.RatingAxis
import com.lioravrahami.souschef.data.model.Step
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Unsaved work of the recipe editor, kept so that it survives process death (in the
 * ViewModel's SavedStateHandle) and an unexpected pop of the editor (in [EditorDraftStash]).
 *
 * @property editing the step editor that was open, if any.
 * @property baseVersionId the version the steps started from, as resolved when the editor
 *   first loaded (null for a new recipe or a recipe without versions).
 */
@Serializable
internal data class EditorDraft(
    val name: String,
    val notes: String,
    val customAxes: List<RatingAxis>,
    val steps: List<Step>,
    val editing: StepEdit? = null,
    val baseVersionId: String? = null,
)

/** JSON encoding of [EditorDraft]; decoding never throws. */
internal object EditorDrafts {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Encodes [draft] as a JSON string. */
    fun encode(draft: EditorDraft): String = json.encodeToString(EditorDraft.serializer(), draft)

    /** Decodes a string made by [encode]; null for null or unreadable input. */
    fun decode(text: String?): EditorDraft? {
        if (text.isNullOrBlank()) return null
        return try {
            json.decodeFromString(EditorDraft.serializer(), text)
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException.
            null
        }
    }
}

/**
 * In-process holding place for drafts whose editor was closed by navigation without the user
 * saving or discarding them (e.g. a cooking session that ends and jumps back to the recipe).
 * The next editor opened for the same recipe picks the draft up again.
 */
internal object EditorDraftStash {
    private val drafts = HashMap<String, EditorDraft>()

    /** Stash key for the editor of [recipeId] (null = a new recipe). */
    fun keyFor(recipeId: String?): String = recipeId ?: "<new>"

    /** Keeps [draft] for [key], replacing an older one. */
    @Synchronized
    fun put(key: String, draft: EditorDraft) {
        drafts[key] = draft
    }

    /** Removes and returns the draft kept for [key], if any. */
    @Synchronized
    fun take(key: String): EditorDraft? = drafts.remove(key)
}
