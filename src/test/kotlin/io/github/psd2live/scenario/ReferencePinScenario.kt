package io.github.psd2live.scenario

import io.github.psd2live.application.WorkspaceLayoutEdits
import io.github.psd2live.application.WorkspaceMaterialFiles
import io.github.psd2live.application.WorkspaceMaterialStaging
import io.github.psd2live.application.WorkspaceRenderSession
import io.github.psd2live.core.Bounds
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * References pinned to keyforms: a sheet drawn at a grid of keyforms is cut into reference layers each pinned to its
 * keyform, a view at a keyform shows only its own cell, a pad sheet lays every keyform out in the sheet's order (and
 * any sub-rectangle of it), unpinning shows a reference at every pose, and the pins survive saving.
 */
class ReferencePinScenario {
	@TempDir lateinit var temp: Path

	@Test fun aKeyformSheetBecomesPinnedReferences() = Studio.run(temp, Characters.figure()) {
		val puppet = now.model.rig.puppet
		val (x, y) = puppet.parameters.filter { it.max > it.min }.take(2)
		val xs = listOf(x.min, (x.min + x.max) / 2f, x.max)
		val ys = listOf(y.max, y.min)
		// Three columns by two rows of flat colours, 20 pixels a cell.
		val colours = listOf(listOf(0xFF0000, 0x00FF00, 0x0000FF), listOf(0xFFFF00, 0x00FFFF, 0xFF00FF))
		val rgba = ByteArray(60 * 40 * 4)
		for (py in 0 until 40) for (px in 0 until 60) {
			val c = colours[py / 20][px / 20]; val o = (py * 60 + px) * 4
			rgba[o] = (c shr 16).toByte(); rgba[o + 1] = (c shr 8).toByte(); rgba[o + 2] = c.toByte(); rgba[o + 3] = -1
		}
		val sheet = Files.createDirectories(temp.resolve("sheet")).resolve("angles.png")
		Files.write(sheet, PngCodec.write(RasterImage(60, 40, rgba)))
		edit("import the sheet", "material_add", req("staging" to WorkspaceMaterialStaging.stage(WorkspaceMaterialFiles.read(listOf(sheet)))))
		val material = now.document.layout.materials.items.single().id
		val width = now.document.source.widthPx.toFloat(); val height = now.document.source.heightPx.toFloat()
		val rect = mapOf("left" to width / 4f, "top" to height / 4f, "width" to width / 2f, "height" to height / 2f)

		refused("y values must number the rows", "reference_place_grid", req("material_id" to material, "x_parameter" to x.id.raw, "x_values" to xs,
			"y_parameter" to y.id.raw, "y_values" to ys, "rows" to 3))
		edit("place the sheet on its keyforms", "reference_place_grid", req("material_id" to material, "x_parameter" to x.id.raw, "x_values" to xs,
			"y_parameter" to y.id.raw, "y_values" to ys, "rect" to rect, "id_prefix" to "angle"))
		val ids = (0..1).flatMap { r -> (0..2).map { c -> "angle_${r}_$c" } }
		expect("placed", now.document.layout.referenceLayerIds.containsAll(ids) && ids.all { id -> now.document.layout.materials.item(id) != null }) {
			"references ${now.document.layout.referenceLayerIds}" }
		expect("pinned", now.document.layout.referencePins["angle_1_2"] == mapOf(x.id.raw to xs[2], y.id.raw to ys[1])) {
			"pins ${now.document.layout.referencePins}" }

		val render = WorkspaceRenderSession(runtime.read(), { it })
		val frame = WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, width, height))
		fun centre(image: BufferedImage, r: List<Int>) = image.getRGB(r[0] + r[2] / 2, r[1] + r[3] / 2) and 0xFFFFFF
		fun at(xv: Float, yv: Float): Int = runBlocking {
			val view = render.renderModel(WorkspaceModelViewRequest(parameters = mapOf(x.id.raw to xv, y.id.raw to yv), includeLayerIds = ids.toSet(),
				frame = frame, output = WorkspaceViewOutputSpec(targetLongEdge = 256)))
			val image = ImageIO.read(view.png.inputStream())
			centre(image, listOf(0, 0, image.width, image.height))
		}
		for ((r, yv) in ys.withIndex()) for ((c, xv) in xs.withIndex())
			expect("keyform $c,$r", at(xv, yv) == colours[r][c]) { "at ${x.id.raw}=$xv ${y.id.raw}=$yv the view shows ${"%06x".format(at(xv, yv))}" }

		fun pad(columns: IntRange? = null, rows: IntRange? = null) = runBlocking {
			render.renderPad(WorkspacePadViewRequest(x.id.raw, y.id.raw, xs, ys, columns, rows, includeLayerIds = ids.toSet(), frame = frame,
				show = WorkspacePadShow.REFERENCE, output = WorkspaceViewOutputSpec(targetLongEdge = 768)))
		}
		val whole = pad()
		val wholeImage = ImageIO.read(whole.images.single().inputStream())
		val cells = whole.metadata.getValue("cells").jsonArray.map { it.jsonObject }
		expect("pad", cells.size == 6) { "${cells.size} cells" }
		for (cell in cells) {
			val c = cell.getValue("column").jsonPrimitive.int; val r = cell.getValue("row").jsonPrimitive.int
			val shown = centre(wholeImage, cell.getValue("imageRect").jsonArray.map { it.jsonPrimitive.int })
			expect("pad cell $c,$r", shown == colours[r][c] && cell.getValue("referenceLayerIds").jsonArray.single().jsonPrimitive.content == "angle_${r}_$c") {
				"cell $c,$r shows ${"%06x".format(shown)}: $cell" }
		}
		val cut = pad(1..2, 1..1).metadata.getValue("cells").jsonArray.map { it.jsonObject.getValue("column").jsonPrimitive.int to it.jsonObject.getValue("row").jsonPrimitive.int }
		expect("sub-rectangle", cut == listOf(1 to 1, 2 to 1)) { "cut $cut" }

		refused("only reference layers are pinned", WorkspaceLayoutEdits.PIN_REFERENCE, req("layer_ids" to listOf(now.document.source.layers.first { it.id.raw !in ids }.id.raw),
			"pin" to mapOf(x.id.raw to x.default)))
		refused("pins name parameters", WorkspaceLayoutEdits.PIN_REFERENCE, req("layer_ids" to listOf("angle_0_0"), "pin" to mapOf("NoSuchParam" to 0)))
		edit("unpin the top left", WorkspaceLayoutEdits.PIN_REFERENCE, req("layer_ids" to listOf("angle_0_0"), "pin" to null))
		expect("unpinned", "angle_0_0" !in now.document.layout.referencePins) { "${now.document.layout.referencePins.keys}" }
		reopen()
		expect("saved", now.document.layout.referencePins.size == 5 && now.document.layout.referencePins["angle_1_0"] == mapOf(x.id.raw to xs[0], y.id.raw to ys[1])) {
			"pins after reopening ${now.document.layout.referencePins}" }
		edit("give one back to the model", WorkspaceLayoutEdits.MARK_REFERENCE, req("layer_ids" to listOf("angle_1_1"), "reference" to false))
		expect("dropped", "angle_1_1" !in now.document.layout.referencePins) { "a model layer kept its pin" }
	}
}
