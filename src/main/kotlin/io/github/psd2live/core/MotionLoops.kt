package io.github.psd2live.core

import org.umamo.runtime.model.Parameter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What a looping motion and a repeating parameter change about a timeline: where a loop's end has to meet its start,
 * and how far past its range a repeating parameter's keys may go. Both are motion data: they replay, undo and export.
 */
object MotionLoops {
	/** Whole turns of its range a repeating parameter's keys may reach past either end. */
	const val REPEAT_TURNS = 64

	/** Values a key of [parameter] may hold: its range, or [REPEAT_TURNS] turns of it either way when it repeats. */
	fun keyLimits(parameter: Parameter): ClosedFloatingPointRange<Float> = keyLimits(parameter.min, parameter.max, parameter.repeat)

	fun keyLimits(min: Float, max: Float, repeat: Boolean): ClosedFloatingPointRange<Float> {
		val span = max - min
		if (!repeat || span <= 0f) return min..max
		return (min - span * REPEAT_TURNS)..(max + span * REPEAT_TURNS)
	}

	fun keyLimits(parameters: List<Parameter>): Map<String, ClosedFloatingPointRange<Float>> =
		parameters.associate { it.id.raw to keyLimits(it) }

	/**
	 * [value] as a repeating axis plays it: unchanged within [min]..[max], else wrapped into [min, max), a value past
	 * the maximum re-entering at the minimum (Cubism's parameter repeat).
	 */
	fun wrap(value: Float, min: Float, max: Float): Float {
		if (value in min..max) return value
		val span = max - min
		if (span <= 0f) return min
		val turned = (value - min) % span
		return min + if (turned < 0f) turned + span else turned
	}

	/**
	 * The curves of [clip] whose end does not meet their start, so the loop jumps there. A repeating parameter meets
	 * its start a whole number of turns away as well.
	 */
	fun seams(clip: MotionClip, parameters: List<Parameter>, tolerance: Float = 1e-3f): Set<String> {
		val byId = parameters.associateBy { it.id.raw }
		return clip.curves.filter { curve ->
			val parameter = byId[curve.parameterId]
			val start = MotionClips.sample(curve, 0f)
			val end = MotionClips.sample(curve, clip.duration)
			val span = parameter?.let { it.max - it.min } ?: 0f
			val gap = if (parameter?.repeat == true && span > 0f) (end - start) - ((end - start) / span).roundToInt() * span else end - start
			abs(gap) > tolerance * (if (span > 0f) span else 1f)
		}.mapTo(LinkedHashSet()) { it.parameterId }
	}

	/**
	 * [clip] with each curve of [parameterIds] (every curve when null) ending where it starts: a key at the start and
	 * one at the end holding the start's value - a repeating parameter's the nearest whole turns on, so a spin keeps
	 * spinning - and, where both segments at the seam ease, the end arriving at the slope the start leaves with.
	 */
	fun closeLoop(clip: MotionClip, parameters: List<Parameter>, parameterIds: Set<String>? = null): MotionClip {
		val byId = parameters.associateBy { it.id.raw }
		return clip.copy(curves = clip.curves.map { curve ->
			if (parameterIds != null && curve.parameterId !in parameterIds) curve
			else MotionCurve(curve.parameterId, closed(curve, clip.duration, byId[curve.parameterId]))
		})
	}

	private fun closed(curve: MotionCurve, duration: Float, parameter: Parameter?): List<MotionKey> {
		val eps = MotionClips.TIME_EPSILON
		val start = MotionClips.sample(curve, 0f)
		val end = MotionClips.sample(curve, duration)
		val span = parameter?.let { it.max - it.min } ?: 0f
		val turns = if (parameter?.repeat == true && span > 0f) ((end - start) / span).roundToInt() else 0
		val limits = parameter?.let(::keyLimits)
		val target = (start + turns * span).let { if (limits != null) it.coerceIn(limits) else it }
		var keys = curve.keys
		if (keys.none { abs(it.time) < eps }) keys = MotionClips.normalized(keys + MotionKey(0f, start))
		val last = keys.lastOrNull { abs(it.time - duration) < eps }
		keys = MotionClips.normalized(keys + (last?.copy(value = target) ?: MotionKey(duration, target)))
		if (keys.size < 2) return keys
		val first = keys.first()
		val second = keys[1]
		val beforeLast = keys[keys.size - 2]
		if (first.interpolation != MotionInterpolation.BEZIER || beforeLast.interpolation != MotionInterpolation.BEZIER) return keys
		val out = first.outHandle.clamped()
		val leaving = second.time - first.time
		val slope = if (out.x * leaving > eps) out.y / (out.x * leaving) else 0f
		val arriving = duration - beforeLast.time
		val end0 = keys.last()
		val x = end0.inHandle.clamped().x.takeIf { it > 0f } ?: (1f / 3f)
		// + 0f: a level start gives a level end, never a negative zero that reads as an edited handle.
		return keys.dropLast(1) + end0.copy(inHandle = MotionHandle(x, -slope * x * arriving + 0f))
	}
}
