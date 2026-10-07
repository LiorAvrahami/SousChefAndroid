package com.lioravrahami.souschef.backup

import com.lioravrahami.souschef.data.backup.BackupCodec
import com.lioravrahami.souschef.data.model.Recipe
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.data.model.TrialMode
import com.lioravrahami.souschef.data.model.TrialStatus
import com.lioravrahami.souschef.data.model.VersionOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyImportTest {
    private val now = 1_700_000_000_000L

    private fun plan(text: String, existing: List<Recipe> = emptyList()) =
        BackupCodec.planImport(text, existing, emptySet(), emptySet(), now = now)

    /** The `resources/recipes.json` shipped with the old web app, verbatim. */
    private val webAppSample = """
        [
            {
                "name": "Microwaved Rice",
                "versions": [
                    {
                        "BaseRecipe": [
                            "1[cups] rice",
                            "1.75[cups] water",
                            "3[shakes] of salt",
                            "1[spoon] of soup-powder",
                            "some olive oil",
                            "15[minuets] in the microwave"
                        ],
                        "Trials": [
                            {}
                        ]
                    }
                ]
            },
            {
                "name": "nerve-calming jasmine tea",
                "versions": [
                    {
                        "BaseRecipe": [
                            "hot water",
                            "add jasmine leaves",
                            "wait a while",
                            "now you have some hot leaf juice "
                        ],
                        "Trials": [
                            {}
                        ]
                    }
                ]
            }
        ]
    """.trimIndent()

    @Test
    fun theWebAppSampleImports() {
        val p = plan(webAppSample)
        assertEquals(listOf("Microwaved Rice", "nerve-calming jasmine tea"), p.recipes.map { it.name })
        assertEquals(2, p.versions.size)
        assertTrue("empty {} trials are ignored", p.trials.isEmpty())
        val rice = p.versions.first()
        assertEquals("v1", rice.name)
        assertEquals(VersionOrigin.IMPORT, rice.origin)
        assertEquals(p.recipes.first().id, rice.recipeId)
        assertEquals(Step.Text("15[minuets] in the microwave"), rice.steps.last())
        assertEquals(6, rice.steps.size)
        assertEquals(Step.Text("now you have some hot leaf juice"), p.versions[1].steps.last())
    }

    @Test
    fun bulletsAndBlankLinesAreCleanedUp() {
        val p = plan("""[{"name":"Toast","versions":[{"BaseRecipe":["- 2[slices] bread", "", "* toast 3[min]", "   "]}]}]""")
        assertEquals(listOf(Step.Text("2[slices] bread"), Step.Text("toast 3[min]")), p.versions.single().steps)
    }

    @Test
    fun aMultiLineStringBaseRecipeIsSplitIntoSteps() {
        val p = plan("""[{"name":"Toast","versions":[{"BaseRecipe":"- 2[slices] bread\n\n- toast 3[min]"}]}]""")
        assertEquals(listOf(Step.Text("2[slices] bread"), Step.Text("toast 3[min]")), p.versions.single().steps)
    }

    @Test
    fun webAppTrialsBecomeFinishedCookings() {
        val text = """
            [{"name":"Rice","versions":[{"BaseRecipe":["1[cups] rice","1.75[cups] water"],
              "Trials":[
                {},
                {"rating":7,"freeText":"a bit dry","TrialSteps":["1[cups] rice","2[cups] water"]},
                {"rating":null,"freeText":"never rated"},
                {"Score":"12","values":[1,1.5],"time":1600000000000},
                {"score":"-3","info":"awful","measurements":[1,2,3]},
                {"Rating":"6,5","date":1600000000}
              ]}]}]
        """.trimIndent()
        val p = plan(text)
        val version = p.versions.single()
        val t = p.trials
        assertEquals(4, t.size)
        t.forEach {
            assertEquals(TrialStatus.DONE, it.status)
            assertEquals(TrialMode.AS_WRITTEN, it.mode)
            assertEquals(version.id, it.versionId)
            assertEquals(p.recipes.single().id, it.recipeId)
        }
        // Values read from the tweaked steps the web app stored.
        assertEquals(listOf(1.0, 2.0), t[0].values)
        assertEquals(7.0, t[0].overallScore!!, 1e-9)
        assertEquals("a bit dry", t[0].notes)
        assertEquals(now, t[0].finishedAt)
        // String score, clamped; explicit values; numeric time.
        assertEquals(10.0, t[1].overallScore!!, 1e-9)
        assertEquals(listOf(1.0, 1.5), t[1].values)
        assertEquals(1600000000000L, t[1].finishedAt)
        // Clamped at 0; a value list of the wrong size falls back to the base values.
        assertEquals(0.0, t[2].overallScore!!, 1e-9)
        assertEquals(listOf(1.0, 1.75), t[2].values)
        assertEquals("awful", t[2].notes)
        // Decimal comma; a time in seconds is converted to millis.
        assertEquals(6.5, t[3].overallScore!!, 1e-9)
        assertEquals(1600000000000L, t[3].finishedAt)
    }

    @Test
    fun versionsAreNumberedAndTheLastIsTheNewest() {
        val p = plan("""[{"name":"Tea","versions":[{"BaseRecipe":["a"]},{"BaseRecipe":["b"]},{"BaseRecipe":["c"]}]}]""")
        assertEquals(listOf("v1", "v2", "v3"), p.versions.map { it.name })
        val created = p.versions.map { it.createdAt }
        assertEquals(created.sorted(), created)
        assertEquals(3, created.toSet().size)
    }

    @Test
    fun aRecipeWithTheSameNameIsSkippedUnlessItIsInTheTrash() {
        val existing = listOf(
            Recipe(name = "Microwaved Rice"),
            Recipe(name = "Nerve-calming Jasmine Tea", deletedAt = 5),
        )
        val p = plan(webAppSample, existing)
        assertEquals(listOf("nerve-calming jasmine tea"), p.recipes.map { it.name })
        assertEquals(1, p.recipesSkipped)
        assertEquals(1, p.versions.size)
    }

    @Test
    fun importingTheSameLegacyFileTwiceAddsNothingTheSecondTime() {
        val first = plan(webAppSample)
        val second = plan(webAppSample, first.recipes)
        assertTrue(second.recipes.isEmpty() && second.versions.isEmpty() && second.trials.isEmpty())
        assertEquals(2, second.recipesSkipped)
    }

    @Test
    fun anEmptyLegacyListIsFine() {
        val p = plan("[]")
        assertTrue(p.recipes.isEmpty())
        assertEquals(0, p.recipesSkipped)
    }
}
