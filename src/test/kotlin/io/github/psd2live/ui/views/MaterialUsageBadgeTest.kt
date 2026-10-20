package io.github.psd2live.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.Motion
import io.github.psd2live.ui.theme.ToolColors
import kotlinx.coroutines.*
import org.umamo.runtime.model.*
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class MaterialUsageBadgeTest {
    @Test fun previewIncludesEveryMeshOfSharedLayersAndNamesPendingLayers() {
        fun mesh(id: String, geometry: Boolean = true) = Drawable(DrawableId(id), "Mesh $id", null, BlendMode.Normal, emptyList(),
            if (geometry) DrawableMesh(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2)) else null, null)
        val drawables = listOf(mesh("one"), mesh("two"), mesh("other"), mesh("no_geometry", false), mesh("unbound"))
        val rows = materialUsageEntries(listOf("shared", "pending", "shared", "unknown"), drawables,
            mapOf("one" to "shared", "two" to "shared", "other" to "unrelated", "no_geometry" to "pending"), mapOf("pending" to "Pending layer"))
        assertEquals(listOf("one", "two", null, null), rows.map { it.drawable?.id?.raw })
        assertEquals(listOf("shared", "shared", "pending", "unknown"), rows.map { it.layerId })
        assertEquals(listOf("Mesh one", "Mesh two", "Pending layer", "unknown"), rows.map { it.name })
    }

    @Test fun badgeHoverOpensAndDismissesPreviewWithoutClicking() = runBlocking {
        withContext(Dispatchers.Main) {
            for (colors in listOf(ToolColors.Dark, ToolColors.Light)) {
                val state = PSD2LiveState()
                val scene = ImageComposeScene(500, 420, density = Density(1f), coroutineContext = currentCoroutineContext()) {
                    CompactToolTheme(colors) {
                        Box(Modifier.fillMaxSize().background(colors.windowBackground)) {
                            MaterialUsageBadge(state, listOf("pending"), Modifier.offset(24.dp, 24.dp))
                        }
                    }
                }
                var time = 0L
                suspend fun frames() { repeat(20) { time += 16_000_000; scene.render(time).close(); yield() } }
                fun popupPixels() = scene.render(time + 16_000_000).use { image ->
                    val pixels = image.toComposeImageBitmap().toPixelMap()
                    (70 until 260 step 10).sumOf { y -> (30 until 220 step 10).count { x -> pixels[x, y] != colors.windowBackground } }
                }
                try {
                    frames()
                    assertEquals(0, popupPixels())
                    scene.sendPointerEvent(PointerEventType.Move, Offset(36f, 32f)); frames()
                    delay(Motion.TooltipDelay + 80L); frames()
                    assertTrue(popupPixels() > 30, "hover must show a preview outside the badge")
                    if (System.getenv("PSD2LIVE_TOOLS") == "1") {
                        val file = java.io.File("build/tools/material-usage/${if (colors.isDark) "dark" else "light"}.png")
                        file.parentFile.mkdirs()
                        scene.render(time + 16_000_000).use { file.writeBytes(requireNotNull(it.encodeToData()).bytes) }
                    }
                    scene.sendPointerEvent(PointerEventType.Move, Offset(480f, 400f)); frames()
                    assertEquals(0, popupPixels(), "leaving the badge must dismiss its preview")
                    assertNull(state.selectedLayerId)
                } finally { scene.close() }
            }
        }
    }
}
