package io.github.psd2live.scenario

import io.github.psd2live.application.WorkspaceDraftRebase
import io.github.psd2live.project.WorkspaceDocument
import kotlinx.serialization.json.*
import kotlin.test.*

/** An editor's field draft carried over commits it did not make. */
class EditorDraftRebaseTest {
    private val base = Characters.head().copy(settings = buildJsonObject { put("texturePadding", 2); put("alphaThreshold", 8) })
    private val layers = base.source.layers.map { it.id.raw }

    private fun WorkspaceDocument.setting(key: String, value: Int) = copy(settings = JsonObject(settings + (key to JsonPrimitive(value))))
    private fun WorkspaceDocument.hidden(layer: String) = copy(layerVisibility = layerVisibility + (layer to false))
    private fun WorkspaceDocument.journal(vararg commands: String) = copy(rigEdits = rigEdits.copy(
        authoringJournal = rigEdits.authoringJournal + commands.map { buildJsonObject { put("command", it) } }))

    @Test fun untouchedFieldsFollowTheCommitAndDraftedOnesStay() {
        val draft = base.setting("texturePadding", 5)
        val next = base.setting("alphaThreshold", 30).hidden(layers[0])
        val carried = WorkspaceDraftRebase.rebase(base, draft, next)
        assertTrue(carried.clean)
        assertEquals(next.setting("texturePadding", 5), carried.document)
        // A draft without changes simply shows the commit; one the commit left alone stays as it is.
        assertSame(next, WorkspaceDraftRebase.rebase(base, base, next).document)
        assertSame(draft, WorkspaceDraftRebase.rebase(base, draft, base).document)
    }

    @Test fun mapsMergeByKeyAndTheCommitWinsAConflict() {
        val draft = base.hidden(layers[0]).setting("texturePadding", 5)
        val next = base.hidden(layers[1]).setting("texturePadding", 7)
        val carried = WorkspaceDraftRebase.rebase(base, draft, next)
        assertEquals(listOf("settings.texturePadding"), carried.conflicts)
        assertEquals(false, carried.document.layerVisibility[layers[0]])
        assertEquals(false, carried.document.layerVisibility[layers[1]])
        assertEquals(7, carried.document.settings["texturePadding"]?.jsonPrimitive?.int)
        // The same change on both sides is no conflict.
        assertTrue(WorkspaceDraftRebase.rebase(base, next, next.setting("alphaThreshold", 30)).clean)
    }

    @Test fun commandsTheDraftAppendedReplayAfterTheCommittedOnes() {
        val start = base.journal("a")
        val carried = WorkspaceDraftRebase.rebase(start, start.journal("draft"), start.journal("agent"))
        assertTrue(carried.clean)
        assertEquals(listOf("a", "agent", "draft"), carried.document.rigEdits.authoringJournal.map { it.getValue("command").jsonPrimitive.content })
        // A draft that rewrote the journal rather than growing it cannot be carried.
        val rewritten = base.journal("b")
        assertEquals(listOf("rigEdits"), WorkspaceDraftRebase.rebase(start, rewritten, start.journal("agent")).conflicts)
    }
}
