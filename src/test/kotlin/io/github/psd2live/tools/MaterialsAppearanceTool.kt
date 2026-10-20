package io.github.psd2live.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.project.*
import io.github.psd2live.scenario.Characters
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.Motion
import io.github.psd2live.ui.theme.ToolColors
import io.github.psd2live.ui.views.MaterialsPanel
import io.github.psd2live.ui.views.MaterialsPanelProbe
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File

/** Synthetic artwork only. Run with PSD2LIVE_TOOLS=1 and --tests '*MaterialsAppearanceTool'. */
@EnabledIfEnvironmentVariable(named = "PSD2LIVE_TOOLS", matches = "1")
@OptIn(ExperimentalComposeUiApi::class)
class MaterialsAppearanceTool {
    @Test fun renderMaterialBadgeAndMeshPreviews() = runBlocking {
        val document = Characters.head(physics = false)
        val config = WorkspaceSettingsCodec.decode(document.settings).copy(layerOverrides = document.layerOverrides)
        val model = PSD2LivePipeline().buildPreview(document.source, config)
        val items = document.source.layers.mapIndexed { index, layer ->
            MaterialItem("material_$index", layer.name, null, MaterialKind.BITMAP, requireNotNull(layer.raster))
        }
        val state = PSD2LiveState(analysis = model.analysis, previewModel = model,
            layout = WorkspaceLayout(materials = MaterialLibrary(items = items),
                layerMaterials = document.source.layers.associate { it.id.raw to items.first().id }))
        PSD2LiveViewModel().use { vm ->
            withContext(Dispatchers.Main) {
                for (colors in listOf(ToolColors.Dark, ToolColors.Light)) {
                    val scene = ImageComposeScene(760, 520, density = Density(1f), coroutineContext = currentCoroutineContext()) {
                        CompactToolTheme(colors) {
                            Box(Modifier.fillMaxSize().background(colors.windowBackground)) {
                                Box(Modifier.width(430.dp).fillMaxHeight()) { MaterialsPanel(state, vm) }
                            }
                        }
                    }
                    var time = 0L
                    suspend fun frames() { repeat(30) { time += 16_000_000; scene.render(time).close(); yield() } }
                    fun save(name: String) {
                        val file = File("build/tools/materials-appearance/${if (colors.isDark) "dark" else "light"}-$name.png")
                        file.parentFile.mkdirs()
                        scene.render(time + 16_000_000).use { file.writeBytes(requireNotNull(it.encodeToData()).bytes) }
                    }
                    try {
                        frames(); delay(100); frames()
                        save("badge")
                        val entry = requireNotNull(MaterialsPanelProbe.firstEntry)
                        scene.sendPointerEvent(PointerEventType.Move, Offset(entry.center.x + 32f, entry.top + 14f)); frames()
                        delay(Motion.TooltipDelay + 80L); frames()
                        save("preview")
                        val press = Offset(entry.left + 20f, entry.top + 40f)
                        scene.sendPointerEvent(PointerEventType.Move, press); frames()
                        scene.sendPointerEvent(PointerEventType.Press, press, buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
                        scene.sendPointerEvent(PointerEventType.Release, press, button = PointerButton.Secondary); frames()
                        scene.sendPointerEvent(PointerEventType.Move, press + Offset(80f, 90f)); frames()
                        save("reference-menu")
                    } finally { scene.close() }
                }
            }
        }
    }
}
