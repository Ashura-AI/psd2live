package io.github.psd2live.scenario

import io.github.psd2live.application.WorkspaceObjectEdits
import io.github.psd2live.core.RigObjectPaste
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test

/**
 * Pasted copies and converted IDs through what regenerates the rig under them: splits, deletions, a classification
 * change and a reopen. A copy never stands in for its original's layer, a converted mesh keeps its new ID, and the
 * objects only a generator or the skeleton holds are neither copied nor renamed.
 */
class ObjectOpsScenario {
	@TempDir lateinit var temp: Path

	private fun convert(vararg renames: Pair<String, String>) =
		req("renames" to renames.map { (target, id) -> mapOf("target" to target, "id" to id) })

	private fun Studio.pasted(): List<String> = now.document.rigEdits.authoringJournal
		.filter { it["op"]?.jsonPrimitive?.content == RigObjectPaste.OP }.flatMap { RigObjectPaste.copies(it) }.map { it.newId }

	private fun Studio.ids() = puppet.drawables.map { it.id.raw }

	private fun Throwable.mentions(text: String) = generateSequence(this) { it.cause }.any { text in it.message.orEmpty() }

	@Test fun pasteAndConvertThroughSplitDeleteAndReopen() = Studio.run(temp, Characters.head(physics = false)) {
		val face = mesh("face")
		edit("paste the face", WorkspaceObjectEdits.PASTE, req("sources" to listOf("mesh:$face")))
		val copy = pasted().single()
		expect("pasted", mesh("face") == face && copy in ids()) { "the face layer names ${meshes("face")}" }
		edit("convert the face", WorkspaceObjectEdits.CONVERT_ID, convert("mesh:$face" to "ArtMeshCheek", "mesh:$copy" to "ArtMeshCheekCopy"))
		expect("converted", mesh("face") == "ArtMeshCheek" && face !in ids() && copy !in ids()) { "the face is ${mesh("face")} among ${ids()}" }
		edit("split the hair", "source_split_polygon", req("layer_id" to "hair", "names" to listOf("Upper", "Lower"),
			"piece_ids" to listOf("upper", "lower"), "polygon" to listOf(listOf(0, 0), listOf(64, 0), listOf(64, 30), listOf(0, 30))))
		expect("split", mesh("face") == "ArtMeshCheek" && "ArtMeshCheekCopy" in ids()) { "after the split the face is ${meshes("face")} among ${ids()}" }
		reopen()
		edit("delete the face", "layer_delete", req("layer_id" to "face"))
		expect("face deleted", "ArtMeshCheek" !in ids() && "ArtMeshCheekCopy" !in ids()) { "the face or its copy outlived its layer: ${ids()}" }
		undo("take the deletion back")
		expect("restored", mesh("face") == "ArtMeshCheek" && "ArtMeshCheekCopy" in ids()) { "the face came back as ${meshes("face")}" }
		edit("delete the lower hair", "layer_delete", req("layer_id" to "lower"))
		expect("deleted", mesh("face") == "ArtMeshCheek" && "ArtMeshCheekCopy" in ids() && meshes("lower").isEmpty()) {
			"deleting the hair piece touched the face: ${ids()}"
		}
		reopen()
	}

	/** A converted mesh's own layer split, and the layer of a pasted original split. */
	@Test fun splitTheLayersOfAConvertedMeshAndOfAPastedOriginal() = Studio.run(temp, Characters.head(physics = false)) {
		val hair = mesh("hair")
		edit("paste the hair", WorkspaceObjectEdits.PASTE, req("sources" to listOf("mesh:$hair")))
		edit("convert the face", WorkspaceObjectEdits.CONVERT_ID, convert("mesh:${mesh("face")}" to "ArtMeshCheek"))
		edit("split the face", "source_split_polygon", req("layer_id" to "face", "names" to listOf("Upper", "Lower"),
			"piece_ids" to listOf("upper", "lower"), "polygon" to listOf(listOf(0, 0), listOf(64, 0), listOf(64, 24), listOf(0, 24))))
		expect("face split", meshes("upper").size == 1 && meshes("lower").size == 1) { "the split made ${meshes("upper")} and ${meshes("lower")}" }
		edit("split the hair", "source_split_polygon", req("layer_id" to "hair", "names" to listOf("Top", "Bottom"),
			"piece_ids" to listOf("top", "bottom"), "polygon" to listOf(listOf(0, 0), listOf(64, 0), listOf(64, 30), listOf(0, 30))))
		expect("hair split", meshes("top").size == 1 && meshes("bottom").size == 1 && meshes("hair").isEmpty()) {
			"the hair split made ${meshes("top")} and ${meshes("bottom")}, the hair layer names ${meshes("hair")}"
		}
		reopen()
	}

	/** A classification change regenerates the rig: a converted mesh merges with its generated self rather than doubling. */
	@Test fun convertedMeshSurvivesRegeneration() = Studio.run(temp, Characters.figure(physics = false)) {
		val top = mesh("top")
		edit("convert the top", WorkspaceObjectEdits.CONVERT_ID, convert("mesh:$top" to "ArtMeshTop"))
		edit("rename the top", "rig_edit_structure", req("edits" to listOf(mapOf("action" to "rename", "kind" to "mesh", "id" to "ArtMeshTop", "name" to "Shirt"))))
		edit("reclassify the right sleeve", "layer_classify", req("layer_id" to "sleeve_r", "role" to "objects"))
		expect("regenerated", meshes("top") == listOf("ArtMeshTop") && puppet.drawables.single { it.id.raw == "ArtMeshTop" }.name == "Shirt") {
			"the top layer names ${meshes("top")}; drawables ${puppet.drawables.map { it.id.raw to it.name }}"
		}
		reopen()
	}

	/** What the skeleton binds keeps the ID the skeleton bakes it under. */
	@Test fun skeletonBoundObjectsAreRefused() = Studio.run(temp, Characters.figure(physics = false)) {
		edit("apply the proposed skeleton", "skeleton_auto", req())
		val bone = now.document.rigEdits.skeleton!!.bones.first { it.drawableIds.isNotEmpty() && !it.role.anchor }
		val mesh = refused("convert a bound mesh", WorkspaceObjectEdits.CONVERT_ID, convert("mesh:${bone.drawableIds.first()}" to "ArtMeshBound"))
		expect("bound mesh", mesh.mentions("skeleton")) { "refused for another reason: ${mesh.message}" }
		val parameter = refused("convert a bone parameter", WorkspaceObjectEdits.CONVERT_ID, convert("parameter:${bone.parameterId}" to "ParamBone"))
		expect("bone parameter", parameter.mentions("skeleton")) { "refused for another reason: ${parameter.message}" }
	}

	/** Objects a simulation adds after the journal are not found, copied or renamed: replay would not find them. */
	@Test fun generatorOwnedObjectsAreLeftOut() = Studio.run(temp, Characters.figure(physics = true)) {
		edit("simulate the back hair", "model_apply_preset", req("preset" to "back_hair"))
		val authored = now.model.authored.rig.puppet
		val generated = puppet.parameters.map { it.id.raw }.filter { id -> authored.parameters.none { it.id.raw == id } }
		expect("generated", generated.isNotEmpty()) { "the simulation added no parameter" }
		val found = WorkspaceObjectEdits.find(now.model, req("kinds" to listOf("parameter"), "field" to "id"))
		expect("find", found.none { it.id in generated }) { "find lists generated parameters ${found.map { it.id }.filter { it in generated }}" }
		refused("paste a generated parameter", WorkspaceObjectEdits.PASTE, req("sources" to listOf("parameter:${generated.first()}")))
		edit("rename every parameter", WorkspaceObjectEdits.RENAME, req("kinds" to listOf("parameter"), "field" to "name", "suffix" to " (edited)"))
		reopen()
	}
}
