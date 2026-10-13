package io.github.psd2live.tools

import io.github.psd2live.core.RigAuthoringJournal
import io.github.psd2live.core.deletionCommand
import io.github.psd2live.core.deletionExport
import io.github.psd2live.core.deletionFixture
import org.umamo.render.eval.CpuDeformationEvaluator
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test

/** PSD2LIVE_TOOLS=1 ./gradlew test --tests '*DeformerDeletionVisualTool'. */
class DeformerDeletionVisualTool {
	@Test fun beforeAfterAndExportKeepTheSameSilhouettes() {
		requireTools()
		val before = deletionFixture()
		val after = RigAuthoringJournal.apply(before, deletionCommand())
		val models = listOf("Before" to before, "Deleted" to after, "MOC3 readback" to deletionExport(after))
		val image = BufferedImage(960, 360, BufferedImage.TYPE_INT_RGB)
		val graphics = image.createGraphics()
		try {
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
			graphics.color = Color(24, 28, 36); graphics.fillRect(0, 0, image.width, image.height)
			for ((panel, entry) in models.withIndex()) {
				graphics.color = Color.WHITE; graphics.drawString(entry.first, panel * 320 + 16, 24)
				val positions = CpuDeformationEvaluator().evaluate(entry.second, emptyMap()).worldPositions
				for (drawable in entry.second.drawables) {
					val points = positions.getValue(drawable.id)
					val shape = Path2D.Float()
					for (vertex in 0 until points.size / 2) {
						val x = panel * 320 + (points[vertex * 2] - 16) * 8
						val y = 40 + (-points[vertex * 2 + 1] - 16) * 8
						if (vertex == 0) shape.moveTo(x.toDouble(), y.toDouble()) else shape.lineTo(x.toDouble(), y.toDouble())
					}
					shape.closePath()
					graphics.color = if (drawable.id.raw == "mesh-0") Color(72, 192, 220) else Color(255, 177, 78)
					graphics.fill(shape)
					graphics.color = Color.WHITE; graphics.stroke = BasicStroke(1.5f); graphics.draw(shape)
				}
			}
		} finally { graphics.dispose() }
		val output = Path.of("build", "tools", "deformer-deletion")
		Files.createDirectories(output)
		ImageIO.write(image, "png", output.resolve("comparison.png").toFile())
	}
}
