package io.github.psd2live.tools

import io.github.psd2live.history.WorkspaceHistorySelection
import io.github.psd2live.history.WorkspaceHistoryTree
import io.github.psd2live.ui.state.*
import java.lang.management.ManagementFactory
import kotlin.test.Test

/** Synthetic history and presentation allocation measurements; opt-in, no project fixtures or rendering. */
class WorkspaceLifecyclePerfTool {
    @Volatile private var sink: Any? = null

    @Test fun measureSnapshotsAndPresentationIdentity() {
        requireTools()
        val allocation = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        val report = StringBuilder("Synthetic workspace lifecycle measurements. Baselines reproduce eager snapshot/identity rebuilding.\n")
        fun measure(name: String, repetitions: Int, action: () -> Any?) {
            repeat(20) { sink = action() }
            val thread = Thread.currentThread().threadId()
            val beforeBytes = allocation?.getThreadAllocatedBytes(thread) ?: 0
            val start = System.nanoTime()
            repeat(repetitions) { sink = action() }
            val elapsed = (System.nanoTime() - start) / 1_000_000.0
            val bytes = (allocation?.getThreadAllocatedBytes(thread) ?: beforeBytes) - beforeBytes
            report.appendLine("$name: repetitions=$repetitions milliseconds=$elapsed allocated_bytes=$bytes")
        }
        var id = 0
        val history = WorkspaceHistoryTree(0, "root", "root", newId = { "history-${id++}" })
        repeat(20_000) { history.commit(history.head().node.id, it, "r$it", "r$it", "Edit") }
        measure("history eager baseline", 200) { history.selections().map { WorkspaceHistorySelection(it.node, it.snapshot) } }
        measure("history shared pages", 200) { history.state() }
        val state = PSD2LiveState().let { base -> base.copy(workspaces = (0 until 20).map { index ->
            base.activeWorkspace.copy(id = "workspace-$index", canvases = (0 until 4).map {
                base.activeCanvas.copy(id = "canvas-$it")
            })
        }, activeWorkspaceId = "workspace-0") }
        val identity = state.saveIdentity
        measure("presentation eager identity baseline", 2_000) { WorkspaceDurableProjection.of(state.copy(historyZoom = 2f)) }
        measure("presentation shared identity", 2_000) { state.copy(historyZoom = 2f).also { it.inheritSaveIdentity(state) }.saveIdentity }
        check(sink === identity)
        output("workspace-lifecycle").resolve("performance.txt").writeText(report.toString())
        println(report)
    }
}
