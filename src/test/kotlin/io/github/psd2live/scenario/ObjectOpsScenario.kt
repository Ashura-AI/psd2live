package io.github.psd2live.scenario

import io.github.psd2live.application.WorkspaceFailure
import io.github.psd2live.application.WorkspaceHitAreaEdits
import io.github.psd2live.application.WorkspaceObjectEdits
import io.github.psd2live.core.ModelExpressions
import io.github.psd2live.core.RigObjectPaste
import io.github.psd2live.core.SeeThroughLayers
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.runtime.model.DrawableId
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

	/** A pasted copy is ordered by its own ID and stays put when its original's layer moves; the original keeps its layer's order. */
	@Test fun pastedCopyKeepsItsOwnDrawOrderAndPlace() = Studio.run(temp, Characters.head(physics = false)) {
		val face = mesh("face")
		edit("paste the face", WorkspaceObjectEdits.PASTE, req("sources" to listOf("mesh:$face")))
		val copy = pasted().single()
		fun order(id: String) = puppet.drawables.single { it.id.raw == id }.drawOrder
		val faceOrder = order(face)
		edit("order the copy", "layer_draw_order", req("target" to "mesh:$copy", "order" to 900))
		expect("copy ordered", order(copy) == 900f && order(face) == faceOrder && now.model.config.drawOrderOverrides.keys == setOf(copy)) {
			"the copy draws at ${order(copy)}, the face at ${order(face)}; overrides ${now.model.config.drawOrderOverrides}"
		}
		edit("order the face layer", "layer_draw_order", req("target" to "layer:face", "order" to 100))
		expect("face ordered", order(face) == 100f && order(copy) == 900f) { "the face draws at ${order(face)}, the copy at ${order(copy)}" }
		val before = bounds(copy)
		edit("move the face layer", "layer_transform", req("layer_id" to "face", "translate" to listOf(4, 2)))
		expect("copy stays", bounds(copy).contentEquals(before) && !bounds(face).contentEquals(before)) {
			"the copy moved with its original's layer: ${before.toList()} -> ${bounds(copy).toList()}"
		}
	}

	/** Conversions that hand a freed ID to another mesh, then a split of the first one's layer: each ID converts once. */
	@Test fun chainedConversionsSurviveASplit() = Studio.run(temp, Characters.head(physics = false)) {
		val face = mesh("face"); val hair = mesh("hair")
		edit("convert the face", WorkspaceObjectEdits.CONVERT_ID, convert("mesh:$face" to "ArtMeshCheek"))
		edit("give the hair the face's old ID", WorkspaceObjectEdits.CONVERT_ID, convert("mesh:$hair" to face))
		expect("converted", mesh("face") == "ArtMeshCheek" && mesh("hair") == face) { "face ${meshes("face")}, hair ${meshes("hair")}" }
		edit("split the face", "source_split_polygon", req("layer_id" to "face", "names" to listOf("Upper", "Lower"),
			"piece_ids" to listOf("upper", "lower"), "polygon" to listOf(listOf(0, 0), listOf(64, 0), listOf(64, 24), listOf(0, 24))))
		expect("split", meshes("upper").size == 1 && meshes("lower").size == 1 && mesh("hair") == face && "ArtMeshCheek" !in ids()) {
			"upper ${meshes("upper")}, lower ${meshes("lower")}, hair ${meshes("hair")} among ${ids()}"
		}
	}

	/**
	 * The skeleton bakes before the journal: the automatic armature leaves out a mesh whose ID was converted and a pasted
	 * copy, and binding either by hand is refused.
	 */
	@Test fun skeletonLeavesOutConvertedMeshesAndPastedCopies() = Studio.run(temp, Characters.figure(physics = false)) {
		edit("convert the right sleeve", WorkspaceObjectEdits.CONVERT_ID, convert("mesh:${mesh("sleeve_r")}" to "ArtMeshSleeveRight"))
		edit("paste the left sleeve", WorkspaceObjectEdits.PASTE, req("sources" to listOf("mesh:${mesh("sleeve_l")}")))
		val copy = pasted().single()
		edit("apply the proposed skeleton", "skeleton_auto", req())
		val bones = now.document.rigEdits.skeleton!!.bones
		val bound = bones.flatMap { it.drawableIds }
		expect("auto", "ArtMeshSleeveRight" !in bound && copy !in bound && mesh("sleeve_l") in bound) { "the armature binds $bound" }
		val bone = bones.first { it.drawableIds.isNotEmpty() && !it.role.anchor }.id
		for ((name, mesh) in listOf("bind the converted sleeve" to "ArtMeshSleeveRight", "bind the pasted sleeve" to copy)) {
			val failure = refused(name, "skeleton_bind", req("drawable_id" to mesh, "bone_id" to bone))
			expect(name, failure.mentions("converted or made after")) { "refused for another reason: ${failure.message}" }
		}
	}

	/** The reference opacity preview changes the layer's own meshes, as the committed edit does; a pasted copy keeps its own. */
	@Test fun referenceOpacityPreviewLeavesPastedCopies() = Studio.run(temp, Characters.head(physics = false)) {
		val face = mesh("face")
		edit("paste the face", WorkspaceObjectEdits.PASTE, req("sources" to listOf("mesh:$face")))
		val copy = pasted().single()
		val preview = io.github.psd2live.ui.state.ReferenceOpacityDraft(io.github.psd2live.ui.state.ReferenceTarget.Layer("face"), 0.3f).preview(now.model)
		fun opacity(id: String) = preview.rig.puppet.drawables.single { it.id.raw == id }.opacity
		expect("preview", opacity(face) == 0.3f && opacity(copy) == puppet.drawables.single { it.id.raw == copy }.opacity) {
			"the face previews at ${opacity(face)}, the copy at ${opacity(copy)}"
		}
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

	/** Hit areas follow a converted mesh, expressions a converted parameter (standard or the document's), the pose a converted part. */
	@Test fun conversionFollowsHitAreasExpressionsAndPose() = Studio.run(temp, Characters.figure(physics = false)) {
		val face = mesh("face")
		edit("set up Head and Body", WorkspaceHitAreaEdits.PRESET, req())
		edit("convert the face", WorkspaceObjectEdits.CONVERT_ID, convert("mesh:$face" to "ArtMeshHead"))
		val head = now.document.rigEdits.hitAreas.single { it.id == "HitAreaHead" }
		expect("hit area", head.meshes == listOf("ArtMeshHead")) { "Head names ${head.meshes}" }

		// Before the document takes them in, the standard expressions find a standard parameter under its new ID.
		fun ids() = puppet.parameters.mapTo(HashSet()) { it.id.raw }
		val standard = ModelExpressions.standard.flatMap { it.entries }.map { it.parameterId }.first { it in ids() }
		edit("convert a standard parameter", WorkspaceObjectEdits.CONVERT_ID, convert("parameter:$standard" to "ParamStandardRenamed"))
		val effective = ModelExpressions.effective(now.document.rigEdits, ids()).flatMap { it.entries }.map { it.parameterId }
		expect("standard expressions", "ParamStandardRenamed" in effective && standard !in effective) { "the standard expressions name $effective" }

		edits("an axis, an expression on it and a pose group",
			"parameter_create" to req("parameter_id" to "ParamTail", "name" to "Tail", "min" to -10, "max" to 10),
			"expression_create" to req("id" to "joy", "name" to "Joy", "values" to mapOf("ParamTail" to 4)))
		val parts = puppet.parts.map { it.id.raw }
		edit("group two parts", "pose_add_group", req("parts" to listOf(parts[0], parts[1])))
		edit("convert the axis and a part", WorkspaceObjectEdits.CONVERT_ID, convert("parameter:ParamTail" to "ParamTailSwing", "part:${parts[0]}" to "PartRenamed"))
		val joy = now.document.rigEdits.expressions.orEmpty().single { it.id == "joy" }
		expect("expression", joy.entries.map { it.parameterId } == listOf("ParamTailSwing")) { "Joy names ${joy.entries}" }
		val pose = now.document.rigEdits.pose!!.groups.single().parts.map { it.partId }
		expect("pose", pose == listOf("PartRenamed", parts[1])) { "the pose names $pose" }
	}

	/**
	 * The copies over the bangs and pasted copies side by side: a pasted brow gets no copy over the bangs of its own, a copy
	 * over the bangs is neither pasted nor converted, converting its brow or bangs keeps the copy, and deleting the brow's
	 * layer takes the brow, its pasted copy and its copy over the bangs.
	 */
	@Test fun copiesOverTheBangsBesidePastedCopies() = Studio.run(temp, Characters.browed()) {
		val brow = mesh("brow_l"); val hair = mesh("front_hair")
		val through = SeeThroughLayers.copyId(DrawableId(brow)).raw
		fun copies() = now.model.rig.seeThroughCopies
		expect("copies", copies()[through] == brow) { "copies ${copies()}" }
		edit("paste the brow", WorkspaceObjectEdits.PASTE, req("sources" to listOf("mesh:$brow")))
		val pastedBrow = pasted().single()
		expect("pasted", pastedBrow in ids() && pastedBrow !in copies().values && mesh("brow_l") == brow) {
			"the pasted brow $pastedBrow; copies ${copies()}; the brow layer names ${meshes("brow_l")}"
		}
		for ((name, operation, request) in listOf(
			Triple("paste the copy over the bangs", WorkspaceObjectEdits.PASTE, req("sources" to listOf("mesh:$through"))),
			Triple("convert the copy over the bangs", WorkspaceObjectEdits.CONVERT_ID, convert("mesh:$through" to "ArtMeshThrough")),
			Triple("duplicate onto the copy over the bangs", WorkspaceObjectEdits.MESH_DUPLICATE, req("source" to "mesh:$brow", "targets" to listOf("mesh:$through"))),
		)) {
			val failure = WorkspaceFailure.from(refused(name, operation, request))
			expect(name, failure.code == "generated_copy") { "refused as ${failure.toJson()}" }
		}
		edit("convert the brow and the bangs", WorkspaceObjectEdits.CONVERT_ID, convert("mesh:$brow" to "ArtMeshBrowLeft", "mesh:$hair" to "ArtMeshBangs"))
		val renamed = SeeThroughLayers.copyId(DrawableId("ArtMeshBrowLeft")).raw
		expect("converted", copies()[renamed] == "ArtMeshBrowLeft" && through !in ids() &&
			puppet.drawables.single { it.id.raw == renamed }.maskedBy == listOf(DrawableId("ArtMeshBangs"))) {
			"copies ${copies()}, masks ${puppet.drawables.firstOrNull { it.id.raw == renamed }?.maskedBy}"
		}
		reopen()
		edit("delete the brow", "layer_delete", req("layer_id" to "brow_l"))
		expect("deleted", listOf("ArtMeshBrowLeft", pastedBrow, renamed).none { it in ids() }) { "left behind: ${ids()}" }
		undo("take the deletion back")
		expect("restored", listOf("ArtMeshBrowLeft", pastedBrow, renamed).all { it in ids() }) { "missing after undo: ${ids()}" }
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
	}
}
