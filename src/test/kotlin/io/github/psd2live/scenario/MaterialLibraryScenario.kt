package io.github.psd2live.scenario

import io.github.psd2live.application.WorkspaceLayoutPreview
import io.github.psd2live.application.WorkspaceLayoutState
import io.github.psd2live.application.WorkspaceMaterialFiles
import io.github.psd2live.application.WorkspaceMaterialQueries
import io.github.psd2live.application.WorkspaceMaterialStaging
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.project.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.psd.PsdReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * The material library: files read into folders and items, organised and tagged, placed on the canvas and replaced,
 * kept through undo and a saved project, and a folder written back out as a PSD.
 */
class MaterialLibraryScenario {
	@TempDir lateinit var temp: Path

	private val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="40" height="20" viewBox="0 0 40 20"><rect x="2" y="2" width="36" height="16" fill="#c04080"/></svg>"""

	@Test fun materialsAreImportedOrganisedPlacedAndExported() = Studio.run(temp, Characters.figure()) {
		val files = Files.createDirectories(temp.resolve("files"))
		val psd = Characters.figurePsd(files)
		val disc = Characters.disc(files)
		val badge = files.resolve("badge.svg").also { Files.writeString(it, svg) }
		val entries = WorkspaceMaterialFiles.read(listOf(psd, disc, badge))
		edit("import the files", "material_add", req("staging" to WorkspaceMaterialStaging.stage(entries)))
		val library = now.document.layout.materials
		val figure = library.folders.single { it.name == "figure" && it.parentId == null }
		expect("imported", library.items.count { it.folderId == figure.id } == now.document.source.layers.size) { "the PSD folder holds ${library.items.count { it.folderId == figure.id }} items" }
		expect("imported", figure.origin == psd.fileName.toString()) { "the PSD folder does not name its file: '${figure.origin}'" }
		val vector = library.items.single { it.name == "badge" }
		expect("imported", vector.kind == MaterialKind.VECTOR && vector.raster.width == 40 && vector.raster.height == 20) { "the SVG reads as ${vector.kind} ${vector.raster.width}x${vector.raster.height}" }
		val hairItem = library.items.single { it.folderId == figure.id && it.name == "front_hair" }
		expect("imported", hairItem.placement != null) { "a PSD layer lost its place" }

		edit("make a folder for props", "material_folder_create", req("name" to "props", "id" to "props"))
		edit("move the badge and disc into it", "material_move", req("ids" to listOf(vector.id, library.items.single { it.name == "disc" }.id), "folder_id" to "props"))
		edit("tag the props as headwear", "material_tag", req("ids" to listOf("props"), "add" to listOf("headwear", "装饰")))

		edit("place the badge", "layer_place", req("material_id" to vector.id, "rect" to mapOf("left" to 180, "top" to 4, "width" to 60, "height" to 30), "layer_id" to "badge"))
		val badgeLayer = now.document.source.layers.single { it.id.raw == "badge" }
		expect("placed", now.document.layout.layerMaterials["badge"] == vector.id && "badge" in now.document.layout.generation.pendingLayerIds) { "the placed badge is not linked and pending" }
		expect("placed", now.document.layerOverrides["badge"]?.tag == SemanticTag.HEADWEAR && now.document.layout.layerTags["badge"] == setOf("装饰")) {
			"the folder's tags did not reach the layer: ${now.document.layerOverrides["badge"]} ${now.document.layout.layerTags["badge"]}" }
		expect("placed", WorkspaceLayoutPreview.isPendingMesh(mesh("badge"))) { "the badge shows a generated mesh before generating" }

		refused("a material in use cannot be deleted", "material_delete", req("ids" to listOf("props")))

		edit("render the badge sharper", "material_set_resolution", req("id" to vector.id, "width" to 160, "height" to 80))
		val sharper = now.document.source.layers.single { it.id.raw == "badge" }
		expect("resolution", sharper.raster.width == 160 && sharper.canvasRect() == badgeLayer.canvasRect()) {
			"the layer did not take the new resolution in place: ${sharper.raster.width}x${sharper.raster.height} ${sharper.canvasRect()}" }

		val discItem = now.document.layout.materials.items.single { it.name == "disc" }
		edit("show the disc instead", "layer_replace_image", req("layer_id" to "badge", "material_id" to discItem.id))
		val replaced = now.document.source.layers.single { it.id.raw == "badge" }
		expect("replaced", replaced.raster.width == discItem.raster.width && replaced.canvasRect() == badgeLayer.canvasRect() &&
			now.document.layout.layerMaterials["badge"] == discItem.id) { "the replacement moved the layer or kept the old link" }

			// Painting and adjusting the material reach the layer that shows it, in place.
			fun shown() = now.document.source.layers.single { it.id.raw == "badge" }
			edit("paint a stroke across the disc", "material_paint", req("id" to discItem.id, "mode" to "brush",
				"points" to listOf(listOf(1, discItem.raster.height / 2), listOf(discItem.raster.width - 2, discItem.raster.height / 2)), "radius" to 2, "color" to listOf(255, 0, 0, 255)))
			val painted = requireNotNull(now.document.layout.materials.item(discItem.id))
			expect("painted", !painted.raster.rgba.contentEquals(discItem.raster.rgba) && painted.raster.width == discItem.raster.width) { "the stroke did not land on the material" }
			expect("painted", shown().raster.rgba.contentEquals(painted.raster.rgba) && shown().canvasRect() == badgeLayer.canvasRect()) { "the layer showing the disc did not take the stroke in place" }
			edit("flip and warm the disc", "material_adjust", req("id" to discItem.id, "flip" to "horizontal", "hue" to 20, "contrast" to 10))
			val adjusted = requireNotNull(now.document.layout.materials.item(discItem.id))
			expect("adjusted", !adjusted.raster.rgba.contentEquals(painted.raster.rgba) && shown().raster.rgba.contentEquals(adjusted.raster.rgba)) { "the adjustment did not reach the layer" }
			refused("an adjustment needs something to change", "material_adjust", req("id" to discItem.id))
		// A painting session's strokes land as one edit, in order: the second stroke erases what the first drew.
		val beforeSession = requireNotNull(now.document.layout.materials.item(discItem.id)).raster
		val row = listOf(listOf(1, 2), listOf(discItem.raster.width - 2, 2))
		edit("paint a session of two strokes", "material_paint", req("id" to discItem.id, "gestures" to listOf(
			mapOf("mode" to "brush", "points" to row, "radius" to 1, "color" to listOf(0, 255, 0, 255)),
			mapOf("mode" to "eraser", "points" to row, "radius" to 1))))
		val session = requireNotNull(now.document.layout.materials.item(discItem.id)).raster
		expect("painted", !session.rgba.contentEquals(beforeSession.rgba) && shown().raster.rgba.contentEquals(session.rgba) &&
			session.rgba[(2 * session.width + session.width / 2) * 4 + 3].toInt() == 0) { "the session's strokes did not land in order" }
		refused("a paint takes one gesture or a list, not both", "material_paint", req("id" to discItem.id, "mode" to "clear",
			"gestures" to listOf(mapOf("mode" to "clear"))))

		// A paint session on the material, as the material window runs one: strokes and undo stay in the session, other
		// edits commit meanwhile, and its pixels land with material_set_pixels.
		val sessions = io.github.psd2live.application.WorkspacePaintSessions(runtime)
		val painting = sessions.beginMaterial(discItem.id)
		painting.gesture(req("mode" to "brush", "points" to listOf(listOf(3, 3), listOf(9, 3)), "radius" to 1, "color" to listOf(0, 0, 255, 255)))
		painting.gesture(req("mode" to "clear"))
		painting.undo()
		edit("tag the props while painting", "material_tag", req("ids" to listOf("props"), "add" to listOf("paint")))
		painting.gesture(req("mode" to "bucket", "point" to listOf(0, 0), "tolerance" to 0, "color" to listOf(255, 255, 0, 255)))
		val image = painting.image()
		val argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
		val pixels = org.umamo.format.art.LayerRaster(image.width, image.height, ByteArray(argb.size * 4).also { out ->
			argb.forEachIndexed { i, p -> if (p ushr 24 != 0) { out[i * 4] = (p shr 16).toByte(); out[i * 4 + 1] = (p shr 8).toByte(); out[i * 4 + 2] = p.toByte(); out[i * 4 + 3] = (p ushr 24).toByte() } }
		})
		expect("session", painting.strokes.size == 3 && pixels.rgba[(3 * pixels.width + 5) * 4 + 2].toInt() and 255 == 255) { "the session lost a stroke or kept the undone one" }
		edit("apply the session", "material_set_pixels", req("id" to discItem.id, "staging" to io.github.psd2live.application.WorkspaceMaterialPixels.stage(pixels)))
		expect("session", now.document.layout.materials.item(discItem.id)!!.raster.rgba.contentEquals(pixels.rgba) && shown().raster.rgba.contentEquals(pixels.rgba) &&
			shown().canvasRect() == badgeLayer.canvasRect()) { "the session's pixels did not reach the material and the layer showing it" }
		val stale = sessions.beginMaterial(discItem.id)
		edit("paint the material elsewhere", "material_paint", req("id" to discItem.id, "mode" to "pencil", "points" to listOf(listOf(4, 4)), "radius" to 1))
		expect("session", runCatching { stale.gesture(req("mode" to "clear")) }.exceptionOrNull() is io.github.psd2live.application.WorkspaceConflict) {
			"a session went on painting after the material's pixels changed" }
			edit("paint over the vector", "material_paint", req("id" to vector.id, "mode" to "clear"))
			expect("painted", now.document.layout.materials.item(vector.id)?.kind == MaterialKind.BITMAP) { "a painted vector stayed a vector" }

		allow("REGENERATION_CONFLICT")
		edit("generate", "rig_generate", req())
		expect("generated", !WorkspaceLayoutPreview.isPendingMesh(mesh("badge"))) { "the badge did not generate" }
		reopen()
		expect("reopened", now.document.layout.materials.items.size == entries.size) { "the saved project lost materials" }
		expect("reopened", now.document.layout.materials.folder(figure.id)?.origin == figure.origin) { "the saved project lost the folder's file" }

		val state = WorkspaceLayoutState(now.document.layout, now.document.source.layers, emptySet(), now.document.deletedLayerIds,
			now.document.source.widthPx, now.document.source.heightPx)
		val (bytes, count) = WorkspaceMaterialQueries.psd(state, figure.id)
		val back = PsdReader.read(bytes)
		expect("exported", count == back.layers.size && back.layers.map { it.name }.toSet() == Characters.figure().source.layers.map { it.id.raw }.toSet()) {
			"the exported PSD holds ${back.layers.map { it.name }}" }
		val hair = back.layers.single { it.name == "front_hair" }
		expect("exported", hair.bounds == WorkspaceMaterialEdits(hairItem)) { "the exported layer moved: ${hair.bounds}" }
	}

	@Test fun anEmptyLayoutProjectIsPlacedThenGenerated() = Studio.run(temp,
		WorkspaceDocument(WorkspaceSourceArt(420, 420, emptyList(), emptyList()), emptyMap(), emptySet(), emptyMap(), emptyMap(),
			io.github.psd2live.core.RigEditOverlay.Empty, Characters.figure().settings, layout = WorkspaceLayout(generation = WorkspaceGeneration.NotGenerated))) {
		expect("created", now.model.layoutPreview && puppet.drawables.isEmpty()) { "an empty project shows meshes" }
		val entries = WorkspaceMaterialFiles.read(listOf(Characters.figurePsd(Files.createDirectories(temp.resolve("files")))))
		edit("import the figure", "material_add", req("staging" to WorkspaceMaterialStaging.stage(entries)))
		val folder = now.document.layout.materials.folders.single()
		edit("place the whole figure", "layer_place", req("material_id" to folder.id))
		expect("placed", now.document.source.layers.size == entries.size && puppet.drawables.size == entries.size) {
			"placing the folder gave ${now.document.source.layers.size} layers and ${puppet.drawables.size} meshes" }
		edit("generate", "rig_generate", req())
		expect("generated", !now.model.layoutPreview && puppet.deformers.isNotEmpty()) { "the placed figure did not generate a rig" }
		reopen()
	}

	@Test fun aLinkedLayerShowsItsMaterial() = Studio.run(temp, Characters.figure()) {
		val disc = WorkspaceMaterialFiles.read(listOf(Characters.disc(Files.createDirectories(temp.resolve("files")), size = 40)))
		edit("import the disc", "material_add", req("staging" to WorkspaceMaterialStaging.stage(disc)))
		val item = now.document.layout.materials.items.single()
		edits("place it twice",
			"layer_place" to req("material_id" to item.id, "rect" to mapOf("left" to 100, "top" to 100, "width" to 40, "height" to 40), "layer_id" to "a"),
			"layer_place" to req("material_id" to item.id, "rect" to mapOf("left" to 200, "top" to 100, "width" to 40, "height" to 40), "layer_id" to "b"))
		allow("REGENERATION_CONFLICT")
		edit("generate", "rig_generate", req())
		fun pixels(id: String) = now.document.source.layers.single { it.id.raw == id }.raster
		fun material() = now.document.layout.materials.item(item.id)!!.raster
		fun allShowTheMaterial() = pixels("a").rgba.contentEquals(material().rgba) && pixels("b").rgba.contentEquals(material().rgba)

		// Painting either layer paints the material, and the other layer follows.
		edit("paint layer a", "source_paint_brush", req("layer_id" to "a", "points" to listOf(listOf(105, 120), listOf(135, 120)), "radius" to 2,
			"color" to listOf(255, 0, 0, 255)))
		expect("linked", !material().rgba.contentEquals(item.raster.rgba) && allShowTheMaterial() &&
			now.document.layout.layerMaterials.keys.containsAll(listOf("a", "b"))) { "painting a linked layer did not paint its material" }
		// A stroke out past the disc: the part on the layer's mesh paints the material, the rest is a layer of its own.
		val count = now.document.source.layers.size
		edit("paint a out past its edge", "source_paint_brush", req("layer_id" to "a", "points" to listOf(listOf(125, 120), listOf(170, 120)), "radius" to 2,
			"color" to listOf(0, 255, 0, 255)))
		val off = now.document.source.layers.singleOrNull { it.id.raw.startsWith("painted_") }
		expect("cut", off != null && now.document.source.layers.size == count + 1 && off.bounds.left >= 135 && off.bounds.left + off.bounds.width >= 170 &&
			allShowTheMaterial() && now.document.layout.layerMaterials.keys.containsAll(listOf("a", "b"))) {
			"a stroke off a linked layer is not a layer of its own: ${off?.bounds}" }
		expect("cut", (material().rgba[(20 * 40 + 30) * 4 + 1].toInt() and 255) == 255) { "the stroke on the layer did not reach its material" }

		// A paint session on a linked layer reaches anywhere on the canvas; its commit writes the material.
		val sessions = io.github.psd2live.application.WorkspacePaintSessions(runtime)
		val session = sessions.begin(now.state, "b")
		expect("linked", session.materialId == item.id && !session.paintsMaterial && session.width == now.document.source.widthPx) {
			"a paint session on a linked layer does not cover the canvas" }
		session.gesture(req("mode" to "brush", "points" to listOf(listOf(205, 110), listOf(235, 110)), "radius" to 2, "color" to listOf(0, 0, 255, 255)))
		step("apply the session") { sessions.commit(session.id, session.sessionState, false, false, MutationAuthor.USER) }
		expect("linked", allShowTheMaterial() && (material().rgba[(10 * 40 + 20) * 4 + 2].toInt() and 255) == 255) { "the session's pixels did not reach both layers" }

		// Rebuilding a mesh keeps the material's raster and the link.
		edit("rebuild b's mesh", "layer_mesh_rebuild", req("layer_id" to "b"))
		expect("linked", now.document.layout.layerMaterials["b"] == item.id && allShowTheMaterial()) { "a mesh rebuild cut the layer loose from its material" }

		// Unlinked, a layer's pixels are its own.
		edit("unlink a", "material_unlink", req("layer_ids" to listOf("a")))
		val kept = material()
		edit("paint layer a alone", "source_paint_brush", req("layer_id" to "a", "points" to listOf(listOf(110, 130)), "radius" to 3, "color" to listOf(0, 255, 0, 255)))
		expect("unlinked", "a" !in now.document.layout.layerMaterials && material() === kept && !pixels("a").rgba.contentEquals(kept.rgba) &&
			pixels("b").rgba.contentEquals(kept.rgba)) { "painting an unlinked layer reached the material" }
		reopen()
		expect("reopened", now.document.layout.layerMaterials == mapOf("b" to item.id)) { "the links did not survive a save" }
	}

	private fun WorkspaceMaterialEdits(item: MaterialItem) = io.github.psd2live.application.WorkspaceMaterialEdits.enclosing(requireNotNull(item.placement))
}
