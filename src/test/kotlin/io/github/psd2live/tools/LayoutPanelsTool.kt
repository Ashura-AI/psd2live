package io.github.psd2live.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.psd2live.scenario.Characters
import io.github.psd2live.ui.state.DesktopWorkspace
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.ToolColors
import io.github.psd2live.ui.views.LayersTableView
import io.github.psd2live.ui.views.LayoutGenerationBar
import io.github.psd2live.ui.views.MaterialsPanel
import java.io.File
import java.nio.file.Path
import kotlin.test.Test

/**
 * The Layout panels rendered headless on the synthetic figure: the materials panel (the imported PSD's folder and its
 * items), the layer table with tag cells and the generation bar, before and after a material is placed so that a layer
 * waits for generation. Checks they compose, lay out and draw, and writes the frames for a look:
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*LayoutPanelsTool' → build/tools/layout-panels/ (imported, placed, folder, selected,
 * hover-bar and tags PNGs).
 */
class LayoutPanelsTool {
	@OptIn(ExperimentalComposeUiApi::class)
	@Test fun render() {
		requireTools()
		val out = output("layout-panels").absoluteFile
		out.listFiles()?.filter { it.extension == "png" }?.forEach { it.delete() }
		PSD2LiveViewModel().use { vm ->
			DesktopWorkspace(vm, Path.of(out.path, "workspace")).use { workspace ->
				vm.attachWorkspace(workspace)
				val psd = Characters.figurePsd(out.toPath())
				kotlinx.coroutines.runBlocking { workspace.importPsd(psd.toAbsolutePath().toString(), true) }
				val width = 1280; val height = 720
				val scene = ImageComposeScene(width, height, density = Density(1f)) {
					CompactToolTheme(colors = ToolColors.Dark) {
						val state by vm.state.collectAsState()
						Row(Modifier.fillMaxSize().background(LocalToolColors.current.windowBackground)) {
							Box(Modifier.width(620.dp).fillMaxHeight()) { MaterialsPanel(state, vm) }
							Column(Modifier.width(660.dp).fillMaxHeight()) {
								Box(Modifier.fillMaxWidth().height(60.dp)) { LayoutGenerationBar(vm) {} }
								Box(Modifier.fillMaxWidth().height(660.dp)) { LayersTableView(state, vm) }
							}
						}
					}
				}
				var clock = 0L
				fun shot(name: String) {
					repeat(40) { clock += 16_000_000L; scene.render(clock).close() }
					Thread.sleep(400)
					clock += 16_000_000L
					scene.render(clock).use { File(out, "$name.png").writeBytes(requireNotNull(it.encodeToData()).bytes) }
				}
				shot("imported")
				val library = vm.state.value.layout.materials
				check(library.items.size == Characters.figure().source.layers.size) { "the imported PSD did not become materials: ${library.items.size}" }
				vm.placeMaterial(library.items.first().id, 300f, 300f)
				val deadline = System.currentTimeMillis() + 60_000
				while (vm.state.value.layout.generation.pendingLayerIds.isEmpty()) {
					check(System.currentTimeMillis() < deadline) { "placing a material did not commit: ${vm.state.value.errorMessage}" }
					Thread.sleep(100)
				}
				shot("placed")
				// Open the figure folder (double-click its tile), then a layer's tag menu.
				fun click(x: Float, y: Float) {
					val at = androidx.compose.ui.geometry.Offset(x, y)
					scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, at)
					scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, at,
						buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
					scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, at, button = androidx.compose.ui.input.pointer.PointerButton.Primary)
				}
				// Double-click the folder tile: it opens.
				fun clickAt(x: Float, y: Float, time: Long) {
					val at = androidx.compose.ui.geometry.Offset(x, y)
					scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, at, timeMillis = time)
					scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, at, timeMillis = time + 10,
						buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
					scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, at, timeMillis = time + 40,
						button = androidx.compose.ui.input.pointer.PointerButton.Primary)
				}
				val now = System.currentTimeMillis()
				clickAt(200f, 110f, now); clock += 16_000_000L; scene.render(clock).close(); clickAt(200f, 110f, now + 120)
				shot("folder")
				click(200f, 110f)
				shot("selected")
				println("LAYOUT PANELS selected: canvasEditBusy=${vm.state.value.canvasEditBusy} workspaceEditBusy=${vm.state.value.workspaceEditBusy}")
				// Drag the selected tile out of the panel onto a canvas: a stand-in drop target maps root (900, 500) and
				// around it to canvas units as an edit canvas does; the release places the material centred there.
				vm.materialDropTargets["probe"] = { root -> if (root.x > 640f) androidx.compose.ui.geometry.Offset(root.x - 700f, root.y - 300f) else null }
				val before = vm.state.value.analysis!!.source.layers.size
				val start = androidx.compose.ui.geometry.Offset(200f, 110f)
				val primary = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true)
				scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, start)
				scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, start, buttons = primary,
					button = androidx.compose.ui.input.pointer.PointerButton.Primary)
				for (step in 1..20) {
					scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move,
						androidx.compose.ui.geometry.Offset(200f + 35f * step, 110f + 19.5f * step), buttons = primary)
					clock += 16_000_000L; scene.render(clock).close()
				}
				check(vm.materialDrag.value != null) { "dragging a tile did not start a material drag" }
				scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, androidx.compose.ui.geometry.Offset(900f, 500f),
					button = androidx.compose.ui.input.pointer.PointerButton.Primary)
				val dropDeadline = System.currentTimeMillis() + 60_000
				while (vm.state.value.analysis!!.source.layers.size == before) {
					check(System.currentTimeMillis() < dropDeadline) { "dropping on the canvas did not place: ${vm.state.value.errorMessage}" }
					Thread.sleep(100)
				}
				val dropped = vm.state.value.analysis!!.source.layers.last()
				val rect = io.github.psd2live.project.LayerCanvasRect.of(dropped.bounds)
				println("LAYOUT PANELS drop placed ${dropped.id.raw} centred at (${rect.left + rect.width / 2}, ${rect.top + rect.height / 2}); expected (200, 200)")
				check(kotlin.math.abs(rect.left + rect.width / 2 - 200f) <= 1f && kotlin.math.abs(rect.top + rect.height / 2 - 200f) <= 1f) { "the drop landed elsewhere" }
				shot("dropped")
				scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, androidx.compose.ui.geometry.Offset(1215f, 24f))
				shot("hover-bar")
				scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, androidx.compose.ui.geometry.Offset(300f, 650f))
				shot("away")
				click(970f, 154f)
				shot("tags")
				// After a menu closes, a drag works again: dismiss the tag menu, then drag the tile once more.
				click(300f, 650f); repeat(40) { clock += 16_000_000L; scene.render(clock).close() }
				val again = vm.state.value.analysis!!.source.layers.size
				scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, start)
				scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, start, buttons = primary,
					button = androidx.compose.ui.input.pointer.PointerButton.Primary)
				for (step in 1..20) {
					scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move,
						androidx.compose.ui.geometry.Offset(200f + 35f * step, 110f + 19.5f * step), buttons = primary)
					clock += 16_000_000L; scene.render(clock).close()
				}
				println("LAYOUT PANELS drag after menu: started=${vm.materialDrag.value != null}")
				scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, androidx.compose.ui.geometry.Offset(900f, 500f),
					button = androidx.compose.ui.input.pointer.PointerButton.Primary)
				val againDeadline = System.currentTimeMillis() + 60_000
				while (vm.state.value.analysis!!.source.layers.size == again && System.currentTimeMillis() < againDeadline) Thread.sleep(100)
				check(vm.state.value.analysis!!.source.layers.size > again) { "a drag after closing a menu did not place" }
				println("LAYOUT PANELS written to $out; pending ${vm.state.value.layout.generation.pendingLayerIds}")
				scene.close()
			}
		}
	}
}
