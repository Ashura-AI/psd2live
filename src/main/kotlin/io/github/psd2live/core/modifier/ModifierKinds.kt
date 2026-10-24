package io.github.psd2live.core.modifier

import kotlin.math.cos
import kotlin.math.sin

/** One adjustable value of a modifier kind: [size] components, [default] until set, within [min]..[max] when bounded. */
class ModifierSetting(
	val key: String,
	val size: Int,
	val default: FloatArray,
	val min: Float = Float.NEGATIVE_INFINITY,
	val max: Float = Float.POSITIVE_INFINITY,
	/** What the value means, for the MCP schema. */
	val description: String,
) {
	init { require(default.size == size) }
}

/**
 * A kind of modifier: its settings and the deformation itself, a pure function of canvas positions (y down) and the
 * settings' values at one pose. It is evaluated exactly while the user drags and sampled into keyforms on commit.
 */
interface ModifierKind {
	val id: String
	val settings: List<ModifierSetting>

	/** [positions] (interleaved canvas x, y) deformed with [values], one per setting key. A new array. */
	fun deform(positions: FloatArray, values: Map<String, FloatArray>): FloatArray

	/** Values for a new modifier on meshes whose rest canvas bounds are [bounds] (min x, min y, max x, max y). */
	fun defaults(bounds: FloatArray): Map<String, FloatArray> = emptyMap()
}

/** The registry of modifier kinds. */
object ModifierKinds {
	private val kinds: Map<String, ModifierKind> = listOf<ModifierKind>(Transform).associateBy { it.id }

	fun of(id: String): ModifierKind? = kinds[id]

	val all: Collection<ModifierKind> get() = kinds.values

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

		override fun defaults(bounds: FloatArray) = mapOf("pivot" to floatArrayOf((bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2))

		override fun deform(positions: FloatArray, values: Map<String, FloatArray>): FloatArray {
			val offset = values.getValue("offset")
			val angle = Math.toRadians(values.getValue("rotation")[0].toDouble())
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
}
