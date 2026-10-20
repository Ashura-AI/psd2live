package io.github.psd2live.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.psd2live.application.*
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.scenario.Characters
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.state.*
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.tooloptions.ActionOption
import io.github.psd2live.ui.tooloptions.menuOptions
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class ReferenceConversionMenuTest {
    @TempDir lateinit var temp: Path

    @Test fun bothDirectionsUseTheSameFirstActionAndPreserveTheSelectedObject() = runBlocking {
        PSD2LiveViewModel().use { vm ->
            DesktopWorkspace(vm, temp.resolve("workspace")).use { workspace ->
                vm.attachWorkspace(workspace)
                workspace.importPsd(Characters.figurePsd(temp).toAbsolutePath().toString(), true)
                workspace.applyDocumentEdits(workspace.snapshot().state, "Reference", listOf(
                    WorkspaceDocumentOperation(WorkspaceMaterialEdits.ADD_REFERENCE, buildJsonObject {
                        put("material_id", vm.state.value.layout.materials.items.first().id)
                    })), MutationAuthor.USER)
                workspace.applyDocumentEdits(workspace.snapshot().state, "Layer reference", listOf(
                    WorkspaceDocumentOperation(WorkspaceReferenceModes.OP, buildJsonObject {
                        put("reference_id", vm.state.value.layout.references.single().id); put("mode", "layer")
                    })), MutationAuthor.USER)
                val layerId = vm.state.value.layout.referenceLayerIds.single()
                vm.selectLayer(layerId)
                vm.canvasEditor.setHierarchyMode(EditHierarchyMode.SELECT)
                val original = vm.state.value.previewModel!!
                val meshId = original.rig.layerIdByDrawableId.entries.single { it.value == layerId }.key
                val history = vm.state.value.historySnapshot!!.nodes.size
                var open by mutableStateOf(true)
                var dismissals = 0
                withContext(Dispatchers.Main) {
                    val scene = ImageComposeScene(280, 560, density = Density(1f), coroutineContext = currentCoroutineContext()) {
                        val state by vm.uiState
                        CompactToolTheme {
                            Box(Modifier.fillMaxSize().background(LocalToolColors.current.panelBackground)) {
                                if (open) Box(Modifier.offset(12.dp, 12.dp)) {
                                    val close = { open = false; dismissals++; Unit }
                                    val drawable = state.previewModel!!.rig.puppet.drawables.single { it.id.raw == meshId }
                                    if (layerId in state.layout.referenceLayerIds) ReferenceMenu(referenceLayerRow(state, layerId, drawable.name), state, vm, close = close)
                                    else ArtMeshMenu(drawable, layerId, null, state, vm, close)
                                }
                            }
                        }
                    }
                    var time = 0L
                    suspend fun frames() { repeat(20) { time += 16_000_000; scene.render(time).close(); yield() } }
                    try {
                        for ((index, toReference) in listOf(false, true).withIndex()) {
                            open = true
                            frames()
                            val state = vm.state.value
                            val conversion = state.referenceConversion(layerId)
                            assertEquals(toReference, conversion.toReference)
                            assertTrue(conversion.enabled)
                            val option = assertIs<ActionOption>(menuOptions(vm.canvasEditor).single { it.id == "layer.reference" })
                            assertEquals(conversion.labelKey, option.labelKey)
                            assertTrue(option.enabled(vm.canvasEditor))
                            assertFalse(state.copy(isGenerating = true).referenceConversion(layerId).enabled)
                            assertFalse(state.copy(editorDraftBusy = true).referenceConversion(layerId).enabled)
                            if (System.getenv("PSD2LIVE_TOOLS") == "1") {
                                scene.sendPointerEvent(PointerEventType.Move, Offset(5f, 5f))
                                frames()
                                val file = java.io.File("build/tools/reference-conversion/${if (toReference) "artmesh" else "reference"}.png")
                                file.parentFile.mkdirs()
                                scene.render(time + 16_000_000).use { file.writeBytes(requireNotNull(it.encodeToData()).bytes) }
                            }
                            // Both menus put the conversion immediately below the object's name, at the same point.
                            val at = Offset(112f, 47f)
                            scene.sendPointerEvent(PointerEventType.Move, at)
                            frames()
                            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                            frames()
                            scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
                            frames()
                            assertFalse(open)
                            assertEquals(index + 1, dismissals)
                            withTimeout(10_000) {
                                while (vm.state.value.workspaceEditBusy || (layerId in vm.state.value.layout.referenceLayerIds) != toReference) delay(10)
                            }
                            assertNull(vm.state.value.errorMessage)
                            assertEquals(layerId, vm.state.value.selectedLayerId)
                            assertEquals(setOf(layerId), vm.canvasEditor.objects)
                            assertEquals(history + index + 1, vm.state.value.historySnapshot!!.nodes.size)
                            assertEquals(meshId, vm.state.value.previewModel!!.rig.layerIdByDrawableId.entries.single { it.value == layerId }.key)
                        }
                    } finally { scene.close() }
                }
                val generated = vm.state.value.analysis!!.source.layers.first { it.id.raw != layerId }.id.raw
                assertFalse(vm.state.value.referenceConversion(generated).enabled)
                assertFalse(vm.state.value.referenceConversion("missing").enabled)
                // A user-created ArtMesh remains convertible even though it cannot be reordered as a pending layer.
                vm.toggleSelectedLayersReference()
                withTimeout(10_000) { while (vm.state.value.workspaceEditBusy || layerId in vm.state.value.layout.referenceLayerIds) delay(10) }
                val one = vm.state.value.referenceSelectionConversion()
                assertTrue(one.enabled && one.toReference)
                assertTrue(vm.reorderableSelection().isEmpty())
                assertEquals(listOf(layerId), vm.referenceSelection())
                assertEquals("references.toReference", one.labelKey)
                vm.toggleLayerSelection(generated)
                val mixed = vm.state.value.referenceSelectionConversion()
                assertEquals(setOf(layerId, generated), mixed.layerIds.toSet())
                assertFalse(mixed.enabled)
                val beforeMixed = workspace.snapshot().state
                assertFalse(vm.toggleSelectedLayersReference())
                assertEquals(beforeMixed, workspace.snapshot().state)
                vm.selectLayer(layerId)
                // Two eligible members are converted atomically, with a target and count in the label.
                workspace.applyDocumentEdits(workspace.snapshot().state, "Another reference", listOf(
                    WorkspaceDocumentOperation(WorkspaceMaterialEdits.PLACE, buildJsonObject {
                        put("material_id", vm.state.value.layout.materials.items.first().id)
                        put("layer_id", "second_guide"); put("reference", true)
                    })), MutationAuthor.USER)
                vm.toggleLayerSelection("second_guide")
                val batch = vm.state.value.referenceSelectionConversion()
                assertEquals(2, batch.layerIds.size)
                assertTrue(batch.enabled && batch.toReference)
                assertEquals("references.toReferenceMany", batch.labelKey)
                val beforeBatch = vm.state.value.historySnapshot!!.nodes.size
                assertTrue(vm.toggleSelectedLayersReference())
                withTimeout(10_000) { while (vm.state.value.workspaceEditBusy || layerId !in vm.state.value.layout.referenceLayerIds) delay(10) }
                assertEquals(beforeBatch + 1, vm.state.value.historySnapshot!!.nodes.size)
                assertEquals(setOf(layerId, "second_guide"), vm.state.value.referenceSelectionConversion().layerIds.toSet())
                assertEquals("references.toModelMany", vm.state.value.referenceSelectionConversion().labelKey)
                assertNull(vm.state.value.errorMessage)
            }
        }
    }
}
