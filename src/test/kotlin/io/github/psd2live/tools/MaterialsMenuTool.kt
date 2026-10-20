package io.github.psd2live.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.Density
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.scenario.Characters
import io.github.psd2live.ui.state.DesktopWorkspace
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.ToolColors
import io.github.psd2live.ui.views.MaterialsPanel
import io.github.psd2live.ui.views.MaterialsPanelProbe
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** PSD2LIVE_TOOLS=1 ./gradlew :test --tests '*MaterialsMenuTool': material menus in both themes and all languages. */
@OptIn(ExperimentalComposeUiApi::class)
class MaterialsMenuTool {
    @TempDir lateinit var temp: Path

    @Test fun menus() = runBlocking {
        requireTools()
        val previous = I18n.currentLanguage
        try {
            PSD2LiveViewModel().use { vm ->
                DesktopWorkspace(vm, temp.resolve("workspace")).use { workspace ->
                    vm.attachWorkspace(workspace)
                    workspace.importPsd(Characters.figurePsd(temp).toAbsolutePath().toString(), true)
                    withContext(Dispatchers.Main) {
                        for (language in AppLanguage.entries) for ((name, colors) in listOf("dark" to ToolColors.Dark, "light" to ToolColors.Light)) {
                            I18n.setLanguage(language, persist = false)
                            val scene = ImageComposeScene(860, 700, density = Density(1f), coroutineContext = currentCoroutineContext()) {
                                val state by vm.uiState
                                CompactToolTheme(colors) {
                                    Box(Modifier.fillMaxSize().background(colors.panelBackground)) { MaterialsPanel(state, vm) }
                                }
                            }
                            var time = 0L
                            suspend fun frames() { repeat(20) { time += 16_000_000; scene.render(time).close(); yield() } }
                            suspend fun click(at: Offset, button: PointerButton) {
                                scene.sendPointerEvent(PointerEventType.Move, at)
                                scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(
                                    isPrimaryPressed = button == PointerButton.Primary, isSecondaryPressed = button == PointerButton.Secondary), button = button)
                                scene.sendPointerEvent(PointerEventType.Release, at, button = button)
                                frames()
                            }
                            fun save(kind: String) {
                                val file = File(output("materials-menu"), "${language.tag}-$name-$kind.png")
                                scene.render(time + 16_000_000).use { file.writeBytes(requireNotNull(it.encodeToData()).bytes) }
                            }
                            try {
                                frames()
                                click(Offset(350f, 330f), PointerButton.Secondary)
                                save("background")
                                // The first action in the background menu creates a folder at the current location.
                                val before = vm.state.value.layout.materials.folders.size
                                click(Offset(420f, 375f), PointerButton.Primary)
                                withTimeout(10_000) { while (vm.state.value.layout.materials.folders.size == before) delay(10) }
                                assertEquals(before + 1, vm.state.value.layout.materials.folders.size)
                                assertNull(vm.state.value.errorMessage)
                                frames()
                                val first = requireNotNull(MaterialsPanelProbe.firstEntry).center
                                click(first, PointerButton.Secondary)
                                save("entry")
                                click(Offset(first.x + 70f, first.y + 214f), PointerButton.Primary)
                                save("tags")
                            } finally { scene.close() }
                        }
                    }
                }
            }
        } finally { I18n.setLanguage(previous, persist = false) }
    }
}
