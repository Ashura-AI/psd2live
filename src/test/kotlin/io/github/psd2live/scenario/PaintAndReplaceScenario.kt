package io.github.psd2live.scenario

import io.github.psd2live.core.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test

/**
 * Painting on a rigged character: a sleeve bound to the skeleton is extended with the brush and its mesh rebuilt, the
 * face is erased without a rebuild, the top's image is replaced and made denser. Rebuilt meshes stay where the art is
 * and keep following their bones; painting never silently regenerates; the project reopens and exports.
 */
class PaintAndReplaceScenario {
	@TempDir lateinit var temp: Path

	@Test fun paintRebuildReplaceUnderTheSkeleton() = Studio.run(temp, Characters.figure()) {
		edit("apply the proposed skeleton", "skeleton_auto", req())
		val arm = now.document.rigEdits.skeleton!!.bones.single { it.role == BoneRole.UPPER_ARM && it.side == Side.LEFT }
		val sleeve = mesh("sleeve_l")
		val drawn = bounds(sleeve)

		edit("extend the left sleeve with the brush", "source_paint_brush", req("layer_id" to "sleeve_l", "radius" to 8, "rebuild_mesh" to true,
			"color" to listOf(200, 80, 60, 255), "points" to listOf(listOf(300, 150), listOf(320, 156), listOf(338, 158))))
		val painted = bounds(mesh("sleeve_l"))
		expect("painted", mesh("sleeve_l") == sleeve) { "the rebuilt sleeve has another ID" }
		expect("painted", abs(painted[0] - drawn[0]) < 3f && abs(painted[1] - drawn[1]) < 3f) {
			"the rebuilt sleeve moved: bounds ${drawn.toList()} -> ${painted.toList()}"
		}
		expect("painted", painted[3] > drawn[3] + 4f) { "the rebuilt mesh does not cover the painted stroke: ${painted.toList()}" }
		expect("painted", motion(sleeve, arm.parameterId, 45f) > 15.0) { "the rebuilt sleeve no longer follows its bone" }

		edit("erase part of the face", "source_paint_eraser", req("layer_id" to "face", "radius" to 10,
			"points" to listOf(listOf(180, 100), listOf(200, 110))))
		expect("erased", !updateGenerated("update after painting")) { "painting changed what the generators make" }

		val image = Characters.disc(temp, "top.png", 64)
		edit("replace the top's image", "layer_replace_image", req("layer_id" to "top",
			"png_base64" to java.util.Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(image)), "fit" to "stretch"))
		val upper = now.document.rigEdits.skeleton!!.bones.single { it.role == BoneRole.UPPER_BODY }
		expect("replaced", motion(mesh("top"), upper.parameterId, 20f) > 5.0) { "the replaced top no longer bends with the upper body" }
		edit("make the top denser", "layer_set_pixel_density", req("layer_ids" to listOf("top"), "density" to 2.0))
		reopen()
		expect("reopened", motion(mesh("sleeve_l"), arm.parameterId, 45f) > 15.0) { "the reopened sleeve no longer follows its bone" }
		edit("export moc3 too", "settings_update", req("changes" to mapOf("exportMoc3" to true)))
		exportMatchesEditor(this)
	}

	/** A paint reaching off a layer's meshes is cut along them: the part off them is a new layer just above, with its own mesh. */
	@Test fun paintOffTheMeshesBecomesItsOwnLayer() = Studio.run(temp, Characters.figure()) {
		val face = now.document.source.layers.single { it.id.raw == "face" }
		val faceMesh = mesh("face")
		val drawn = bounds(faceMesh)
		val count = now.document.source.layers.size
		val y = ((drawn[1] + drawn[3]) / 2).toInt()
		val outside = (drawn[2] + 30f).toInt()
		edit("paint from the face out past its edge", "source_paint_brush", req("layer_id" to "face", "radius" to 3, "color" to listOf(0, 0, 255, 255),
			"points" to listOf(listOf(((drawn[0] + drawn[2]) / 2).toInt(), y), listOf(outside, y))))
		val layers = now.document.source.layers
		val added = layers.singleOrNull { it.id.raw.startsWith("painted_") }
		expect("cut", added != null && layers.size == count + 1 && added.name == face.name + "+" &&
			layers.indexOf(added) == layers.indexOfFirst { it.id.raw == "face" } + 1) { "the paint off the face is not a new layer just above it: ${layers.map { it.id.raw }}" }
		added!!
		val kept = layers.single { it.id.raw == "face" }
		val (fb, kb) = face.bounds to kept.bounds
		expect("cut", kb.left >= fb.left && kb.top >= fb.top && kb.left + kb.width <= fb.left + fb.width && kb.top + kb.height <= fb.top + fb.height) {
			"the face grew: $fb -> $kb" }
		expect("cut", mesh("face") == faceMesh && bounds(faceMesh).contentEquals(drawn)) { "the face's mesh changed: ${drawn.toList()} -> ${bounds(faceMesh).toList()}" }
		val space = LayerSpace.of(added)
		val x = space.canvasToRasterX(outside + 0.5f).toInt(); val row = space.canvasToRasterY(y + 0.5f).toInt()
		val at = (row * added.raster.width + x) * 4
		expect("cut", x in 0 until added.raster.width && (added.raster.rgba[at + 2].toInt() and 255) == 255 && added.raster.rgba[at + 3].toInt() != 0) {
			"the new layer does not hold the stroke off the face" }
		val material = now.document.layout.layerMaterials[added.id.raw]?.let(now.document.layout.materials::item)
		expect("cut", material != null && material.name == added.name && material.raster.rgba.contentEquals(added.raster.rgba)) {
			"the new layer has no material of its own in the library" }
		val parent = puppet.drawables.single { it.id.raw == faceMesh }.parentDeformerId
		expect("cut", puppet.drawables.single { it.id.raw == mesh(added.id.raw) }.parentDeformerId == parent) { "the new layer's mesh is not under the face's parent" }
		reopen()
		expect("reopened", now.document.source.layers.any { it.id == added.id } && mesh(added.id.raw).isNotEmpty()) { "the new layer did not survive a save" }
	}
}
