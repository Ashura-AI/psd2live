package io.github.psd2live.scenario

import io.github.psd2live.application.WorkspaceConflict
import io.github.psd2live.application.WorkspaceDocumentEdit
import io.github.psd2live.application.WorkspaceDraftQueue
import io.github.psd2live.application.WorkspaceDraftRebase
import io.github.psd2live.application.WorkspaceRuntime
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.project.WorkspaceDocument
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** An editor's field draft carried over commits it did not make, in the rebase and in the draft queue. */
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

    @Test fun aQueuedDraftFollowsAnotherAuthorsCommitAndConflictsOnlyOnTheSameField() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = WorkspaceRuntime<String>({ it.settings.toString() })
            runtime.install(runtime.state.value.state, "project", base, "initial")
            val queue = WorkspaceDraftQueue(runtime, scope)
            suspend fun external(document: WorkspaceDocument) {
                val before = runtime.capture()
                runtime.execute("project", before.state, "Agent edit", MutationAuthor.AGENT, listOf(WorkspaceDocumentEdit { _, _ -> document }))
            }
            suspend fun draftAround(draft: (WorkspaceDocument) -> WorkspaceDocument, agent: (WorkspaceDocument) -> WorkspaceDocument): Deferred<*> {
                val before = runtime.capture()
                val gate = CompletableDeferred<Unit>()
                val submitted = queue.submit("project", before.state, draft(before.document), "Editor edit", MutationAuthor.USER,
                    beforeCommit = { _, _, _ -> }, prepare = { start, _, candidate -> gate.await(); start.copy(document = candidate) })
                external(agent(before.document))
                gate.complete(Unit)
                return submitted
            }

            val merged = draftAround({ it.setting("texturePadding", 5) }, { it.setting("alphaThreshold", 30) })
            merged.await()
            assertEquals(base.setting("texturePadding", 5).setting("alphaThreshold", 30), runtime.capture().document)
            assertEquals(listOf("Agent edit", "Editor edit"), runtime.history().selections.drop(1).map { it.node.summary })

            val refused = draftAround({ it.setting("texturePadding", 9) }, { it.setting("texturePadding", 11) })
            assertFailsWith<WorkspaceConflict> { refused.await() }
            assertEquals(11, runtime.capture().document.settings["texturePadding"]?.jsonPrimitive?.int)
            queue.forgetRejected()
        } finally { scope.cancel() }
    }
}
