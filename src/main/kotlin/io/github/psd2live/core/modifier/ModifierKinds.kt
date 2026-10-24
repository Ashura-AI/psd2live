package io.github.psd2live.core.modifier

import io.github.psd2live.core.SemanticTag
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * One adjustable value of a modifier kind: [size] components, [default] until set, within [min]..[max] when bounded.
 * [field] is false for a value edited on the canvas only (a lattice's control points), which the panel leaves out.
 */
class ModifierSetting(
	val key: String,
	val size: Int,
	val default: FloatArray,
	val min: Float = Float.NEGATIVE_INFINITY,
	val max: Float = Float.POSITIVE_INFINITY,
	/** What the value means, for the MCP schema. */
	val description: String,
	val field: Boolean = true,
) {
	init { require(default.size == size) }
}

/**
 * A kind of modifier: its settings and the deformation itself, a pure function of canvas positions (y down) and the
 * settings' values at one pose. It is evaluated exactly while the user drags and sampled into keyforms on commit.
 *
 * Every kind is the identity at its [defaults] apart from where it sits, so a new modifier moves nothing until its
 * amount is set. Its formula is part of the project format: the runtime's exact mode evaluates the same one.
 */
interface ModifierKind {
	val id: String
	val settings: List<ModifierSetting>

	/** [positions] (interleaved canvas x, y) deformed with [values], one per setting key. A new array. */
	fun deform(positions: FloatArray, values: Map<String, FloatArray>): FloatArray

	/** Values for a new modifier on meshes whose rest canvas bounds are [bounds] (min x, min y, max x, max y). */
	fun defaults(bounds: FloatArray): Map<String, FloatArray> = emptyMap()

	/** The layer roles this kind is offered first for, in the add menu. */
	val suits: Set<SemanticTag> get() = emptySet()
}

/** The registry of modifier kinds. */
object ModifierKinds {
	private val kinds: Map<String, ModifierKind> = listOf(Transform, Bend, Twist, Wave, Lattice).associateBy { it.id }

	fun of(id: String): ModifierKind? = kinds[id]

	val all: Collection<ModifierKind> get() = kinds.values

	/** The kinds offered first for a layer of [role], then the rest, each in registry order. */
	fun forRole(role: SemanticTag?): List<ModifierKind> = all.sortedBy { if (role != null && role in it.suits) 0 else 1 }

	private fun radians(degrees: Float) = degrees * PI / 180.0

	/**
	 * Moves, turns and scales about a pivot: `pivot + R·S·(p − pivot) + offset`. Rotation is nonlinear in its angle,
	 * so a keyed turn is sampled between its keys as a curve, not a chord.
	 */
	object Transform : ModifierKind {
		override val id = "transform"
		override val settings = listOf(
			ModifierSetting("offset", 2, floatArrayOf(0f, 0f), description = "Translation [x, y] in canvas pixels, y down."),
			ModifierSetting("rotation", 1, floatArrayOf(0f), -3600f, 3600f, "Rotation in degrees about the pivot, clockwise on the canvas."),
			ModifierSetting("scale", 2, floatArrayOf(1f, 1f), -16f, 16f, "Scale [x, y] about the pivot."),
			ModifierSetting("pivot", 2, floatArrayOf(0f, 0f), description = "Pivot [x, y] in canvas pixels; a new modifier takes its meshes' rest centre."),
		)
		override val suits = setOf(SemanticTag.HEADWEAR, SemanticTag.EYEWEAR, SemanticTag.EARWEAR, SemanticTag.OBJECTS)

		override fun defaults(bounds: FloatArray) = mapOf("pivot" to floatArrayOf((bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2))

		override fun deform(positions: FloatArray, values: Map<String, FloatArray>): FloatArray {
			val offset = values.getValue("offset")
			val angle = radians(values.getValue("rotation")[0])
			val scale = values.getValue("scale")
			val pivot = values.getValue("pivot")
			val c = cos(angle); val s = sin(angle)
			return FloatArray(positions.size).also { out ->
				for (i in 0 until positions.size / 2) {
					val x = (positions[i * 2] - pivot[0]) * scale[0].toDouble()
					val y = (positions[i * 2 + 1] - pivot[1]) * scale[1].toDouble()
					out[i * 2] = (pivot[0] + c * x - s * y + offset[0]).toFloat()
					out[i * 2 + 1] = (pivot[1] + s * x + c * y + offset[1]).toFloat()
				}
			}
		}
	}

	/**
	 * Bends along an axis, as Blender's simple deform: the axis starts at `origin` pointing at `direction` degrees
	 * (0 right, −90 up, on the canvas). Over the first `length` pixels of the axis it curls into an arc turning by
	 * `angle` degrees, clockwise for positive angles; points before the origin stay, points past the arc follow its end
	 * straight on. A point keeps its distance across the axis: in axis coordinates (u along, v across, to the axis's
	 * right), with R = length / θ, φ = θ·clamp(u / length, 0, 1) and r = R − v, it goes to (r·sin φ, R − r·cos φ),
	 * plus (u − length)·(cos θ, sin θ) past the arc.
	 */
	object Bend : ModifierKind {
		override val id = "bend"
		override val settings = listOf(
			ModifierSetting("angle", 1, floatArrayOf(0f), -720f, 720f, "Total turn in degrees over the arc, clockwise for positive."),
			ModifierSetting("origin", 2, floatArrayOf(0f, 0f), description = "Where the axis starts [x, y], canvas pixels; nothing before it moves."),
			ModifierSetting("direction", 1, floatArrayOf(-90f), -360f, 360f, "Axis direction in degrees: 0 right, -90 up, 90 down."),
			ModifierSetting("length", 1, floatArrayOf(100f), 1f, 100_000f, "Length of the arc along the axis, canvas pixels."),
		)
		override val suits = setOf(SemanticTag.TAIL, SemanticTag.BACK_HAIR, SemanticTag.FRONT_HAIR, SemanticTag.TOPWEAR, SemanticTag.WINGS)

		// The axis runs up from the bottom centre over the whole height, as a strand or tail hangs from its root.
		override fun defaults(bounds: FloatArray) = mapOf(
			"origin" to floatArrayOf((bounds[0] + bounds[2]) / 2, bounds[3]),
			"length" to floatArrayOf(maxOf(1f, bounds[3] - bounds[1])),
		)

		override fun deform(positions: FloatArray, values: Map<String, FloatArray>): FloatArray {
			val theta = radians(values.getValue("angle")[0])
			val origin = values.getValue("origin")
			val direction = radians(values.getValue("direction")[0])
			val length = values.getValue("length")[0].toDouble()
			val dx = cos(direction); val dy = sin(direction)
			// The axis's right on a y-down canvas: d turned a quarter clockwise.
			val nx = -dy; val ny = dx
			val out = positions.copyOf()
			if (abs(theta) < 1e-9) return out
			val radius = length / theta
			for (i in 0 until positions.size / 2) {
				val px = positions[i * 2] - origin[0].toDouble(); val py = positions[i * 2 + 1] - origin[1].toDouble()
				val u = px * dx + py * dy
				val v = px * nx + py * ny
				if (u <= 0) continue
				val along = minOf(u, length)
				val phi = theta * along / length
				// The arc's centre sits at distance R on the axis's right; a point at v from the axis keeps R − v to it.
				val r = radius - v
				var a = r * sin(phi)
				var b = radius - r * cos(phi)
				if (u > length) { a += (u - length) * cos(theta); b += (u - length) * sin(theta) }
				out[i * 2] = (origin[0] + a * dx + b * nx).toFloat()
				out[i * 2 + 1] = (origin[1] + a * dy + b * ny).toFloat()
			}
			return out
		}
	}

	/**
	 * Twirls about a centre, as an image twirl: a point at distance r < `radius` turns by `angle`·(1 − r/radius)²
	 * degrees, clockwise for positive; the centre turns by the whole angle and the rim not at all, so the edge stays.
	 */
	object Twist : ModifierKind {
		override val id = "twist"
		override val settings = listOf(
			ModifierSetting("angle", 1, floatArrayOf(0f), -3600f, 3600f, "Turn at the centre in degrees, clockwise for positive; none at the radius."),
			ModifierSetting("center", 2, floatArrayOf(0f, 0f), description = "Centre [x, y], canvas pixels."),
			ModifierSetting("radius", 1, floatArrayOf(100f), 1f, 100_000f, "Radius of the twirl, canvas pixels; nothing outside it moves."),
		)
		override val suits = setOf(SemanticTag.IRIDES, SemanticTag.TOPWEAR, SemanticTag.BOTTOMWEAR)

		override fun defaults(bounds: FloatArray) = mapOf(
			"center" to floatArrayOf((bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2),
			"radius" to floatArrayOf(maxOf(1f, hypot(bounds[2] - bounds[0], bounds[3] - bounds[1]) / 2)),
		)

		override fun deform(positions: FloatArray, values: Map<String, FloatArray>): FloatArray {
			val angle = radians(values.getValue("angle")[0])
			val center = values.getValue("center")
			val radius = values.getValue("radius")[0].toDouble()
			val out = positions.copyOf()
			for (i in 0 until positions.size / 2) {
				val x = positions[i * 2] - center[0].toDouble(); val y = positions[i * 2 + 1] - center[1].toDouble()
				val r = hypot(x, y)
				if (r >= radius) continue
				val k = 1 - r / radius
				val phi = angle * k * k
				val c = cos(phi); val s = sin(phi)
				out[i * 2] = (center[0] + c * x - s * y).toFloat()
				out[i * 2 + 1] = (center[1] + s * x + c * y).toFloat()
			}
			return out
		}
	}

	/**
	 * Waves across an axis from `origin` along `direction`: a point at distance u along it moves across it (to the
	 * axis's right) by `amplitude`·sin(2π·u / `wavelength` + `phase`) · clamp(u / `ramp`, 0, 1). The ramp holds the
	 * root still, as a strand waves from where it grows; nothing before the origin moves. Keying `phase` on a
	 * parameter that a loop or a pendulum drives makes the wave travel.
	 */
	object Wave : ModifierKind {
		override val id = "wave"
		override val settings = listOf(
			ModifierSetting("amplitude", 1, floatArrayOf(0f), -10_000f, 10_000f, "Displacement across the axis at the crest, canvas pixels."),
			ModifierSetting("wavelength", 1, floatArrayOf(100f), 1f, 100_000f, "Distance between crests along the axis, canvas pixels."),
			ModifierSetting("phase", 1, floatArrayOf(0f), -36_000f, 36_000f, "Phase in degrees; 360 moves the wave one wavelength back."),
			ModifierSetting("origin", 2, floatArrayOf(0f, 0f), description = "Where the axis starts [x, y], canvas pixels; nothing before it moves."),
			ModifierSetting("direction", 1, floatArrayOf(90f), -360f, 360f, "Axis direction in degrees: 0 right, -90 up, 90 down."),
			ModifierSetting("ramp", 1, floatArrayOf(100f), 0f, 100_000f, "Distance from the origin over which the wave grows to full, canvas pixels; 0 for none."),
		)
		override val suits = setOf(SemanticTag.BACK_HAIR, SemanticTag.FRONT_HAIR, SemanticTag.TAIL, SemanticTag.BOTTOMWEAR, SemanticTag.WINGS)

		// The axis hangs down from the top centre, one wave over the height, growing over the first half.
		override fun defaults(bounds: FloatArray): Map<String, FloatArray> {
			val height = maxOf(1f, bounds[3] - bounds[1])
			return mapOf(
				"origin" to floatArrayOf((bounds[0] + bounds[2]) / 2, bounds[1]),
				"wavelength" to floatArrayOf(height),
				"ramp" to floatArrayOf(height / 2),
			)
		}

		override fun deform(positions: FloatArray, values: Map<String, FloatArray>): FloatArray {
			val amplitude = values.getValue("amplitude")[0].toDouble()
			val wavelength = values.getValue("wavelength")[0].toDouble()
			val phase = radians(values.getValue("phase")[0])
			val origin = values.getValue("origin")
			val direction = radians(values.getValue("direction")[0])
			val ramp = values.getValue("ramp")[0].toDouble()
			val dx = cos(direction); val dy = sin(direction)
			val nx = -dy; val ny = dx
			val out = positions.copyOf()
			if (amplitude == 0.0) return out
			for (i in 0 until positions.size / 2) {
				val u = (positions[i * 2] - origin[0]) * dx + (positions[i * 2 + 1] - origin[1]) * dy
				if (u <= 0) continue
				val grow = if (ramp <= 0) 1.0 else minOf(1.0, u / ramp)
				val w = amplitude * sin(2 * PI * u / wavelength + phase) * grow
				out[i * 2] = (positions[i * 2] + w * nx).toFloat()
				out[i * 2 + 1] = (positions[i * 2 + 1] + w * ny).toFloat()
			}
			return out
		}
	}

	/**
	 * A free-form cage over `bounds`, as Blender's lattice: 4 × 4 control points on a regular grid at rest, each moved
	 * by its `offsets` pair (row by row from the top left), carry the art with them as a bicubic Bézier patch, so the
	 * art bends smoothly through the inner points as well. A point outside the cage follows its nearest edge.
	 */
	object Lattice : ModifierKind {
		const val SIZE = 4
		override val id = "lattice"
		override val settings = listOf(
			ModifierSetting("bounds", 4, floatArrayOf(0f, 0f, 100f, 100f), description = "The cage at rest [min x, min y, max x, max y], canvas pixels."),
			ModifierSetting("offsets", SIZE * SIZE * 2, FloatArray(SIZE * SIZE * 2), -100_000f, 100_000f,
				"How far each of the 4 x 4 control points moves [x, y], row by row from the top left, canvas pixels.", field = false),
		)
		override val suits = setOf(SemanticTag.FACE, SemanticTag.TOPWEAR, SemanticTag.BOTTOMWEAR, SemanticTag.HEADWEAR, SemanticTag.OBJECTS)

		override fun defaults(bounds: FloatArray) = mapOf("bounds" to bounds.copyOf())

		/** The Bernstein weights of the cubic at [t]. */
		private fun basis(t: Double) = doubleArrayOf((1 - t) * (1 - t) * (1 - t), 3 * t * (1 - t) * (1 - t), 3 * t * t * (1 - t), t * t * t)

		override fun deform(positions: FloatArray, values: Map<String, FloatArray>): FloatArray {
			val bounds = values.getValue("bounds")
			val offsets = values.getValue("offsets")
			val out = positions.copyOf()
			if (offsets.all { it == 0f }) return out
			val w = (bounds[2] - bounds[0]).toDouble(); val h = (bounds[3] - bounds[1]).toDouble()
			if (w <= 0 || h <= 0) return out
			for (i in 0 until positions.size / 2) {
				// The patch's offset is a Bézier blend of the control points' offsets; at rest the grid maps to itself.
				val bu = basis(((positions[i * 2] - bounds[0]) / w).coerceIn(0.0, 1.0))
				val bv = basis(((positions[i * 2 + 1] - bounds[1]) / h).coerceIn(0.0, 1.0))
				var x = 0.0; var y = 0.0
				for (row in 0 until SIZE) for (column in 0 until SIZE) {
					val k = bv[row] * bu[column]
					x += k * offsets[(row * SIZE + column) * 2]; y += k * offsets[(row * SIZE + column) * 2 + 1]
				}
				out[i * 2] = (positions[i * 2] + x).toFloat()
				out[i * 2 + 1] = (positions[i * 2 + 1] + y).toFloat()
			}
			return out
		}
	}
}
