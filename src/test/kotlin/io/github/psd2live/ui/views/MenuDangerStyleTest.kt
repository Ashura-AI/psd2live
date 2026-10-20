package io.github.psd2live.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.ToolColors
import io.github.psd2live.ui.views.texture.FloatingMenuRow
import io.github.psd2live.ui.views.tooloptions.GlidingColumn
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.test.*

class MenuDangerStyleTest {
    @Test fun dangerUsesOneGlidingHighlightAndDisabledRowsNeitherHighlightNorExecute() = runBlocking {
        withContext(Dispatchers.Main) {
            for (colors in listOf(ToolColors.Dark, ToolColors.Light)) {
                var normal = 0
                var disabled = 0
                val scene = ImageComposeScene(240, 80, density = Density(1f), coroutineContext = currentCoroutineContext()) {
                    CompactToolTheme(colors) {
                        Box(Modifier.fillMaxSize().background(colors.panelBackground)) {
                            GlidingColumn(Modifier.width(236.dp), 2, { it == 0 }, dangers = { true }) { index ->
                                FloatingMenuRow("Delete", { if (index == 0) normal++ else disabled++ }, enabled = index == 0, danger = true)
                            }
                        }
                    }
                }
                var time = 0L
                suspend fun frames() { repeat(20) { time += 16_000_000; scene.render(time).close(); yield() } }
                fun pixel(y: Int) = scene.render(time + 16_000_000).use { it.toComposeImageBitmap().toPixelMap()[220, y] }
                try {
                    frames()
                    scene.sendPointerEvent(PointerEventType.Move, Offset(210f, 14f)); frames()
                    val expected = colors.error.copy(alpha = 0.14f).compositeOver(colors.panelBackground)
                    val hover = pixel(14)
                    assertTrue(abs(hover.red - expected.red) < 0.03f && abs(hover.green - expected.green) < 0.03f)
                    scene.sendPointerEvent(PointerEventType.Press, Offset(210f, 14f), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, Offset(210f, 14f), button = PointerButton.Primary); frames()
                    assertEquals(1, normal)
                    scene.sendPointerEvent(PointerEventType.Move, Offset(210f, 43f)); frames()
                    val idle = pixel(43)
                    assertTrue(abs(idle.red - colors.panelBackground.red) < 0.02f && abs(idle.green - colors.panelBackground.green) < 0.02f)
                    scene.sendPointerEvent(PointerEventType.Press, Offset(210f, 43f), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, Offset(210f, 43f), button = PointerButton.Primary); frames()
                    assertEquals(0, disabled)
                } finally { scene.close() }
            }
        }
    }
}
