package io.github.psd2live.scenario

import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.LayerBlend
import org.umamo.format.psd.PsdReader
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.DrawableId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * The artist hands over a face drawn on a Screen layer and hair on a Linear Dodge (Add) one. The face keeps Screen
 * through generation, edits, history and a reopen; a Cubism 5.3 moc3 carries it, a Cubism 5.0 one draws it as Add (the
 * nearest mode that runtime has) and the loss report says so by mesh ID. The posed PSD gives both layers back with their
 * blends - the Add hair too, which rendered on its own used to keep the empty backdrop's alpha and vanish.
 */
class ScreenBlendScenario {
	@TempDir lateinit var temp: Path

	private fun screened() = Characters.head(physics = false).let { document ->
		val source = document.source as WorkspaceSourceArt
		document.copy(source = source.copy(layers = source.layers.map { layer ->
			when (layer.id.raw) {
				"face" -> (layer as WorkspaceSourceLayer).copy(blend = LayerBlend.Screen)
				"hair" -> (layer as WorkspaceSourceLayer).copy(blend = LayerBlend.Add)
				else -> layer
			}
		}))
	}

	@Test fun aScreenLayerStaysScreenFromThePsdToTheExport() = Studio.run(temp, screened()) {
		val face = DrawableId(mesh("face"))
		val hair = DrawableId(mesh("hair"))
		fun blend(id: DrawableId) = puppet.drawables.single { it.id == id }.blendMode
		expect("generated", blend(face) == BlendMode.Screen) { "the Screen layer's mesh is ${blend(face)}" }
		expect("generated", blend(hair) == BlendMode.AdditivePremultiplied) { "the Add layer's mesh is ${blend(hair)}" }
		val cubism50 = now

		// Cubism 5.0 (the default target) has no Screen: Add, reported as an approximation rather than dropped.
		val older = moc3("export moc3 for Cubism 5.0")
		val olderFace = older.drawables.single { it.id == face }.blendMode
		expect("5.0 moc3", olderFace == BlendMode.AdditivePremultiplied) { "Screen became $olderFace for Cubism 5.0" }
		expect("5.0 moc3", older.drawables.single { it.id == hair }.blendMode == BlendMode.AdditivePremultiplied) { "the Add mesh changed blend" }
		val reported = losses.filter { it.getValue("feature").jsonPrimitive.content == "blend_mode" }
		expect("5.0 losses", reported.size == 1 && reported.single().getValue("handling").jsonPrimitive.content == "approximated" &&
			reported.single().getValue("object").jsonPrimitive.content == face.raw &&
			"screen" in reported.single().getValue("note").jsonPrimitive.content) { "the Cubism 5.0 loss report has $reported" }

		settings("target Cubism 5.3", req("runtimeTarget" to "Cubism53"))
		expect("5.3", blend(face) == BlendMode.Screen) { "changing the target changed the mesh to ${blend(face)}" }
		val newer = moc3("export moc3 for Cubism 5.3")
		val newerFace = newer.drawables.single { it.id == face }.blendMode
		expect("5.3 moc3", newerFace == BlendMode.Screen) { "the Cubism 5.3 moc3 reads back $newerFace" }
		expect("5.3 losses", losses.none { it.getValue("feature").jsonPrimitive.content == "blend_mode" }) { "Cubism 5.3 reported a blend loss: $losses" }

		val psd = export("psd-pose").single { it.toString().endsWith(".psd") }
		val layers = PsdReader.read(Files.readAllBytes(psd)).layers
		expect("psd pose", layers.single { it.name == "face" }.blend == LayerBlend.Screen) { "the posed face layer is ${layers.single { it.name == "face" }.blend}" }
		val posedHair = layers.singleOrNull { it.name == "hair" }
		expect("psd pose", posedHair?.blend == LayerBlend.Add) { "the posed hair layer is ${posedHair?.blend}" }
		expect("psd pose", posedHair != null && (3 until posedHair.raster.rgba.size step 4).any { posedHair.raster.rgba[it] != 0.toByte() }) { "the posed hair layer is empty" }
		expect("psd pose", losses.filter { it.getValue("feature").jsonPrimitive.content == "blend_mode" }.map { it.getValue("object").jsonPrimitive.content } == listOf(hair.raw)) {
			"the posed PSD reported ${losses.filter { it.getValue("feature").jsonPrimitive.content == "blend_mode" }}"
		}

		checkout("back to Cubism 5.0", cubism50.historyHead)
		expect("history", blend(face) == BlendMode.Screen) { "going back in history changed the mesh to ${blend(face)}" }
		reopen()
		expect("reopened", blend(face) == BlendMode.Screen) { "the reopened mesh is ${blend(face)}" }
	}
}
