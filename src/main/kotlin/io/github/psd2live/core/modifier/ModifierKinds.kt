package io.github.psd2live.core.modifier

import io.github.psd2live.core.SemanticTag
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.PuppetModel

/**
 * One adjustable value of a modifier kind: [size] components, [default] until set, within [min]..[max] when bounded.
 * [field] is false for a value edited on the canvas only, which the panel leaves out.
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
 * A choice a kind takes as text rather than numbers: a parameter ([Type.PARAMETER]), another mesh or warp
 * ([Type.TARGET]) or one of [choices]. A [required] option has no default; the others are left out when unset.
 */
class ModifierOption(val key: String, val type: Type, val description: String, val required: Boolean = false, val choices: List<String> = emptyList()) {
	enum class Type { PARAMETER, TARGET, CHOICE }
}

/** Where a kind sits in the add menu; a new kind joins one of these. */
enum class ModifierCategory(val id: String) { SYMMETRY("symmetry"), CORRECTION("correction") }

/**
 * What a kind reads while it is evaluated at one [pose] (every parameter given) on [target] (a mesh's ID or
 * `warp:<id>`): the shape the entries before it on the same target make at any pose, the target's triangles to read
 * a field between its points, and other targets' shapes as the model poses them. Shapes are canvas positions
 * (interleaved x, y, y down), each target taken through its parents at the default pose, as the sampler takes them.
 */
class ModifierContext internal constructor(
	val model: PuppetModel,
	val target: String,
	val pose: Map<String, Float>,
	private val beforeAt: (Map<String, Float>) -> FloatArray,
	private val shapes: (String, Map<String, Float>) -> FloatArray?,
) {
	private val defaults: Map<String, Float> by lazy { model.parameters.associate { it.id.raw to it.default } }

	/** [pose] over the parameters' defaults. */
	fun complete(pose: Map<String, Float>): Map<String, Float> = if (pose.keys.containsAll(defaults.keys)) pose else defaults + pose

	/** The shape the entries before this one make at [pose]. */
	fun before(pose: Map<String, Float> = this.pose): FloatArray = beforeAt(complete(pose))

	/** The target's triangles as index triples over its points. */
	val triangles: IntArray by lazy { ModifierSampler.triangles(model, target) ?: IntArray(0) }

	/** [key]'s shape at [pose] as the model poses it, without any stack; null when it is not a mesh or warp of the model. */
	fun shapeOf(key: String, pose: Map<String, Float> = this.pose): FloatArray? = shapes(key, complete(pose))

	fun trianglesOf(key: String): IntArray? = ModifierSampler.triangles(model, key)

	fun parameter(id: String): Parameter? = model.parameters.firstOrNull { it.id.raw == id }
}

/**
 * A kind of modifier: its settings and options, and what it makes of its target at one pose ([deform]). It is
 * evaluated exactly while the user edits and sampled into keyforms on commit; an export carries only the keyforms.
 * Every kind leaves the target as the entries before it made it at its [defaults], so a new modifier moves nothing
 * until it is set up.
 */
interface ModifierKind {
	val id: String
	val category: ModifierCategory
	val settings: List<ModifierSetting>
	val options: List<ModifierOption> get() = emptyList()

	/** The target's shape at [context]'s pose, from what the entries before it make; [values] are the settings there. A new array. */
	fun deform(context: ModifierContext, values: Map<String, FloatArray>, options: Map<String, String>): FloatArray

	/** Values for a new modifier on targets whose rest canvas bounds are [bounds] (min x, min y, max x, max y). */
	fun defaults(bounds: FloatArray): Map<String, FloatArray> = emptyMap()

	/** The parameters it is sampled on besides those its settings are keyed on. */
	fun drivers(options: Map<String, String>): List<String> = emptyList()

	/** Keys to sample [parameter] at besides its own and the target's: where its result changes over [own], the target's keys on it. */
	fun keys(options: Map<String, String>, parameter: Parameter, own: FloatArray): List<Float> = emptyList()

	/** The layer roles this kind is offered first for, in the add menu. */
	val suits: Set<SemanticTag> get() = emptySet()

	/** Values for options a new modifier is not given: [parameters] are the document's, [keyed] those its targets are keyed on. */
	fun defaultOptions(parameters: List<Parameter>, keyed: Set<String>): Map<String, String> = emptyMap()
}

/** The registry of modifier kinds. */
object ModifierKinds {
	private val builtIn: List<ModifierKind> = listOf(Mirror)
	private val registered = java.util.concurrent.ConcurrentHashMap<String, ModifierKind>()

	fun of(id: String): ModifierKind? = builtIn.firstOrNull { it.id == id } ?: registered[id]

	val all: List<ModifierKind> get() = builtIn + registered.values.sortedBy { it.id }

	/** Adds a kind outside the built-in ones; tests use it for kinds of their own. */
	internal fun register(kind: ModifierKind) { require(builtIn.none { it.id == kind.id }); registered[kind.id] = kind }

	/** The kinds offered first for a layer of [role], then the rest, each in registry order. */
	fun forRole(role: SemanticTag?): List<ModifierKind> = all.sortedBy { if (role != null && role in it.suits) 0 else 1 }

	/**
	 * Mirrors one side of a parameter onto the other: only the source side is keyed, and the far side is the mirror
	 * image of the near one about a vertical line at `axis`. At a pose on the mirrored side the target is its shape
	 * with the parameter at its default, plus the source side's displacement there - read at the mirror point of each
	 * vertex and flipped across the line - so it holds for asymmetric meshes too: the displacement field is mirrored,
	 * not the vertices. The sides meet at the default; a parameter's two halves of unequal length map in proportion.
	 *
	 * With `source` the displacement comes from another mesh or warp instead (a left eye for the right): the target
	 * takes the source's displacement at the same value of `source_parameter` (the same parameter by default), mirrored,
	 * over the whole range.
	 */
	object Mirror : ModifierKind {
		override val id = "mirror"
		override val category = ModifierCategory.SYMMETRY
		override val settings = listOf(
			ModifierSetting("axis", 1, floatArrayOf(0f), description = "Canvas x of the vertical mirror line; a new modifier takes its targets' rest centre."),
		)
		override val options = listOf(
			ModifierOption("parameter", ModifierOption.Type.PARAMETER, "The parameter whose source side is mirrored onto its other side.", required = true),
			ModifierOption("side", ModifierOption.Type.CHOICE, "The side that is kept and mirrored: positive (above the default, the default) or negative.",
				choices = listOf("positive", "negative")),
			ModifierOption("source", ModifierOption.Type.TARGET, "Another mesh or warp:<id> whose displacement is mirrored onto the target, over the whole range."),
			ModifierOption("source_parameter", ModifierOption.Type.PARAMETER, "With source: the source's parameter read at the target parameter's value (default: the same)."),
		)
		override val suits = setOf(SemanticTag.FACE, SemanticTag.EYEWHITE, SemanticTag.IRIDES, SemanticTag.EYELASH, SemanticTag.EYEBROW, SemanticTag.FRONT_HAIR)

		override fun defaults(bounds: FloatArray) = mapOf("axis" to floatArrayOf((bounds[0] + bounds[2]) / 2))

		/** The first two-sided parameter the targets are keyed on, Angle X before any other; else any. */
		override fun defaultOptions(parameters: List<Parameter>, keyed: Set<String>): Map<String, String> {
			val sided = parameters.filter { it.min < it.default && it.default < it.max }
			fun pick(from: List<Parameter>) = from.firstOrNull { it.id.raw == "ParamAngleX" } ?: from.firstOrNull()
			return (pick(sided.filter { it.id.raw in keyed }) ?: pick(sided))?.let { mapOf("parameter" to it.id.raw) }.orEmpty()
		}


		override fun drivers(options: Map<String, String>) = listOfNotNull(options["parameter"])

		override fun keys(options: Map<String, String>, parameter: Parameter, own: FloatArray): List<Float> {
			if (options["source"] != null || parameter.id.raw != options["parameter"]) return emptyList()
			// Each key on the kept side has its image on the mirrored one.
			return own.map { mirrored(parameter, it) }
		}

		/** [value]'s image across [p]'s default, its two halves mapped in proportion. */
		internal fun mirrored(p: Parameter, value: Float): Float = when {
			value > p.default && p.default > p.min -> p.default - (value - p.default) / (p.max - p.default) * (p.default - p.min)
			value < p.default && p.max > p.default -> p.default + (p.default - value) / (p.default - p.min) * (p.max - p.default)
			else -> p.default
		}

		override fun deform(context: ModifierContext, values: Map<String, FloatArray>, options: Map<String, String>): FloatArray {
			val axis = values.getValue("axis")[0]
			val parameter = options["parameter"]?.let(context::parameter) ?: return context.before()
			val id = parameter.id.raw
			val v = context.pose[id] ?: parameter.default
			val rest = context.pose + (id to parameter.default)
			val base = context.before(rest)
			val source = options["source"]
			val (points, field, triangles) = if (source == null) {
				val positive = options["side"] != "negative"
				val mirroredSide = if (positive) v < parameter.default - ModifierValue.EPS else v > parameter.default + ModifierValue.EPS
				if (!mirroredSide) return context.before()
				val kept = context.before(context.pose + (id to mirrored(parameter, v)))
				Triple(base, FloatArray(base.size) { kept[it] - base[it] }, context.triangles)
			} else {
				val read = options["source_parameter"] ?: id
				val sourceDefault = context.parameter(read)?.default ?: return context.before()
				val at = context.shapeOf(source, context.pose + (read to v)) ?: return context.before()
				val still = context.shapeOf(source, context.pose + (read to sourceDefault)) ?: return context.before()
				Triple(still, FloatArray(at.size) { at[it] - still[it] }, context.trianglesOf(source) ?: IntArray(0))
			}
			val reader = PointField(points, triangles)
			val out = base.copyOf()
			val d = FloatArray(2)
			for (i in 0 until base.size / 2) {
				reader.sample(field, 2 * axis - base[i * 2], base[i * 2 + 1], d)
				out[i * 2] = base[i * 2] - d[0]
				out[i * 2 + 1] = base[i * 2 + 1] + d[1]
			}
			return out
		}
	}
}

/**
 * A field known at [points] (interleaved x, y), read anywhere: inside one of [triangles] it is interpolated
 * barycentrically, elsewhere it is the nearest point's. Triangles are bucketed on a grid so a read looks at a few.
 */
internal class PointField(private val points: FloatArray, private val triangles: IntArray) {
	private val count = points.size / 2
	private val minX: Float; private val minY: Float; private val cell: Float; private val columns: Int; private val rows: Int
	private val buckets: Array<IntArray>

	init {
		var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
		for (i in 0 until count) { x0 = minOf(x0, points[i * 2]); x1 = maxOf(x1, points[i * 2]); y0 = minOf(y0, points[i * 2 + 1]); y1 = maxOf(y1, points[i * 2 + 1]) }
		if (count == 0) { x0 = 0f; y0 = 0f; x1 = 1f; y1 = 1f }
		minX = x0; minY = y0
		val side = maxOf(x1 - x0, y1 - y0, 1e-3f)
		val n = kotlin.math.sqrt((triangles.size / 3).toDouble()).toInt().coerceIn(1, 64)
		cell = side / n
		columns = ((x1 - x0) / cell).toInt() + 1; rows = ((y1 - y0) / cell).toInt() + 1
		val lists = Array(columns * rows) { ArrayList<Int>() }
		for (t in 0 until triangles.size / 3) {
			var tx0 = Float.MAX_VALUE; var ty0 = Float.MAX_VALUE; var tx1 = -Float.MAX_VALUE; var ty1 = -Float.MAX_VALUE
			for (k in 0..2) { val p = triangles[t * 3 + k]; tx0 = minOf(tx0, points[p * 2]); tx1 = maxOf(tx1, points[p * 2]); ty0 = minOf(ty0, points[p * 2 + 1]); ty1 = maxOf(ty1, points[p * 2 + 1]) }
			for (cy in row(ty0)..row(ty1)) for (cx in column(tx0)..column(tx1)) lists[cy * columns + cx] += t
		}
		buckets = Array(lists.size) { lists[it].toIntArray() }
	}

	private fun column(x: Float) = ((x - minX) / cell).toInt().coerceIn(0, columns - 1)
	private fun row(y: Float) = ((y - minY) / cell).toInt().coerceIn(0, rows - 1)

	/** [field] (two values per point) at ([x], [y]) into [out]. */
	fun sample(field: FloatArray, x: Float, y: Float, out: FloatArray) {
		if (count == 0) { out[0] = 0f; out[1] = 0f; return }
		val inside = x >= minX && y >= minY && x <= minX + columns * cell && y <= minY + rows * cell
		if (inside) for (t in buckets[row(y) * columns + column(x)]) {
			val a = triangles[t * 3]; val b = triangles[t * 3 + 1]; val c = triangles[t * 3 + 2]
			val ax = points[a * 2]; val ay = points[a * 2 + 1]
			val bx = points[b * 2] - ax; val by = points[b * 2 + 1] - ay
			val cx = points[c * 2] - ax; val cy = points[c * 2 + 1] - ay
			val det = bx * cy - by * cx
			if (kotlin.math.abs(det) < 1e-9f) continue
			val px = x - ax; val py = y - ay
			val u = (px * cy - py * cx) / det; val v = (bx * py - by * px) / det
			if (u < -1e-4f || v < -1e-4f || u + v > 1 + 1e-4f) continue
			val w = 1 - u - v
			out[0] = w * field[a * 2] + u * field[b * 2] + v * field[c * 2]
			out[1] = w * field[a * 2 + 1] + u * field[b * 2 + 1] + v * field[c * 2 + 1]
			return
		}
		var best = 0; var distance = Float.MAX_VALUE
		for (i in 0 until count) {
			val dx = points[i * 2] - x; val dy = points[i * 2 + 1] - y
			val d = dx * dx + dy * dy
			if (d < distance) { distance = d; best = i }
		}
		out[0] = field[best * 2]; out[1] = field[best * 2 + 1]
	}
}
