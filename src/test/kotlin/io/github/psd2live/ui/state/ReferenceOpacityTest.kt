package io.github.psd2live.ui.state

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import io.github.psd2live.application.WorkspaceDocumentOperation
import io.github.psd2live.application.WorkspaceMaterialEdits
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.scenario.Characters
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class ReferenceOpacityTest {
	@TempDir lateinit var temp: Path

	@Test fun aScrubPreviewsWithoutHistoryAndCommitsOnlyItsFinalValue() = runBlocking {
		PSD2LiveViewModel().use { vm ->
			DesktopWorkspace(vm, temp.resolve("workspace")).use { workspace ->
				vm.attachWorkspace(workspace)
				workspace.importPsd(Characters.figurePsd(temp).toAbsolutePath().toString(), true)
				val material = vm.state.value.layout.materials.items.first().id
				workspace.applyDocumentEdits(workspace.snapshot().state, "Reference", listOf(
					WorkspaceDocumentOperation(WorkspaceMaterialEdits.ADD_REFERENCE, buildJsonObject { put("material_id", material) })), MutationAuthor.USER)
				val before = assertNotNull(vm.state.value.historySnapshot)
				val reference = vm.state.value.layout.references.single()
				for (value in listOf(0f, 0.12f, 0.37f, 0.63f)) {
					vm.previewReferenceOpacity(reference.id, value)
					assertEquals(ReferenceOpacityDraft(ReferenceTarget.Image(reference.id), value), vm.referenceOpacityDraft.value)
					assertEquals(reference.opacity, vm.state.value.layout.references.single().opacity)
					assertEquals(before.nodes.size, vm.state.value.historySnapshot?.nodes?.size)
				}
				vm.commitReferenceOpacity(reference.id, 0.63f)
				withTimeout(10_000) { while (vm.referenceOpacityDraft.value != null || vm.state.value.workspaceEditBusy) delay(10) }
				assertNull(vm.state.value.errorMessage)
				assertEquals(0.63f, vm.state.value.layout.references.single().opacity)
				val after = assertNotNull(vm.state.value.historySnapshot)
				assertEquals(before.nodes.size + 1, after.nodes.size)
				workspace.checkoutHistory(before.headNodeId, MutationAuthor.USER)
				assertEquals(reference.opacity, vm.state.value.layout.references.single().opacity)
				workspace.checkoutHistory(after.headNodeId, MutationAuthor.USER)
				val file = temp.resolve("reference.psd2live")
				workspace.save(file)
				workspace.open(file)
				assertEquals(0.63f, vm.state.value.layout.references.single().opacity)
				// Failure clears transient feedback too, without publishing a history prefix.
				vm.previewReferenceOpacity("missing", 0.4f)
				vm.commitReferenceOpacity("missing", 0.4f)
				withTimeout(10_000) { while (vm.referenceOpacityDraft.value != null || vm.state.value.workspaceEditBusy) delay(10) }
				assertNotNull(vm.state.value.errorMessage)
				assertEquals(after.nodes.size, vm.state.value.historySnapshot?.nodes?.size)
			}
		}
	}
    @Test fun layerMenuScrubPreviewsWithoutHistoryAndSurvivesUndoAndReopen() = runBlocking {
        PSD2LiveViewModel().use { vm ->
            DesktopWorkspace(vm, temp.resolve("layers")).use { workspace ->
                vm.attachWorkspace(workspace)
                workspace.importPsd(Characters.figurePsd(temp).toAbsolutePath().toString(), true)
                workspace.applyDocumentEdits(workspace.snapshot().state, "Reference", listOf(
                    WorkspaceDocumentOperation(WorkspaceMaterialEdits.ADD_REFERENCE, buildJsonObject {
                        put("material_id", vm.state.value.layout.materials.items.first().id); put("opacity", 0.8f)
                    })), MutationAuthor.USER)
                workspace.applyDocumentEdits(workspace.snapshot().state, "Layer reference", listOf(
                    WorkspaceDocumentOperation(io.github.psd2live.application.WorkspaceReferenceModes.OP, buildJsonObject {
                        put("reference_id", vm.state.value.layout.references.single().id); put("mode", "layer")
                    })), MutationAuthor.USER)
                val layerId = vm.state.value.layout.referenceLayerIds.single()
                val target = ReferenceTarget.Layer(layerId)
                vm.selectLayer(layerId)
                val before = assertNotNull(vm.state.value.historySnapshot)
                val original = assertNotNull(io.github.psd2live.ui.views.selectedReferenceRow(vm.state.value)).opacity
                assertEquals(0.8f, original)
                var finalOpacity = 0f
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val scene = androidx.compose.ui.ImageComposeScene(270, 320, density = androidx.compose.ui.unit.Density(1f),
                        coroutineContext = kotlinx.coroutines.currentCoroutineContext()) {
                        io.github.psd2live.ui.theme.CompactToolTheme {
                            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()
                                .background(io.github.psd2live.ui.theme.LocalToolColors.current.panelBackground)) {
                                io.github.psd2live.ui.views.ReferenceMenu(
                                    io.github.psd2live.ui.views.referenceLayerRow(vm.state.value, layerId, "Reference layer"), vm.state.value, vm) {}
                            }
                        }
                    }
                    var time = 0L
                    suspend fun frames() { repeat(5) { time += 16_000_000; scene.render(time).close(); kotlinx.coroutines.yield() } }
                    try {
                        frames()
                        if (System.getenv("PSD2LIVE_TOOLS") == "1") {
                            val file = java.io.File("build/tools/reference-controls/layer-menu.png")
                            file.parentFile.mkdirs()
                            scene.render(time + 16_000_000).use { file.writeBytes(requireNotNull(it.encodeToData()).bytes) }
                        }
                        val start = androidx.compose.ui.geometry.Offset(90f, 149f)
                        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, start)
                        frames()
                        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, start,
                            buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true),
                            button = androidx.compose.ui.input.pointer.PointerButton.Primary)
                        frames()
                        for (x in listOf(105f, 125f, 150f)) {
                            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, androidx.compose.ui.geometry.Offset(x, start.y),
                                buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true))
                            frames()
                            val draft = assertNotNull(vm.referenceOpacityDraft.value, "The layer menu must expose an opacity slider")
                            assertEquals(target, draft.target)
                            val source = assertNotNull(vm.state.value.previewModel)
                            val preview = draft.preview(source)
                            val mesh = preview.rig.puppet.drawables.first { preview.rig.layerIdByDrawableId[it.id.raw] == layerId }
                            assertEquals(draft.opacity, mesh.opacity)
                            assertEquals(original, source.rig.puppet.drawables.first { source.rig.layerIdByDrawableId[it.id.raw] == layerId }.opacity)
                            assertEquals(before.nodes.size, vm.state.value.historySnapshot!!.nodes.size)
                            finalOpacity = draft.opacity
                        }
                        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, androidx.compose.ui.geometry.Offset(150f, start.y),
                            button = androidx.compose.ui.input.pointer.PointerButton.Primary)
                        frames()
                    } finally { scene.close() }
                }
                withTimeout(10_000) { while (vm.referenceOpacityDraft.value != null || vm.state.value.workspaceEditBusy) delay(10) }
                assertNull(vm.state.value.errorMessage)
                assertTrue(finalOpacity in 0f..1f && finalOpacity != original)
                fun opacity() = io.github.psd2live.ui.views.referenceLayerRow(vm.state.value, layerId, "Reference layer").opacity
                assertEquals(finalOpacity, opacity())
                val after = assertNotNull(vm.state.value.historySnapshot)
                assertEquals(before.nodes.size + 1, after.nodes.size)
                workspace.checkoutHistory(before.headNodeId, MutationAuthor.USER)
                assertEquals(original, opacity())
                workspace.checkoutHistory(after.headNodeId, MutationAuthor.USER)
                val file = temp.resolve("reference-layer.psd2live")
                workspace.save(file)
                workspace.open(file)
                assertEquals(finalOpacity, opacity())
            }
        }
    }

}
