package io.github.psd2live.scenario

import io.github.psd2live.application.WorkspaceLayoutPreview
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import org.umamo.format.cmo3.Cmo3
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.format.cmo3.model.custom.CModelSource
import java.nio.file.Files

/**
 * Place first, generate later, and generate again: a project placed before generating builds the rig an imported
 * one does; layers placed after it show as plain meshes until the next generation, which merges them in and keeps the
 * user's edits; a classification change waits for the next generation too.
 */
class LayoutGenerationScenario {
	@TempDir lateinit var temp: Path

	@Test fun pendingMeshesReorderAcrossGeneratedLayers() = Studio.run(temp, Characters.withPlacedHat()) {
		val id = mesh("hat")
		fun order() = puppet.drawables.single { it.id.raw == id }.drawOrder
		edit("bring the pending mesh forward", "layer_reorder", req("layer_ids" to listOf("hat"), "to" to "front"))
		expect("front", puppet.drawables.filter { it.id.raw != id }.all { it.drawOrder < order() }) { "reordering did not move the editable mesh in front" }
		edit("send the pending mesh behind the rig", "layer_reorder", req("layer_ids" to listOf("hat"), "to" to "back"))
		expect("back", puppet.drawables.filter { it.id.raw != id }.all { it.drawOrder > order() }) { "reordering did not move the editable mesh behind the rig" }
	}

	@Test fun importedImagesHaveEditableMeshesAndGenerateOnTheirExistingTopology() {
		val head = Characters.head(physics = false)
		val empty = head.copy(source = WorkspaceSourceArt(64, 64, emptyList(), emptyList()),
			layout = WorkspaceLayout(generation = WorkspaceGeneration.NotGenerated))
		Studio.run(temp, empty) {
			val layer = importImages("import an image onto the empty canvas", listOf(Characters.disc(temp))).single()
			val id = mesh(layer)
			val topology = puppet.drawables.single { it.id.raw == id }.mesh!!.indices.toList()
			edit("classify the image", "layer_classify", req("layer_id" to layer, "role" to "face"))
			edit("generate from its tag", "rig_generate", req())
			expect("bound", mesh(layer) == id && puppet.drawables.single { it.id.raw == id }.mesh!!.indices.toList() == topology &&
				motion(id, "ParamAngleX", 30f) > 0.1) { "the image was not bound on its existing mesh: $id -> ${mesh(layer)}, parent ${puppet.drawables.single { it.id.raw == id }.parentDeformerId}, motion ${motion(id, "ParamAngleX", 30f)}" }
		}
	}

	@Test fun emptyAndSparseLayoutsUseTheOrdinaryEditingWorkspace() {
		for (count in 0..2) {
			val head = Characters.head(physics = false)
			val art = head.source
			val source = WorkspaceSourceArt(art.widthPx, art.heightPx, art.layers.take(count), art.groups)
			Studio.run(Files.createDirectories(temp.resolve("layers-$count")), head.copy(source = source,
				layout = WorkspaceLayout(generation = WorkspaceGeneration.NotGenerated))) {
				expect("ready", puppet.drawables.size == count && puppet.deformers.isEmpty() && puppet.parameters.isEmpty()) { "the layout is not an empty editing rig" }
				structural("create a parameter group before generation", "rig_edit_structure" to req("edits" to listOf(mapOf(
					"kind" to "param_group", "action" to "create", "id" to "UserGroup", "name" to "User group", "parent_id" to null))))
				edit("create an axis before generation", "parameter_create", req("parameter_id" to "ParamUser", "name" to "User"))
				if (count > 0) {
					val id = puppet.drawables.first().id.raw
					structural("create a warp before generation", "canvas_warp" to req("id" to "UserWarp", "name" to "User warp", "meshes" to listOf(id)))
					edit("rebuild a mesh before generation", "layer_mesh_rebuild", req("layer_id" to now.model.rig.layerIdByDrawableId.getValue(id)))
				}
				reopen("save the editable layout")
				allow("REGENERATION_CONFLICT")
				edit("generate a sparse rig", "rig_generate", req())
				expect("preserved", puppet.parameters.any { it.id.raw == "ParamUser" }) { "generation discarded user objects" }
				if (count > 0) {
					val files = export("cmo3")
					val exported = files.single { it.toString().endsWith(".cmo3") }
					val read = Cmo3Import.fromModelSource(Cmo3.read(Files.readAllBytes(exported)).root as CModelSource)
					expect("export", read.drawables.size == count) { "the sparse rig did not export its meshes" }
					for (layer in now.document.source.layers.map { it.id.raw })
						edit("delete $layer", "layer_delete", req("layer_id" to layer))
					expect("empty again", puppet.drawables.isEmpty()) { "deleting the final layer left a mesh" }
					reopen("save the emptied workspace")
				}
			}
		}
	}

	@Test fun layersPlacedLaterJoinTheRigAndTheUsersEditsStay() = Studio.run(temp, Characters.withPlacedHat()) {
		val placed = mesh("hat")
		expect("placed", !WorkspaceLayoutPreview.isPendingMesh(placed)) { "the placed hat has a temporary mesh: $placed" }
		val pending = inspect("generation").getValue("pending_layers").jsonArray.map { it.jsonPrimitive.content }
		expect("placed", pending == listOf("hat")) { "inspect lists $pending as pending" }
		val sleeve = mesh("sleeve_r")
		structural("give the right sleeve a warp and a custom axis",
			"canvas_warp" to req("id" to "SleeveWarp", "name" to "Sleeve warp", "meshes" to listOf(sleeve)),
			"parameter_create" to req("parameter_id" to "ParamFlap", "name" to "Flap", "min" to -1, "max" to 1),
			"keyform_apply" to req("changes" to listOf(mapOf("target" to "warp:SleeveWarp", "key" to mapOf("ParamFlap" to 0), "op" to "seed"))),
			"rig_deform" to req("changes" to listOf(mapOf("target" to "warp:SleeveWarp", "key" to mapOf("ParamFlap" to 1),
				"operations" to listOf(mapOf("type" to "translate", "delta" to listOf(0, 0.2)))))))
		structural("the pending hat is editable", "canvas_warp" to req("id" to "HatWarp", "name" to "Hat", "meshes" to listOf(placed)))
		edit("move the hat down a little", "layer_set_canvas_rect", req("layer_id" to "hat", "rect" to mapOf("left" to 0, "top" to 6, "width" to 420, "height" to 420)))
		val hat = now.document.source.layers.single { it.id.raw == "hat" }
		expect("moved", hat.transform.f == 6f) { "the hat did not move: ${hat.transform}" }
		expect("moved", motion(sleeve, "ParamFlap", 1f) > 3.0) { "moving a pending layer touched the user's axis" }

		allow("REGENERATION_CONFLICT")
		edit("generate again", "rig_generate", req())
		val generated = mesh("hat")
		expect("generated", !WorkspaceLayoutPreview.isPendingMesh(generated)) { "the hat still shows as a pending mesh" }
		expect("generated", generated == placed && puppet.deformers.any { it.id.raw == "HatWarp" }) { "generation lost the hat's authored hierarchy" }
		expect("generated", mesh("sleeve_r") == sleeve) { "the sleeve's mesh was renamed" }
		expect("generated", motion(sleeve, "ParamFlap", 1f) > 3.0) { "the user's axis no longer moves the sleeve" }
		expect("generated", now.document.layout.generation == WorkspaceGeneration.Generated) { "something still waits: ${now.document.layout.generation}" }
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
