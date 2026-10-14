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
				click(56f, 86f)
				shot("folder")
				click(200f, 110f)
				shot("selected")
				scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, androidx.compose.ui.geometry.Offset(1215f, 24f))
				shot("hover-bar")
				click(970f, 154f)
				shot("tags")
				println("LAYOUT PANELS written to $out; pending ${vm.state.value.layout.generation.pendingLayerIds}")
				scene.close()
			}
		}
	}
}
