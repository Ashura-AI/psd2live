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

    private fun changePresentation(vm: PSD2LiveViewModel) {
        val layer = vm.state.value.analysis!!.layers.first().source.id.raw
        vm.selectLayer(layer)
        vm.selectLayers(listOf(layer))
        vm.deselectLayers(listOf(layer))
        vm.selectHistoryNode(vm.state.value.historySnapshot!!.headNodeId)
        vm.setParameterSearchQuery("Angle")
        vm.setHierarchyView(width = 250f, collapsed = true, search = "layer")
        vm.setHistoryView(1.5f, 10f, 20f, "edit", true)
        vm.setHistoryCompressed(false)
        vm.setDrawOrderRulerWidth(45f)
        vm.setInspectorCollapsed(true)
        vm.setDynamicsSubExpanded(true)
        vm.setSimulationPresetsExpanded(true)
        vm.setStrengthSubExpanded(true)
        vm.setRigTuningExpanded(true)
        vm.setRigTuningAdvancedExpanded(true)
        vm.setLogPanelExpanded(true)
        vm.setLogPanelHeight(200f)
        vm.setWorkspaceLayout(vm.state.value.activeWorkspace.id, "{\"layout\":[]}")
        vm.setCanvasView(2f, 30f, 40f)
        vm.setCanvasViewOptions(options = vm.state.value.activeCanvas.view.copy(showMesh = true))
        vm.setCanvasMode(vm.state.value.activeCanvas.id, CanvasMode.PREVIEW)
        vm.setAnimationEnabled(true)
        vm.setMouseTrackingEnabled(false)
        vm.setSmoothMouseTracking(true)
        vm.clearLogs()
    }

    @Test fun presentationChangesAndRepeatedSettersDoNotRequireSaveOrPreventClosing() = editor { vm, workspace ->
        vm.saveProjectNow(temp.resolve("presentation.psd2live"))
        val version = vm.state.value.projectEditVersion
        val identity = vm.state.value.saveIdentity
        val contentVersion = vm.lifecycle.capture()!!.contentVersion
        val token = workspace.stateToken()
        val head = workspace.snapshot().historyHeadNodeId
        repeat(2) { changePresentation(vm) }
        assertFalse(vm.state.value.projectDirty)
        assertFalse(workspace.hasUnsavedChanges())
        assertEquals(version, vm.state.value.projectEditVersion)
        assertSame(identity, vm.state.value.saveIdentity, "Presentation must share the existing content identity")
        assertEquals(contentVersion, vm.lifecycle.capture()!!.contentVersion)
        assertEquals(token, workspace.stateToken())
        assertEquals(head, workspace.snapshot().historyHeadNodeId)
        var closed = false
        vm.confirmUnsavedChanges = { fail("Presentation must not trigger Save/Discard") }
        vm.withSavedChanges { closed = true }
        assertTrue(closed)
        vm.saveProjectNow(temp.resolve("presentation.psd2live"))
        workspace.open(temp.resolve("presentation.psd2live"))
        assertEquals(TabCamera(2f, 30f, 40f), vm.state.value.activeCanvas.camera)
        assertFalse(vm.state.value.historyCompressed, "The history view mode must survive save and reopen")
        assertFalse(vm.state.value.projectDirty)
        assertEquals(head, workspace.snapshot().historyHeadNodeId)
    }

    @Test fun presentationChangingDuringSaveStillAllowsTheCapturedContentToBecomeClean() = editor { vm, workspace ->
        duringSave(vm, workspace, temp.resolve("changing-presentation.psd2live")) { changePresentation(vm) }
        assertFalse(vm.state.value.projectDirty)
        assertFalse(workspace.hasUnsavedChanges())
        assertNull(vm.state.value.projectSaveError)
    }

    @Test fun changingAnUncommittedFieldBackToItsSavedValueClearsTheDirtyState() = editor { vm, workspace ->
        vm.saveProjectNow(temp.resolve("reverted-field.psd2live"))
        val before = vm.state.value
        vm.setOutputPath(temp.resolve("different-output").toString())
        assertTrue(vm.state.value.projectDirty)
        vm.setOutputPath(before.outputPath)
        assertFalse(vm.state.value.projectDirty)
        vm.beginEditorField("export-setting")
        vm.setExportIncludePhysics(!before.exportIncludePhysics)
        assertTrue(vm.state.value.projectDirty)
        vm.setExportIncludePhysics(before.exportIncludePhysics)
        vm.endEditorField("export-setting")
        assertFalse(vm.state.value.projectDirty)
        assertEquals(before.historySnapshot!!.headNodeId, workspace.snapshot().historyHeadNodeId)
    }

    @Test fun aRevertedModelGestureDoesNotLeaveDirtyStateOrAHistoryNode() = editor { vm, workspace ->
        vm.saveProjectNow(temp.resolve("reverted-model.psd2live"))
        val before = vm.state.value
        vm.beginEditorField("model-setting")
        vm.setMouthThickness(4f)
        withTimeout(20_000) { vm.state.first { it.previewModel?.config?.mouthThickness == 4f } }
        vm.setMouthThickness(before.mouthThickness)
        vm.endEditorField("model-setting")
        workspace.awaitEditorDrafts()
        withTimeout(20_000) { vm.state.first { !it.editorDraftBusy } }
        assertEquals(before.historySnapshot!!.headNodeId, workspace.snapshot().historyHeadNodeId)
        assertFalse(vm.state.value.projectDirty)
        assertFalse(workspace.hasUnsavedChanges())
    }

    @Test fun anInFlightNoOpDoesNotChangeTheTitleButStillGuardsClosing() = editor { vm, workspace ->
        vm.saveProjectNow(temp.resolve("no-op-history.psd2live"))
        val head = workspace.snapshot().historyHeadNodeId!!
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        vm.attachWorkspace(object : WorkspaceBackend by workspace {
            override suspend fun checkoutHistory(nodeId: String, author: MutationAuthor): WorkspaceMutationResult {
                entered.complete(Unit); release.await()
                return workspace.checkoutHistory(nodeId, author)
            }
        })
        vm.checkoutHistoryNode(head)
        withTimeout(20_000) { entered.await() }
        try {
            assertTrue(vm.state.value.workspaceEditBusy)
            assertFalse(vm.state.value.projectDirty)
            var prompted = false
            vm.confirmUnsavedChanges = { prompted = true; 2 }
            vm.withSavedChanges { fail("Closing must still wait for the pending operation") }
            assertTrue(prompted)
        } finally { release.complete(Unit) }
        withTimeout(20_000) { vm.state.first { !it.workspaceEditBusy } }
        assertFalse(vm.state.value.projectDirty)
        assertEquals(head, workspace.snapshot().historyHeadNodeId)
    }

    /** Holds the real open immediately before installation, so edits during loading are deterministic. */
    private suspend fun openWithInterveningEdit(vm: PSD2LiveViewModel, workspace: DesktopWorkspace, file: Path,
                                              discard: Boolean, edit: suspend () -> Unit) {
        val expected = workspace.captureProjectOpen(discard)
        ProjectRepository().open(file).use { opened ->
            edit()
            val loaded = workspace.installProject(opened.projectId, opened.file, opened.source,
                WorkspaceStateCodec.decode(opened.presentation), opened.history, opened.store, expected, discard, opened.presentation)
            workspace.rememberProjectDirectory(opened.transferDirectory())
            vm.refreshWorkspaceRenderer(loaded.model)
        }
    }

    @Test fun discardingEditsOpensTheProjectDespitePresentationUpdatesAndClearsOldRecovery() = editor { vm, workspace ->
        val archive = temp.resolve("discard.psd2live")
        vm.saveProjectNow(archive)
        val savedModel = Oracles.hash(vm.state.value.previewModel!!.rig.puppet)
        workspace.updateParameter(WorkspaceUpdateParameterRequest("ParamAngleX", workspace.stateToken()!!, name = "Discard this edit"))
        workspace.flushProjectPersistence()
        assertNotNull(workspace.sessionRecovery.pending())
        openWithInterveningEdit(vm, workspace, archive, true) { changePresentation(vm) }
        workspace.flushProjectPersistence()
        assertFalse(vm.state.value.projectDirty)
        assertFalse(workspace.hasUnsavedChanges())
        assertNull(workspace.sessionRecovery.pending())
        assertEquals(savedModel, Oracles.hash(vm.state.value.previewModel!!.rig.puppet))
    }

    @Test fun openingACleanProjectAllowsPresentationChangesWithoutDiscardPermission() = editor { vm, workspace ->
        val archive = temp.resolve("clean-open.psd2live")
        vm.saveProjectNow(archive)
        openWithInterveningEdit(vm, workspace, archive, false) { changePresentation(vm) }
        assertFalse(vm.state.value.projectDirty)
        assertFalse(workspace.hasUnsavedChanges())
    }

    @Test fun discardDoesNotAuthorizeLosingAnEditMadeAfterOpeningStarted() = editor { vm, workspace ->
        val archive = temp.resolve("late-edit.psd2live")
        vm.saveProjectNow(archive)
        val generation = vm.state.value.projectOpenGeneration
        assertFailsWith<IllegalArgumentException> {
            openWithInterveningEdit(vm, workspace, archive, true) {
                workspace.updateParameter(WorkspaceUpdateParameterRequest("ParamAngleX", workspace.stateToken()!!, name = "Keep the newer edit"))
            }
        }
        assertEquals(generation, vm.state.value.projectOpenGeneration)
        assertTrue(vm.state.value.projectDirty)
        assertEquals("Keep the newer edit", vm.state.value.previewModel!!.rig.puppet.parameters.single { it.id.raw == "ParamAngleX" }.name)
    }

    @Test fun unsavedChangesAreRejectedAndFailedDiscardOpenPreservesTheCurrentProject() = editor { vm, workspace ->
        val archive = temp.resolve("protected.psd2live")
        vm.saveProjectNow(archive)
        vm.setOutputPath(temp.resolve("unsaved-output").toString())
        val current = vm.state.value
        assertFailsWith<io.github.psd2live.application.WorkspaceUnsavedChanges> { vm.openProjectNow(archive) }
        val broken = temp.resolve("broken.psd2live")
        Files.writeString(broken, "invalid archive")
        assertFails { vm.openProjectNow(broken, discardUnsaved = true) }
        assertEquals(current.saveIdentity, vm.state.value.saveIdentity)
        assertEquals(current.projectOpenGeneration, vm.state.value.projectOpenGeneration)
        assertEquals(current.projectFile, vm.state.value.projectFile)
        assertTrue(vm.state.value.projectDirty)
    }

    @Test fun cancelKeepsTheWorkspaceAndDiscardReplacesItThroughTheGuiEntry() = editor { vm, workspace ->
        val archive = temp.resolve("gui-discard.psd2live")
        vm.saveProjectNow(archive)
        val generation = vm.state.value.projectOpenGeneration
        vm.setOutputPath(temp.resolve("discard-output").toString())
        var prompts = 0
        vm.confirmUnsavedChanges = { prompts++; 2 }
        vm.openProject(archive)
        assertEquals(generation, vm.state.value.projectOpenGeneration)
        assertTrue(vm.state.value.projectDirty)
        vm.confirmUnsavedChanges = { prompts++; 1 }
        vm.openProject(archive)
        withTimeout(20_000) { vm.state.first { it.projectOpenGeneration > generation } }
        assertEquals(2, prompts)
        assertFalse(vm.state.value.projectDirty)
        assertNull(vm.state.value.errorMessage)
    }

    @Test fun discardAndOpenWaitsForAnEarlierEditAndThenReplacesTheProject() = editor { vm, workspace ->
        val archive = temp.resolve("discard-pending.psd2live")
        vm.saveProjectNow(archive)
        val generation = vm.state.value.projectOpenGeneration
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        vm.attachWorkspace(object : WorkspaceBackend by workspace {
            override suspend fun deleteLayer(layerId: String, expectedState: String, taskId: String?): WorkspaceMutationResult {
                entered.complete(Unit); release.await()
                return workspace.deleteLayer(layerId, expectedState, taskId)
            }
        })
        val layer = vm.state.value.analysis!!.layers.first().source.id.raw
        vm.deleteLayer(layer)
        withTimeout(20_000) { entered.await() }
        vm.confirmUnsavedChanges = { 1 }
        vm.openProject(archive)
        try {
            withContext(Dispatchers.Main) { yield() }
            assertEquals(generation, vm.state.value.projectOpenGeneration)
        } finally { release.complete(Unit) }
        withTimeout(20_000) { vm.state.first { it.projectOpenGeneration > generation } }
        assertTrue(vm.state.value.analysis!!.source.layers.any { it.id.raw == layer })
        assertFalse(vm.state.value.projectDirty)
        assertNull(vm.state.value.errorMessage)
    }

    @Test fun failedArchiveWriteKeepsThePreviousArchiveAndAllowsRetry() = editor { vm, workspace ->
        val archive = temp.resolve("disk-failure.psd2live")
        vm.saveProjectNow(archive)
        val original = Files.readAllBytes(archive)
        vm.setOutputPath(temp.resolve("changed-output").toString())
        val repository = ProjectRepository(writeArchive = { _, _, _ -> throw java.io.IOException("Archive write failed") })
        assertFailsWith<java.io.IOException> { workspace.save(archive, repository = repository) }
        assertContentEquals(original, Files.readAllBytes(archive))
        assertTrue(vm.state.value.projectDirty)
        assertFalse(vm.state.value.projectSaving)
        assertEquals("Archive write failed", vm.state.value.projectSaveError)
        vm.saveProjectNow(archive)
        assertFalse(vm.state.value.projectDirty)
        assertNull(vm.state.value.projectSaveError)
        workspace.open(archive)
        assertEquals(temp.resolve("changed-output").toString(), vm.state.value.outputPath)
    }

    @Test fun aResultValidationFailureAfterTheArchiveCommittedDoesNotLeaveSaveBusy() = editor { vm, workspace ->
        val archive = temp.resolve("committed-result.psd2live")
        val completion = io.github.psd2live.application.WorkspaceJobCompletion { error("Invalid job result") }
        assertFailsWith<IllegalStateException> {
            withContext(completion) { workspace.save(archive) }
        }
        assertNotNull(completion.result)
        assertTrue(Files.isRegularFile(archive))
        assertFalse(vm.state.value.projectSaving)
        assertFalse(vm.state.value.projectDirty)
        assertFalse(workspace.hasUnsavedChanges())
        vm.saveProjectNow(archive)
        assertFalse(vm.state.value.projectSaving)
    }

    @Test fun openingReleasesTheReplacedArchiveDirectoryAndClosingTheLast() = editor { vm, workspace ->
        val archive = temp.resolve("cleanup.psd2live")
        vm.saveProjectNow(archive)
        workspace.open(archive)
        val first = Path.of(vm.state.value.loadedInputPath!!).parent.parent
        assertTrue(Files.isDirectory(first))
        workspace.open(archive)
        val second = Path.of(vm.state.value.loadedInputPath!!).parent.parent
        workspace.flushProjectPersistence()
        assertFalse(Files.exists(first))
        assertTrue(Files.isDirectory(second))
        workspace.close()
        assertFalse(Files.exists(first))
        assertFalse(Files.exists(second))
        assertTrue(Files.isRegularFile(archive))
    }

    @Test fun closingPreservesTheExtractedStoreNeededForUnsavedRecovery() = editor { vm, workspace ->
        val archive = temp.resolve("retain-recovery.psd2live")
        vm.saveProjectNow(archive)
        workspace.open(archive)
        val extracted = Path.of(vm.state.value.loadedInputPath!!).parent.parent
        try {
            workspace.updateParameter(WorkspaceUpdateParameterRequest("ParamAngleX", workspace.stateToken()!!, name = "Recover this edit"))
            workspace.close()
            val session = assertNotNull(workspace.sessionRecovery.pending())
            assertTrue(Files.isRegularFile(session.head))
            assertTrue(Files.isDirectory(extracted))
        } finally {
            workspace.sessionRecovery.forget()
            ProjectArchive.deleteTemporaryDirectory(extracted)
        }
    }

    @Test fun auxiliaryOnlyChangesAndWorkspaceContentRecoverTogetherWithoutAddingHistory() = editor { vm, workspace ->
        vm.saveProjectNow(temp.resolve("complete-recovery.psd2live"))
        val head = workspace.snapshot().historyHeadNodeId!!
        val layer = vm.state.value.analysis!!.layers.first().source.id.raw
        workspace.setPreviewSession(buildJsonObject {
            put("state", workspace.stateToken()); put("mode", "set")
            putJsonObject("values") { put("ParamAngleX", 20f) }
            putJsonObject("locks") { put("ParamAngleX", true) }
        })
        vm.saveParameterSnapshot("Recovery pose")
        vm.editHistoryAnnotation(head, "Recovery title", "Recovery note", true)
        vm.toggleLayerVisibility(layer)
        vm.setOutputPath(temp.resolve("recovered-output").toString())
        val expected = vm.state.value
        val model = Oracles.hash(expected.previewModel!!.rig.puppet)
        workspace.flushProjectPersistence()
        val session = assertNotNull(workspace.sessionRecovery.pending())
        assertEquals(head, workspace.snapshot().historyHeadNodeId)
        workspace.close()
        PSD2LiveViewModel().use { reopened ->
            DesktopWorkspace(reopened, temp.resolve("recovery-store")).use { replacement ->
                reopened.attachWorkspace(replacement)
                replacement.restoreSession(session)
                assertEquals(expected.parameterSnapshots, reopened.state.value.parameterSnapshots)
                assertEquals(expected.historyAnnotations, reopened.state.value.historyAnnotations)
                assertEquals(expected.parameterValues, reopened.state.value.parameterValues)
                assertEquals(expected.lockedParameters, reopened.state.value.lockedParameters)
                assertEquals(expected.layerVisibility, reopened.state.value.layerVisibility)
                assertEquals(expected.outputPath, reopened.state.value.outputPath)
                assertEquals(head, replacement.snapshot().historyHeadNodeId)
                assertEquals(model, Oracles.hash(reopened.state.value.previewModel!!.rig.puppet))
                assertTrue(reopened.state.value.projectDirty)
                reopened.saveProjectNow(temp.resolve("recovered.psd2live"))
                replacement.open(temp.resolve("recovered.psd2live"))
                assertEquals(expected.parameterSnapshots, reopened.state.value.parameterSnapshots)
                assertFalse(reopened.state.value.projectDirty)
            }
        }
    }

    @Test fun deletingOrChangingTheExternalSourceDoesNotChangeSavedImportBytes() = editor { vm, workspace ->
        val source = Path.of(vm.state.value.loadedInputPath!!)
        val bytes = Files.readAllBytes(source)
        val model = Oracles.hash(vm.state.value.previewModel!!.rig.puppet)
        Files.writeString(source, "External file was changed after import")
        val changed = temp.resolve("changed-origin.psd2live")
        vm.saveProjectNow(changed)
        java.util.zip.ZipFile(changed.toFile()).use { zip ->
            assertContentEquals(bytes, zip.getInputStream(zip.getEntry("source/original.psd")).use { it.readAllBytes() })
        }
        Files.delete(source)
        val deleted = temp.resolve("deleted-origin.psd2live")
        vm.saveProjectNow(deleted)
        workspace.open(deleted)
        assertEquals(model, Oracles.hash(vm.state.value.previewModel!!.rig.puppet))
        assertEquals(model, Oracles.hash(io.github.psd2live.application.WorkspaceModelLoader.load(deleted).rig.puppet))
        vm.saveProjectNow(deleted)
        assertFalse(vm.state.value.projectDirty)
    }

    @Test fun explicitDiscardReleasesTheRecoveryDirectoryAndMarker() = editor { vm, workspace ->
        val archive = temp.resolve("discard-close.psd2live")
        vm.saveProjectNow(archive)
        workspace.open(archive)
        val directory = Path.of(vm.state.value.loadedInputPath!!).parent.parent
        vm.saveParameterSnapshot("Discard snapshot")
        workspace.flushProjectPersistence()
        assertNotNull(workspace.sessionRecovery.pending())
        workspace.prepareClose(io.github.psd2live.application.WorkspaceCloseReason.DISCARDED)
        workspace.close()
        workspace.close()
        assertNull(workspace.sessionRecovery.pending())
        assertFalse(Files.exists(directory))
        assertTrue(Files.isRegularFile(archive))
    }

    @Test fun anImportedCmo3KeepsItsOriginalPayloadAfterTheExternalFileIsDeleted() = editor { vm, workspace ->
        val exported = temp.resolve("import-payload")
        val result = workspace.exportTarget(workspace.stateToken()!!, "cmo3", exported.toString(), emptyMap())
        val source = exported.resolve(result.getValue("files").jsonArray.map { it.jsonPrimitive.content }.single { it.endsWith(".cmo3") })
        val bytes = Files.readAllBytes(source)
        workspace.importCmo3(source, io.github.psd2live.core.Cmo3ImportMode.NEW, true)
        val model = Oracles.hash(vm.state.value.previewModel!!.rig.puppet)
        Files.delete(source)
        val archive = temp.resolve("owned-cmo3.psd2live")
        vm.saveProjectNow(archive)
        java.util.zip.ZipFile(archive.toFile()).use { zip ->
            assertContentEquals(bytes, zip.getInputStream(zip.getEntry("source/original.cmo3")).use { it.readAllBytes() })
        }
        workspace.open(archive)
        assertEquals(model, Oracles.hash(vm.state.value.previewModel!!.rig.puppet))
    }

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
        workspace.open(archive)
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
        vm.setOutputPath(temp.resolve("export-polling").toString())
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
        workspace.open(temp.resolve("batch.psd2live"))
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

    @Test fun aGestureOverAnExternalCommitLandsAndDoesNotPoisonFutureSaves() = editor { vm, workspace ->
        val archive = temp.resolve("carried.psd2live")
        vm.saveProjectNow(archive)
        vm.beginEditorField("probe-thickness")
        vm.setMouthThickness(4f)
        workspace.updateParameter(WorkspaceUpdateParameterRequest("ParamAngleX", workspace.stateToken()!!, name = "Renamed axis"))
        vm.endEditorField("probe-thickness")
        workspace.awaitEditorDrafts()
        withTimeout(20_000) { vm.state.first { !it.editorDraftBusy } }
        assertNull(vm.state.value.errorMessage)
        assertEquals(4f, vm.state.value.mouthThickness)
        assertEquals(4f, workspace.captureQueries().projectSettings().getValue("mouthThickness").jsonPrimitive.float)
        assertEquals("Renamed axis", vm.state.value.previewModel!!.rig.puppet.parameters.single { it.id.raw == "ParamAngleX" }.name)
        val model = Oracles.hash(vm.state.value.previewModel!!.rig.puppet)
        repeat(3) { vm.saveProjectNow(archive); assertFalse(vm.state.value.projectDirty) }
        workspace.open(archive)
        assertEquals(model, Oracles.hash(vm.state.value.previewModel!!.rig.puppet))
    }

    @Test fun aGestureOverAnExternalCommitOfTheSameSettingYieldsToIt() = editor { vm, workspace ->
        val archive = temp.resolve("yielded.psd2live")
        vm.saveProjectNow(archive)
        val nodes = workspace.history().nodes.size
        vm.beginEditorField("probe-thickness")
        vm.setMouthThickness(4f)
        workspace.updateProjectSettings(workspace.stateToken()!!, buildJsonObject { put("mouthThickness", 6f) })
        assertEquals(6f, vm.state.value.mouthThickness, "the editor kept its draft over the committed value")
        vm.endEditorField("probe-thickness")
        workspace.awaitEditorDrafts()
        withTimeout(20_000) { vm.state.first { !it.editorDraftBusy } }
        assertEquals(6f, workspace.captureQueries().projectSettings().getValue("mouthThickness").jsonPrimitive.float)
        assertEquals(nodes + 1, workspace.history().nodes.size, "the dropped draft was still committed")
        repeat(2) { vm.saveProjectNow(archive); assertFalse(vm.state.value.projectDirty) }
    }

    /** Whether the editor shows exactly the runtime's document, with no field drafted over it. */
    private fun showsTheRuntime(vm: PSD2LiveViewModel, workspace: DesktopWorkspace) =
        WorkspaceRevisions.of(WorkspaceStateCodec.document(vm.state.value)) == workspace.snapshot().revisionId

    private suspend fun idle(vm: PSD2LiveViewModel, workspace: DesktopWorkspace) {
        workspace.awaitEditorDrafts()
        withTimeout(20_000) { vm.state.first { !it.editorDraftBusy && !it.workspaceEditBusy && !it.canvasEditBusy } }
    }

    @Test fun aSettingYieldedInAFieldKeepsTheAgentsValueWhereASwitchWouldDeriveIt() = editor { vm, workspace ->
        fun setting(key: String) = workspace.captureQueries().projectSettings().getValue(key).jsonPrimitive.boolean
        assertTrue(setting("generateDeformers"))
        vm.beginEditorField("probe-mesh-only")
        // Mesh-only derives generateDeformers=false; the agent sets that key itself meanwhile.
        vm.setMeshOnly(true)
        workspace.updateProjectSettings(workspace.stateToken()!!, buildJsonObject { put("generateDeformers", false) })
        workspace.updateProjectSettings(workspace.stateToken()!!, buildJsonObject { put("generateDeformers", true) })
        vm.endEditorField("probe-mesh-only")
        idle(vm, workspace)
        assertTrue(setting("meshOnly"), "the switch made in the field was lost")
        assertTrue(setting("generateDeformers"), "replaying the switch overwrote the agent's value")
        assertTrue(showsTheRuntime(vm, workspace))
    }

    @Test fun aCheckoutDiscardingAFieldWarnsAndForgetsItsSwitches() = editor { vm, workspace ->
        val root = workspace.snapshot().historyHeadNodeId!!
        workspace.updateParameter(WorkspaceUpdateParameterRequest("ParamAngleX", workspace.stateToken()!!, name = "Renamed axis"))
        val thickness = vm.state.value.mouthThickness
        vm.beginEditorField("probe-thickness")
        vm.setMouthThickness(thickness + 2f)
        workspace.checkoutHistory(root, MutationAuthor.AGENT)
        assertEquals(thickness, vm.state.value.mouthThickness)
        assertTrue(vm.state.value.logEntries.any { it.message == io.github.psd2live.i18n.tr("log.editorDraftDiscarded") },
            "discarding the field's draft was not reported")
        val head = workspace.snapshot().historyHeadNodeId
        vm.endEditorField("probe-thickness")
        idle(vm, workspace)
        assertEquals(head, workspace.snapshot().historyHeadNodeId, "the discarded switch replayed onto the checked-out version")
        assertTrue(showsTheRuntime(vm, workspace))
    }

    @Test fun deletingADeformerLeavesNoDraftBehind() = editor { vm, workspace ->
        val deformer = vm.state.value.previewModel!!.rig.puppet.deformers.first { it.parent != null }.id.raw
        // An old v1 parent override on the deformer, committed through a draft.
        vm.setStateForTest(vm.state.value.copy(parentOverrides = mapOf(deformer to null)))
        vm.setTexturePadding(vm.state.value.texturePadding + 1)
        idle(vm, workspace)
        assertTrue(showsTheRuntime(vm, workspace))
        vm.deleteDeformers(listOf(deformer))
        idle(vm, workspace)
        assertTrue(vm.state.value.previewModel!!.rig.puppet.deformers.none { it.id.raw == deformer })
        assertTrue(showsTheRuntime(vm, workspace), "the editor drafts a field the deletion never commits")
    }

    @Test fun aMotionKeyDragOvertakenByAnAgentCommitLeavesNoDraftBehind() = editor { vm, workspace ->
        vm.createMotionClip()
        idle(vm, workspace)
        vm.addMotionCurve("ParamAngleX")
        idle(vm, workspace)
        val dragged = vm.editingMotionClip()!!
        vm.beginMotionKeyDrag()
        vm.dragMotionKeys(0.1f)
        workspace.updateProjectSettings(workspace.stateToken()!!, buildJsonObject { put("alphaThreshold", 40) })
        vm.dragMotionKeys(0.2f)
        vm.endMotionKeyDrag()
        idle(vm, workspace)
        assertNull(vm.state.value.errorMessage)
        assertTrue(showsTheRuntime(vm, workspace), "the drag's preview stayed as a draft")
        // The agent left the clip alone, so the drag lands over its commit.
        val committed = workspace.captureQueries().motionClips().single { it.id == dragged.id }
        assertNotEquals(dragged, committed, "the overtaken drag was dropped")
        vm.saveProjectNow(temp.resolve("drag.psd2live"))
        assertFalse(vm.state.value.projectDirty)
    }

    @Test fun aValueTheEditorTakesBackAfterYieldingItIsCommitted() = editor { vm, workspace ->
        fun padding() = workspace.captureQueries().projectSettings().getValue("texturePadding").jsonPrimitive.int
        val start = padding()
        // The editor shows a queued value when the agent commits another one to the same setting: the editor yields.
        vm.setStateForTest(vm.state.value.copy(texturePadding = start + 7))
        workspace.updateProjectSettings(workspace.stateToken()!!, buildJsonObject { put("texturePadding", start + 9) })
        assertEquals(start + 9, vm.state.value.texturePadding)
        // The user then sets their value again; nothing they yielded earlier may write the agent's back over it.
        vm.setTexturePadding(start + 7)
        idle(vm, workspace)
        assertEquals(start + 7, padding(), "an earlier yield overwrote the user's new value")
        assertTrue(showsTheRuntime(vm, workspace))
    }

    @Test fun aReplacingImportIsWhatTheEditorMirrorsAfterwards() = editor { vm, workspace ->
        val exported = temp.resolve("replace-source")
        val result = workspace.exportTarget(workspace.stateToken()!!, "cmo3", exported.toString(), emptyMap())
        val source = exported.resolve(result.getValue("files").jsonArray.map { it.jsonPrimitive.content }.single { it.endsWith(".cmo3") })
        workspace.importCmo3(source, io.github.psd2live.core.Cmo3ImportMode.NEW, true)
        workspace.updateParameter(WorkspaceUpdateParameterRequest("ParamAngleX", workspace.stateToken()!!, name = "Renamed axis"))
        val before = workspace.snapshot().historyHeadNodeId!!
        workspace.importCmo3(source, io.github.psd2live.core.Cmo3ImportMode.REPLACE, true)
        idle(vm, workspace)
        assertTrue(showsTheRuntime(vm, workspace))
        if (workspace.history().nodes.any { it.id == before } && workspace.snapshot().historyHeadNodeId != before) {
            workspace.checkoutHistory(before, MutationAuthor.USER)
            assertTrue(vm.state.value.logEntries.none { it.message == io.github.psd2live.i18n.tr("log.editorDraftDiscarded") },
                "a checkout after the import reported a draft the editor never had")
        }
    }

    @Test fun aCheckoutRightAfterOpeningDiscardsAFieldWithAWarning() = editor { vm, workspace ->
        val root = workspace.snapshot().historyHeadNodeId!!
        workspace.updateParameter(WorkspaceUpdateParameterRequest("ParamAngleX", workspace.stateToken()!!, name = "Renamed axis"))
        val archive = temp.resolve("opened.psd2live")
        vm.saveProjectNow(archive)
        workspace.open(archive)
        vm.beginEditorField("probe-padding")
        vm.setTexturePadding(vm.state.value.texturePadding + 4)
        workspace.checkoutHistory(root, MutationAuthor.AGENT)
        assertTrue(vm.state.value.logEntries.any { it.message == io.github.psd2live.i18n.tr("log.editorDraftDiscarded") })
        vm.endEditorField("probe-padding")
        idle(vm, workspace)
        assertEquals(root, workspace.snapshot().historyHeadNodeId)
    }

    @Test fun aReplacingShowEndsTheSessionsSwitchesBeforeTheHostDiscardsItsQueues() = editor { vm, workspace ->
        idle(vm, workspace)
        val committed = WorkspaceStateCodec.document(vm.state.value)
        val nodes = workspace.history().nodes.size
        vm.beginEditorField("probe-thickness")
        vm.setMouthThickness(vm.state.value.mouthThickness + 2f)
        // What a checkout shows, before the host gets to discard its queues: the field closing now replays nothing.
        vm.showWorkspaceCommit(vm.state.value.previewModel!!, null, committed, "Checked out")
        vm.endEditorField("probe-thickness")
        idle(vm, workspace)
        assertEquals(nodes, workspace.history().nodes.size, "the session's switch replayed onto the replaced document")
    }

    /**
     * The editor shows a commit inside the runtime's lock and the runtime publishes its state afterwards: a field
     * closing in between reads the older state while already showing that commit, and here the commit is published
     * before the draft reaches the queue. The draft still lands, carried from the commit it was shown.
     */
    @Test fun aFieldClosingBetweenACommitsShowAndItsPublicationStillLands() = editor { vm, workspace ->
        fun setting(key: String) = workspace.captureQueries().projectSettings().getValue(key).jsonPrimitive.int
        val padding = setting("texturePadding")
        val stale = workspace.stateToken()!!
        val submittedOn = java.util.concurrent.atomic.AtomicReference<String?>()
        val reached = CountDownLatch(1)
        vm.attachWorkspace(object : WorkspaceBackend by workspace {
            override fun submitEditorDraft(projectId: String, state: String, draft: io.github.psd2live.application.WorkspaceEditorDraft,
                                           settingsIntents: List<JsonObject>, summary: String, author: MutationAuthor) =
                run {
                    // Reach the queue only once the commit the field read before has been published.
                    reached.countDown()
                    val deadline = System.nanoTime() + 10_000_000_000
                    while (workspace.stateToken() == state && System.nanoTime() < deadline) Thread.sleep(1)
                    submittedOn.set(state)
                    workspace.submitEditorDraft(projectId, state, draft, settingsIntents, summary, author)
                }
        })
        vm.beginEditorField("probe-padding")
        vm.setTexturePadding(padding + 3)
        val closing = arrayOfNulls<Thread>(1)
        workspace.host.beforePublishingForTest {
            workspace.host.beforePublishingForTest(null)
            closing[0] = Thread { vm.endEditorField("probe-padding") }.also { it.start() }
            // The field has read its state and is on its way to the queue when the commit gets published.
            assertTrue(reached.await(20, TimeUnit.SECONDS))
        }
        workspace.updateProjectSettings(stale, buildJsonObject { put("alphaThreshold", 40) })
        closing[0]!!.join(20_000)
        assertEquals(stale, submittedOn.get(), "the field did not read the state from before the commit")
        idle(vm, workspace)
        assertNull(vm.state.value.errorMessage)
        assertEquals(padding + 3, setting("texturePadding"), "the field's draft was rejected")
        assertEquals(40, setting("alphaThreshold"))
        assertTrue(showsTheRuntime(vm, workspace))
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
        workspace.open(archive)
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
        workspace.open(archive)
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
        vm.setOutputPath(temp.resolve("export-after-commit").toString())
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
        val save = async { workspace.save(path, repository = repository) }
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

    @Test fun poseEditsRequireSavingWhileCameraChangesAreCapturedWithoutRequiringSaving() = editor { vm, workspace ->
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
        assertFalse(vm.state.value.projectDirty)
        vm.saveProjectNow(archive)
        val posed = org.umamo.render.eval.CpuDeformationEvaluator().evaluate(vm.state.value.previewModel!!.rig.puppet,
            vm.state.value.parameterValues).worldPositions.mapValues { it.value.toList() }
        workspace.open(archive)
        assertEquals(20f, vm.state.value.parameterValues[ParameterId("ParamAngleX")])
        assertEquals(TabCamera(2f, 30f, 40f), vm.state.value.activeCanvas.camera)
        assertEquals(posed, org.umamo.render.eval.CpuDeformationEvaluator().evaluate(vm.state.value.previewModel!!.rig.puppet,
            vm.state.value.parameterValues).worldPositions.mapValues { it.value.toList() })
        assertFalse(vm.state.value.projectDirty)
    }
}
