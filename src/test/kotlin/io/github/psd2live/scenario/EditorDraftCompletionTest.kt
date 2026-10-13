package io.github.psd2live.scenario

import io.github.psd2live.application.WorkspaceDraftQueue
import io.github.psd2live.application.WorkspaceRuntime
import io.github.psd2live.project.MutationAuthor
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class EditorDraftCompletionTest {
    @Test fun refreshFailuresAndLateCancellationCannotRejectAPublishedEdit() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = WorkspaceRuntime<String>({ it.settings.toString() })
            val document = Characters.head().copy(settings = JsonObject(emptyMap()))
            runtime.install(runtime.state.value.state, "project", document, "initial")
            val queue = WorkspaceDraftQueue(runtime, scope)
            for (failure in listOf(IllegalStateException("Renderer failed"), CancellationException("Late cancellation"))) {
                val before = runtime.capture()
                val next = before.document.copy(settings = buildJsonObject { put("step", runtime.history().selections.size) })
                val submitted = queue.submit("project", before.state, next, "Edit", MutationAuthor.USER,
                    beforeCommit = { _, _, _, _ -> }, committed = { throw failure })
                val committed = submitted.await()
                assertTrue(committed.applied)
                assertEquals(next, runtime.capture().document)
                assertEquals(before.historyHead, runtime.history().selections.single { it.node.id == committed.capture.historyHead }.node.parentId)
                queue.awaitIdle()
                assertFalse(submitted.isCancelled)
            }
        } finally { scope.cancel() }
    }
}
