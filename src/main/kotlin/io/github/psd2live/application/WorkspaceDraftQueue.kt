package io.github.psd2live.application

import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.project.WorkspaceDocument
import kotlinx.coroutines.*

/** Ordered, process-owned editor completions. Only this queue's own commits can advance a draft's expectation. */
internal class WorkspaceDraftQueue<M>(
    private val runtime: WorkspaceRuntime<M>,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private var tail: Entry? = null

    private inner class Entry(
        val projectId: String,
        val inputState: String,
        val document: WorkspaceDocument,
        /** The runtime document the draft's untouched fields mirror, when known. */
        val base: WorkspaceDocument?,
        var previous: Entry?,
    ) {
        lateinit var result: Deferred<WorkspaceCommit<M>>
        var lineage: Set<String> = emptySet()
        var completedState: String? = null
    }

    /**
     * Queues [document], the editor's draft on [state]. A draft that finds another author's commit ahead of it is
     * carried over that commit ([WorkspaceDraftRebase]) and tried again: a field both changed takes the commit's
     * value, and [yielded] hears which ones; only a draft overtaken [MAX_REBASES] times is rejected.
     *
     * [origin] is the runtime document the draft's untouched fields mirror, as the editor last showed it; it may be
     * newer than [state] when the editor read a commit's projection before the runtime published its state. Without
     * it the runtime's document at [state] is used, if [state] is still current. Settings named in [yieldedSettings]
     * keep the runtime's value even where the draft's settings switches would derive another.
     */
    fun submit(projectId: String, state: String, document: WorkspaceDocument, summary: String,
               author: MutationAuthor,
               origin: WorkspaceDocument? = null,
               yieldedSettings: Set<String> = emptySet(),
               yielded: (List<String>) -> Unit = {},
               beforeCommit: (WorkspaceCapture<M>, WorkspaceDocument, M) -> Unit,
               committed: (WorkspaceCommit<M>) -> Unit = {},
               prepare: suspend (WorkspaceDraft, M, WorkspaceDocument) -> WorkspaceDraft = { before, _, draft -> before.copy(document = draft) },
               rebase: (WorkspaceDocument, WorkspaceDocument, WorkspaceDocument) -> WorkspaceDocument = { _, draft, _ -> draft },
               auxiliary: (WorkspaceCapture<M>, WorkspaceDraft, M) -> Unit = { _, _, _ -> }): Deferred<WorkspaceCommit<M>> = synchronized(lock) {
        // A draft submitted on a state the previous draft has already moved follows it whether or not that draft has
        // finished; only a rejected one ends the chain.
        val previous = tail?.takeUnless { it.result.isCompleted && it.result.isCancelled }
        // A finished draft's own predecessors are settled; dropping them keeps the chain from growing per edit.
        if (previous?.result?.isCompleted == true) previous.previous = null
        val base = origin ?: runtime.state.value.capture?.takeIf { it.state == state && it.projectId == projectId }?.document
        val entry = Entry(projectId, state, document, base, previous)
        entry.result = scope.async(start = CoroutineStart.LAZY) {
            val preceding = previous?.result?.await()
            val follows = preceding != null && previous.projectId == projectId && state in previous.lineage
            var expected = if (follows) preceding.capture.state else state
            // The runtime document the draft's untouched fields mirror, to carry it over a commit that lands first.
            var mirrored = if (follows) preceding.capture.document else entry.base
            var carried: WorkspaceDocument? = null
            var attempts = 0
            val conflicts = LinkedHashSet<String>()
            // A draft may also move auxiliary data, such as the poses a settings switch released during the session.
            var committedDraft: WorkspaceCommit<M>? = null
            while (committedDraft == null) {
                try {
                    committedDraft = runtime.executeDraft(projectId, expected, summary, author,
                        listOf(WorkspaceDraftEdit { before, model ->
                            val candidate = carried ?: if (follows) rebase(previous.document, document, before.document) else document
                            val keep = yieldedSettings + conflicts.mapNotNull { it.removePrefix("settings.").takeIf { key -> key != it } }
                            prepare(before, model, candidate).let { prepared ->
                                if (keep.isEmpty()) prepared else prepared.copy(document = runtimeSettings(prepared.document, before.document, keep))
                            }
                        })) { before, next, model ->
                        beforeCommit(before, next.document, model)
                        auxiliary(before, next, model)
                    }
                } catch (conflict: WorkspaceConflict) {
                    // Another author committed first: carry the draft over it; where both changed a field, theirs stays.
                    val from = mirrored ?: throw conflict
                    val current = runtime.state.value.capture ?: throw conflict
                    if (current.projectId != projectId || ++attempts > MAX_REBASES) throw conflict
                    val draft = carried ?: if (follows) rebase(previous.document, document, from) else document
                    val over = WorkspaceDraftRebase.rebase(from, draft, current.document)
                    conflicts += over.conflicts
                    carried = over.document
                    expected = current.state
                    mirrored = current.document
                }
            }
            val result: WorkspaceCommit<M> = committedDraft
            if (conflicts.isNotEmpty()) runCatching { yielded(conflicts.toList()) }
            entry.lineage = (if (follows) previous.lineage else emptySet()) + state + result.capture.state
            entry.completedState = result.capture.state
            // This callback persists/refreshes a commit already published by the runtime. A renderer failure
            // or late cancellation here cannot turn that edit into a rejected draft and poison future saves.
            try { committed(result) }
            catch (failure: Throwable) {
                runCatching {
                    System.getLogger("io.github.psd2live.application.WorkspaceDraftQueue")
                        .log(System.Logger.Level.WARNING, "Unable to refresh committed editor draft", failure)
                }
            }
            result
        }
        tail = entry
        entry.result.start()
        entry.result
    }

    /** Capture the current tail, including failures; callers never accidentally save a rejected draft. */
    suspend fun awaitIdle() {
        while (true) {
            val current = synchronized(lock) { tail }
            current?.result?.await()
            if (synchronized(lock) { tail === current }) return
        }
    }

    /** A subsequent GUI action may follow its own completed drafts, never an external edit. */
    suspend fun settleCommand(projectId: String, state: String): String {
        awaitIdle()
        return synchronized(lock) {
            val captured = runtime.capture()
            if (captured.projectId != projectId) throw WorkspaceConflict(state, captured.state)
            if (captured.state == state) return@synchronized state
            val last = tail
            if (last != null && last.projectId == projectId && last.result.isCompleted &&
                !last.result.isCancelled && state in last.lineage && captured.state == last.completedState) captured.state
            else throw WorkspaceConflict(state, captured.state)
        }
    }

    /** Other commands can repair a rejected draft; cancellation of their own wait still propagates. */
    suspend fun awaitSettled() {
        try { awaitIdle() }
        catch (_: Exception) { currentCoroutineContext().ensureActive() }
    }

    fun isRejectedTail(result: Deferred<WorkspaceCommit<M>>): Boolean = synchronized(lock) {
        tail?.result === result && result.isCancelled
    }

    fun forgetRejected(result: Deferred<WorkspaceCommit<M>>? = null) = synchronized(lock) {
        if (tail?.result?.isCancelled == true && (result == null || tail?.result === result)) tail = null
    }

    /** Successful project installation ends the old draft chain, including any remembered failure. */
    fun discard() = synchronized(lock) {
        var current = tail
        tail = null
        while (current != null) {
            current.result.cancel()
            current = current.previous
        }
    }

    /** [document] with [keys] of its settings as [runtime] has them. */
    private fun runtimeSettings(document: WorkspaceDocument, runtime: WorkspaceDocument, keys: Set<String>): WorkspaceDocument {
        val settings = LinkedHashMap(document.settings)
        for (key in keys) runtime.settings[key]?.let { settings[key] = it } ?: settings.remove(key)
        return document.copy(settings = kotlinx.serialization.json.JsonObject(settings))
    }

    private companion object {
        /** Commits by other authors a draft follows before it gives up; each one rebuilds it. */
        const val MAX_REBASES = 4
    }
}
