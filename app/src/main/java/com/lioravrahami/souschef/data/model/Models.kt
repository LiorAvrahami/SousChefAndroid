package com.lioravrahami.souschef.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

/**
 * One step of a recipe version.
 *
 * A recipe version is an ordered list of steps. Numbers followed by a unit in square
 * brackets inside a [Text] step (e.g. "1.75[cups] water") are *parameters* that the
 * optimizer may tweak. The duration of a [Wait] step is also a parameter.
 */
@Serializable
sealed class Step {
    /**
     * Free-text instruction such as "Add 1.75[cups] water and 3[shakes] of salt".
     * [locked] holds the indices (within this step, in order of appearance) of the
     * parameters the optimizer must never change.
     */
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
        val locked: List<Int> = emptyList(),
    ) : Step()

    /**
     * A wait with a reliable timer. [seconds] is a parameter unless [locked].
     * [label] is an optional description such as "Let the dough rise".
     */
    @Serializable
    @SerialName("wait")
    data class Wait(
        val label: String = "",
        val seconds: Int,
        val locked: Boolean = false,
    ) : Step()
}

/** A rating axis shown as a slider from -2 ([lowLabel]) to +2 ([highLabel]); 0 means "just right". */
@Serializable
data class RatingAxis(val id: String, val lowLabel: String, val highLabel: String)

object DefaultAxes {
    val all: List<RatingAxis> = listOf(
        RatingAxis("moisture", "Too dry", "Too wet"),
        RatingAxis("doneness", "Undercooked", "Burnt"),
        RatingAxis("salt", "Bland", "Too salty"),
    )
}

enum class VersionOrigin { MANUAL, AI, IMPORT }

enum class TrialStatus { IN_PROGRESS, DONE, ABORTED }

/** How a trial was started: best-so-far, classical exploration, AI suggestion, or a version cooked exactly as written. */
enum class TrialMode { BEST, EXPLORE, AI, AS_WRITTEN }

@Serializable
@Entity(tableName = "recipes")
data class Recipe(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Non-null while the recipe sits in the trash. */
    val deletedAt: Long? = null,
    /** Extra rating axes for this recipe, on top of [DefaultAxes]. */
    val customAxes: List<RatingAxis> = emptyList(),
    val notes: String = "",
)

/**
 * A structural variant of a recipe: its own list of steps. Different versions can differ
 * in wording, ingredient order, or added/removed steps. Within one version, trials differ
 * only in parameter values.
 */
@Serializable
@Entity(tableName = "versions", indices = [Index("recipeId")])
data class RecipeVersion(
    @PrimaryKey val id: String = newId(),
    val recipeId: String,
    val name: String,
    val steps: List<Step>,
    val parentVersionId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Archived versions are kept for history but never picked by the optimizer. */
    val archived: Boolean = false,
    val origin: VersionOrigin = VersionOrigin.MANUAL,
    val note: String = "",
)

/**
 * One cooking of a version with concrete parameter [values] (same order as
 * `StepParser.params(version.steps)`). While IN_PROGRESS it also carries the cooking
 * session state so the app can resume after being killed.
 */
@Serializable
@Entity(tableName = "trials", indices = [Index("recipeId"), Index("versionId"), Index("status")])
data class Trial(
    @PrimaryKey val id: String = newId(),
    val recipeId: String,
    val versionId: String,
    val values: List<Double>,
    val status: TrialStatus,
    val mode: TrialMode,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    /** 0..10, null until rated. */
    val overallScore: Double? = null,
    /** axis id -> -2..+2 */
    val axes: Map<String, Double> = emptyMap(),
    val notes: String = "",
    /** Why the optimizer / AI proposed these values. */
    val rationale: String = "",
    // ---- cooking session state (meaningful while IN_PROGRESS) ----
    val currentStep: Int = 0,
    val timerStepIndex: Int? = null,
    /** Absolute wall-clock millis when the running wait step ends. */
    val timerEndAt: Long? = null,
    val timerStartedAt: Long? = null,
)
