package io.github.psd2live.application

import io.github.psd2live.core.PipelineConfig
import kotlin.test.*

class WorkspaceLifecycleCoordinatorTest {
    private val original = WorkspaceDurableIdentity(null, PipelineConfig(), "output", emptyList(), emptyMap(), 0, emptyList())

    @Test fun savingAnEarlierCaptureDoesNotClearNewContentOrAnotherLoad() {
        val lifecycle = WorkspaceLifecycleCoordinator()
        lifecycle.install("project", 1, original, true, "first.psd2live")
        val next = original.copy(outputPath = "later-output")
        assertTrue(lifecycle.observe("project", 1, next, false, false, false))
        lifecycle.saved("project", 1, original, "saved-copy.psd2live")
        assertTrue(lifecycle.observe("project", 1, next, false, false, false))
        assertEquals("saved-copy.psd2live", lifecycle.capture()!!.projectFile)
        lifecycle.install("project", 2, original, true, "reopened.psd2live")
        lifecycle.saved("project", 1, next, "stale.psd2live")
        assertFalse(lifecycle.observe("project", 2, original, false, false, false))
        assertEquals("reopened.psd2live", lifecycle.capture()!!.projectFile)
    }

    @Test fun unchangedObservationsAndBusyStateDoNotAdvanceContentOrCreateDirtyState() {
        val lifecycle = WorkspaceLifecycleCoordinator()
        lifecycle.install("project", 1, original, true)
        repeat(100) { assertFalse(lifecycle.observe("project", 1, original, false, false, true)) }
        assertEquals(0, lifecycle.capture()!!.contentVersion)
        assertTrue(lifecycle.observe("project", 1, original, true, false, false))
    }
}
