package com.lioravrahami.souschef.domain.llm

import com.lioravrahami.souschef.data.model.RecipeDetails
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.data.settings.AppSettings
import com.lioravrahami.souschef.domain.llm.OpenRouterClient.LlmException
import com.lioravrahami.souschef.domain.optimizer.OptimizerSettings

/** What the AI proposes to cook next. */
sealed class LlmSuggestion {
    /** One line for the cook, e.g. "Use a little less water (toned down by the reviewer)". */
    abstract val summary: String

    /** Why, the concrete changes, and what the reviewing AI did; plain text ready to show. */
    abstract val rationale: String

    /** Same steps as [versionId], different parameter values. */
    data class Values(
        val versionId: String,
        val values: List<Double>,
        override val summary: String,
        override val rationale: String,
    ) : LlmSuggestion()

    /** A structural change (reordered / added / removed / reworded steps): becomes a new version. */
    data class NewVersion(
        val parentVersionId: String,
        val name: String,
        val steps: List<Step>,
        override val summary: String,
        override val rationale: String,
    ) : LlmSuggestion()
}

/**
 * Two-agent AI optimizer: a *suggester* proposes one tweak from the recipe's history
 * (versions, trials, scores, axis feedback, notes); a *checker* vets it and tones it down
 * if the change is too large or unsafe.
 *
 * Neither model is trusted: every proposal is validated in code ([ProposalValidator]) before
 * the checker sees it, and again after the checker revises it.
 */
class LlmOptimizer internal constructor(
    private val client: OpenRouterClient,
    private val model: () -> String,
) {

    /** Production optimizer: uses the model chosen in [settings] at the time of each call. */
    constructor(client: OpenRouterClient, settings: AppSettings) : this(client, { settings.openRouterModel })

    /**
     * Asks the AI for the next thing to cook. Makes two or three OpenRouter calls (suggester,
     * one retry if its reply is unusable, checker).
     *
     * Throws [OpenRouterClient.LlmException] with a readable message on failure.
     */
    suspend fun suggest(details: RecipeDetails, optimizerSettings: OptimizerSettings): LlmSuggestion {
        val context = RecipeContext(details)
        val boldness = optimizerSettings.boldness.takeIf { it.isFinite() }?.coerceIn(MIN_BOLDNESS, MAX_BOLDNESS)
            ?: DEFAULT_BOLDNESS
        val validator = ProposalValidator(context)
        val proposal = propose(context, boldness, validator)
        return review(context, boldness, validator, proposal)
    }

    /** Suggester call, retried once when the reply is unparseable or breaks a rule. */
    private suspend fun propose(context: RecipeContext, boldness: Double, validator: ProposalValidator): ValidProposal {
        val userPrompt = LlmPrompts.suggesterUser(context, boldness)
        var lastError: LlmException? = null
        for (attempt in 0 until SUGGESTER_ATTEMPTS) {
            val retryNote = lastError?.let {
                "\n\nIMPORTANT: your previous reply was rejected (${it.message}). " +
                    "Reply again with ONLY the JSON object in the required format."
            }.orEmpty()
            val reply = client.chat(
                system = LlmPrompts.SUGGESTER_SYSTEM,
                user = userPrompt + retryNote,
                model = model(),
                temperature = SUGGESTER_TEMPERATURE,
                maxTokens = MAX_TOKENS,
            )
            val raw = LlmReplies.parseProposal(reply)
            if (raw == null) {
                lastError = LlmException(UNREADABLE_REPLY)
                continue
            }
            try {
                return validator.validate(raw)
            } catch (e: LlmException) {
                lastError = e
            }
        }
        throw lastError ?: LlmException(UNREADABLE_REPLY)
    }

    /** Checker call and the decision what to return. */
    private suspend fun review(
        context: RecipeContext,
        boldness: Double,
        validator: ProposalValidator,
        proposal: ValidProposal,
    ): LlmSuggestion {
        val verdict = try {
            LlmReplies.parseVerdict(
                client.chat(
                    system = LlmPrompts.CHECKER_SYSTEM,
                    user = LlmPrompts.checkerUser(context, boldness, proposal),
                    model = model(),
                    temperature = CHECKER_TEMPERATURE,
                    maxTokens = MAX_TOKENS,
                ),
            )
        } catch (e: LlmException) {
            null // The suggestion is already paid for; fall back to the code-level limits below.
        }

        return when (verdict) {
            CheckerVerdict.Approved -> build(context, proposal, Review.Approved)
            is CheckerVerdict.Rejected -> {
                val reason = verdict.reason.ifBlank { "the change looked too large or unsafe" }.trimEnd('.')
                val revised = verdict.revised?.let {
                    try {
                        validator.validate(it)
                    } catch (e: LlmException) {
                        null
                    }
                } ?: throw LlmException("The reviewing AI rejected the suggestion: $reason. Try again.")
                build(context, revised, Review.Revised(reason), original = proposal)
            }
            CheckerVerdict.Unreadable, null -> {
                val unavailable = if (verdict == null) {
                    "The reviewing AI could not be reached"
                } else {
                    "The reviewing AI's answer could not be read"
                }
                when (proposal) {
                    is ValidProposal.Values -> {
                        val maxRelative = CAP_FACTOR * boldness
                        val (capped, limited) = validator.cap(proposal, maxRelative)
                        if (capped.changes.isEmpty()) throw LlmException("The AI suggested no change.")
                        build(context, capped, Review.Unchecked(unavailable, LlmPrompts.percent(maxRelative), limited))
                    }
                    is ValidProposal.NewVersion ->
                        build(context, proposal, Review.Unchecked(unavailable, limit = null, limited = false))
                }
            }
        }
    }

    /** What happened in the review, for the summary and rationale texts. */
    private sealed class Review {
        data object Approved : Review()
        data class Revised(val reason: String) : Review()
        data class Unchecked(val why: String, val limit: String?, val limited: Boolean) : Review()
    }

    private fun build(
        context: RecipeContext,
        proposal: ValidProposal,
        review: Review,
        original: ValidProposal = proposal,
    ): LlmSuggestion {
        val rationale = proposal.raw.rationale.ifBlank { original.raw.rationale }.oneParagraph()
        val reviewNote = when (review) {
            Review.Approved -> "A second AI reviewed this tweak and found it reasonable."
            is Review.Revised -> "A second AI toned the original idea down: ${review.reason}."
            is Review.Unchecked -> when {
                review.limited -> "${review.why}, so each change was limited to ±${review.limit}."
                review.limit != null -> "${review.why}; the change is within the usual ±${review.limit} limit."
                else -> "${review.why}; read the new steps carefully before cooking."
            }
        }
        val adjusted = when (review) {
            is Review.Revised -> " (toned down by the reviewer)"
            is Review.Unchecked -> if (review.limited) " (limited for safety)" else ""
            Review.Approved -> ""
        }

        return when (proposal) {
            is ValidProposal.Values -> {
                val changeLine = proposal.changes.joinToString("; ") { it.text }
                // A summary written for other numbers would mislead: use the real changes when code limited them.
                val headline = if (review is Review.Unchecked && review.limited) {
                    changeLine
                } else {
                    proposal.raw.summary.oneParagraph().ifBlank { changeLine }
                }
                LlmSuggestion.Values(
                    versionId = proposal.version.id,
                    values = proposal.values,
                    summary = (headline + adjusted).take(MAX_SUMMARY_LENGTH),
                    rationale = listOf(
                        rationale,
                        "Changes from ${context.startingPointName(proposal.version)}: $changeLine.",
                        reviewNote,
                    ).filter { it.isNotBlank() }.joinToString("\n\n"),
                )
            }
            is ValidProposal.NewVersion -> {
                val headline = proposal.raw.summary.oneParagraph()
                    .ifBlank { "Try a new version: ${proposal.name}" }
                LlmSuggestion.NewVersion(
                    parentVersionId = proposal.parent.id,
                    name = proposal.name,
                    steps = proposal.steps,
                    summary = (headline + adjusted).take(MAX_SUMMARY_LENGTH),
                    rationale = listOf(
                        rationale,
                        "New version \"${proposal.name}\" based on \"${proposal.parent.name}\" " +
                            "(${stepChangeText(proposal)}).",
                        reviewNote,
                    ).filter { it.isNotBlank() }.joinToString("\n\n"),
                )
            }
        }
    }

    /** "2 steps changed", "1 step added", ... a rough size of the structural change. */
    private fun stepChangeText(proposal: ValidProposal.NewVersion): String {
        val before = proposal.parent.steps
        val after = proposal.steps
        val kept = after.count { it in before }
        val changed = after.size - kept
        val delta = after.size - before.size
        val parts = buildList {
            if (changed > 0) add(if (changed == 1) "1 step new or reworded" else "$changed steps new or reworded")
            if (delta < 0) add(if (delta == -1) "1 step removed" else "${-delta} steps removed")
            if (changed == 0 && delta == 0) add("steps reordered")
        }
        return parts.joinToString(", ").ifEmpty { "steps rearranged" }
    }

    private fun String.oneParagraph(): String = replace(WHITESPACE, " ").trim()

    private companion object {
        const val SUGGESTER_TEMPERATURE = 0.7
        const val CHECKER_TEMPERATURE = 0.2
        const val SUGGESTER_ATTEMPTS = 2
        const val MAX_TOKENS = 2000
        const val CAP_FACTOR = 3.0
        const val MIN_BOLDNESS = 0.02
        const val MAX_BOLDNESS = 0.8
        const val DEFAULT_BOLDNESS = 0.15
        const val MAX_SUMMARY_LENGTH = 200
        const val UNREADABLE_REPLY = "The AI reply could not be understood."
        val WHITESPACE = Regex("""\s+""")
    }
}
