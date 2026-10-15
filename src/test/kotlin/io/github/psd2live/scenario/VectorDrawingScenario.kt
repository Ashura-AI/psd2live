package io.github.psd2live.scenario

import io.github.psd2live.application.WorkspaceMaterialFiles
import io.github.psd2live.application.WorkspaceMaterialStaging
import io.github.psd2live.application.WorkspaceReadSession
import io.github.psd2live.application.WorkspaceMaterialQueries
import io.github.psd2live.application.WorkspaceReferenceQueries
import io.github.psd2live.application.WorkspaceRenderSession
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.BasicStroke
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test

/**
 * Drawing parts as vectors over a reference image: colours and outlines read off the reference, a part drawn in
 * canvas coordinates with the shared kit and tapered lines, redrawn larger with its layer following, and the kit
 * changed under it.
 */
class VectorDrawingScenario {
	@TempDir lateinit var temp: Path

	/** A grey disc in a black ring at (210, 210) radius 60, and a black line along y 300..303. */
	private fun referenceImage(): Path {
		val image = BufferedImage(420, 420, BufferedImage.TYPE_INT_ARGB)
		val g = image.createGraphics()
		g.color = Color.WHITE; g.fillRect(0, 0, 420, 420)
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
		g.color = Color(0x80, 0x80, 0x80); g.fillOval(150, 150, 120, 120)
		g.color = Color.BLACK; g.stroke = BasicStroke(3f); g.drawOval(150, 150, 120, 120)
		g.fillRect(50, 300, 320, 3)
		g.dispose()
		return Files.createDirectories(temp.resolve("files")).resolve("reference.png").also { ImageIO.write(image, "png", it.toFile()) }
	}

	private fun JsonObject.points() = getValue("points").jsonArray.map { it.jsonObject }

	@Test fun partsAreDrawnOverTheReference() = Studio.run(temp, Characters.figure()) {
		edit("import the reference", "material_add", req("staging" to WorkspaceMaterialStaging.stage(WorkspaceMaterialFiles.read(listOf(referenceImage())))))
		val referenceId = now.document.layout.materials.items.single().id
		edit("set it as the reference", "layout_set_reference", req("material_id" to referenceId, "rect" to mapOf("left" to 0, "top" to 0, "width" to 420, "height" to 420)))

		// Reading the reference.
		val state = requireNotNull(WorkspaceReadSession(runtime.read()).layoutState())
		val sampled = WorkspaceReferenceQueries.sample(state, req("points" to listOf(listOf(210, 210)), "rect" to mapOf("left" to 140, "top" to 140, "width" to 140, "height" to 140), "colors" to 3))
		expect("sample", sampled.points().single()["color"]?.jsonPrimitive?.content == "#808080") { "the disc's colour reads ${sampled.points()}" }
		val palette = sampled.getValue("palette").jsonArray.map { it.jsonObject.getValue("color").jsonPrimitive.content }
		fun grey(hex: String, low: Int, high: Int) = (0..2).all { hex.substring(1 + it * 2, 3 + it * 2).toInt(16) in low..high }
		expect("sample", palette.size == 3 && grey(palette.first(), 0, 0x30) && palette.any { grey(it, 0x78, 0x88) }) { "the palette is $palette" }
		val traced = WorkspaceReferenceQueries.trace(state, req("seed" to listOf(210, 210)))
		val area = traced.getValue("area").jsonPrimitive.double
		expect("trace", abs(area - Math.PI * 58.5 * 58.5) < 0.06 * area && traced.getValue("path").jsonPrimitive.content.startsWith("M") &&
			traced.getValue("touches_edge").jsonPrimitive.boolean.not()) { "the disc traced as $traced" }
		val silhouette = WorkspaceReferenceQueries.trace(state, req("seed" to listOf(5, 5), "holes" to 2)).getValue("hole_outlines").jsonArray
		val discHole = silhouette.first().jsonObject.getValue("area").jsonPrimitive.double
		expect("trace", silhouette.size == 2 && abs(discHole - Math.PI * 61.5 * 61.5) < 0.06 * discHole) { "the background's largest hole is $discHole" }
		val snapped = WorkspaceReferenceQueries.snap(state, req("points" to listOf(listOf(200, 297), listOf(30, 30)), "radius" to 5)).points()
		val onLine = snapped[0].getValue("point").jsonArray.map { it.jsonPrimitive.float }
		expect("snap", snapped[0]["snapped"]?.jsonPrimitive?.boolean == true && onLine[1] in 300f..303f && snapped[1]["snapped"]?.jsonPrimitive?.boolean == false) {
			"the points snapped to $snapped" }

		// The kit, and a part drawn with it in canvas coordinates.
		edit("set the kit", "layout_set_drawing_kit", req("colors" to mapOf("skin" to "#f0d0c0", "line" to "#302020"),
			"styles" to ".line { stroke: var(--line); fill: none; stroke-width: 3 }"))
		fun svg(box: String) = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="$box" width="999" height="999">
			<ellipse cx="120" cy="110" rx="18" ry="8" fill="var(--skin)"/>
			<path class="line" data-taper="0 1 0" d="M104 104 L136 104"/>
		</svg>"""
		refused("an unknown colour is refused", "material_draw", req("id" to "eye_white", "svg" to svg("100 100 40 20").replace("--skin", "--nope")))
		edit("draw a part", "material_draw", req("id" to "eye_white", "svg" to svg("100 100 40 20"), "density" to 4))
		val drawn = requireNotNull(now.document.layout.materials.item("eye_white"))
		expect("draw", drawn.kind == MaterialKind.VECTOR && drawn.placement == LayerCanvasRect(100f, 100f, 40f, 20f) && drawn.raster.width == 160 && drawn.raster.height == 80) {
			"the drawing sits at ${drawn.placement} with ${drawn.raster.width}x${drawn.raster.height} pixels" }
		fun pixel(raster: org.umamo.format.art.LayerRaster, x: Int, y: Int) = (0..3).map { raster.rgba[(y * raster.width + x) * 4 + it].toInt() and 0xFF }
		expect("draw", pixel(drawn.raster, 80, 40).take(3) == listOf(0xf0, 0xd0, 0xc0)) { "the kit colour did not fill the shape: ${pixel(drawn.raster, 80, 40)}" }
		// The tapered line is thickest in its middle: count its dark rows at the middle and near an end.
		fun thickness(x: Int) = (0 until 40).count { y -> pixel(drawn.raster, x, y).let { it[3] > 128 && it[0] < 0x80 } }
		expect("taper", thickness(80) >= 10 && thickness(40) in 1 until thickness(80)) { "the line is ${thickness(40)} rows near its end and ${thickness(80)} in its middle" }

		// An ellipse filled with a gradient over its own box, which the renderer would paint black.
		edit("draw a gradient ellipse", "material_draw", req("id" to "iris", "svg" to """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20">
			<defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ff0000"/><stop offset="1" stop-color="#0000ff"/></linearGradient></defs>
			<ellipse cx="10" cy="10" rx="8" ry="9" fill="url(#g)"/></svg>"""))
		val iris = requireNotNull(now.document.layout.materials.item("iris")).raster
		expect("ellipse", pixel(iris, 10, 4)[0] > 150 && pixel(iris, 10, 16)[2] > 150) { "the gradient ellipse is ${pixel(iris, 10, 4)} at its top" }

		edit("place it", "layer_place", req("material_id" to "eye_white", "layer_id" to "eye_white"))
		edit("draw it larger", "material_draw", req("id" to "eye_white", "svg" to svg("90 95 60 30")))
		val larger = requireNotNull(now.document.layout.materials.item("eye_white"))
		val layer = now.document.source.layers.single { it.id.raw == "eye_white" }
		expect("redraw", larger.raster.width == 240 && layer.canvasRect() == LayerCanvasRect(90f, 95f, 60f, 30f) &&
			now.document.layout.layerMaterials["eye_white"] == "eye_white" && layer.raster.rgba.contentEquals(larger.raster.rgba)) {
			"the layer did not follow the drawing: ${layer.canvasRect()} with ${layer.raster.width} pixels" }

		edit("warm the skin", "layout_set_drawing_kit", req("colors" to mapOf("skin" to "#ffd0b0")))
		val warmer = requireNotNull(now.document.layout.materials.item("eye_white"))
		expect("kit", pixel(warmer.raster, 120, 60).take(3) == listOf(0xff, 0xd0, 0xb0) &&
			now.document.source.layers.single { it.id.raw == "eye_white" }.raster.rgba.contentEquals(warmer.raster.rgba)) { "the kit change did not reach the drawing and its layer" }
		refused("a colour in use cannot go", "layout_set_drawing_kit", req("colors" to mapOf("skin" to null)))

		// A drawing changed in place by element id, and read back as stored.
		fun lock(guide: String) = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 60 110">
			<path id="lock" d="$guide" stroke="var(--skin)" stroke-width="16" data-taper="0.8 1 0"/></svg>"""
		edit("draw a lock", "material_draw", req("id" to "lock", "svg" to lock("M20 10 C 24 40, 18 70, 26 100"), "density" to 2))
		val straight = requireNotNull(now.document.layout.materials.item("lock")).raster
		edit("bend it", "material_draw", req("id" to "lock", "edits" to listOf(mapOf("id" to "lock", "set" to mapOf("d" to "M20 10 C 40 40, 10 70, 40 100")))))
		val bent = requireNotNull(now.document.layout.materials.item("lock"))
		val readBack = WorkspaceMaterialQueries.get(requireNotNull(WorkspaceReadSession(runtime.read()).layoutState()), "lock", includeSvg = true)
		expect("edits", !bent.raster.rgba.contentEquals(straight.rgba) && bent.raster.width == straight.width &&
			readBack["svg"]?.jsonPrimitive?.content?.contains("C 40 40") == true) { "the edit did not redraw the lock" }
		refused("an edit names a missing element", "material_draw", req("id" to "lock", "edits" to listOf(mapOf("id" to "nope", "set" to mapOf("d" to "M0 0")))))

		val render = WorkspaceRenderSession(runtime.read(), { it })
		val view = runBlocking { render.renderModel(WorkspaceModelViewRequest(includeLayerIds = emptySet(),
			frame = WorkspaceViewFrame.CanvasRect(io.github.psd2live.core.Bounds(80f, 80f, 160f, 140f)),
			reference = WorkspaceViewReference(WorkspaceViewReferenceMode.UNDER, 1f), grid = 1f, outlineMaterialIds = listOf("eye_white"),
			output = WorkspaceViewOutputSpec(targetLongEdge = 400))) }
		expect("view", view.gridStep == 5f && view.outlinedMaterialIds == listOf("eye_white")) { "the view drew grid ${view.gridStep} and outlines ${view.outlinedMaterialIds}" }

		reopen()
		expect("saved", now.document.layout.drawingKit?.colors?.get("skin") == "#ffd0b0" &&
			now.document.layout.materials.item("eye_white")?.svg == larger.svg) { "the kit or the drawing did not survive saving" }
	}
}
