package io.github.psd2live.scenario

import io.github.psd2live.agent.AgentCallRecord
import io.github.psd2live.application.WorkspaceBackend
import io.github.psd2live.project.*
import io.github.psd2live.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.runtime.model.ParameterId
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SaveAndCloseTest {
    @TempDir lateinit var temp: Path

    private fun editor(body: suspend (PSD2LiveViewModel, DesktopWorkspace) -> Unit) = runBlocking {
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(atlasSize = 512, exportMoc3 = false, generatePhysics = false))
            DesktopWorkspace(vm, temp.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                workspace.importPsd(Characters.figurePsd(temp).toString(), true)
                body(vm, workspace)
            }
        }
    }

    private fun observeRead(vm: PSD2LiveViewModel) = vm.agentCallObserver.onCall(
        AgentCallRecord("workspace_call", "job_get", true, true, 1, JsonObject(emptyMap()),
            buildJsonObject { put("status", "running") }, null, emptyList()))

    @Test fun changingAnAgentsParameterDefaultPreservesTheAuthoredPoseThroughSaveCloseAndReopen() = editor { vm, workspace ->
        val archive = temp.resolve("parameter.psd2live")
        vm.saveProjectNow(archive)
        workspace.createParameter(WorkspaceCreateParameterRequest("ParamProbe", workspace.stateToken()!!, "Probe", default = 0.25f))
        workspace.updateParameter(WorkspaceUpdateParameterRequest("ParamProbe", workspace.stateToken()!!, default = 0.75f))
        val probe = ParameterId("ParamProbe")
        assertEquals(0.25f, workspace.authoredPose(vm.state.value.activeWorkspace.id).values[probe])
        val head = workspace.snapshot().historyHeadNodeId
        val model = Oracles.hash(vm.state.value.previewModel!!.rig.puppet)
        repeat(3) {
            vm.saveProjectNow(archive)
            assertFalse(vm.state.value.projectDirty)
            assertEquals(head, workspace.snapshot().historyHeadNodeId)
        }
        vm.selectLayer(vm.state.value.analysis!!.layers.first().source.id.raw)
        val closed = CompletableDeferred<Unit>()
        vm.confirmUnsavedChanges = { 0 }
        vm.withSavedChanges { closed.complete(Unit) }
        withTimeout(20_000) { closed.await() }
        ProjectController(vm).open(workspace, archive)
        assertFalse(vm.state.value.projectDirty)
        assertEquals(0.25f, vm.state.value.parameterValues[probe])
        assertEquals(model, Oracles.hash(vm.state.value.previewModel!!.rig.puppet))
    }

    @Test fun readOnlyPollingDoesNotPreventSaveAndClose() = editor { vm, workspace ->
        val archive = temp.resolve("polling.psd2live")
        vm.saveProjectNow(archive)
        val original = workspace.snapshot()
        repeat(3) {
            coroutineScope {
                val poll = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(20_000) { vm.projectProgress.first { (it?.fraction ?: 0f) >= 0.15f } }
                    repeat(10) { observeRead(vm) }
                }
                vm.saveProjectNow(archive)
                poll.await()
            }
            assertFalse(vm.state.value.projectDirty)
            assertNull(vm.state.value.projectSaveError)
            assertEquals(original.state, workspace.stateToken())
            assertEquals(original.historyHeadNodeId, workspace.snapshot().historyHeadNodeId)
        }
        vm.selectLayer(vm.state.value.analysis!!.layers.first().source.id.raw)
        coroutineScope {
            val poll = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(20_000) { vm.projectProgress.first { (it?.fraction ?: 0f) >= 0.15f } }
                observeRead(vm)
            }
            val closed = CompletableDeferred<Unit>()
            vm.confirmUnsavedChanges = { 0 }
            vm.withSavedChanges { closed.complete(Unit) }
            withTimeout(20_000) { closed.await() }
            poll.await()
        }
    }

    @Test fun aBatchKeepsThePoseIntroducedBeforeALaterDefaultChange() = editor { vm, workspace ->
        workspace.applyDocumentEdits(workspace.stateToken()!!, "Create and update an axis", listOf(
            io.github.psd2live.application.WorkspaceDocumentOperation("parameter_create", buildJsonObject {
                put("parameter_id", "ParamProbe"); put("name", "Probe"); put("default", 0.25f)
            }),
            io.github.psd2live.application.WorkspaceDocumentOperation("parameter_update", buildJsonObject {
                put("parameter_id", "ParamProbe"); put("default", 0.75f)
            })), MutationAuthor.AGENT)
        val probe = ParameterId("ParamProbe")
        assertEquals(0.25f, vm.state.value.parameterValues[probe])
        assertEquals(0.25f, workspace.authoredPose(vm.state.value.activeWorkspace.id).values[probe])
        vm.saveProjectNow(temp.resolve("batch.psd2live"))
        assertFalse(vm.state.value.projectDirty)
        ProjectController(vm).open(workspace, temp.resolve("batch.psd2live"))
        assertEquals(0.25f, vm.state.value.parameterValues[probe])
    }

    @Test fun aMotionCompletingDuringSaveDoesNotMakeTheProjectDirty() = editor { vm, workspace ->
        val clipId = vm.createMotionClip()
        withTimeout(20_000) { vm.state.first { !it.workspaceEditBusy } }
        val clip = vm.state.value.rigEdits.motionClips.single { it.id == clipId }
        val holdClock = AtomicBoolean(true)
        vm.attachWorkspace(object : WorkspaceBackend by workspace {
            override fun playbackFrame(dt: Float?): JsonObject = workspace.playbackFrame(if (holdClock.get()) 0f else dt)
        })
        workspace.controlPlayback(buildJsonObject {
            put("state", workspace.stateToken()); put("mode", "start"); put("clip_id", clipId)
            put("time", clip.duration - 0.01f)
        })
        val token = workspace.stateToken()
        duringSave(vm, workspace, temp.resolve("motion-end.psd2live")) {
            assertTrue(vm.state.value.animationEnabled)
            holdClock.set(false)
            withTimeout(20_000) { vm.state.first { !it.animationEnabled } }
        }
        assertFalse(vm.state.value.projectDirty)
        assertEquals(token, workspace.stateToken())
    }

    @Test fun aRejectedGestureRestoresTheCommittedDocumentAndDoesNotPoisonFutureSaves() = editor { vm, workspace ->
        val archive = temp.resolve("rejected.psd2live")
        vm.saveProjectNow(archive)
        val thickness = vm.state.value.mouthThickness
        vm.beginEditorField("probe-thickness")
        vm.setMouthThickness(4f)
        workspace.updateParameter(WorkspaceUpdateParameterRequest("ParamAngleX", workspace.stateToken()!!, name = "Renamed axis"))
        vm.endEditorField("probe-thickness")
        withTimeout(20_000) { vm.state.first { !it.editorDraftBusy && it.errorMessage != null } }
        workspace.awaitEditorDrafts()
        assertEquals(thickness, vm.state.value.mouthThickness)
        assertEquals("Renamed axis", vm.state.value.previewModel!!.rig.puppet.parameters.single { it.id.raw == "ParamAngleX" }.name)
        val model = Oracles.hash(vm.state.value.previewModel!!.rig.puppet)
        repeat(3) { vm.saveProjectNow(archive); assertFalse(vm.state.value.projectDirty) }
        ProjectController(vm).open(workspace, archive)
        assertEquals(model, Oracles.hash(vm.state.value.previewModel!!.rig.puppet))
    }

    @Test fun failuresBeforeArchiveCaptureAreReportedAndRetryCanSucceed() = editor { vm, workspace ->
        val fail = AtomicBoolean(true)
        vm.attachWorkspace(object : WorkspaceBackend by workspace {
            override suspend fun awaitEditorDrafts() {
                if (fail.get()) throw IllegalStateException("Cannot settle the editor")
                workspace.awaitEditorDrafts()
            }
        })
        val archive = temp.resolve("retry.psd2live")
        assertFailsWith<IllegalStateException> { vm.saveProjectNow(archive) }
        assertEquals("Cannot settle the editor", vm.state.value.projectSaveError)
        assertFalse(vm.state.value.projectSaving)
        assertFalse(Files.exists(archive))
        fail.set(false)
        vm.saveProjectNow(archive)
        assertNull(vm.state.value.projectSaveError)
        assertFalse(vm.state.value.projectSaving)
        assertFalse(vm.state.value.projectDirty)
    }

    @Test fun savingCatchesUpAPresentationLeftBehindAPublishedHistory() = editor { vm, workspace ->
        val old = vm.state.value.historySnapshot
        workspace.updateParameter(WorkspaceUpdateParameterRequest("ParamAngleX", workspace.stateToken()!!, name = "Committed axis"))
        vm.setStateForTest(vm.state.value.copy(historySnapshot = old))
        val archive = temp.resolve("refresh.psd2live")
        vm.saveProjectNow(archive)
        assertEquals(workspace.snapshot().historyHeadNodeId, vm.state.value.historySnapshot?.headNodeId)
        assertFalse(vm.state.value.projectDirty)
        ProjectController(vm).open(workspace, archive)
        assertEquals("Committed axis", vm.state.value.previewModel!!.rig.puppet.parameters.single { it.id.raw == "ParamAngleX" }.name)
    }

    @Test fun saveAndCloseQueuedDuringAnEditRunsWhenTheEditFinishes() = editor { vm, workspace ->
        val archive = temp.resolve("queued.psd2live")
        vm.saveProjectNow(archive)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        vm.attachWorkspace(object : WorkspaceBackend by workspace {
            override suspend fun deleteLayer(layerId: String, expectedState: String, taskId: String?): WorkspaceMutationResult {
                entered.complete(Unit)
                release.await()
                return workspace.deleteLayer(layerId, expectedState, taskId)
            }
        })
        val layer = vm.state.value.analysis!!.layers.first().source.id.raw
        vm.deleteLayer(layer)
        withTimeout(20_000) { entered.await() }
        val closed = CompletableDeferred<Unit>()
        vm.confirmUnsavedChanges = { 0 }
        vm.withSavedChanges { closed.complete(Unit) }
        assertEquals(false, vm.queuedProjectSave)
        release.complete(Unit)
        withTimeout(20_000) { closed.await() }
        assertNull(vm.queuedProjectSave)
        assertFalse(vm.state.value.projectDirty)
        ProjectController(vm).open(workspace, archive)
        assertTrue(vm.state.value.analysis!!.source.layers.none { it.id.raw == layer })
    }

    @Test fun persistentFrameFailuresBackOffWithoutBlockingSaveAndAChangedInputRetriesImmediately() = editor { vm, workspace ->
        val attempts = AtomicInteger()
        val reject = AtomicBoolean(false)
        vm.attachWorkspace(object : WorkspaceBackend by workspace {
            override fun playbackFrame(dt: Float?): JsonObject {
                if (reject.get()) {
                    attempts.incrementAndGet()
                    throw IllegalStateException("Preview failure")
                }
                return workspace.playbackFrame(dt)
            }
        })
        vm.setCanvasMode(vm.state.value.activeCanvas.id, CanvasMode.PREVIEW)
        vm.setAnimationEnabled(true)
        val archive = temp.resolve("preview.psd2live")
        vm.saveProjectNow(archive)
        reject.set(true)
        withTimeout(20_000) { vm.state.first { state -> state.logEntries.any { it.tag == "Motion" && it.message == "Preview failure" } } }
        delay(100)
        assertEquals(1, attempts.get())
        vm.saveProjectNow(archive)
        assertFalse(vm.state.value.projectDirty)
        assertEquals(1, vm.state.value.logEntries.count { it.tag == "Motion" && it.message == "Preview failure" })
        reject.set(false)
        vm.setAnimationEnabled(false)
        vm.setAnimationEnabled(true)
        vm.saveProjectNow(archive)
        assertFalse(vm.state.value.projectDirty)
    }

    @Test fun cancellingCloseWithdrawsTheQueuedCloseButAllowsTheSaveToFinish() = editor { vm, workspace ->
        vm.saveProjectNow(temp.resolve("cancel-close.psd2live"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        vm.attachWorkspace(object : WorkspaceBackend by workspace {
            override suspend fun deleteLayer(layerId: String, expectedState: String, taskId: String?): WorkspaceMutationResult {
                entered.complete(Unit); release.await()
                return workspace.deleteLayer(layerId, expectedState, taskId)
            }
        })
        vm.deleteLayer(vm.state.value.analysis!!.layers.first().source.id.raw)
        withTimeout(20_000) { entered.await() }
        val closed = AtomicBoolean()
        vm.confirmUnsavedChanges = { 0 }
        vm.withSavedChanges { closed.set(true) }
        vm.confirmUnsavedChanges = { 2 }
        vm.withSavedChanges { closed.set(true) }
        release.complete(Unit)
        withTimeout(20_000) { vm.state.first { !it.workspaceEditBusy && !it.projectDirty && !it.projectSaving } }
        withContext(Dispatchers.Main) { yield() }
        assertFalse(closed.get())
        assertNull(vm.queuedProjectSave)
    }

    @Test fun anEditStartingAfterTheDiskCommitCannotRunThePendingClose() = editor { vm, workspace ->
        vm.saveProjectNow(temp.resolve("after-commit.psd2live"))
        val layer = vm.state.value.analysis!!.layers.first().source.id.raw
        val interfere = AtomicBoolean(true)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        vm.attachWorkspace(object : WorkspaceBackend by workspace {
            override suspend fun saveProjectAt(path: Path?): WorkspaceMutationResult {
                val result = workspace.saveProjectAt(path)
                if (interfere.getAndSet(false)) vm.deleteLayer(layer)
                return result
            }
            override suspend fun deleteLayer(layerId: String, expectedState: String, taskId: String?): WorkspaceMutationResult {
                entered.complete(Unit); release.await()
                return workspace.deleteLayer(layerId, expectedState, taskId)
            }
        })
        vm.selectLayer(layer)
        val closed = CompletableDeferred<Unit>()
        vm.confirmUnsavedChanges = { 0 }
        vm.withSavedChanges { closed.complete(Unit) }
        try {
            withTimeout(20_000) { entered.await() }
            withContext(Dispatchers.Main) { yield() }
            assertFalse(closed.isCompleted)
        } finally { release.complete(Unit) }
        withTimeout(20_000) { vm.state.first { !it.workspaceEditBusy } }
        assertTrue(vm.state.value.projectDirty)
        vm.withSavedChanges { closed.complete(Unit) }
        withTimeout(20_000) { closed.await() }
        assertFalse(vm.state.value.projectDirty)
    }

    @Test fun anEditStillRunningWhenTheArchiveCompletesCannotBeMarkedSaved() = editor { vm, workspace ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        vm.attachWorkspace(object : WorkspaceBackend by workspace {
            override suspend fun deleteLayer(layerId: String, expectedState: String, taskId: String?): WorkspaceMutationResult {
                entered.complete(Unit); release.await()
                return workspace.deleteLayer(layerId, expectedState, taskId)
            }
        })
        val archive = temp.resolve("in-flight.psd2live")
        try {
            duringSave(vm, workspace, archive) {
                vm.deleteLayer(vm.state.value.analysis!!.layers.first().source.id.raw)
                withTimeout(20_000) { entered.await() }
            }
            assertTrue(vm.state.value.workspaceEditBusy)
            assertTrue(vm.state.value.projectDirty)
        } finally { release.complete(Unit) }
        withTimeout(20_000) { vm.state.first { !it.workspaceEditBusy } }
        vm.saveProjectNow(archive)
        assertFalse(vm.state.value.projectDirty)
    }

    /** Pauses just before the real archive write, after capturing and staging its immutable input. */
    private suspend fun duringSave(vm: PSD2LiveViewModel, workspace: DesktopWorkspace, path: Path,
                                   edit: suspend () -> Unit) = coroutineScope {
        val staged = CompletableDeferred<Unit>()
        val resume = CountDownLatch(1)
        val repository = ProjectRepository(writeArchive = { root, target, id ->
            staged.complete(Unit)
            check(resume.await(20, TimeUnit.SECONDS))
            ProjectArchive.write(root, target, id)
        })
        val save = async { ProjectController(vm, repository).save(workspace, path) }
        try { withTimeout(20_000) { staged.await() }; edit() }
        finally { resume.countDown() }
        save.await()
    }

    @Test fun anAgentEditDuringArchiveWriteRemainsUnsavedAndTheArchiveKeepsItsCapturedVersion() = editor { vm, workspace ->
        val archive = temp.resolve("concurrent.psd2live")
        val name = vm.state.value.previewModel!!.rig.puppet.parameters.single { it.id.raw == "ParamAngleX" }.name
        duringSave(vm, workspace, archive) {
            workspace.updateParameter(WorkspaceUpdateParameterRequest("ParamAngleX", workspace.stateToken()!!, name = "Later edit"))
        }
        assertTrue(vm.state.value.projectDirty)
        ProjectRepository().open(archive).use { saved ->
            val head = saved.history.head().snapshot
            val model = io.github.psd2live.application.WorkspacePreviewBuilder().build(head)
            assertEquals(name, model.rig.puppet.parameters.single { it.id.raw == "ParamAngleX" }.name)
        }
        vm.saveProjectNow(archive)
        assertFalse(vm.state.value.projectDirty)
    }

    @Test fun poseAndCameraEditsDuringArchiveWriteAreNotMarkedSaved() = editor { vm, workspace ->
        val archive = temp.resolve("pose-camera.psd2live")
        val head = workspace.snapshot().historyHeadNodeId
        duringSave(vm, workspace, archive) {
            workspace.setPreviewSession(buildJsonObject {
                put("state", workspace.stateToken()); put("mode", "set")
                putJsonObject("values") { put("ParamAngleX", 20f) }
            })
        }
        assertTrue(vm.state.value.projectDirty)
        assertEquals(head, workspace.snapshot().historyHeadNodeId)
        vm.saveProjectNow(archive)
        duringSave(vm, workspace, archive) {
            vm.setCanvasView(2f, 30f, 40f)
        }
        assertTrue(vm.state.value.projectDirty)
        vm.saveProjectNow(archive)
        val posed = org.umamo.render.eval.CpuDeformationEvaluator().evaluate(vm.state.value.previewModel!!.rig.puppet,
            vm.state.value.parameterValues).worldPositions.mapValues { it.value.toList() }
        ProjectController(vm).open(workspace, archive)
        assertEquals(20f, vm.state.value.parameterValues[ParameterId("ParamAngleX")])
        assertEquals(TabCamera(2f, 30f, 40f), vm.state.value.activeCanvas.camera)
        assertEquals(posed, org.umamo.render.eval.CpuDeformationEvaluator().evaluate(vm.state.value.previewModel!!.rig.puppet,
            vm.state.value.parameterValues).worldPositions.mapValues { it.value.toList() })
        assertFalse(vm.state.value.projectDirty)
    }
}
