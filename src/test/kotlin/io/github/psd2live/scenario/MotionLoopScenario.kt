package io.github.psd2live.scenario

import io.github.psd2live.core.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.runtime.model.ParameterId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * A spinning wheel and a looping head turn: a repeating parameter keyed a turn past its range, a loop closed so it has
 * no seam, undone, replayed and reopened, then exported. The motion3 keeps the unwrapped spin and the loop flag, the
 * moc3 the repeat flag, and the head's path through the turn is the path the editor draws.
 */
class MotionLoopScenario {
	@TempDir lateinit var temp: Path

	@Test fun repeatingSpinAndClosedLoopReplayReopenAndExport() = Studio.run(temp, Characters.figure()) {
		edits("a repeating wheel axis and a looping motion",
			"parameter_create" to req("parameter_id" to "ParamWheel", "name" to "Wheel", "min" to 0, "max" to 360, "default" to 0, "repeat" to true),
			"motion_create" to req("id" to "spin", "name" to "Spin"),
			"motion_properties" to req("id" to "spin", "loop" to true, "duration" to 1))
		edits("spin a turn and a half, and turn the head",
			"motion_set_key" to req("id" to "spin", "parameter" to "ParamWheel", "key" to mapOf("time" to 0, "value" to 0, "interpolation" to "LINEAR")),
			"motion_set_key" to req("id" to "spin", "parameter" to "ParamWheel", "key" to mapOf("time" to 0.5, "value" to 540)),
			"motion_set_key" to req("id" to "spin", "parameter" to "ParamAngleZ", "key" to mapOf("time" to 0, "value" to -20, "out" to listOf(0.3, 6))),
			"motion_set_key" to req("id" to "spin", "parameter" to "ParamAngleZ", "key" to mapOf("time" to 0.5, "value" to 20)))
		fun clip() = now.document.rigEdits.motionClips.single { it.id == "spin" }
		expect("keyed", clip().curve("ParamWheel")!!.keys.last().value == 540f) { "a repeating axis lost its key past the range" }
		val wheel = now.model.rig.puppet.parameters.single { it.id.raw == "ParamWheel" }
		val frame = boundedPreviewPose(MotionClips.sampleAll(clip(), 0.5), now.model.rig.puppet.parameters)
		expect("keyed", kotlin.math.abs(frame.getValue(ParameterId("ParamWheel")) - 180f) < 1e-3f) { "540 plays as ${frame[ParameterId("ParamWheel")]}, not 180" }
		expect("keyed", MotionLoops.seams(clip(), now.model.rig.puppet.parameters) == setOf("ParamWheel", "ParamAngleZ")) {
			"seams before closing: ${MotionLoops.seams(clip(), now.model.rig.puppet.parameters)}"
		}

		edit("close the loop", "motion_close_loop", req("id" to "spin"))
		expect("closed", MotionLoops.seams(clip(), now.model.rig.puppet.parameters).isEmpty()) { "a seam remains: ${clip().curves}" }
		val spin = clip().curve("ParamWheel")!!.keys
		expect("closed", spin.last().time == 1f && spin.last().value == 720f) { "the spin ends at ${spin.last()}, not two turns on at the end" }
		val head = clip().curve("ParamAngleZ")!!.keys
		val leaving = head.first().outHandle.y / (head.first().outHandle.x * (head[1].time - head[0].time))
		val arriving = -head.last().inHandle.y / (head.last().inHandle.x * (head.last().time - head[head.size - 2].time))
		expect("closed", head.last().value == -20f && kotlin.math.abs(leaving - arriving) < 1e-3f) {
			"the head returns to ${head.last().value} at slope $arriving, leaving at $leaving"
		}
		undo("undo the close")
		expect("undone", MotionLoops.seams(clip(), now.model.rig.puppet.parameters).isNotEmpty()) { "undo left the loop closed" }
		redo("redo the close")

		// The editor's trajectory of the face through the head turn.
		val track = MotionTrajectory.sample(now.model.rig.puppet, clip(), meshes("face").toSet(), null, emptyMap())!!
		val xs = (0 until track.size).map { track.points[it * 2] }
		expect("trajectory", track.size == 31 && xs.max() - xs.min() > 1f) { "the face's path spans ${xs.max() - xs.min()} over ${track.size} frames" }
		expect("trajectory", kotlin.math.abs(track.points[0] - track.points[(track.size - 1) * 2]) < 1e-3f) { "a closed loop ends where it starts" }

		// Auto-key over the spin keys the turn the curve is on, not the wrapped value the pose shows.
		val turning = MotionClips.sample(clip().curve("ParamWheel")!!, 0.75f)
		val shown = MotionLoops.wrap(turning, 0f, 360f) + 10f
		kotlinx.coroutines.runBlocking {
			io.github.psd2live.application.WorkspacePoseCommands(runtime).execute(now.projectId, now.state, "main",
				req("values" to mapOf("ParamWheel" to shown), "auto_key" to mapOf("clip_id" to "spin", "time" to 0.75)), io.github.psd2live.project.MutationAuthor.USER)
		}
		val keyed = clip().curve("ParamWheel")!!.keys.single { kotlin.math.abs(it.time - 0.75f) < 1e-4f }.value
		expect("auto key", kotlin.math.abs(keyed - (turning + 10f)) < 1e-2f) { "auto-key wrote $keyed over a curve at $turning, not ${turning + 10f}" }

		reopen()
		expect("reopened", clip().curve("ParamWheel")!!.keys.last().value == 720f) { "the spin changed on reopen" }
		// The default Cubism 5.0 target strips the repeat, and the report says the spin will clamp there.
		val losses = step("export for Cubism 5.0") {
			io.github.psd2live.application.WorkspaceExportSession(now, "Figure.psd").target("moc3", Files.createTempDirectory(temp, "moc50").toAbsolutePath(), emptyMap())
		}.getValue("losses").jsonArray.map { it.jsonObject }
		expect("export", losses.any { it.getValue("object").jsonPrimitive.content == "spin/ParamWheel" && it.getValue("handling").jsonPrimitive.content == "approximated" }) {
			"the 5.0 report does not name the clamped spin: $losses"
		}
		expect("export", losses.none { it.getValue("object").jsonPrimitive.content.endsWith("/ParamAngleZ") }) { "an ordinary curve was reported: $losses" }
		// Parameter repeat needs a Cubism 5.3 target; the default 5.0 one strips it with a notice.
		edit("export motions for Cubism 5.3", "settings_update", req("changes" to mapOf("exportMoc3" to true, "exportMotions" to true, "runtimeTarget" to "Cubism53")))
		val motion = export("moc3").filter { it.toString().endsWith(".motion3.json") }
			.map { Json.parseToJsonElement(Files.readString(it)).jsonObject }
			.single { json -> json.getValue("Curves").jsonArray.any { it.jsonObject.getValue("Id").jsonPrimitive.content == "ParamWheel" } }
		expect("export", motion.getValue("Meta").jsonObject.getValue("Loop").jsonPrimitive.boolean) { "the loop flag did not export" }
		val segments = motion.getValue("Curves").jsonArray.map { it.jsonObject }.single { it.getValue("Id").jsonPrimitive.content == "ParamWheel" }
			.getValue("Segments").jsonArray.map { it.jsonPrimitive.float }
		expect("export", segments.any { it == 720f } && segments.any { it == 540f }) { "the exported spin was clamped: $segments" }
		expect("export", moc3().parameters.single { it.id.raw == "ParamWheel" }.repeat) { "the moc3 lost the repeat flag" }
		expect("export", wheel.repeat) { "the axis does not repeat" }
		val cmo3 = export("cmo3").single { it.toString().endsWith(".cmo3") }
		val source = org.umamo.interop.cmo3.Cmo3Import.fromModelSource(org.umamo.format.cmo3.Cmo3.read(Files.readAllBytes(cmo3)).root as org.umamo.format.cmo3.model.custom.CModelSource)
		expect("export", source.parameters.single { it.id.raw == "ParamWheel" }.repeat) { "the cmo3 lost the repeat flag" }
	}
}
