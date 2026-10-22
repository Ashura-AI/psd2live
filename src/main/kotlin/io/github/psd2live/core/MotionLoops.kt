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
	 * [value] as a repeating axis plays it, as Cubism's parameter repeat does: unchanged within [min]..[max], a value
	 * past the maximum re-entering from the minimum and one below the minimum from the maximum - so a whole number of
	 * turns past the maximum lands on the minimum, and below the minimum on the maximum.
	 */
	fun wrap(value: Float, min: Float, max: Float): Float {
		if (value in min..max) return value
		val span = max - min
		if (span <= 0f) return min
		return if (value > max) min + (value - max) % span else max - (min - value) % span
	}

	/**
	 * A value the preview shows for [parameter] - wrapped when it repeats - as a key over a curve whose own value is
	 * [raw]: the whole turns on that keep it nearest [raw], so keying the shown pose over a spin at 540 keeps 540
	 * rather than 180, and a turn keyed a little past the end goes on rather than back round.
	 */
	fun unwrapped(value: Float, raw: Float?, parameter: Parameter?): Float {
		if (raw == null || parameter == null || !parameter.repeat) return value
		val span = parameter.max - parameter.min
		if (span <= 0f) return value
		return (value + ((raw - value) / span).roundToInt() * span).coerceIn(keyLimits(parameter))
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
	 * one at the end holding the start's value - a repeating parameter's whole turns on in the way it is going, so a
	 * spin keeps spinning - and, where the segment into the end eases, the end arriving at the slope the start leaves
	 * with. Fails rather than clips a spin past [REPEAT_TURNS] turns.
	 */
	fun closeLoop(clip: MotionClip, parameters: List<Parameter>, parameterIds: Set<String>? = null): MotionClip {
		val byId = parameters.associateBy { it.id.raw }
		return clip.copy(curves = clip.curves.map { curve ->
			if (parameterIds != null && curve.parameterId !in parameterIds) curve
			else MotionCurve(curve.parameterId, closed(curve, clip.duration, byId[curve.parameterId]))
		})
	}

	/** Whole turns from [start] to [end] in the way they go: 0 to 170 of 360 is one turn on, 0 to 540 two. */
	private fun turns(start: Float, end: Float, span: Float): Int {
		val turned = (end - start) / span
		val nearest = turned.roundToInt()
		return when {
			abs(turned - nearest) < 1e-4f -> nearest
			turned > 0f -> kotlin.math.ceil(turned).toInt()
			else -> kotlin.math.floor(turned).toInt()
		}
	}

	private fun closed(curve: MotionCurve, duration: Float, parameter: Parameter?): List<MotionKey> {
		val eps = MotionClips.TIME_EPSILON
		val start = MotionClips.sample(curve, 0f)
		val end = MotionClips.sample(curve, duration)
		val span = parameter?.let { it.max - it.min } ?: 0f
		val target = if (parameter?.repeat == true && span > 0f) start + turns(start, end, span) * span else start
		parameter?.let(::keyLimits)?.let { limits ->
			require(target in limits) { "Closing ${curve.parameterId} would take it past $REPEAT_TURNS turns of its range" }
		}
		var keys = curve.keys
		if (keys.none { abs(it.time) < eps }) keys = MotionClips.normalized(keys + MotionKey(0f, start))
		val last = keys.lastOrNull { abs(it.time - duration) < eps }
		keys = MotionClips.normalized(keys + (last?.copy(value = target) ?: MotionKey(duration, target)))
		if (keys.size < 2) return keys
		val first = keys.first()
		val second = keys[1]
		val beforeLast = keys[keys.size - 2]
		// Only an easing segment into the end has a handle to meet the start's slope with.
		if (beforeLast.interpolation != MotionInterpolation.BEZIER) return keys
		val leaving = second.time - first.time
		val slope = when (first.interpolation) {
			MotionInterpolation.BEZIER -> first.outHandle.clamped().let { out -> if (out.x * leaving > eps) out.y / (out.x * leaving) else 0f }
			MotionInterpolation.LINEAR -> if (leaving > eps) (second.value - first.value) / leaving else 0f
			// A held or stepped start leaves level.
			else -> 0f
		}
		val arriving = duration - beforeLast.time
		val end0 = keys.last()
		val x = end0.inHandle.clamped().x.takeIf { it > 0f } ?: (1f / 3f)
		// + 0f: a level start gives a level end, never a negative zero that reads as an edited handle.
		return keys.dropLast(1) + end0.copy(inHandle = MotionHandle(x, -slope * x * arriving + 0f))
	}
}
