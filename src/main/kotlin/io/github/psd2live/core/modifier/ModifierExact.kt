package io.github.psd2live.core.modifier

import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.KeyAxis
import io.github.psd2live.format.model.ModifierIR
import io.github.psd2live.format.model.ModifierStackIR
import io.github.psd2live.format.model.ModifierValueIR
import org.umamo.runtime.model.PuppetModel
import kotlin.math.abs
import kotlin.math.max

/**
 * The modifier stacks as the runtime's advanced mode evaluates them exactly (`MODS` in `docs/zh/spec/P2LRT_V2.md`),
 * and the reference evaluation the runtime is checked against.
 *
 * The rig carries each stack sampled into keyforms ([ModifierSampler]); the runtime adds the stack's departure from
 * its own interpolation over the sampled keys, mapped into the mesh's parent space by the inverse Jacobian of the
 * default pose's parent mapping at the rest shape. At every sampled key that departure is zero. The stack starts from
 * the mesh as its own keyforms pose it, on the canvas: kept at each combination of the sampled keys of its own axes
 * and interpolated between them.
 */
object ModifierExact {
	/** Within this of a sampled key a value takes the key, as the runtime's keyform grids snap. */
	private const val KEY_SNAP = 0.001f

	/**
	 * Every mesh's stack of [modifiers] on [before] (the rig the modifiers act on, without them), as the sampler
	 * sampled it: the stacks it leaves out (an issue, too many keyforms) are left out here too.
	 */
	fun stacks(before: PuppetModel, modifiers: List<RigModifier>): List<ModifierStackIR> {
		if (modifiers.isEmpty()) return emptyList()
		val model = ModifierSampler.withCreated(before, modifiers)
		return ModifierSampler.stacks(modifiers, model).mapNotNull { (mesh, modifiers) ->
			val (stack, mapping) = ModifierSampler.stack(model, mesh, modifiers) ?: return@mapNotNull null
			val (axes, _) = ModifierSampler.offsets(model, mesh, modifiers) ?: return@mapNotNull null
			val drawable = model.drawables.first { it.id.raw == mesh }
			val local = ModifierSampler.local(model, drawable)
			fun canvas(l: FloatArray) = mapping.localToWorld(l).also { w -> for (i in 1 until w.size step 2) w[i] = -w[i] }
			// The start at each combination of the mesh's own axes' sampled keys, as the sampler poses it there.
			val own = drawable.geometryGrid?.axes.orEmpty().map { it.parameterId.raw }.toSet()
			val startAxes = axes.filter { it.parameter in own }
			val starts = (0 until startAxes.fold(1) { n, a -> n * a.keys.size }).map { index ->
				var rest = index
				val pose = startAxes.associate { a -> org.umamo.runtime.model.ParameterId(a.parameter) to a.keys[rest % a.keys.size].also { rest /= a.keys.size } }
				canvas(if (startAxes.isEmpty()) local else org.umamo.render.eval.drawableLocalPosed(model, pose, drawable.id) ?: local)
			}
			ModifierStackIR(
				mesh,
				axes.map { KeyAxis(it.parameter, Floats.of(it.keys)) },
				ModifierValueIR(startAxes.map { KeyAxis(it.parameter, Floats.of(it.keys)) }, Floats.wrap(FloatArray(starts.size * local.size) { starts[it / local.size][it % local.size] })),
				Floats.wrap(toParent(local, ::canvas)),
				stack.modifiers.map { m ->
					ModifierIR(
						m.kind,
						m.definition.settings.filter { it.key in EXACT_SETTINGS }.associate { it.key to value(m.value(it.key)) },
						stack.weights[m.id]?.let(Floats::of) ?: Floats.Empty,
						if (m.kind == ModifierKinds.Wave.id) m.value("phaseSpeed").at { p -> model.parameters.first { it.id.raw == p }.default }[0] else 0f,
					)
				},
			)
		}
	}

	/** The settings the runtime evaluates: all but the wave's speed, which it reads on its own. */
	private val EXACT_SETTINGS: Set<String> = ModifierKinds.all.flatMap { k -> k.settings.map { it.key } }.toSet() - "phaseSpeed"

	private fun value(v: ModifierValue): ModifierValueIR = when (v) {
		is ModifierValue.Constant -> ModifierValueIR(emptyList(), Floats.of(v.value))
		is ModifierValue.Keyed -> ModifierValueIR(
			v.axes.map { KeyAxis(it.parameter, Floats.of(it.keys)) },
			Floats.wrap(FloatArray(v.values.size * v.size) { v.values[it / v.size][it % v.size] }),
		)
	}

	/**
	 * Per vertex of [local] (interleaved, the mesh's parent space) a, b, c, d mapping a canvas displacement back into
	 * that space: the inverse of [toCanvas]'s Jacobian there, by central differences, which are exact for an affine or
	 * bilinear map. A vertex where the map cannot be inverted gets zeros: nothing is corrected there.
	 */
	fun toParent(local: FloatArray, toCanvas: (FloatArray) -> FloatArray): FloatArray {
		val n = local.size / 2
		var extent = 0f
		for (axis in 0..1) {
			var lo = Float.POSITIVE_INFINITY; var hi = Float.NEGATIVE_INFINITY
			for (v in 0 until n) { lo = minOf(lo, local[v * 2 + axis]); hi = max(hi, local[v * 2 + axis]) }
			if (n > 0) extent = max(extent, hi - lo)
		}
		val h = max(extent, 1e-3f) * 0.01f
		fun moved(axis: Int, by: Float) = toCanvas(FloatArray(local.size) { if (it % 2 == axis) local[it] + by else local[it] })
		val (xPlus, xMinus, yPlus, yMinus) = listOf(moved(0, h), moved(0, -h), moved(1, h), moved(1, -h))
		return FloatArray(n * 4).also { out ->
			for (v in 0 until n) {
				val m00 = (xPlus[v * 2] - xMinus[v * 2]) / (2 * h).toDouble(); val m10 = (xPlus[v * 2 + 1] - xMinus[v * 2 + 1]) / (2 * h).toDouble()
				val m01 = (yPlus[v * 2] - yMinus[v * 2]) / (2 * h).toDouble(); val m11 = (yPlus[v * 2 + 1] - yMinus[v * 2 + 1]) / (2 * h).toDouble()
				val det = m00 * m11 - m01 * m10
				if (abs(det) < 1e-12 || !det.isFinite()) continue
				out[v * 4] = (m11 / det).toFloat(); out[v * 4 + 1] = (-m01 / det).toFloat()
				out[v * 4 + 2] = (-m10 / det).toFloat(); out[v * 4 + 3] = (m00 / det).toFloat()
			}
		}
	}

	/** [value] at [pose] (parameter id to value): multilinear between keys, held past the ends, as [ModifierValue.Keyed.at]. */
	private fun at(value: ModifierValueIR, pose: (String) -> Float): FloatArray {
		if (value.axes.isEmpty()) return value.values.toArray()
		val cells = value.axes.fold(1) { n, a -> n * a.keys.size }
		val size = value.values.size / cells
		val values = List(cells) { c -> FloatArray(size) { value.values[c * size + it] } }
		return ModifierValue.Keyed(value.axes.map { ModifierValue.Keyed.Axis(it.parameter, it.keys.toArray()) }, values).at(pose)
	}

	/** [stack]'s canvas positions at [pose]: [ModifierSampler.Stack.canvasFrom] its start there, from the stack's own data. */
	fun canvasAt(stack: ModifierStackIR, pose: (String) -> Float): FloatArray {
		var positions = at(stack.start, pose)
		for (m in stack.modifiers) {
			val kind = requireNotNull(ModifierKinds.of(m.kind)) { "Unknown modifier kind ${m.kind}" }
			val values = m.settings.mapValues { at(it.value, pose) }
			val out = kind.deform(positions, values)
			if (!m.weights.isEmpty()) for (i in out.indices) out[i] = positions[i] + (out[i] - positions[i]) * m.weights[i / 2]
			positions = out
		}
		return positions
	}

	/**
	 * What the runtime's advanced mode adds to [stack]'s mesh at [pose], in the mesh's parent space before its
	 * parent's transform, at time zero: the stack now less its interpolation over the sampled keys around now.
	 */
	fun correction(stack: ModifierStackIR, pose: (String) -> Float): FloatArray {
		// Values within the snap of a key take it, and each axis brackets its value as a keyform grid does.
		val snapped = HashMap<String, Float>()
		class Span(val lo: Int, val hi: Int, val t: Float)
		val spans = stack.axes.map { axis ->
			val keys = axis.keys.toArray()
			val raw = pose(axis.parameter)
			val v = keys.firstOrNull { abs(raw - it) < KEY_SNAP } ?: raw
			snapped[axis.parameter] = v
			val last = keys.size - 1
			when {
				last == 0 || v <= keys[0] -> Span(0, 0, 0f)
				v >= keys[last] -> Span(last, last, 0f)
				else -> {
					var j = 0
					while (j + 1 < last && keys[j + 1] <= v) j++
					val width = keys[j + 1] - keys[j]
					Span(j, j + 1, if (width > 0f) (v - keys[j]) / width else 0f)
				}
			}
		}
		val now = canvasAt(stack) { snapped[it] ?: pose(it) }
		val interpolated = FloatArray(now.size)
		for (corner in 0 until (1 shl spans.size)) {
			var weight = 1f
			val at = HashMap(snapped)
			for ((i, s) in spans.withIndex()) {
				val upper = (corner shr i) and 1 == 1
				if (upper && s.hi == s.lo) { weight = 0f; break }
				weight *= if (upper) s.t else 1 - s.t
				at[stack.axes[i].parameter] = stack.axes[i].keys[if (upper) s.hi else s.lo]
			}
			if (weight <= 0f) continue
			val shape = canvasAt(stack) { at[it] ?: pose(it) }
			for (i in interpolated.indices) interpolated[i] += shape[i] * weight
		}
		val t = stack.toParent
		return FloatArray(now.size).also { out ->
			for (v in 0 until now.size / 2) {
				val dx = now[v * 2] - interpolated[v * 2]; val dy = now[v * 2 + 1] - interpolated[v * 2 + 1]
				out[v * 2] = t[v * 4] * dx + t[v * 4 + 1] * dy
				out[v * 2 + 1] = t[v * 4 + 2] * dx + t[v * 4 + 3] * dy
			}
		}
	}
}
