package io.github.psd2live.ui.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.ExperimentalComposeUiApi
import io.github.psd2live.ui.TransformHandles
import io.github.psd2live.ui.drawTransformBox
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

@OptIn(ExperimentalComposeUiApi::class)
class ReferenceControlsTest {
	@Test fun movingTheFrameDuringADragDoesNotMoveThePointerOriginOrCommitPrefixes() = runBlocking {
		withContext(Dispatchers.Main) {
			val source = LayoutReference("material", LayerCanvasRect(100f, 100f, 180f, 120f))
			val interaction = ReferenceCanvasInteraction()
			val handles = TransformHandles.ALL
			val commits = ArrayList<LayerCanvasRect>()
            val events = ArrayList<String>()
			val view = CanvasViewport(1.0, 0.0, 0.0, 500f, 400f)
			val scene = ImageComposeScene(500, 400, density = Density(1f), coroutineContext = currentCoroutineContext()) {
				CompactToolTheme {
					Box(Modifier.fillMaxSize()
                        .onPointerEvent(PointerEventType.Press) { event ->
                            events += "press ${event.button} at ${event.changes.first().position}"
                            if (event.button == PointerButton.Primary) interaction.press(source, view, event.changes.first().position, handles)
                        }
                        .onPointerEvent(PointerEventType.Move) { event ->
                            if (interaction.active) interaction.move(event.changes.first().position, false, false, handles)
                        }
                        .onPointerEvent(PointerEventType.Release) { event ->
                            if (event.button == PointerButton.Primary) interaction.release()?.let { commits += it.rect }
                        }) {
						val colors = LocalToolColors.current
						Canvas(Modifier.fillMaxSize()) {
							drawRect(colors.windowBackground)
							val rect = (interaction.draft ?: source).rect
							drawRect(colors.controlHover, Offset(rect.left, rect.top), Size(rect.width, rect.height))
                            drawTransformBox(interaction.frame(source, view), interaction.activeHandle, colors)
						}
					}
				}
			}
			var clock = 0L
			suspend fun frame() { clock += 16_000_000; scene.render(clock).close(); yield() }
			try {
				frame()
				val press = Offset(140f, 140f)
                scene.sendPointerEvent(PointerEventType.Move, press)
                frame()
				scene.sendPointerEvent(PointerEventType.Press, press, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                frame()
                kotlin.test.assertTrue(interaction.active, events.toString())
				for (delta in listOf(Offset(20f, 10f), Offset(40f, 25f), Offset(60f, 35f))) {
					scene.sendPointerEvent(PointerEventType.Move, press + delta, buttons = PointerButtons(isPrimaryPressed = true))
					frame()
					assertEquals(source.rect.copy(left = 100f + delta.x, top = 100f + delta.y), interaction.draft?.rect)
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
				interaction.cancel()
				scene.sendPointerEvent(PointerEventType.Press, press, buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
				scene.sendPointerEvent(PointerEventType.Move, press + Offset(30f, 30f), buttons = PointerButtons(isSecondaryPressed = true))
				scene.sendPointerEvent(PointerEventType.Release, press + Offset(30f, 30f), button = PointerButton.Secondary)
				frame()
				assertNull(interaction.draft)
				assertEquals(1, commits.size)
			} finally { scene.close() }
		}
	}
    @Test fun scalingPublishesEveryPreviewBeforeReleaseAndKeepsOnlyOneCommit() = runBlocking {
        withContext(Dispatchers.Main) {
            val source = LayoutReference("material", LayerCanvasRect(100f, 100f, 180f, 120f), opacity = 1f)
            val interaction = ReferenceCanvasInteraction()
            val view = CanvasViewport(1.0, 0.0, 0.0, 500f, 400f)
            val raster = io.github.psd2live.core.PreviewRenderer.rasterImage(4, 4, ByteArray(64) { index ->
                if (index % 4 == 0 || index % 4 == 3) -1 else 0
            }).toComposeImageBitmap()
            val scene = ImageComposeScene(500, 400, density = Density(1f), coroutineContext = currentCoroutineContext()) {
                CompactToolTheme {
                    Canvas(Modifier.fillMaxSize()) { drawReference(interaction.draft ?: source, raster, view) }
                }
            }
            try {
                var clock = 0L
                fun pixels() = scene.render(clock.also { clock += 16_000_000 }).use { it.toComposeImageBitmap().toPixelMap() }
                pixels()
                kotlin.test.assertTrue(interaction.press(source, view, Offset(280f, 160f), TransformHandles.ALL))
                for (delta in listOf(20f, 40f, 60f)) {
                    interaction.move(Offset(280f + delta, 160f), false, false, TransformHandles.ALL)
                    yield()
                    val image = pixels()
                    assertEquals(180f + delta, interaction.draft!!.rect.width, 1e-3f)
                    kotlin.test.assertTrue(image[(280f + delta - 5).toInt(), 130].alpha > 0.9f, "Expanded pixels must draw before release")
                }
                assertEquals(240f, kotlin.test.assertNotNull(interaction.release()).rect.width, 1e-3f)
                assertNull(interaction.release(), "Release may commit only once")
                interaction.cancel()
                yield()
                kotlin.test.assertTrue(pixels()[330, 130].alpha < 0.1f, "Cancel must restore the source picture")
            } finally { scene.close() }
        }
    }

}
