package io.github.psd2live.ui.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.ExperimentalComposeUiApi
import io.github.psd2live.ui.TransformHandles
import androidx.compose.ui.unit.Density
import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.project.LayerCanvasRect
import io.github.psd2live.project.LayoutReference
import io.github.psd2live.ui.theme.CompactToolTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalComposeUiApi::class)
class ReferenceControlsTest {
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
