package io.github.psd2live.tools

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.psd2live.render.SkiaGpu
import io.github.psd2live.scenario.Characters
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.WorkspacePreset
import io.github.psd2live.ui.views.MaterialsPanelProbe
import io.github.psd2live.ui.views.PSD2LiveApp
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.Test

/**
 * The Layout workspace in the real editor window: the synthetic figure imported, its material folder dragged from the
 * materials panel onto the edit canvas with AWT mouse events (the real cursor stays where it is), the generation bar's
 * button clicked. Checks the drop lands where the pointer is released, that the button is enabled while nothing runs
 * and that generating clears what waits; writes build/tools/layout-window/: report.txt and the window's own frames
 * (layout.png, dragging.png, placed.png, generated.png).
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*LayoutWindowTool'
 */
class LayoutWindowTool {
	@Test fun drive() {
		requireTools()
		SkiaGpu.requestOpenGl()
		val out = output("layout-window").absoluteFile
		out.listFiles()?.filter { it.extension == "png" }?.forEach { it.delete() }
		val vm = PSD2LiveViewModel()
		val workspace = io.github.psd2live.ui.state.DesktopWorkspace(vm, out.resolve("workspace").toPath())
		vm.attachWorkspace(workspace)
		val window = AtomicReference<java.awt.Window?>()
		Thread {
			application(exitProcessOnExit = false) {
				Window(onCloseRequest = ::exitApplication, state = rememberWindowState(size = DpSize(1600.dp, 1000.dp)), undecorated = true) {
					window.set(this.window)
					PSD2LiveApp(viewModel = vm, window = this.window)
				}
			}
		}.apply { isDaemon = true; start() }
		fun waitFor(seconds: Int, what: String, condition: () -> Boolean) {
			val end = System.nanoTime() + seconds * 1_000_000_000L
			while (!condition()) { require(System.nanoTime() < end) { "Timed out waiting for $what" }; Thread.sleep(20) }
		}
		val report = StringBuilder()
		try {
			waitFor(60, "window") { window.get()?.isShowing == true }
			val psd = Characters.figurePsd(out.toPath())
			vm.openRecentFile(psd.toAbsolutePath().toString())
			waitFor(300, "model") { vm.state.value.previewModel != null && !vm.state.value.isBusy }
			SwingUtilities.invokeAndWait { vm.dismissMeshSplitBatch(); vm.openWorkspacePreset(WorkspacePreset.LAYOUT) }
			waitFor(30, "GPU renderer") { SkiaGpu.status.value !is SkiaGpu.Status.Starting }
			waitFor(30, "materials panel") { MaterialsPanelProbe.firstEntry != null && vm.materialDropTargets.isNotEmpty() }
			Thread.sleep(1500)
			val win = window.get()!!
			val scale = win.graphicsConfiguration.defaultTransform.scaleX.toFloat()
			report.appendLine("GPU ${SkiaGpu.status.value}, scale $scale, workspace ${vm.state.value.activeWorkspace.preset}")
			fun capture(name: String) = SwingUtilities.invokeAndWait {
				fun layer(c: java.awt.Component): org.jetbrains.skiko.SkiaLayer? = c as? org.jetbrains.skiko.SkiaLayer
					?: (c as? java.awt.Container)?.components?.firstNotNullOfOrNull(::layer)
				val bitmap = layer(win)?.screenshot() ?: return@invokeAndWait
				org.jetbrains.skia.Image.makeFromBitmap(bitmap).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes?.let { File(out, "$name.png").writeBytes(it) }
			}
			capture("layout")
			fun onWindow(root: androidx.compose.ui.geometry.Offset) = java.awt.Point((root.x / scale).toInt(), (root.y / scale).toInt())
			var buttons = 0
			fun mouse(id: Int, at: java.awt.Point, button: Int = MouseEvent.NOBUTTON, clicks: Int = 1) = SwingUtilities.invokeAndWait {
				val target = SwingUtilities.getDeepestComponentAt(win, at.x, at.y) ?: return@invokeAndWait
				val p = SwingUtilities.convertPoint(win, at, target)
				if (id == MouseEvent.MOUSE_PRESSED) buttons = InputEvent.BUTTON1_DOWN_MASK
				val modifiers = if (id == MouseEvent.MOUSE_RELEASED || id == MouseEvent.MOUSE_CLICKED) InputEvent.BUTTON1_DOWN_MASK else buttons
				target.dispatchEvent(MouseEvent(target, id, System.currentTimeMillis(), modifiers, p.x, p.y, clicks, false, button))
				if (id == MouseEvent.MOUSE_RELEASED) buttons = 0
			}

			// The figure folder, dragged from the panel onto the middle of the canvas.
			val folder = requireNotNull(MaterialsPanelProbe.firstEntry)
			val start = onWindow(folder.center)
			val drop = java.awt.Point((win.width * 0.48f).toInt(), (win.height * 0.5f).toInt())
			val target = vm.materialDropTargets.values.firstNotNullOfOrNull { it(androidx.compose.ui.geometry.Offset(drop.x * scale, drop.y * scale)) }
			report.appendLine("drag folder from window $start to $drop; the canvas maps the drop to ${target}")
			requireNotNull(target) { "the drop point is not on an edit canvas" }
			val before = vm.state.value.analysis!!.source.layers.size
			mouse(MouseEvent.MOUSE_MOVED, start); mouse(MouseEvent.MOUSE_PRESSED, start, MouseEvent.BUTTON1)
			for (step in 1..30) {
				mouse(MouseEvent.MOUSE_DRAGGED, java.awt.Point(start.x + (drop.x - start.x) * step / 30, start.y + (drop.y - start.y) * step / 30))
				Thread.sleep(16)
				if (step == 20) { Thread.sleep(300); capture("dragging") }
			}
			report.appendLine("drag in progress: ${vm.materialDrag.value != null}")
			mouse(MouseEvent.MOUSE_RELEASED, drop, MouseEvent.BUTTON1)
			waitFor(120, "placement") { vm.state.value.analysis!!.source.layers.size > before && !vm.state.value.workspaceEditBusy }
			val placed = vm.state.value.analysis!!.source.layers.drop(before)
			val left = placed.minOf { it.bounds.left }; val right = placed.maxOf { it.bounds.left + it.bounds.width }
			val top = placed.minOf { it.bounds.top }; val bottom = placed.maxOf { it.bounds.top + it.bounds.height }
			val centre = (left + right) / 2f to (top + bottom) / 2f
			report.appendLine("placed ${placed.size} layers centred at $centre; pending ${vm.state.value.layout.generation.pendingLayerIds.size}")
			require(placed.size == Characters.figure().source.layers.size) { "the whole folder was not placed" }
			require(kotlin.math.abs(centre.first - target.x) <= 1.5f && kotlin.math.abs(centre.second - target.y) <= 1.5f) { "the folder did not land at the drop point" }
			Thread.sleep(1500)
			capture("placed")

			// The bar's button, enabled while nothing runs: a click generates the waiting layers into the rig.
			val button = requireNotNull(MaterialsPanelProbe.generateButton) { "the generation bar did not show" }
			report.appendLine("generate button at $button; busy ${vm.state.value.workspaceEditBusy}")
			// The accent body sits at the button's left part; the chevron at its right end.
			val press = onWindow(androidx.compose.ui.geometry.Offset(button.left + button.width * 0.3f, button.center.y))
			mouse(MouseEvent.MOUSE_MOVED, press); mouse(MouseEvent.MOUSE_PRESSED, press, MouseEvent.BUTTON1)
			mouse(MouseEvent.MOUSE_RELEASED, press, MouseEvent.BUTTON1); mouse(MouseEvent.MOUSE_CLICKED, press, MouseEvent.BUTTON1)
			waitFor(300, "generation") { vm.state.value.layout.generation.pendingLayerIds.isEmpty() && !vm.state.value.workspaceEditBusy }
			report.appendLine("generated: pending ${vm.state.value.layout.generation.pendingLayerIds.size}, meshes ${vm.state.value.previewModel!!.rig.puppet.drawables.size}, " +
				"error ${vm.state.value.errorMessage}")
			Thread.sleep(1500)
			capture("generated")
		} finally {
			File(out, "report.txt").writeText(report.toString())
			println(report)
			SwingUtilities.invokeLater { window.get()?.dispose() }
		}
	}
}
