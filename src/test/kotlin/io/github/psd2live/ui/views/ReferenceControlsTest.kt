package io.github.psd2live.ui.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.project.LayerCanvasRect
import io.github.psd2live.project.LayoutReference
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.LocalToolColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReferenceControlsTest {
	@Test fun movingTheFrameDuringADragDoesNotMoveThePointerOriginOrCommitPrefixes() = runBlocking {
		withContext(Dispatchers.Main) {
			val source = LayoutReference("material", LayerCanvasRect(100f, 100f, 180f, 120f))
			var draft by mutableStateOf<LayoutReference?>(null)
			val commits = ArrayList<LayerCanvasRect>()
			val view = CanvasViewport(1.0, 0.0, 0.0, 500f, 400f)
			val scene = ImageComposeScene(500, 400, density = Density(1f), coroutineContext = currentCoroutineContext()) {
				CompactToolTheme {
					Box(Modifier.fillMaxSize()) {
						val colors = LocalToolColors.current
						Canvas(Modifier.fillMaxSize()) {
							drawRect(colors.windowBackground)
							val rect = (draft ?: source).rect
							drawRect(colors.controlHover, Offset(rect.left, rect.top), Size(rect.width, rect.height))
						}
						ReferenceHandles(source, view, { draft = it }, { rect, _ -> commits += rect })
					}
				}
			}
			var clock = 0L
			suspend fun frame() { clock += 16_000_000; scene.render(clock).close(); yield() }
			try {
				frame()
				val press = Offset(140f, 140f)
				scene.sendPointerEvent(PointerEventType.Press, press, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
				for (delta in listOf(Offset(20f, 10f), Offset(40f, 25f), Offset(60f, 35f))) {
					scene.sendPointerEvent(PointerEventType.Move, press + delta, buttons = PointerButtons(isPrimaryPressed = true))
					frame()
					assertEquals(source.rect.copy(left = 100f + delta.x, top = 100f + delta.y), draft?.rect)
					assertEquals(0, commits.size)
				}
				scene.sendPointerEvent(PointerEventType.Release, press + Offset(60f, 35f), button = PointerButton.Primary)
				frame()
				assertEquals(listOf(source.rect.copy(left = 160f, top = 135f)), commits)
				if (System.getenv("PSD2LIVE_TOOLS") == "1") {
					val file = File("build/tools/reference-controls/transform.png")
					file.parentFile.mkdirs()
					scene.render(clock + 16_000_000).use { file.writeBytes(requireNotNull(it.encodeToData()).bytes) }
				}
				// Secondary presses must stay available for menus rather than beginning a reference transform.
				draft = null
				scene.sendPointerEvent(PointerEventType.Press, press, buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
				scene.sendPointerEvent(PointerEventType.Move, press + Offset(30f, 30f), buttons = PointerButtons(isSecondaryPressed = true))
				scene.sendPointerEvent(PointerEventType.Release, press + Offset(30f, 30f), button = PointerButton.Secondary)
				frame()
				assertNull(draft)
				assertEquals(1, commits.size)
			} finally { scene.close() }
		}
	}
}
