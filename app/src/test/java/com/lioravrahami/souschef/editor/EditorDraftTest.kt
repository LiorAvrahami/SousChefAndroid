package com.lioravrahami.souschef.editor

import com.lioravrahami.souschef.data.model.RatingAxis
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.ui.editor.EditorDraft
import com.lioravrahami.souschef.ui.editor.EditorDraftStash
import com.lioravrahami.souschef.ui.editor.EditorDrafts
import com.lioravrahami.souschef.ui.editor.StepEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditorDraftTest {
    private val draft = EditorDraft(
        name = "Pancakes",
        notes = "Sunday",
        customAxes = listOf(RatingAxis("a1", "Too flat", "Too fluffy")),
        steps = listOf(
            Step.Text("Mix 1.75[cups] flour with 1/2[cup] milk", locked = listOf(1)),
            Step.Wait(label = "Rest", seconds = 300, locked = true),
        ),
        editing = StepEdit(0, Step.Text("Mix 1.75[cups] flour with 1/2[cup] milk", locked = listOf(1))),
        baseVersionId = "v1",
    )

    @Test
    fun draftSurvivesEncodeDecode() {
        val back = EditorDrafts.decode(EditorDrafts.encode(draft))
        assertEquals(draft, back)
        // The step editor's saved text is keyed by the hash of the open StepEdit.
        assertEquals(draft.editing.hashCode(), back!!.editing.hashCode())
    }

    @Test
    fun draftWithoutOptionalPartsSurvives() {
        val plain = EditorDraft("", "", emptyList(), emptyList())
        assertEquals(plain, EditorDrafts.decode(EditorDrafts.encode(plain)))
    }

    @Test
    fun unreadableDraftIsIgnored() {
        assertNull(EditorDrafts.decode(null))
        assertNull(EditorDrafts.decode(""))
        assertNull(EditorDrafts.decode("{not json"))
        assertNull(EditorDrafts.decode("""{"name": 3}"""))
    }

    @Test
    fun stashIsTakenOnce() {
        val key = EditorDraftStash.keyFor("recipe-under-test")
        EditorDraftStash.put(key, draft)
        assertEquals(draft, EditorDraftStash.take(key))
        assertNull(EditorDraftStash.take(key))
        assertEquals(EditorDraftStash.keyFor(null), EditorDraftStash.keyFor(null))
    }
}
