package io.github.psd2live.scenario

import io.github.psd2live.core.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * The model's sidecar data, authored as Cubism's animation view and viewer set it: an expression made from a pose and
 * reblended, the standard expressions taken in and trimmed, a pose group of two parts and an event on a motion. Each
 * survives undo, a cold replay and a save and reopen; the moc3 export writes it as exp3, pose3 and motion3 user data
 * that model3.json names, the VTube Studio package toggles the expressions, and deleting a parameter takes its entries
 * along.
 */
class ExpressionPoseScenario {
	@TempDir lateinit var temp: Path

	@Test fun expressionsPoseAndEventsReplayReopenAndExport() = Studio.run(temp, Characters.figure()) {
		edits("add a tail axis and a motion on it",
			"parameter_create" to req("parameter_id" to "ParamTail", "name" to "Tail", "min" to -10, "max" to 10),
			"motion_create" to req("id" to "wave", "name" to "Wave"),
			"motion_set_key" to req("id" to "wave", "parameter" to "ParamTail", "key" to mapOf("time" to 0, "value" to 0)),
			"motion_set_key" to req("id" to "wave", "parameter" to "ParamTail", "key" to mapOf("time" to 1, "value" to 6)))
		val standard = ModelExpressions.standardFor(puppet.parameters.mapTo(HashSet()) { it.id.raw })
		expect("standard", now.document.rigEdits.expressions == null) { "a new model already holds expressions of its own" }

		edit("make an expression of a pose", "expression_create", req("id" to "joy", "name" to "Joy", "fade_in" to 0.25,
			"values" to mapOf("ParamTail" to 4, "ParamAngleX" to 0)))
		fun expressions() = now.document.rigEdits.expressions.orEmpty()
		fun joy() = expressions().single { it.id == "joy" }
		expect("create", expressions().map { it.id } == standard.map { it.id } + "joy") { "the standard expressions were not taken in before Joy: ${expressions().map { it.id }}" }
		expect("create", joy().entries == listOf(ExpressionEntry("ParamTail", ExpressionBlend.ADD, 4f)) && joy().fadeIn == 0.25f) {
			"the pose became ${joy()}; only the tail moved from its default, by 4"
		}

		edit("overwrite the tail instead", "expression_set_entries", req("id" to "joy", "entries" to listOf(mapOf("parameter" to "ParamTail", "blend" to "OVERWRITE"))))
		expect("reblend", joy().entries.single() == ExpressionEntry("ParamTail", ExpressionBlend.OVERWRITE, 4f)) { "reblending changed the face: ${joy().entries}" }
		val deleted = standard.first().id
		edit("drop a standard expression", "expression_delete", req("id" to deleted))

		val parts = puppet.parts.map { it.id.raw }
		edits("group two parts and key an event",
			"pose_add_group" to req("parts" to listOf(parts[0], mapOf("part" to parts[1]))),
			"pose_update" to req("fade_in" to 0.8),
			"motion_set_event" to req("id" to "wave", "time" to 0.5, "value" to "wave_peak"))
		fun pose() = now.document.rigEdits.pose
		fun wave() = now.document.rigEdits.motionClips.single { it.id == "wave" }
		expect("pose", pose() == ModelPose(0.8f, listOf(PoseGroup(listOf(PosePart(parts[0]), PosePart(parts[1])))))) { "the pose is ${pose()}" }
		expect("event", wave().events == listOf(MotionEvent(0.5f, "wave_peak"))) { "the events are ${wave().events}" }
		val refused = refused("put a part in two groups", "pose_add_group", req("parts" to listOf(parts[0])))
		expect("pose", refused.message.orEmpty().contains("one pose group")) { "a part entered a second group: ${refused.message}" }

		val authored = Triple(expressions(), pose(), wave())
		undo(); redo()
		expect("undo", Triple(expressions(), pose(), wave()) == authored) { "undo and redo changed the sidecar data" }
		reopen()
		expect("reopen", Triple(expressions(), pose(), wave()) == authored) { "the reopened project lost sidecar data: ${Triple(expressions(), pose(), wave())}" }

		val files = export("moc3")
		fun json(suffix: String) = Json.parseToJsonElement(Files.readString(files.single { it.fileName.toString().endsWith(suffix) })).jsonObject
		val manifest = json(".model3.json").getValue("FileReferences").jsonObject
		val listed = manifest.getValue("Expressions").jsonArray.map { it.jsonObject.getValue("Name").jsonPrimitive.content to it.jsonObject.getValue("File").jsonPrimitive.content }
		expect("export", listed.map { it.first } == expressions().map { it.name } && listed.none { it.first == standard.first().name }) {
			"model3.json lists the expressions $listed"
		}
		val exp3 = json(".joy.exp3.json")
		expect("export", exp3["Type"]?.jsonPrimitive?.content == "Live2D Expression" && exp3.getValue("FadeInTime").jsonPrimitive.float == 0.25f &&
			exp3.getValue("Parameters").jsonArray.single().jsonObject.let {
				it.getValue("Id").jsonPrimitive.content == "ParamTail" && it.getValue("Blend").jsonPrimitive.content == "Overwrite" && it.getValue("Value").jsonPrimitive.float == 4f
			}) { "joy.exp3.json is $exp3" }
		expect("export", manifest["Pose"]?.jsonPrimitive?.content?.endsWith(".pose3.json") == true) { "model3.json names no pose3: $manifest" }
		val pose3 = json(".pose3.json")
		expect("export", pose3.getValue("FadeInTime").jsonPrimitive.float == 0.8f &&
			pose3.getValue("Groups").jsonArray.single().jsonArray.map { it.jsonObject.getValue("Id").jsonPrimitive.content } == parts.take(2)) { "pose3.json is $pose3" }
		val motion = files.map { it.fileName.toString() }.single { it.endsWith(".motion3.json") && "wave" in it.lowercase() }
		val motion3 = json(motion)
		expect("export", motion3["UserData"]?.jsonArray?.single()?.jsonObject?.getValue("Value")?.jsonPrimitive?.content == "wave_peak" &&
			motion3.getValue("Meta").jsonObject.getValue("UserDataCount").jsonPrimitive.int == 1) { "$motion carries no event: $motion3" }
		for (file in files.filter { it.toString().endsWith(".exp3.json") || it.toString().endsWith(".pose3.json") })
			expect("export", !Regex("[0-9][eE][-+]?[0-9]").containsMatchIn(Files.readString(file))) { "${file.fileName} has numbers Cubism cannot read" }

		val vtube = export("vtube-studio").single { it.toString().endsWith(".vtube.json") }
		val toggles = Json.parseToJsonElement(Files.readString(vtube)).jsonObject.getValue("Hotkeys").jsonArray.map { it.jsonObject }
			.filter { it.getValue("Action").jsonPrimitive.content == "ToggleExpression" }.map { it.getValue("File").jsonPrimitive.content }
		expect("vtube", toggles == listed.map { it.second }) { "VTube Studio toggles $toggles, the model lists ${listed.map { it.second }}" }

		edit("delete the tail axis", "parameter_delete", req("parameter_id" to "ParamTail"))
		expect("maintenance", joy().entries.isEmpty() && "joy" in expressions().map { it.id }) { "Joy kept an entry on the deleted axis: ${joy().entries}" }
		edit("back to the standard expressions", "expression_reset", req())
		expect("reset", now.document.rigEdits.expressions == null) { "the standard expressions did not come back" }
	}
}
