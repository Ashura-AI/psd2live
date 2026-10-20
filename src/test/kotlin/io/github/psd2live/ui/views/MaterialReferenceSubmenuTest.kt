package io.github.psd2live.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.ToolColors
import kotlinx.coroutines.*
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class MaterialReferenceSubmenuTest {
    @Test fun hoverOpensSecondLevelAndDisabledActionsCannotRun() = runBlocking {
        withContext(Dispatchers.Main) {
            for (colors in listOf(ToolColors.Dark, ToolColors.Light)) {
                val actions = mutableListOf<String>()
                val lines = listOf(MenuLine.Submenu("Add as reference", listOf(
                    MenuLine.Action("Background", { actions += "background" }),
                    MenuLine.Action("Layer", { actions += "layer" }, enabled = false),
                    MenuLine.Action("Foreground", { actions += "foreground" }))),
                    MenuLine.Action("Other", { actions += "other" }))
                val scene = ImageComposeScene(720, 360, density = Density(1f), coroutineContext = currentCoroutineContext()) {
                    CompactToolTheme(colors) {
                        Box(Modifier.fillMaxSize().background(colors.panelBackground)) {
                            Box(Modifier.offset(12.dp, 12.dp)) { MaterialMenuContent(lines) }
                        }
                    }
                }
                var time = 0L
                suspend fun frames() { repeat(20) { time += 16_000_000; scene.render(time).close(); yield() } }
                suspend fun click(at: Offset) {
                    scene.sendPointerEvent(PointerEventType.Move, at); frames()
                    scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary); frames()
                }
                try {
                    frames()
                    scene.sendPointerEvent(PointerEventType.Move, Offset(100f, 25f)); frames()
                    if (System.getenv("PSD2LIVE_TOOLS") == "1") {
                        val file = java.io.File("build/tools/material-reference/${if (colors.isDark) "dark" else "light"}.png")
                        file.parentFile.mkdirs()
                        scene.render(time + 16_000_000).use { file.writeBytes(requireNotNull(it.encodeToData()).bytes) }
                    }
                    // The child starts beside the 236dp primary, with its own heading and three rows.
                    click(Offset(330f, 68f))
                    assertTrue(actions.isEmpty(), "disabled reference placement must not execute")
                    click(Offset(330f, 42f))
                    assertEquals(listOf("background"), actions)
                    click(Offset(330f, 95f))
                    assertEquals(listOf("background", "foreground"), actions)
                    scene.sendPointerEvent(PointerEventType.Move, Offset(100f, 52f)); frames()
                    click(Offset(330f, 42f))
                    assertEquals(2, actions.size, "a plain primary row must close the second level")
                } finally { scene.close() }
            }
        }
    }
}
