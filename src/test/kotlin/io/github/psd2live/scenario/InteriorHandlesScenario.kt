package io.github.psd2live.scenario

import androidx.compose.ui.geometry.Offset
import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.core.interior.InteriorHandle
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.state.DesktopWorkspace
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.toolbarGroups
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

/**
 * The interior handles live in object mode, where no mesh points are drawn under them: arming the tool from another
 * mode switches to object mode, a handle drag commits one history step that undoes and redoes, and a press on another
 * object picks it as object mode does.
 */
class InteriorHandlesScenario {
	@TempDir lateinit var temp: Path

	@Test fun theHandlesWorkInObjectModeAndAPressOnAnotherObjectPicksIt() = runBlocking {
		PSD2LiveViewModel().use { vm ->
			vm.setStateForTest(vm.state.value.copy(atlasSize = 512, exportMoc3 = false, generatePhysics = false))
			DesktopWorkspace(vm, temp.resolve("store")).use { workspace ->
				vm.attachWorkspace(workspace)
				suspend fun settle() {
					repeat(3) { withContext(Dispatchers.Main) { yield() } }
					withTimeout(30_000) { vm.state.first { !it.workspaceEditBusy && !it.editorDraftBusy && !it.canvasEditBusy && !it.isAnalyzing && !it.isGenerating && !vm.interior.busy } }
					withContext(Dispatchers.Main) { yield() }
				}
				workspace.importPsd(Characters.figurePsd(temp).toString(), true)
				settle()

				assertTrue(CanvasTool.INTERIOR in toolbarGroups(EditHierarchyMode.SELECT).flatten())
				assertFalse(CanvasTool.INTERIOR in toolbarGroups(EditHierarchyMode.EDIT).flatten(), "edit mode keeps its tools to the mesh points")

				val model = vm.state.value.previewModel!!
				val puppet = model.rig.puppet
				val layerOf = model.rig.ownLayerIdByDrawableId
				// The largest mesh, so its handles have room to be told apart.
				val drawable = puppet.drawables.filter { it.mesh != null && layerOf[it.id.raw] != null }.maxBy { it.mesh!!.vertexCount }
				val layer = layerOf.getValue(drawable.id.raw)
				withContext(Dispatchers.Main) {
					vm.selectLayer(layer)
					vm.interior.put(buildJsonObject { put("target", drawable.id.raw); put("enabled", true) })
				}
				settle()
				val before = assertNotNull(vm.state.value.rigEdits.interiorEffects.singleOrNull { it.target == drawable.id.raw })

				val canvas = withContext(Dispatchers.Main) {
					// The active canvas's own editor: object picks write that canvas's selection.
					vm.canvasEditorFor(vm.state.value.activeCanvas.id).also {
						it.hierarchyMode = EditHierarchyMode.EDIT
						it.activateTool(CanvasTool.INTERIOR)
					}
				}
				val nodes = assertNotNull(vm.state.value.historySnapshot).nodes.size
				val viewport = CanvasViewport(1.0, 0.0, 0.0, puppet.canvasWidth, puppet.canvasHeight)
				withContext(Dispatchers.Main) {
					assertEquals(EditHierarchyMode.SELECT, canvas.hierarchyMode, "the tool belongs to object mode")
					assertEquals(CanvasTool.INTERIOR, canvas.tool)
					assertNull(canvas.interiorTool.missing())
					val scene = assertNotNull(canvas.interiorTool.scene())
					val bulge = assertNotNull(canvas.interiorTool.surfaceHandles(scene, viewport)[InteriorHandle.BULGE])
					assertTrue(canvas.press(bulge, viewport, shift = false, alt = false))
					assertTrue(canvas.interiorTool.dragging)
					canvas.move(bulge + Offset(0f, -6f), viewport, shift = false)
					canvas.move(bulge + Offset(0f, -14f), viewport, shift = false)
					canvas.release()
				}
				settle()
				val dragged = vm.state.value.rigEdits.interiorEffects.single { it.target == drawable.id.raw }
				assertNotEquals(before.settings.bulge, dragged.settings.bulge, "the drag did not reach the document")
				assertEquals(before.settings.press, dragged.settings.press)
				assertEquals(nodes + 1, assertNotNull(vm.state.value.historySnapshot).nodes.size, "one drag is one history step")

				withContext(Dispatchers.Main) { vm.undoHistory() }
				settle()
				assertEquals(before.settings.bulge, vm.state.value.rigEdits.interiorEffects.single { it.target == drawable.id.raw }.settings.bulge,
					"one undo takes the whole drag back")
				withContext(Dispatchers.Main) { vm.redoHistory() }
				settle()
				assertEquals(dragged.settings.bulge, vm.state.value.rigEdits.interiorEffects.single { it.target == drawable.id.raw }.settings.bulge,
					"redo replays the drag")

				// A press on another object - none of the selected mesh under it - picks that object, not a fold line.
				val picked = withContext(Dispatchers.Main) {
					val other = (0 until puppet.canvasWidth.toInt() step 6).flatMap { x -> (0 until puppet.canvasHeight.toInt() step 6).map { y -> Offset(x + .5f, y + .5f) } }
						.firstOrNull { val under = canvas.layerCandidates(it, viewport); under.isNotEmpty() && layer !in under }
					val at = assertNotNull(other, "the figure has a part away from the largest mesh")
					val picked = canvas.layerCandidates(at, viewport)
					canvas.press(at, viewport, shift = false, alt = false)
					canvas.release()
					assertTrue(canvas.interiorTool.drawing.isEmpty(), "a press on another object started a fold line")
					assertEquals(CanvasTool.INTERIOR, canvas.tool, "picking keeps the tool in hand")
					assertEquals(EditHierarchyMode.SELECT, canvas.hierarchyMode)
					picked
				}
				settle()
				val now = vm.state.value
				assertTrue(now.selectedLayerId in picked, "the press did not pick the object under it: ${now.selectedLayerId} / ${now.selectedDeformerId}, under $picked")
			}
		}
	}
}
