package io.github.psd2live.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
				val scene = ImageComposeScene(520, 560, density = Density(1f), coroutineContext = currentCoroutineContext()) {
					CompactToolTheme {
						Box(Modifier.fillMaxSize().background(LocalToolColors.current.panelBackground)) {
							ReferenceMenu(ReferenceRow(ReferenceTarget.Image(reference.id), "Reference", ReferenceMode.FOREGROUND, true, false, false, 0.63f), state, vm) {}
						}
					}
				}
				try {
					var clock = 0L
					suspend fun shot(name: String) {
						repeat(20) { clock += 16_000_000; scene.render(clock).close(); yield() }
						scene.render(clock + 16_000_000).use { File(output("reference-controls"), "$name.png").writeBytes(requireNotNull(it.encodeToData()).bytes) }
					}
					shot("menu")
					scene.sendPointerEvent(PointerEventType.Move, Offset(90f, 201f))
					shot("pins")
				} finally { scene.close() }
			}
		}
	}
}
