package io.github.psd2live.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.psd2live.application.*
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.scenario.Characters
import io.github.psd2live.ui.state.*
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.ToolColors
import io.github.psd2live.ui.views.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test

/** PSD2LIVE_TOOLS=1 ./gradlew test --tests '*UiConsistencyTool': synthetic menus in both themes and at larger scale. */
class UiConsistencyTool {
    @Test fun menus() = runBlocking {
        requireTools()
        val builder = WorkspacePreviewBuilder()
        val base = WorkspaceMaterialEdits.addLinked(Characters.head(false), "head.psd")
        val placed = WorkspaceDocumentEdits.apply(WorkspaceDocumentOperation(WorkspaceMaterialEdits.PLACE, buildJsonObject {
            put("material_id", base.layout.materials.items.first().id); put("layer_id", "guide"); put("reference", true)
        }), base, builder.build(base))
        val model = builder.build(placed)
        val state = PSD2LiveState(analysis = model.analysis, previewModel = model, layout = placed.layout)
        val mesh = model.rig.puppet.drawables.first { model.rig.layerIdByDrawableId[it.id.raw] != "guide" }
        val deformer = model.rig.puppet.deformers.first()
        val previousLanguage = I18n.currentLanguage
        try { PSD2LiveViewModel().use { vm -> withContext(Dispatchers.Main) {
            for (language in AppLanguage.entries) {
                I18n.setLanguage(language, persist = false)
                for ((name, colors, scale) in listOf(Triple("dark", ToolColors.Dark, 1f), Triple("light", ToolColors.Light, 1f), Triple("scaled", ToolColors.Dark, 1.25f))) {
                val scene = ImageComposeScene((780 * scale).toInt(), (580 * scale).toInt(), density = Density(1f), coroutineContext = currentCoroutineContext()) {
                    CompactToolTheme(colors, uiScale = scale, fontScale = if (scale > 1f) 1.2f else 1f) {
                        Row(Modifier.fillMaxSize().background(colors.panelBackground).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ArtMeshMenu(mesh, model.rig.layerIdByDrawableId[mesh.id.raw], null, state, vm, {})
                            DeformerMenu(deformer, listOf(deformer.id.raw), state, vm, {}, {}, {}, null)
                            ReferenceMenu(referenceLayerRow(state, "guide", "Reference"), state, vm) {}
                        }
                    }
                }
                try {
                    var time = 0L
                    repeat(40) { time += 16_000_000; scene.render(time).close(); yield() }
                    scene.render(time + 16_000_000).use { File(output("ui-consistency"), "${language.tag}-$name.png").writeBytes(requireNotNull(it.encodeToData()).bytes) }
                } finally { scene.close() }
                }
            }
        } } } finally { I18n.setLanguage(previousLanguage, persist = false) }
    }
}
