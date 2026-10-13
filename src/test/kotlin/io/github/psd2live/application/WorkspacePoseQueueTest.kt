package io.github.psd2live.application

import org.umamo.runtime.model.ParameterId
import kotlin.test.*

class WorkspacePoseQueueTest {
    @Test fun projectionKeepsLaterGesturesAndDoesNotMixWorkspaceOrLoadGeneration() {
        val queue = WorkspacePoseQueue()
        val parameter = ParameterId("Angle")
        val first = queue.open(1, "a", mapOf(parameter to 1f))
        queue.open(1, "a", mapOf(parameter to 2f))
        queue.open(2, "a", mapOf(parameter to 3f))
        queue.open(1, "b", mapOf(parameter to 4f))
        assertEquals(mapOf(parameter to 2f), queue.consume(1, "a", first.id))
        assertFalse(queue.contains(first.id))
        queue.discard(1, "a")
        assertTrue(queue.values(1, "a").isEmpty())
        assertEquals(mapOf(parameter to 3f), queue.values(2, "a"))
        assertEquals(mapOf(parameter to 4f), queue.values(1, "b"))
    }

    @Test fun onlyOwnedCommitsAdvanceTheExpectedStateAndBusyIncludesQueuedWork() {
        val queue = WorkspacePoseQueue()
        queue.committed("a", "b")
        queue.committed("b", "c")
        assertEquals("c", queue.lineage("a"))
        assertEquals("external", queue.lineage("external"))
        queue.enqueued(); queue.enqueued()
        queue.finished()
        assertTrue(queue.busy)
        queue.finished()
        assertFalse(queue.busy)
    }
}
