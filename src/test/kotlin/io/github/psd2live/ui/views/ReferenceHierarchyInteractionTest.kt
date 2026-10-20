package io.github.psd2live.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import io.github.psd2live.application.*
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.scenario.Characters
import io.github.psd2live.ui.state.*
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.LocalToolColors
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class ReferenceHierarchyInteractionTest {
    @TempDir lateinit var temp: Path

    @Test fun referenceLayersUseTheSameSelectionHoverAndReparentGesturesAsMeshes() = runBlocking {
        PSD2LiveViewModel().use { vm -> DesktopWorkspace(vm, temp.resolve("workspace")).use { workspace ->
            vm.attachWorkspace(workspace)
            workspace.importPsd(Characters.figurePsd(temp).toAbsolutePath().toString(), true)
            workspace.applyDocumentEdits(workspace.snapshot().state, "Guide", listOf(
                WorkspaceDocumentOperation(WorkspaceMaterialEdits.PLACE, buildJsonObject {
                    put("material_id", vm.state.value.layout.materials.items.first().id); put("layer_id", "guide"); put("reference", true)
                })), MutationAuthor.USER)
            val initial = vm.state.value.previewModel!!
            val guide = initial.rig.puppet.drawables.single { initial.rig.layerIdByDrawableId[it.id.raw] == "guide" }
            val other = initial.rig.puppet.drawables.first { initial.rig.layerIdByDrawableId[it.id.raw]?.let { id -> id != "guide" } == true }
            val otherLayer = initial.rig.layerIdByDrawableId.getValue(other.id.raw)
            val parent = initial.rig.puppet.deformers.first().id.raw
            val history = vm.state.value.historySnapshot!!
            withContext(Dispatchers.Main) {
                val drag = TreeDragState()
                val bounds = mutableMapOf<String, ItemLayoutInfo>()
                var container by mutableStateOf<LayoutCoordinates?>(null)
                val scene = ImageComposeScene(360, 180, density = Density(1f), coroutineContext = currentCoroutineContext()) {
                    val state by vm.uiState
                    val model = state.previewModel!!
                    CompactToolTheme {
                        Box(Modifier.fillMaxSize().background(LocalToolColors.current.panelBackground)
                            .onGloballyPositioned { container = it }
                            .onPointerEvent(PointerEventType.Move) { event ->
                                drag.onMove(event.changes.first().position, bounds.values, model.rig.puppet.deformers, state.hierarchyParentOverrides)
                            }
                            .onPointerEvent(PointerEventType.Release) { event ->
                                if (event.button == PointerButton.Primary && drag.isPressed) drag.onRelease(vm) {
                                    selectHierarchyItem(vm, state, it, listOf("guide", otherLayer), event.keyboardModifiers.isShiftPressed,
                                        event.keyboardModifiers.isAltPressed, event.keyboardModifiers.isPrimaryPressed)
                                }
                            }) {
                            Column {
                                for ((index, id) in listOf(guide.id, other.id).withIndex()) DrawableTreeItem(
                                    model.rig.puppet.drawables.single { it.id == id }, 1, index == 1, listOf(true), model, state, vm,
                                    drag, container, bounds)
                            }
                        }
                    }
                }
                var time = 0L
                suspend fun frames() { repeat(8) { time += 16_000_000; scene.render(time).close(); yield() } }
                suspend fun click(y: Float, keys: PointerKeyboardModifiers = PointerKeyboardModifiers()) {
                    val at = Offset(90f, y)
                    scene.sendPointerEvent(PointerEventType.Move, at, keyboardModifiers = keys); frames()
                    scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), keyboardModifiers = keys, button = PointerButton.Primary)
                    frames()
                    assertTrue(drag.isPressed, "Every layer row must join the shared tree gesture")
                    scene.sendPointerEvent(PointerEventType.Release, at, keyboardModifiers = keys, button = PointerButton.Primary); frames()
                }
                try {
                    frames()
                    assertEquals(guide.id.raw, assertNotNull(bounds["guide"]).objectId)
                    assertTrue(bounds["guide"]!!.top < bounds[otherLayer]!!.top)
                    click(10f)
                    assertEquals(setOf("guide"), vm.canvasEditor.objects)
                    assertEquals("guide", vm.state.value.hoveredLayerId)
                    click(30f, PointerKeyboardModifiers(isCtrlPressed = true, isMetaPressed = true))
                    assertEquals(setOf("guide", otherLayer), vm.canvasEditor.objects)
                    click(10f)
                    click(30f, PointerKeyboardModifiers(isShiftPressed = true))
                    assertEquals(setOf("guide", otherLayer), vm.canvasEditor.objects)
                    click(30f, PointerKeyboardModifiers(isAltPressed = true))
                    assertEquals(setOf("guide"), vm.canvasEditor.objects)
                    assertEquals(history.nodes.size, vm.state.value.historySnapshot!!.nodes.size)
                    bounds["parent"] = ItemLayoutInfo("parent", parent, parent, "Parent", true, null, 50f, 80f)
                    val at = Offset(90f, 10f)
                    scene.sendPointerEvent(PointerEventType.Move, at); frames()
                    scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary); frames()
                    scene.sendPointerEvent(PointerEventType.Move, Offset(90f, 65f), buttons = PointerButtons(isPrimaryPressed = true)); frames()
                    assertTrue(drag.isDragging)
                    assertEquals(parent, drag.hoverTargetId)
                    scene.sendPointerEvent(PointerEventType.Release, Offset(90f, 65f), button = PointerButton.Primary); frames()
                } finally { scene.close() }
            }
            withTimeout(10_000) { while (vm.state.value.workspaceEditBusy) delay(10) }
            assertNull(vm.state.value.errorMessage)
            fun actualParent() = vm.state.value.previewModel!!.rig.puppet.drawables.single { it.id == guide.id }.parentDeformerId?.raw
            assertEquals(parent, actualParent())
            val after = vm.state.value.historySnapshot!!
            assertEquals(history.nodes.size + 1, after.nodes.size)
            workspace.checkoutHistory(history.headNodeId, MutationAuthor.USER)
            assertEquals(guide.parentDeformerId?.raw, actualParent())
            workspace.checkoutHistory(after.headNodeId, MutationAuthor.USER)
            val file = temp.resolve("guide.psd2live")
            workspace.save(file); workspace.open(file)
            assertEquals(parent, actualParent())
        } }
    }
}
