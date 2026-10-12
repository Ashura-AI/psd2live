package io.github.psd2live.tools

import io.github.psd2live.application.WorkspaceGenerationCommands
import io.github.psd2live.application.WorkspaceDraft
import io.github.psd2live.core.Bounds
import io.github.psd2live.core.MouthCurve
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.project.WorkspaceSettingsCodec
import io.github.psd2live.scenario.Characters
import io.github.psd2live.scenario.Studio
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test

/** Synthetic mouth previews before and during a preset field edit. Run with PSD2LIVE_TOOLS=1. */
class MouthPresetPreviewTool {
    @TempDir lateinit var temp: Path

    @Test fun renderPresetFieldPreviews() {
        requireTools()
        Studio.run(temp, checks = emptySet()) {
            importPsd(Characters.mouthPsd(temp), PipelineConfig(atlasSize = 256, generatePhysics = false, exportMoc3 = false, mouthOutlineEnabled = true))
            val initial = now.model
            val commands = WorkspaceGenerationCommands(runtime)
            fun preview(config: PipelineConfig, keys: Set<String>) = runBlocking {
                val patch = JsonObject(WorkspaceSettingsCodec.encode(config).filterKeys { it in keys })
                val prepared = commands.prepareSettingsDraft(WorkspaceDraft(now.document, now.auxiliary), initial, listOf(patch))
                builder.build(prepared.document, initial)
            }
            val flat = preview(initial.config.copy(mouthShape = "flat", mouthCurve = MouthCurve.preset("flat")), setOf("mouthShape", "mouthCurve"))
            val thick = preview(initial.config.copy(mouthThickness = 6f), setOf("mouthThickness"))
            val colored = preview(initial.config.copy(mouthColor = 0xDD2244), setOf("mouthColor"))
            val pose = mapOf("ParamMouthOpenY" to 0.7f, "ParamMouthForm" to 0f)
            val rect = Bounds(32f, 72f, 96f, 112f)
            sheet(listOf("Smile" to Renderer(initial, 480).render(pose, rect),
                "Flat" to Renderer(flat, 480).render(pose, rect),
                "Thickness 6 px" to Renderer(thick, 480).render(pose, rect),
                "Picked color" to Renderer(colored, 480).render(pose, rect)), output("mouth-preset-preview").resolve("mouth.png"), columns = 2)
        }
    }
}
