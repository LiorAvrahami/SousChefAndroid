package com.lioravrahami.souschef.llm

import com.lioravrahami.souschef.data.model.Recipe
import com.lioravrahami.souschef.data.model.RecipeDetails
import com.lioravrahami.souschef.data.model.RecipeVersion
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.data.model.Trial
import com.lioravrahami.souschef.data.model.TrialMode
import com.lioravrahami.souschef.data.model.TrialStatus
import com.lioravrahami.souschef.domain.llm.HttpStatusException
import com.lioravrahami.souschef.domain.llm.LlmOptimizer
import com.lioravrahami.souschef.domain.llm.LlmSuggestion
import com.lioravrahami.souschef.domain.llm.OpenRouterClient.LlmException
import com.lioravrahami.souschef.domain.optimizer.OptimizerSettings
import com.lioravrahami.souschef.llm.FakeTransport.Companion.client
import com.lioravrahami.souschef.llm.FakeTransport.Companion.reply
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LlmOptimizerTest {

    // Parameters: #0 water 2 cups, #1 salt 1 tsp (LOCKED), #2 Simmer 600 s, #3 180 °C
    private val version = RecipeVersion(
        id = "4b9e0c1e-0000-4000-8000-000000000001",
        recipeId = "r1",
        name = "Original",
        createdAt = 1_000,
        steps = listOf(
            Step.Text("Add 2[cups] water and 1[tsp] salt", locked = listOf(1)),
            Step.Wait(label = "Simmer", seconds = 600),
            Step.Text("Bake at 180[°C] until golden"),
        ),
    )

    private val trial = Trial(
        recipeId = "r1",
        versionId = version.id,
        values = listOf(2.0, 1.0, 600.0, 180.0),
        status = TrialStatus.DONE,
        mode = TrialMode.BEST,
        createdAt = 2_000,
        finishedAt = 3_000,
        overallScore = 6.0,
        axes = mapOf("moisture" to 1.0, "doneness" to 0.0),
        notes = "A bit watery",
    )

    private val details = RecipeDetails(Recipe(id = "r1", name = "Soup"), listOf(version), listOf(trial))
    private val settings = OptimizerSettings(boldness = 0.1, explorationRate = 0.25)

    private val ok = reply("""{"ok":true}""")

    private fun values(vararg v: Double, versionId: String = "v1", summary: String = "Less water") =
        """{"type":"values","versionId":"$versionId","values":[${v.joinToString(",")}],""" +
            """"summary":"$summary","rationale":"It was too wet."}"""

    private fun optimizer(transport: FakeTransport) = LlmOptimizer(client(transport), model = { FakeTransport.MODEL })

    private fun assertValues(expected: List<Double>, actual: List<Double>) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertEquals("value #$it", expected[it], actual[it], 1e-9) }
    }

    private suspend fun expectError(transport: FakeTransport): String = try {
        optimizer(transport).suggest(details, settings)
        fail("Expected LlmException")
        ""
    } catch (e: LlmException) {
        e.message.orEmpty()
    }

    @Test
    fun valuesProposalAccepted() = runTest {
        val transport = FakeTransport(reply(values(1.8, 1.0, 660.0, 180.0)), ok)
        val suggestion = optimizer(transport).suggest(details, settings) as LlmSuggestion.Values

        assertEquals(version.id, suggestion.versionId)
        assertValues(listOf(1.8, 1.0, 660.0, 180.0), suggestion.values)
        assertEquals("Less water", suggestion.summary)
        assertTrue(suggestion.rationale.startsWith("It was too wet."))
        assertTrue(suggestion.rationale.contains("water: 2 → 1.8 cups"))
        assertTrue(suggestion.rationale.contains("Simmer: 10 min → 11 min"))
        assertTrue(suggestion.rationale.contains("found it reasonable"))

        val (suggester, checker) = transport.requests
        assertEquals(0.7, suggester.temperature, 0.0)
        assertEquals(0.2, checker.temperature, 0.0)
        assertEquals(FakeTransport.MODEL, suggester.model)
        // The prompt names every parameter, shows history and the current best.
        assertTrue(suggester.user.contains("#0 | step 1 | water | cups | 2"))
        assertTrue(suggester.user.contains("#1 | step 1 | salt | tsp | 1 LOCKED"))
        assertTrue(suggester.user.contains("moisture: +1 (Too wet)"))
        assertTrue(suggester.user.contains("A bit watery"))
        assertTrue(suggester.user.contains("CURRENT BEST: v1, score 6/10, values [2, 1, 600, 180]"))
        assertTrue(checker.user.contains("water: 2 cups -> 1.8 cups (-10%)"))
    }

    @Test
    fun fullVersionIdIsAcceptedToo() = runTest {
        val transport = FakeTransport(reply(values(1.8, 1.0, 600.0, 180.0, versionId = version.id)), ok)
        val suggestion = optimizer(transport).suggest(details, settings) as LlmSuggestion.Values
        assertEquals(version.id, suggestion.versionId)
    }

    @Test
    fun lockedParamIsResetToItsBaseValue() = runTest {
        val transport = FakeTransport(reply(values(1.8, 3.0, 600.0, 180.0)), ok)
        val suggestion = optimizer(transport).suggest(details, settings) as LlmSuggestion.Values
        assertValues(listOf(1.8, 1.0, 600.0, 180.0), suggestion.values)
    }

    @Test
    fun negativeValuesClampToZeroAndWaitsRoundToWholeSeconds() = runTest {
        val transport = FakeTransport(reply(values(-0.5, 1.0, 612.4, 180.0)), ok)
        val suggestion = optimizer(transport).suggest(details, settings) as LlmSuggestion.Values
        assertValues(listOf(0.0, 1.0, 612.0, 180.0), suggestion.values)
    }

    @Test
    fun fencedJsonWithChatterIsParsed() = runTest {
        val fenced = "Sure! Here is my idea:\n```json\n${values(1.9, 1.0, 600.0, 180.0)}\n```\nEnjoy your soup."
        val transport = FakeTransport(reply(fenced), reply("```json\n{\"ok\": true}\n```"))
        val suggestion = optimizer(transport).suggest(details, settings) as LlmSuggestion.Values
        assertValues(listOf(1.9, 1.0, 600.0, 180.0), suggestion.values)
    }

    @Test
    fun checkerRevisionIsUsed() = runTest {
        val revised = values(1.7, 1.0, 600.0, 180.0, summary = "Slightly less water")
        val transport = FakeTransport(
            reply(values(1.0, 1.0, 600.0, 180.0, summary = "Halve the water")),
            reply("""{"ok":false,"reason":"Halving the water is too big a step.","revised":$revised}"""),
        )
        val suggestion = optimizer(transport).suggest(details, settings) as LlmSuggestion.Values
        assertValues(listOf(1.7, 1.0, 600.0, 180.0), suggestion.values)
        assertEquals("Slightly less water (toned down by the reviewer)", suggestion.summary)
        assertTrue(suggestion.rationale.contains("Halving the water is too big a step"))
        assertTrue(suggestion.rationale.contains("water: 2 → 1.7 cups"))
    }

    @Test
    fun checkerRevisionIsValidatedLikeAProposal() = runTest {
        val revised = values(1.7, 5.0, 600.0, 180.0)
        val transport = FakeTransport(
            reply(values(1.0, 1.0, 600.0, 180.0)),
            reply("""{"ok":false,"reason":"Too much.","revised":$revised}"""),
        )
        val suggestion = optimizer(transport).suggest(details, settings) as LlmSuggestion.Values
        assertValues(listOf(1.7, 1.0, 600.0, 180.0), suggestion.values)
    }

    @Test
    fun checkerRejectionWithoutRevisionLimitsTheOriginal() = runTest {
        // boldness 0.1 -> each change limited to 10%: water 2 -> 1.8.
        val transport = FakeTransport(
            reply(values(1.0, 1.0, 600.0, 180.0)),
            reply("""{"ok":false,"reason":"Unsafe."}"""),
        )
        val suggestion = optimizer(transport).suggest(details, settings) as LlmSuggestion.Values
        assertValues(listOf(1.8, 1.0, 600.0, 180.0), suggestion.values)
        assertTrue(suggestion.rationale.contains("objected: Unsafe"))
        assertTrue(suggestion.rationale.contains("limited to ±10%"))
        assertTrue(suggestion.summary.endsWith("(limited after the reviewer's objection)"))
        assertTrue(suggestion.summary.contains("water: 2 → 1.8 cups"))
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun checkerRevisionWithWrongValueCountLimitsTheOriginal() = runTest {
        val revised = values(1.7, 600.0, 180.0)
        val transport = FakeTransport(
            reply(values(1.0, 1.0, 600.0, 180.0)),
            reply("""{"ok":false,"reason":"Too much.","revised":$revised}"""),
        )
        val suggestion = optimizer(transport).suggest(details, settings) as LlmSuggestion.Values
        assertValues(listOf(1.8, 1.0, 600.0, 180.0), suggestion.values)
        assertTrue(suggestion.rationale.contains("objected: Too much"))
    }

    @Test
    fun checkerRejectionOfANewVersionWithoutRevisionIsAnError() = runTest {
        val proposal = """{"type":"new_version","parentVersionId":"v1","name":"Lower heat",
            "steps":[{"type":"text","text":"Add 2[cups] water and 1[tsp] salt"},
                     {"type":"wait","label":"Simmer","seconds":900,"locked":false},
                     {"type":"text","text":"Bake at 160[°C] on the middle rack"}],
            "summary":"s","rationale":"r"}"""
        val transport = FakeTransport(reply(proposal), reply("""{"ok":false,"reason":"Unsafe."}"""))
        assertEquals("The reviewing AI rejected the suggestion: Unsafe. Try again.", expectError(transport))
    }

    @Test
    fun checkerGarbageCapsTheOriginal() = runTest {
        // boldness 0.1 -> cap 30%: water 2 -> at most 2.6; simmer 600 -> at most 780.
        val transport = FakeTransport(reply(values(4.0, 1.0, 1200.0, 170.0)), reply("Looks great to me!"))
        val suggestion = optimizer(transport).suggest(details, settings) as LlmSuggestion.Values
        assertValues(listOf(2.6, 1.0, 780.0, 170.0), suggestion.values)
        assertTrue(suggestion.rationale.contains("limited to ±30%"))
        assertTrue(suggestion.summary.endsWith("(limited for safety)"))
        assertTrue(suggestion.summary.contains("water: 2 → 2.6 cups"))
    }

    @Test
    fun unreachableCheckerAlsoCapsTheOriginal() = runTest {
        val transport = FakeTransport(reply(values(1.9, 1.0, 600.0, 180.0)), { throw HttpStatusException(502, "") })
        val suggestion = optimizer(transport).suggest(details, settings) as LlmSuggestion.Values
        assertValues(listOf(1.9, 1.0, 600.0, 180.0), suggestion.values)
        assertTrue(suggestion.rationale.contains("could not be reached"))
    }

    @Test
    fun newVersionIsParsedIntoSteps() = runTest {
        val proposal = """
            {"type":"new_version","parentVersionId":"v1","name":"Lower heat",
             "steps":[
               {"type":"text","text":"Add 2[cups] water and 1[tsp] salt"},
               {"type":"wait","label":"Simmer","seconds":900,"locked":false},
               {"type":"text","text":"Bake at 160[°C] on the middle rack","locked":[]}
             ],
             "summary":"Bake lower and simmer longer","rationale":"Burnt outside, raw inside."}
        """.trimIndent()
        val transport = FakeTransport(reply(proposal), ok)
        val suggestion = optimizer(transport).suggest(details, settings) as LlmSuggestion.NewVersion

        assertEquals(version.id, suggestion.parentVersionId)
        assertEquals("Lower heat", suggestion.name)
        assertEquals(
            listOf(
                // The salt lock is carried over from the parent even though the model forgot it.
                Step.Text("Add 2[cups] water and 1[tsp] salt", locked = listOf(1)),
                Step.Wait(label = "Simmer", seconds = 900, locked = false),
                Step.Text("Bake at 160[°C] on the middle rack"),
            ),
            suggestion.steps,
        )
        assertEquals("Bake lower and simmer longer", suggestion.summary)
        assertTrue(suggestion.rationale.contains("based on \"Original\""))
    }

    @Test
    fun newVersionThatDropsALockedAmountIsRejected() = runTest {
        val proposal = """{"type":"new_version","parentVersionId":"v1","name":"x",
            "steps":[{"type":"text","text":"Add 2[cups] water and 2[tsp] salt"}],"summary":"s","rationale":"r"}"""
        val transport = FakeTransport(reply(proposal), reply(proposal))
        assertTrue(expectError(transport).startsWith("The AI changed a locked amount (salt: 1 tsp)"))
    }

    @Test
    fun unparseableSuggesterIsRetriedOnce() = runTest {
        val transport = FakeTransport(reply("I would add less water."), reply(values(1.8, 1.0, 600.0, 180.0)), ok)
        val suggestion = optimizer(transport).suggest(details, settings) as LlmSuggestion.Values
        assertValues(listOf(1.8, 1.0, 600.0, 180.0), suggestion.values)
        assertEquals(3, transport.requests.size)
        assertTrue(transport.requests[1].user.contains("your previous reply was rejected"))
    }

    @Test
    fun unparseableSuggesterTwiceIsAnError() = runTest {
        val transport = FakeTransport(reply("no idea"), reply("{ broken json"))
        assertEquals("The AI reply could not be understood.", expectError(transport))
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun unknownVersionIsAnError() = runTest {
        val bad = reply(values(1.8, 1.0, 600.0, 180.0, versionId = "v7"))
        assertEquals("The AI referred to a recipe version that does not exist.", expectError(FakeTransport(bad, bad)))
    }

    @Test
    fun wrongValueCountIsAnError() = runTest {
        val bad = reply(values(1.8, 1.0, 600.0))
        assertEquals("The AI gave 3 amounts, but \"Original\" has 4.", expectError(FakeTransport(bad, bad)))
    }

    @Test
    fun sameValuesAsTheBestAreNoChange() = runTest {
        val same = reply(values(2.0, 1.0, 600.0, 180.0))
        assertEquals("The AI suggested no change.", expectError(FakeTransport(same, same)))
    }

    @Test
    fun networkErrorsFromTheSuggesterPropagate() = runTest {
        val transport = FakeTransport({ throw HttpStatusException(402, "") })
        assertEquals("Your OpenRouter account has no credits left.", expectError(transport))
        assertEquals(1, transport.requests.size)
    }
}
