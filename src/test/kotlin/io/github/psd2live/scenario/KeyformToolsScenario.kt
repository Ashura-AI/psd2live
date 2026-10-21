package io.github.psd2live.scenario

import io.github.psd2live.core.GeneratedOverrides
import io.github.psd2live.core.RigGeometryTools
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.runtime.model.Deformer
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test

/**
 * The user resets the far end of a generated hair swing to its rest shape, then paste-corrects part of it from the
 * other end: both are keyform tools on a shape a generator owns, so each lands as an override, undoes as one step and
 * survives saving and reopening.
 */
class KeyformToolsScenario {
	@TempDir lateinit var temp: Path

	@Test fun keyformToolsOnAGeneratedShapeBecomeOverrides() = Studio.run(temp, Characters.head()) {
		edit("add hair swing", "swing_put", req("id" to "sway", "kind" to "lateral", "targets" to listOf("DeformHairBackPhysics"), "magnitude" to 0.3))
		val parameter = puppet.parameters.single { it.id.raw.startsWith("ParamSwing") }
		val warp = puppet.deformers.single { it.id.raw == "DeformHairBackPhysics" } as Deformer.Warp
		fun key(value: Float) = warp.geometryGrid!!.axes.associate { it.parameterId.raw to (if (it.parameterId == parameter.id) value
			else puppet.parameters.single { p -> p.id == it.parameterId }.default) }
		fun shape(value: Float) = RigGeometryTools.geometry(puppet, "warp", warp.id.raw, key(value)).points
		fun differs(a: FloatArray, b: FloatArray) = a.indices.maxOf { abs(a[it] - b[it]) }
		val far = key(parameter.max)
		val generated = shape(parameter.max)
		val rest = shape(parameter.default)
		expect("generated", differs(generated, rest) > 1e-3f) { "the swing does not move its far end" }

		edit("reset the far end", "keyform_apply", req("changes" to listOf(mapOf("op" to "reset", "target" to "warp:${warp.id.raw}", "key" to far))))
		expect("override", now.document.rigEdits.authoringJournal.any { it.jsonObject["op"]?.jsonPrimitive?.content == GeneratedOverrides.OP }) {
			"the reset of a generated keyform was not recorded as an override"
		}
		expect("reset", differs(shape(parameter.max), rest) < 1e-4f) { "the far end was not reset to the default-key shape" }

		// The free end is the last row: the swing pins its top.
		val n = rest.size / 2
		edit("paste two points back", "keyform_apply", req("changes" to listOf(mapOf("op" to "paste", "target" to "warp:${warp.id.raw}",
			"from" to key(parameter.min), "key" to far, "vertices" to listOf(n - 2, n - 1)))))
		val pasted = shape(parameter.max)
		val source = shape(parameter.min)
		val tail = rest.size - 4
		expect("paste", differs(source.copyOfRange(tail, rest.size), rest.copyOfRange(tail, rest.size)) > 1e-3f) { "the source end does not move" }
		expect("paste", differs(pasted.copyOfRange(tail, rest.size), source.copyOfRange(tail, rest.size)) < 1e-4f) { "the picked points did not take the source shape" }
		expect("paste", differs(pasted.copyOfRange(0, tail), rest.copyOfRange(0, tail)) < 1e-4f) { "points that were not picked moved" }

		undo("undo the paste")
		expect("undo", differs(shape(parameter.max), rest) < 1e-4f) { "undo did not take back exactly the paste" }
		redo("redo the paste")
		reopen()
		expect("reopened", differs(shape(parameter.max), pasted) < 1e-4f) { "the keyform tools changed on reopen" }
	}
}
