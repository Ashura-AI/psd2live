package io.github.psd2live.tools

import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.core.RigCanvasSupport
import io.github.psd2live.project.WorkspaceGeneration
import io.github.psd2live.project.WorkspaceLayout
import io.github.psd2live.scenario.Characters
import io.github.psd2live.scenario.Studio
import io.github.psd2live.scenario.req
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test
import org.umamo.runtime.model.ParameterId

/** PSD2LIVE_TOOLS=1 ./gradlew :test --tests '*LayoutEditingTool'; synthetic artwork only. */
class LayoutEditingTool {
    @Test fun renderEditingBeforeAndAfterGeneration() {
        requireTools()
        val out = output("layout-editing")
        val figure = Characters.figure().let { it.copy(layout = WorkspaceLayout(generation = WorkspaceGeneration.NotGenerated)) }
        Studio.run(out.toPath(), figure) {
            fun render(name: String, pose: Map<ParameterId, Float> = emptyMap()) {
                val image = BufferedImage(420, 420, BufferedImage.TYPE_INT_ARGB)
                val g = image.createGraphics()
                try {
                    g.color = Color(38, 38, 42); g.fillRect(0, 0, 420, 420)
                    val model = now.model
                    RigCanvasSupport.paintTexturedRig(g, model, RigCanvasSupport.evaluateExact(model, pose),
                        CanvasViewport(1.0, 0.0, 0.0, 420f, 420f))
                } finally { g.dispose() }
                ImageIO.write(image, "png", out.resolve("$name.png"))
            }
            render("editable")
            structural("edit hierarchy before generation", "canvas_warp" to req("id" to "UserWarp", "name" to "User warp", "meshes" to listOf(mesh("sleeve_r"))))
            allow("REGENERATION_CONFLICT")
            edit("fit the semantic rig", "rig_generate", req())
            render("generated")
            render("head-turn", mapOf(ParameterId("ParamAngleX") to 30f))
            reopen()
            render("reopened")
        }
    }
}
