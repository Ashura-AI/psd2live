package io.github.psd2live.scenario

import io.github.psd2live.application.WorkspaceLayoutPreview
import io.github.psd2live.application.WorkspaceNotGenerated
import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test

/**
 * Place first, generate later, and generate again: a project placed before generating builds the rig an imported
 * one does; layers placed after it show as plain meshes until the next generation, which merges them in and keeps the
 * user's edits; a classification change waits for the next generation too.
 */
class LayoutGenerationScenario {
	@TempDir lateinit var temp: Path

	private fun Throwable.notGenerated() = generateSequence(this) { it.cause }.any { it is WorkspaceNotGenerated }

	@Test fun aProjectPlacedFirstGeneratesTheRigAnImportedOneHas() {
		val figure = Characters.figure()
		val direct = Oracles.hash(runBlocking { WorkspacePreviewBuilder().build(figure) }.rig.puppet)
		Studio.run(temp, figure.copy(layout = WorkspaceLayout(generation = WorkspaceGeneration.NotGenerated))) {
			expect("placed", now.model.layoutPreview && puppet.deformers.isEmpty()) { "an ungenerated project shows a rig" }
			expect("placed", puppet.drawables.size == figure.source.layers.size) { "every layer shows as one mesh: ${puppet.drawables.map { it.id.raw }}" }
			val refused = refused("a warp needs a rig", "canvas_warp", req("id" to "W", "name" to "W", "meshes" to listOf(puppet.drawables.first().id.raw)))
			expect("refused", refused.notGenerated()) { "the refusal is not a not-generated failure: $refused" }
			edit("generate", "rig_generate", req())
			expect("generated", !now.model.layoutPreview && hash() == direct) { "the first generation differs from a direct build" }
			expect("generated", now.document == figure) { "the generated document is not the imported one" }
		}
	}

	@Test fun layersPlacedLaterJoinTheRigAndTheUsersEditsStay() = Studio.run(temp, Characters.withPlacedHat()) {
		val placed = mesh("hat")
		expect("placed", WorkspaceLayoutPreview.isPendingMesh(placed)) { "the placed hat has a generated mesh already: $placed" }
		val pending = inspect("generation").getValue("pending_layers").jsonArray.map { it.jsonPrimitive.content }
		expect("placed", pending == listOf("hat")) { "inspect lists $pending as pending" }
		val sleeve = mesh("sleeve_r")
		structural("give the right sleeve a warp and a custom axis",
			"canvas_warp" to req("id" to "SleeveWarp", "name" to "Sleeve warp", "meshes" to listOf(sleeve)),
			"parameter_create" to req("parameter_id" to "ParamFlap", "name" to "Flap", "min" to -1, "max" to 1),
			"keyform_apply" to req("changes" to listOf(mapOf("target" to "warp:SleeveWarp", "key" to mapOf("ParamFlap" to 0), "op" to "seed"))),
			"rig_deform" to req("changes" to listOf(mapOf("target" to "warp:SleeveWarp", "key" to mapOf("ParamFlap" to 1),
				"operations" to listOf(mapOf("type" to "translate", "delta" to listOf(0, 0.2)))))))
		val refused = refused("the hat has no rig to warp", "canvas_warp", req("id" to "HatWarp", "name" to "Hat", "meshes" to listOf(placed)))
		expect("refused", refused.notGenerated()) { "editing a pending mesh is not a not-generated failure: $refused" }
		edit("move the hat down a little", "layer_set_canvas_rect", req("layer_id" to "hat", "rect" to mapOf("left" to 0, "top" to 6, "width" to 420, "height" to 420)))
		val hat = now.document.source.layers.single { it.id.raw == "hat" }
		expect("moved", hat.bounds.top == 6) { "the hat did not move: ${hat.bounds}" }
		expect("moved", motion(sleeve, "ParamFlap", 1f) > 3.0) { "moving a pending layer touched the user's axis" }

		allow("REGENERATION_CONFLICT")
		edit("generate again", "rig_generate", req())
		val generated = mesh("hat")
		expect("generated", !WorkspaceLayoutPreview.isPendingMesh(generated)) { "the hat still shows as a pending mesh" }
		expect("generated", motion(generated, "ParamAngleX", 30f) > 1.0) { "the generated hat does not turn with the head" }
		expect("generated", mesh("sleeve_r") == sleeve) { "the sleeve's mesh was renamed" }
		expect("generated", motion(sleeve, "ParamFlap", 1f) > 3.0) { "the user's axis no longer moves the sleeve" }
		expect("generated", now.document.layout.generation == WorkspaceGeneration.Generated) { "something still waits: ${now.document.layout.generation}" }
		reopen()
	}

	@Test fun aClassificationWaitsForTheNextGeneration() = Studio.run(temp, Characters.figure()) {
		val before = hash()
		edit("reclassify the right sleeve", "layer_classify", req("layer_id" to "sleeve_r", "role" to "objects", "side" to "none"))
		expect("pending", hash() == before) { "a classification changed the rig before generating" }
		expect("pending", "sleeve_r" in now.document.layout.generation.pendingClassifications) { "the classification does not wait" }
		val row = inspect("layers").getValue("items").jsonArray.map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == "sleeve_r" }
		expect("pending", row["role"]?.jsonPrimitive?.content == "objects" && row["pending"]?.jsonPrimitive?.boolean == true) { "inspect shows $row" }
		allow("REGENERATION_CONFLICT")
		edit("generate", "rig_generate", req())
		val generated = hash()
		expect("generated", generated != before && now.document.layerOverrides["sleeve_r"]?.tag == io.github.psd2live.core.SemanticTag.OBJECTS) {
			"generating did not apply the classification"
		}
		undo("back to the waiting classification"); undo("back to the original")
		edits("classify and generate in one batch",
			"layer_classify" to req("layer_id" to "sleeve_r", "role" to "objects", "side" to "none"), "rig_generate" to req())
		expect("batched", hash() == generated) { "a batch of classify and generate makes another rig" }
	}
}
