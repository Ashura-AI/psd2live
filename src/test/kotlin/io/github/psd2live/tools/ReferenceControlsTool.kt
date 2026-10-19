package io.github.psd2live.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import io.github.psd2live.application.WorkspaceMaterialEdits
import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.project.LayerCanvasRect
import io.github.psd2live.project.LayoutReference
import io.github.psd2live.scenario.Characters
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.ReferenceMode
import io.github.psd2live.ui.state.ReferenceTarget
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.views.ReferenceMenu
import io.github.psd2live.ui.views.ReferenceRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import kotlin.test.Test
import kotlinx.serialization.json.put

/** Synthetic reference menus for visual review: PSD2LIVE_TOOLS=1 ./gradlew test --tests '*ReferenceControlsTool'. */
class ReferenceControlsTool {
	@Test fun menus() = runBlocking {
		requireTools()
		val document = WorkspaceMaterialEdits.addLinked(Characters.head(false), "head.psd")
		val model = WorkspacePreviewBuilder().build(document)
		val reference = LayoutReference(document.layout.materials.items.first().id, LayerCanvasRect(0f, 0f, 100f, 100f), opacity = 0.63f)
		val state = PSD2LiveState(analysis = model.analysis, previewModel = model, layout = document.layout.copy(references = listOf(reference)))
		withContext(Dispatchers.Main) {
			PSD2LiveViewModel().use { vm ->
                for (left in listOf(false, true)) {
                val offset = if (left) 280 else 0
				val scene = ImageComposeScene(540, 560, density = Density(1f), coroutineContext = currentCoroutineContext()) {
					CompactToolTheme {
						Box(Modifier.fillMaxSize().background(LocalToolColors.current.panelBackground)) {
                            Box(Modifier.offset(x = offset.dp)) {
							ReferenceMenu(ReferenceRow(ReferenceTarget.Image(reference.id), "Reference", ReferenceMode.FOREGROUND, true, false, false, 0.63f), state, vm) {}
                            }
						}
					}
				}
				try {
					var clock = 0L
					suspend fun shot(name: String) {
						repeat(20) { clock += 16_000_000; scene.render(clock).close(); yield() }
						scene.render(clock + 16_000_000).use { File(output("reference-controls"), "$name${if (left) "-left" else ""}.png").writeBytes(requireNotNull(it.encodeToData()).bytes) }
					}
					shot("menu")
					scene.sendPointerEvent(PointerEventType.Move, Offset(offset + 90f, 201f))
					shot("pins")
				} finally { scene.close() }
                }
			}
		}
	}
    @Test fun canvas() = runBlocking {
        requireTools()
        val root = java.nio.file.Files.createTempDirectory(output("reference-controls").toPath(), "canvas-")
        PSD2LiveViewModel().use { vm ->
            io.github.psd2live.ui.state.DesktopWorkspace(vm, root.resolve("workspace")).use { workspace ->
                vm.attachWorkspace(workspace)
                workspace.importPsd(Characters.figurePsd(root).toAbsolutePath().toString(), true)
                workspace.applyDocumentEdits(workspace.snapshot().state, "Reference", listOf(
                    io.github.psd2live.application.WorkspaceDocumentOperation(WorkspaceMaterialEdits.ADD_REFERENCE,
                        kotlinx.serialization.json.buildJsonObject {
                            put("material_id", vm.state.value.layout.materials.items.first().id)
                            put("opacity", 1f)
                            put("rect", kotlinx.serialization.json.buildJsonObject {
                                put("left", 40f); put("top", 40f); put("width", 140f); put("height", 100f)
                            })
                        })), io.github.psd2live.project.MutationAuthor.USER)
                vm.setCanvasMode(vm.state.value.activeCanvas.id, io.github.psd2live.ui.state.CanvasMode.EDIT)
                vm.canvasEditor.setHierarchyMode(io.github.psd2live.ui.EditHierarchyMode.SELECT)
                withContext(Dispatchers.Main) {
                    val scene = ImageComposeScene(1000, 760, density = Density(1f), coroutineContext = currentCoroutineContext()) {
                        CompactToolTheme {
                            io.github.psd2live.ui.views.CanvasViewportComposable(io.github.psd2live.ui.state.CanvasMode.EDIT, vm.uiState.value, vm)
                        }
                    }
                    var time = 0L
                    suspend fun frames(name: String? = null) {
                        repeat(15) { time += 16_000_000; scene.render(time).close(); yield() }
                        if (name != null) scene.render(time + 16_000_000).use {
                            File(output("reference-controls"), "$name.png").writeBytes(requireNotNull(it.encodeToData()).bytes)
                        }
                    }
                    fun send(type: PointerEventType, at: Offset, secondary: Boolean = false) {
                        scene.sendPointerEvent(type, at,
                            buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = type != PointerEventType.Release && !secondary,
                                isSecondaryPressed = type != PointerEventType.Release && secondary),
                            button = if (type == PointerEventType.Move) null else if (secondary) androidx.compose.ui.input.pointer.PointerButton.Secondary else androidx.compose.ui.input.pointer.PointerButton.Primary)
                    }
                    try {
                        frames()
                        val reference = vm.state.value.layout.references.single()
                        val viewport = kotlin.test.assertNotNull(vm.canvasEditor.viewport)
                        val at = Offset(viewport.x(70f).toFloat(), (viewport.offsetY + 70f * viewport.scale).toFloat())
                        scene.sendPointerEvent(PointerEventType.Move, at)
                        frames()
                        send(PointerEventType.Press, at)
                        frames()
                        send(PointerEventType.Release, at)
                        frames("canvas-selected")
                        kotlin.test.assertEquals(reference.id, vm.state.value.selectedReferenceId)
                        kotlin.test.assertNull(vm.state.value.selectedLayerId)
                        val before = vm.state.value.historySnapshot!!.nodes.size
                        val grip = Offset(viewport.x(reference.rect.left + reference.rect.width).toFloat(),
                            (viewport.offsetY + (reference.rect.top + reference.rect.height / 2f) * viewport.scale).toFloat())
                        send(PointerEventType.Press, grip)
                        frames()
                        send(PointerEventType.Move, grip + Offset(40f, 0f))
                        frames("canvas-scaling")
                        kotlin.test.assertEquals(reference.rect, vm.state.value.layout.references.single().rect, "A live transform must remain a draft until release")
                        send(PointerEventType.Release, grip + Offset(40f, 0f))
                        kotlinx.coroutines.withTimeout(10_000) {
                            while (vm.state.value.layout.references.single().rect == reference.rect || vm.state.value.workspaceEditBusy) kotlinx.coroutines.delay(10)
                        }
                        frames("canvas-scaled")
                        kotlin.test.assertEquals(reference.rect.width + 40f / viewport.scale.toFloat(), vm.state.value.layout.references.single().rect.width, 1e-2f)
                        kotlin.test.assertEquals(before + 1, vm.state.value.historySnapshot!!.nodes.size)
                        send(PointerEventType.Press, at, true)
                        frames("canvas-reference-menu")
                        send(PointerEventType.Release, at, true)
                        kotlin.test.assertEquals(reference.id, vm.canvasEditor.state.selectedReferenceId)
                    } finally { scene.close() }
                }
            }
        }
    }

}
