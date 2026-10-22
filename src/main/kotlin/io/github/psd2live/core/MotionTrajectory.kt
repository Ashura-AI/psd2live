package io.github.psd2live.core

import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*

/**
 * Where an object goes through a motion, frame by frame (Cubism's locus of tracks): the centre of the chosen meshes,
 * or a deformer's pivot (rotation) or middle (warp), in world coordinates. The motion is played over [base] the way the
 * editor plays it - frozen tracks held, repeating parameters wrapped - without physics or tracking.
 */
internal object MotionTrajectory {
	class Track(val times: FloatArray, val points: FloatArray) {
		val size: Int get() = times.size

		/** The sample nearest [time]. */
		fun indexAt(time: Float): Int = times.indices.minByOrNull { kotlin.math.abs(times[it] - time) } ?: 0
	}

	const val MAX_SAMPLES = 241

	fun sample(
		puppet: PuppetModel,
		clip: MotionClip,
		drawableIds: Set<String>,
		deformerId: String?,
		base: Map<ParameterId, Float>,
		frozen: Map<ParameterId, Float> = emptyMap(),
		cancelled: () -> Boolean = { false },
	): Track? {
		val probes = puppet.drawables.filter { it.id.raw in drawableIds }.toMutableList()
		puppet.deformers.firstOrNull { it.id.raw == deformerId }?.let { deformer ->
			// A probe point under the deformer: its pivot is the local origin, a warp's middle is (0.5, 0.5).
			val point = if (deformer is Deformer.Warp) floatArrayOf(0.5f, 0.5f) else floatArrayOf(0f, 0f)
			probes += Drawable(DrawableId("__trajectory_${deformer.id.raw}"), deformer.name, deformer.id, BlendMode.Normal, emptyList(),
				DrawableMesh(point, FloatArray(point.size), intArrayOf()), null)
		}
		if (probes.isEmpty()) return null
		val model = puppet.copy(drawables = probes, glues = emptyList()).withDerivedRenderRoot()
		val evaluator = CpuDeformationEvaluator()
		val count = (kotlin.math.ceil(clip.duration * clip.fps).toInt() + 1).coerceIn(2, MAX_SAMPLES)
		val times = FloatArray(count) { clip.duration * it / (count - 1) }
		val points = FloatArray(count * 2)
		for (i in 0 until count) {
			if (cancelled()) return null
			val pose = boundedPreviewPose(base + MotionClips.sampleAll(clip, times[i].toDouble(), loop = false) + frozen, puppet.parameters)
			val world = evaluator.evaluate(model, pose).worldPositions
			var x = 0.0
			var y = 0.0
			var n = 0
			for (probe in probes) {
				val positions = world[probe.id] ?: continue
				for (v in 0 until positions.size / 2) { x += positions[v * 2]; y += positions[v * 2 + 1]; n++ }
			}
			// A hidden object at this frame keeps the last place it was seen.
			if (n == 0) {
				if (i > 0) { points[i * 2] = points[i * 2 - 2]; points[i * 2 + 1] = points[i * 2 - 1] }
				continue
			}
			points[i * 2] = (x / n).toFloat()
			points[i * 2 + 1] = (y / n).toFloat()
		}
		return Track(times, points)
	}
}
