package io.github.psd2live.application

import io.github.psd2live.core.PreviewFrame
import kotlin.test.*

class WorkspaceRenderCoordinatorTest {
    @Test fun sharedViewReleasesOnceAndLateFramesCannotRecreateIt() {
        val render = WorkspaceRenderCoordinator()
        val key = "1/workspace/canvas/PREVIEW"
        val flow = render.retain(key)
        render.retain(key)
        val frame = PreviewFrame(8, 8, emptyMap(), viewId = key)
        assertTrue(render.publish(frame))
        assertSame(frame, flow.value)
        assertFalse(render.releaseRetained(key))
        assertTrue(render.releaseRetained(key))
        render.remove(key)
        assertNull(flow.value)
        assertFalse(render.publish(frame))
        assertFalse(render.releaseRetained(key))
    }

    @Test fun modelLoadsOnlyForAMountedLiveViewAndFramesStayIndependent() {
        val render = WorkspaceRenderCoordinator()
        render.invalidateModel()
        assertFalse(render.claimModelLoad(true))
        val first = render.retain("a")
        val second = render.retain("b")
        assertFalse(render.claimModelLoad(false))
        assertTrue(render.claimModelLoad(true))
        assertFalse(render.claimModelLoad(true))
        render.publish(PreviewFrame(8, 8, emptyMap(), viewId = "a"))
        assertNotNull(first.value)
        assertNull(second.value)
        render.close()
        assertNull(first.value)
        assertFalse(render.contains("a"))
        assertFalse(render.claimModelLoad(true))
    }
}
