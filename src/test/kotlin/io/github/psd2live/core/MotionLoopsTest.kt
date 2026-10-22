package io.github.psd2live.core

import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import kotlin.test.*

class MotionLoopsTest {
	private val angle = Parameter(ParameterId("ParamAngleX"), "Angle X", -30f, 30f, 0f)
	private val wheel = Parameter(ParameterId("ParamWheel"), "Wheel", -180f, 180f, 0f, repeat = true)

	@Test fun aRepeatingAxisWrapsAndKeysTurnsPastItsRange() {
		assertEquals(-170f, MotionLoops.wrap(190f, -180f, 180f))
		assertEquals(170f, MotionLoops.wrap(-190f, -180f, 180f))
		assertEquals(180f, MotionLoops.wrap(180f, -180f, 180f), "the range itself stays as it is")
		assertEquals(-30f..30f, MotionLoops.keyLimits(angle))
		assertEquals(-180f - 360f * MotionLoops.REPEAT_TURNS, MotionLoops.keyLimits(wheel).start)
		val clip = MotionClip("m", "M", curves = listOf(
			MotionCurve("ParamWheel", listOf(MotionKey(0f, 0f), MotionKey(1f, 900f))),
			MotionCurve("ParamAngleX", listOf(MotionKey(0f, 0f), MotionKey(1f, 30f)))))
		val narrowed = MotionClips.reconcileParameters(listOf(clip), listOf(angle.copy(max = 10f), wheel)).single()
		assertEquals(900f, narrowed.curve("ParamWheel")!!.keys.last().value, "a repeating axis keeps its turns")
		assertEquals(10f, narrowed.curve("ParamAngleX")!!.keys.last().value)
		assertEquals(30f, MotionClips.withParameterRange(listOf(clip), "ParamAngleX", -30f, 30f).single().curve("ParamAngleX")!!.keys.last().value)
		assertEquals(10f, MotionClips.withParameterRange(listOf(clip), "ParamWheel", 0f, 10f).single().curve("ParamWheel")!!.keys.last().value,
			"a repeat turned off clamps again")
	}

	@Test fun closingALoopMeetsTheStartAndKeepsSelectedCurvesOnly() {
		// Keys inside the clip only: closing adds the start and end keys, holding the first value.
		val clip = MotionClip("m", "M", duration = 2f, loop = true, curves = listOf(
			MotionCurve("ParamAngleX", listOf(MotionKey(0.5f, 10f, MotionInterpolation.LINEAR), MotionKey(1f, -20f))),
			MotionCurve("ParamWheel", listOf(MotionKey(0f, 0f), MotionKey(1f, 170f)))))
		assertEquals(setOf("ParamAngleX", "ParamWheel"), MotionLoops.seams(clip, listOf(angle, wheel)))
		val only = MotionLoops.closeLoop(clip, listOf(angle, wheel), setOf("ParamAngleX"))
		assertEquals(clip.curve("ParamWheel"), only.curve("ParamWheel"))
		val keys = only.curve("ParamAngleX")!!.keys
		assertEquals(listOf(0f to 10f, 0.5f to 10f, 1f to -20f, 2f to 10f), keys.map { it.time to it.value })
		assertEquals(setOf("ParamWheel"), MotionLoops.seams(only, listOf(angle, wheel)))
		// 170 is nearer no turn than one: the wheel winds back to 0.
		assertEquals(0f, MotionLoops.closeLoop(clip, listOf(angle, wheel)).curve("ParamWheel")!!.keys.last().value)
		val closed = MotionLoops.closeLoop(clip, listOf(angle, wheel))
		assertEquals(closed, MotionLoops.closeLoop(closed, listOf(angle, wheel)), "closing a closed loop changes nothing")
	}
}
