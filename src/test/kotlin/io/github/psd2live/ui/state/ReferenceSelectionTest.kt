package io.github.psd2live.ui.state

import io.github.psd2live.application.WorkspaceDocumentOperation
import io.github.psd2live.application.WorkspaceMaterialEdits
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.scenario.Characters
import io.github.psd2live.ui.views.selectedReferenceRow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class ReferenceSelectionTest {
    @TempDir lateinit var temp: Path

    @Test fun treeAndCanvasPicksReplaceEachOtherInOneCanvasSelectionAndMenu() = runBlocking {
        PSD2LiveViewModel().use { vm ->
            DesktopWorkspace(vm, temp.resolve("workspace")).use { workspace ->
                vm.attachWorkspace(workspace)
                workspace.importPsd(Characters.figurePsd(temp).toAbsolutePath().toString(), true)
                val layer = vm.state.value.analysis!!.source.layers.first().id.raw
                workspace.applyDocumentEdits(workspace.snapshot().state, "Reference", listOf(
                    WorkspaceDocumentOperation(WorkspaceMaterialEdits.ADD_REFERENCE, buildJsonObject {
                        put("material_id", vm.state.value.layout.materials.items.first().id)
                    })), MutationAuthor.USER)
                val id = vm.state.value.layout.references.single().id
                fun referenceOnly() {
                    val state = vm.state.value
                    assertEquals(id, state.selectedReferenceId)
                    assertNull(state.selectedLayerId)
                    assertTrue(state.selectedLayerIds.isEmpty())
                    assertNull(state.selectedDeformerId)
                    assertEquals(state.selectedReferenceId, state.activeCanvas.presentation.selectedReferenceId)
                    assertNotNull(selectedReferenceRow(state))
                }
                vm.selectLayer(layer)
                vm.selectReference(id)
                referenceOnly()
                val row = selectedReferenceRow(vm.state.value)
                vm.selectReference(id, vm.state.value.activeCanvas.id)
                assertEquals(row, selectedReferenceRow(vm.canvasEditor.state))
                vm.selectLayer(layer)
                assertNull(vm.state.value.selectedReferenceId)
                assertNull(selectedReferenceRow(vm.state.value))
                vm.selectReference(id)
                vm.canvasEditor.selectLayer(layer)
                assertNull(vm.state.value.selectedReferenceId)
                vm.selectReference(id)
                vm.canvasEditor.objects = setOf(layer)
                assertNull(vm.state.value.selectedReferenceId)
                vm.selectReference(id)
                vm.selectDeformer(vm.state.value.previewModel!!.rig.puppet.deformers.first().id.raw)
                assertNull(vm.state.value.selectedReferenceId)
                vm.selectReference(id)
                referenceOnly()
                val decoded = WorkspaceStateCodec.decode(WorkspaceStateCodec.encode(vm.state.value))
                assertEquals(id, decoded.selectedReferenceId)
                assertEquals(id, decoded.activeCanvas.presentation.selectedReferenceId)
                vm.selectLayer(null)
                assertNull(vm.state.value.selectedReferenceId)
            }
        }
    }
}
