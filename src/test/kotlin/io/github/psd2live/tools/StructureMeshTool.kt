package io.github.psd2live.tools

import io.github.psd2live.application.WorkspaceStructureMeshEdits
import io.github.psd2live.core.*
import io.github.psd2live.core.mesh.structure.StructureKind
import kotlinx.serialization.json.*
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Remeshes layers of a sample PSD along their texture structure ([WorkspaceStructureMeshEdits]) and draws, per
 * layer, the texture with the old mesh (left) and with the lines found and the new mesh (right): creases orange
 * (banded ones wider), shading blue, patterns violet; lines the mesh does not follow are faint. Writes
 * build/tools/structure-mesh/<layer>.png and report.txt.
 *
 * PSD2LIVE_STRUCTURE_PSD picks the PSD (default examples/tml/psd-input/tml.psd), PSD2LIVE_STRUCTURE_LAYERS a
 * comma-separated list of layer IDs (default: clothing and hair layers), PSD2LIVE_STRUCTURE_SETTINGS a JSON object of
 * layer_mesh_structure settings.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*StructureMeshTool'
 */
class StructureMeshTool {
	private val wear = setOf(SemanticTag.TOPWEAR, SemanticTag.BOTTOMWEAR, SemanticTag.HANDWEAR, SemanticTag.LEGWEAR,
		SemanticTag.FRONT_HAIR, SemanticTag.BACK_HAIR)

	@Test
	fun render() {
		requireTools()
		val psd = Path.of(System.getenv("PSD2LIVE_STRUCTURE_PSD") ?: "examples/tml/psd-input/tml.psd").toAbsolutePath()
		val out = File("build/tools/structure-mesh").apply { mkdirs() }
		val model = PSD2LivePipeline().buildPreview(psd)
		val only = System.getenv("PSD2LIVE_STRUCTURE_LAYERS")?.split(",")?.map { it.trim() }?.toSet()
		val request = buildJsonObject {
			System.getenv("PSD2LIVE_STRUCTURE_SETTINGS")?.let { put("settings", Json.parseToJsonElement(it).jsonObject) }
		}
		val settings = WorkspaceStructureMeshEdits.settings(request)
		val report = StringBuilder()
		for (layer in model.analysis.layers) {
			val id = layer.source.id.raw
			if (if (only != null) id !in only else layer.semantic.tag !in wear) continue
			for (drawable in model.rig.puppet.drawables.filter { it.mesh != null && model.rig.layerIdByDrawableId[it.id.raw] == id }) {
				val started = System.nanoTime()
				val plan = try {
					WorkspaceStructureMeshEdits.plan(model, RigEditOverlay(), drawable.id.raw, settings)
				} catch (e: IllegalArgumentException) {
					report.appendLine("${drawable.id.raw}: ${e.message}"); continue
				}
				val ms = (System.nanoTime() - started) / 1_000_000
				val kinds = StructureKind.entries.joinToString(" ") { k -> "${k.name.lowercase()} ${plan.analysis.lines.count { it.kind == k }}" }
				report.appendLine("${drawable.id.raw}: ${plan.previous.vertexCount} -> ${plan.replacement.vertexCount} vertices, " +
					"${plan.mesh.lines.size}/${plan.analysis.lines.size} lines followed ($kinds), ${plan.mesh.bands} bands, rows ${plan.rim.rows}, $ms ms")
				ImageIO.write(draw(layer, plan), "png", File(out, "${drawable.id.raw.replace(Regex("[^A-Za-z0-9_-]"), "_")}.png"))
			}
		}
		File(out, "report.txt").writeText(report.toString())
		println(report)
	}

	private fun draw(layer: ClassifiedLayer, plan: WorkspaceStructureMeshEdits.Plan): BufferedImage {
		val raster = layer.source.textureLayer.raster
		val scale = (900.0 / maxOf(raster.width, raster.height)).coerceIn(1.0, 4.0)
		val w = (raster.width * scale).toInt(); val h = (raster.height * scale).toInt()
		val image = BufferedImage(w * 2 + 12, h, BufferedImage.TYPE_INT_ARGB)
		val g = image.createGraphics()
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
		g.color = Color(40, 42, 46); g.fillRect(0, 0, image.width, image.height)
		val texture = BufferedImage(raster.width, raster.height, BufferedImage.TYPE_INT_ARGB)
		for (y in 0 until raster.height) for (x in 0 until raster.width) {
			val o = (y * raster.width + x) * 4
			val rgba = raster.rgba
			texture.setRGB(x, y, ((rgba[o + 3].toInt() and 255) shl 24) or ((rgba[o].toInt() and 255) shl 16) or
				((rgba[o + 1].toInt() and 255) shl 8) or (rgba[o + 2].toInt() and 255))
		}
		for (panel in 0..1) g.drawImage(texture, panel * (w + 12), 0, w, h, null)
		fun wires(points: List<io.github.psd2live.core.mesh.Point>, indices: IntArray, dx: Int, color: Color) {
			g.color = color; g.stroke = BasicStroke(1f)
			for (t in 0 until indices.size / 3) for (k in 0..2) {
				val a = points[indices[t * 3 + k]]; val b = points[indices[t * 3 + (k + 1) % 3]]
				g.draw(Line2D.Double(dx + a.x * scale, a.y * scale, dx + b.x * scale, b.y * scale))
			}
		}
		wires(plan.rim.positions, plan.rim.indices, 0, Color(20, 110, 190, 190))
		wires(plan.mesh.points, plan.mesh.triangles, w + 12, Color(20, 110, 190, 150))
		val followed = plan.mesh.lines.associateBy { it.line.id }
		for (line in plan.analysis.lines) {
			val base = when (line.kind) {
				StructureKind.FOLD -> Color(255, 138, 61); StructureKind.SHADE -> Color(92, 200, 255); StructureKind.PATTERN -> Color(215, 184, 255)
			}
			val placed = followed[line.id]
			g.color = if (placed != null) base else Color(base.red, base.green, base.blue, 100)
			g.stroke = BasicStroke(if (placed?.banded == true) 3f else 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
			val path = Path2D.Double()
			line.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(w + 12 + p.x * scale, p.y * scale) else path.lineTo(w + 12 + p.x * scale, p.y * scale) }
			g.draw(path)
		}
		g.dispose()
		return image
	}
}
