package io.github.psd2live.history

import kotlin.test.*

class WorkspaceHistorySnapshotTest {
    @Test fun capturedPagesStayImmutableAcrossAppendsCheckoutsAndRestoration() {
        var sequence = 0
        val tree = WorkspaceHistoryTree("root", "r0", "r0", newId = { "node-${sequence++}" })
        val root = tree.state()
        repeat(130) { index -> tree.commit(tree.head().node.id, "value-$index", "r$index", "r$index", "Edit $index") }
        val captured = tree.state()
        tree.checkout(root.headNodeId)
        tree.commit(root.headNodeId, "branch", "branch", "branch", "Branch")
        assertEquals(1, root.selections.size)
        assertEquals(131, captured.selections.size)
        assertEquals("value-129", captured.selections.last().snapshot)
        assertEquals(132, tree.state().selections.size)
        val restored = WorkspaceHistoryTree.restore(captured.selections, captured.headNodeId)
        assertEquals(captured, restored.state())
        assertSame(captured.selections, captured.copy(headNodeId = root.headNodeId).selections)
    }
}
