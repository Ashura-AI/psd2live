package io.github.psd2live.core.modifier

import kotlinx.serialization.json.*
import kotlin.math.*

/*
 * The canvas handles of a modifier, as pure geometry in canvas pixels (y down) - the space the settings themselves
 * are in: where each handle sits for a modifier's values at one pose, what values a handle dragged to a point stands
 * for, and the lines that show what the modifier does. The canvas only maps these points to and from the screen.
 */

/** A point on the canvas, pixels, y down. */
internal data class ModifierPoint(val x: Float, val y: Float) {
	operator fun plus(o: ModifierPoint) = ModifierPoint(x + o.x, y + o.y)
	operator fun minus(o: ModifierPoint) = ModifierPoint(x - o.x, y - o.y)
	operator fun times(k: Float) = ModifierPoint(x * k, y * k)
	fun length() = hypot(x, y)
}

/** One draggable handle: what it edits, and for a lattice corner or control point which one ([index]). */
internal data class ModifierHandle(val role: Role, val index: Int = 0) {
	enum class Role(val key: String, val settings: List<String>, val point: Boolean) {
		PIVOT("pivot", listOf("pivot"), true),
		OFFSET("offset", listOf("offset"), true),
		ROTATION("rotation", listOf("rotation"), false),
		ORIGIN("origin", listOf("origin"), true),
		AXIS("axis", listOf("direction", "length"), false),
		BEND("bend", listOf("angle"), false),
		CENTER("center", listOf("center"), true),
		RADIUS("radius", listOf("radius"), false),
		TWIST("twist", listOf("angle"), false),
		DIRECTION("direction", listOf("direction"), false),
		WAVELENGTH("wavelength", listOf("wavelength"), false),
		AMPLITUDE("amplitude", listOf("amplitude"), false),
		RAMP("ramp", listOf("ramp"), false),
		CORNER("corner", listOf("bounds"), false),
		CONTROL("control", listOf("offsets"), true),
		/** A path's control point where it rests, edited apart from where it has moved to ([CONTROL]). */
		PATH("path", listOf("path"), true),
		FADE("fade", listOf("radius"), false),
	}

	val labelKey get() = "modifiers.handle.${role.key}"
}

/** A line drawn with the handles: [dashed] for where things rest, solid for what the modifier makes of them. */
internal data class ModifierGuide(val points: List<ModifierPoint>, val dashed: Boolean = false)

internal object ModifierHandles {
	/** The least length, radius, wavelength and cage size a handle leaves, canvas pixels: the settings' own floor. */
	const val MIN_LENGTH = 1f

	/** Steps each way a bend's angle is searched for, at 1° and then 0.05°. */
	private const val SEARCH = 90

	/** Positions to 0.1 px and angles to 0.1°, as the panel's fields show them. */
	private fun round1(v: Float) = round(v * 10f) / 10f
	private fun point(v: FloatArray, at: Int = 0) = ModifierPoint(v[at], v[at + 1])
	private fun pair(p: ModifierPoint) = floatArrayOf(round1(p.x), round1(p.y))
	private fun unit(degrees: Float) = ModifierPoint(cos(degrees * PI.toFloat() / 180f), sin(degrees * PI.toFloat() / 180f))
	private fun degrees(p: ModifierPoint) = atan2(p.y, p.x) * 180f / PI.toFloat()

	/** The turn from [a] to [b] about [center] in degrees, within ±180: dragged a little at a time, turns add up past a whole one. */
	fun turn(center: ModifierPoint, a: ModifierPoint, b: ModifierPoint): Float {
		var d = degrees(b - center) - degrees(a - center)
		while (d > 180f) d -= 360f
		while (d < -180f) d += 360f
		return d
	}

	/** [p] in an axis's coordinates: along it, and across it to its right on the canvas. */
	private fun axial(origin: ModifierPoint, direction: Float, p: ModifierPoint): ModifierPoint {
		val d = unit(direction)
		val q = p - origin
		return ModifierPoint(q.x * d.x + q.y * d.y, -q.x * d.y + q.y * d.x)
	}

	private fun across(direction: Float) = unit(direction).let { ModifierPoint(-it.y, it.x) }

	/** The rest position of lattice control point [i], row by row from the top left. */
	fun latticeRest(bounds: FloatArray, i: Int): ModifierPoint {
		val n = ModifierKinds.Lattice.SIZE - 1
		return ModifierPoint(bounds[0] + (bounds[2] - bounds[0]) * (i % (n + 1)) / n, bounds[1] + (bounds[3] - bounds[1]) * (i / (n + 1)) / n)
	}

	/** Where a lattice corner's handle sits: off the cage's rest corner by [reach] each way, so it never covers the corner's control point. */
	private fun corner(bounds: FloatArray, i: Int, reach: Float) = ModifierPoint(
		if (i % 2 == 0) bounds[0] - reach / 2 else bounds[2] + reach / 2,
		if (i < 2) bounds[1] - reach / 2 else bounds[3] + reach / 2,
	)

	/**
	 * The handles of a [kind] modifier with [v] (every setting at one pose). [reach] is a handle's arm where a value has
	 * no length of its own (a rotation, a direction), in canvas pixels; the canvas passes a fixed length on screen.
	 */
	fun handles(kind: String, v: Map<String, FloatArray>, reach: Float): Map<ModifierHandle, ModifierPoint> = when (kind) {
		ModifierKinds.Transform.id -> {
			val moved = point(v.getValue("pivot")) + point(v.getValue("offset"))
			mapOf(
				ModifierHandle(ModifierHandle.Role.PIVOT) to point(v.getValue("pivot")),
				// Below the moved pivot on an arm of its own, so it is clear of the pivot while there is no offset.
				ModifierHandle(ModifierHandle.Role.OFFSET) to moved + ModifierPoint(0f, reach),
				ModifierHandle(ModifierHandle.Role.ROTATION) to moved + unit(v.getValue("rotation")[0]) * reach,
			)
		}
		ModifierKinds.Bend.id -> {
			val origin = point(v.getValue("origin"))
			val direction = v.getValue("direction")[0]
			val length = v.getValue("length")[0]
			mapOf(
				ModifierHandle(ModifierHandle.Role.ORIGIN) to origin,
				ModifierHandle(ModifierHandle.Role.AXIS) to origin + unit(direction) * length,
				// Past the arc's end, along where the bent axis carries on: a steering arm off the end.
				ModifierHandle(ModifierHandle.Role.BEND) to bent(v, listOf(origin + unit(direction) * (length + reach))).single(),
			)
		}
		ModifierKinds.Twist.id -> {
			val center = point(v.getValue("center"))
			val radius = v.getValue("radius")[0]
			mapOf(
				ModifierHandle(ModifierHandle.Role.CENTER) to center,
				ModifierHandle(ModifierHandle.Role.RADIUS) to center + ModifierPoint(radius, 0f),
				ModifierHandle(ModifierHandle.Role.TWIST) to center + unit(v.getValue("angle")[0]) * (radius / 2),
			)
		}
		ModifierKinds.Wave.id -> {
			val origin = point(v.getValue("origin"))
			val direction = v.getValue("direction")[0]
			val d = unit(direction)
			val n = across(direction)
			val wavelength = v.getValue("wavelength")[0]
			mapOf(
				ModifierHandle(ModifierHandle.Role.ORIGIN) to origin,
				ModifierHandle(ModifierHandle.Role.DIRECTION) to origin + d * waveExtent(v, reach),
				ModifierHandle(ModifierHandle.Role.WAVELENGTH) to origin + d * wavelength,
				// At a quarter wavelength, where the crest is at no phase.
				ModifierHandle(ModifierHandle.Role.AMPLITUDE) to origin + d * (wavelength / 4) + n * v.getValue("amplitude")[0],
				// Beside the axis on its left, so a ramp of one wavelength does not cover the wavelength's handle.
				ModifierHandle(ModifierHandle.Role.RAMP) to origin + d * v.getValue("ramp")[0] - n * (reach / 2),
			)
		}
		ModifierKinds.Lattice.id -> {
			val bounds = v.getValue("bounds")
			val offsets = v.getValue("offsets")
			buildMap {
				for (i in 0 until 4) put(ModifierHandle(ModifierHandle.Role.CORNER, i), corner(bounds, i, reach))
				for (i in 0 until offsets.size / 2) put(ModifierHandle(ModifierHandle.Role.CONTROL, i), latticeRest(bounds, i) + point(offsets, i * 2))
			}
		}
		ModifierKinds.Path.id -> {
			val rest = v.getValue("path")
			val offsets = v.getValue("offsets")
			val (middle, normal) = pathMiddle(rest)
			buildMap {
				for (i in 0 until rest.size / 2) put(ModifierHandle(ModifierHandle.Role.CONTROL, i), point(rest, i * 2) + point(offsets, i * 2))
				for (i in 0 until rest.size / 2) put(ModifierHandle(ModifierHandle.Role.PATH, i), point(rest, i * 2))
				put(ModifierHandle(ModifierHandle.Role.FADE), middle + normal * v.getValue("radius")[0])
			}
		}
		else -> emptyMap()
	}

	/** The cubic Bézier through control points [c] (4 pairs) at [t]. */
	fun bezier(c: FloatArray, t: Float): ModifierPoint {
		val u = 1 - t
		val w = floatArrayOf(u * u * u, 3 * t * u * u, 3 * t * t * u, t * t * t)
		return ModifierPoint((0 until 4).sumOf { (w[it] * c[it * 2]).toDouble() }.toFloat(), (0 until 4).sumOf { (w[it] * c[it * 2 + 1]).toDouble() }.toFloat())
	}

	/** The rest curve's middle and its unit normal there (the tangent turned a quarter clockwise); a degenerate curve's normal points left. */
	private fun pathMiddle(rest: FloatArray): Pair<ModifierPoint, ModifierPoint> {
		val tangent = bezier(rest, .51f) - bezier(rest, .49f)
		val l = tangent.length()
		val normal = if (l < 1e-6f) ModifierPoint(-1f, 0f) else ModifierPoint(-tangent.y / l, tangent.x / l)
		return bezier(rest, .5f) to normal
	}

	/** How far a wave's axis is drawn: past its wavelength and ramp, to the direction handle. */
	private fun waveExtent(v: Map<String, FloatArray>, reach: Float) = max(v.getValue("wavelength")[0], v.getValue("ramp")[0]) + reach

	private fun bent(v: Map<String, FloatArray>, points: List<ModifierPoint>): List<ModifierPoint> {
		val xy = FloatArray(points.size * 2) { if (it % 2 == 0) points[it / 2].x else points[it / 2].y }
		val out = ModifierKinds.Bend.deform(xy, v)
		return points.indices.map { ModifierPoint(out[it * 2], out[it * 2 + 1]) }
	}

	/**
	 * The settings [handle] changes when dragged from [grab] to [p], from [start] (the values at the press): each
	 * changed setting's whole value. Point handles follow the pointer by what it moved since the press; a turn adds the
	 * turn from [previous] to [p] to [current] (the values so far), so a drag can wind past a whole turn; a turn is not
	 * rounded on its way, or the steps' rounding would add up ([rounded] rounds what is sent). [reach] is as [handles] takes it.
	 */
	fun drag(
		kind: String,
		handle: ModifierHandle,
		start: Map<String, FloatArray>,
		current: Map<String, FloatArray>,
		grab: ModifierPoint,
		previous: ModifierPoint,
		p: ModifierPoint,
		reach: Float,
	): Map<String, FloatArray> {
		val moved = p - grab
		fun shifted(key: String) = key to pair(point(start.getValue(key)) + moved)
		fun clamp(key: String, value: Float): Float {
			val s = ModifierKinds.of(kind)!!.settings.first { it.key == key }
			return value.coerceIn(s.min, s.max)
		}
		return when (handle.role) {
			ModifierHandle.Role.PIVOT -> mapOf(shifted("pivot"))
			ModifierHandle.Role.OFFSET -> mapOf(shifted("offset"))
			ModifierHandle.Role.ORIGIN -> mapOf(shifted("origin"))
			ModifierHandle.Role.CENTER -> mapOf(shifted("center"))
			ModifierHandle.Role.ROTATION -> {
				val center = point(current.getValue("pivot")) + point(current.getValue("offset"))
				mapOf("rotation" to floatArrayOf(clamp("rotation", current.getValue("rotation")[0] + turn(center, previous, p))))
			}
			ModifierHandle.Role.AXIS -> {
				val origin = point(current.getValue("origin"))
				val direction = current.getValue("direction")[0] + turn(origin, previous, p)
				mapOf(
					"direction" to floatArrayOf(clamp("direction", direction)),
					"length" to floatArrayOf(round1(clamp("length", max(MIN_LENGTH, (p - origin).length())))),
				)
			}
			ModifierHandle.Role.BEND -> {
				// The arm's end moves with the angle, so the angle is found that brings the handle nearest the pointer,
				// searched about the angle so far: the arc follows the hand without jumping to another turn.
				val tip = point(current.getValue("origin")) + unit(current.getValue("direction")[0]) * (current.getValue("length")[0] + reach)
				fun miss(angle: Float) = (bent(current + ("angle" to floatArrayOf(angle)), listOf(tip)).single() - p).length()
				var best = current.getValue("angle")[0]
				for (step in listOf(1f, .05f)) {
					val around = best
					for (i in -SEARCH..SEARCH) {
						val angle = clamp("angle", around + i * step)
						if (miss(angle) < miss(best)) best = angle
					}
				}
				mapOf("angle" to floatArrayOf(round1(best)))
			}
			ModifierHandle.Role.RADIUS -> {
				val center = point(current.getValue("center"))
				mapOf("radius" to floatArrayOf(round1(clamp("radius", max(MIN_LENGTH, (p - center).length())))))
			}
			ModifierHandle.Role.TWIST -> {
				val center = point(current.getValue("center"))
				mapOf("angle" to floatArrayOf(clamp("angle", current.getValue("angle")[0] + turn(center, previous, p))))
			}
			ModifierHandle.Role.DIRECTION -> {
				val origin = point(current.getValue("origin"))
				mapOf("direction" to floatArrayOf(clamp("direction", current.getValue("direction")[0] + turn(origin, previous, p))))
			}
			ModifierHandle.Role.WAVELENGTH -> {
				val along = axial(point(current.getValue("origin")), current.getValue("direction")[0], p).x
				mapOf("wavelength" to floatArrayOf(round1(clamp("wavelength", max(MIN_LENGTH, along)))))
			}
			ModifierHandle.Role.AMPLITUDE -> {
				val acrossBy = axial(point(current.getValue("origin")), current.getValue("direction")[0], p).y
				mapOf("amplitude" to floatArrayOf(round1(clamp("amplitude", acrossBy))))
			}
			ModifierHandle.Role.RAMP -> {
				val along = axial(point(current.getValue("origin")), current.getValue("direction")[0], p).x
				mapOf("ramp" to floatArrayOf(round1(clamp("ramp", max(0f, along)))))
			}
			ModifierHandle.Role.CORNER -> {
				// The corner moves and the opposite one stays; the cage keeps a least size rather than turning inside out.
				val b = start.getValue("bounds").copyOf()
				val i = handle.index
				if (i % 2 == 0) b[0] = min(b[0] + moved.x, b[2] - MIN_LENGTH) else b[2] = max(b[2] + moved.x, b[0] + MIN_LENGTH)
				if (i < 2) b[1] = min(b[1] + moved.y, b[3] - MIN_LENGTH) else b[3] = max(b[3] + moved.y, b[1] + MIN_LENGTH)
				mapOf("bounds" to FloatArray(4) { round1(b[it]) })
			}
			ModifierHandle.Role.PATH -> {
				// The rest point moves and the moved point with it: its offset stays.
				val path = start.getValue("path").copyOf()
				val i = handle.index * 2
				path[i] = round1(path[i] + moved.x); path[i + 1] = round1(path[i + 1] + moved.y)
				mapOf("path" to path)
			}
			ModifierHandle.Role.FADE -> {
				val (middle, normal) = pathMiddle(current.getValue("path"))
				val q = p - middle
				mapOf("radius" to floatArrayOf(round1(clamp("radius", abs(q.x * normal.x + q.y * normal.y)))))
			}
			ModifierHandle.Role.CONTROL -> {
				val offsets = start.getValue("offsets").copyOf()
				val i = handle.index * 2
				offsets[i] = round1(clamp("offsets", offsets[i] + moved.x)); offsets[i + 1] = round1(clamp("offsets", offsets[i + 1] + moved.y))
				mapOf("offsets" to offsets)
			}
		}
	}

	/** [values] to 0.1, as the panel's fields show them: what a drag sends. */
	fun rounded(values: Map<String, FloatArray>): Map<String, FloatArray> = values.mapValues { (_, v) -> FloatArray(v.size) { round1(v[it]) } }

	/** The lines shown with the handles of a [kind] modifier with [v]. */
	fun guides(kind: String, v: Map<String, FloatArray>, reach: Float, samples: Int = 48): List<ModifierGuide> = when (kind) {
		ModifierKinds.Transform.id -> {
			val pivot = point(v.getValue("pivot"))
			val moved = pivot + point(v.getValue("offset"))
			listOf(
				ModifierGuide(listOf(pivot, moved), dashed = true),
				ModifierGuide(listOf(moved + ModifierPoint(0f, reach), moved, moved + unit(v.getValue("rotation")[0]) * reach)),
			)
		}
		ModifierKinds.Bend.id -> {
			val origin = point(v.getValue("origin"))
			val d = unit(v.getValue("direction")[0])
			val length = v.getValue("length")[0]
			// The axis at rest, and bent: the arc, carried straight on past it to the steering handle.
			val axis = (0..samples).map { origin + d * ((length + reach) * it / samples) }
			listOf(ModifierGuide(listOf(origin, origin + d * length), dashed = true), ModifierGuide(bent(v, axis)))
		}
		ModifierKinds.Twist.id -> {
			val center = point(v.getValue("center"))
			val radius = v.getValue("radius")[0]
			val rim = (0..samples).map { center + unit(360f * it / samples) * radius }
			// A diameter through the centre, twirled: the turn it shows falls off to nothing at the rim.
			val diameter = (0..samples).map { center + ModifierPoint(radius * (2f * it / samples - 1), 0f) }
			val xy = FloatArray(diameter.size * 2) { if (it % 2 == 0) diameter[it / 2].x else diameter[it / 2].y }
			val twirled = ModifierKinds.Twist.deform(xy, v)
			listOf(
				ModifierGuide(rim, dashed = true),
				ModifierGuide(diameter.indices.map { ModifierPoint(twirled[it * 2], twirled[it * 2 + 1]) }),
				ModifierGuide(listOf(center, center + unit(v.getValue("angle")[0]) * (radius / 2))),
			)
		}
		ModifierKinds.Wave.id -> {
			val origin = point(v.getValue("origin"))
			val direction = v.getValue("direction")[0]
			val d = unit(direction)
			val extent = waveExtent(v, reach)
			val axis = (0..samples * 2).map { origin + d * (extent * it / (samples * 2)) }
			val xy = FloatArray(axis.size * 2) { if (it % 2 == 0) axis[it / 2].x else axis[it / 2].y }
			val waved = ModifierKinds.Wave.deform(xy, v)
			val ramp = origin + d * v.getValue("ramp")[0]
			listOf(
				ModifierGuide(listOf(origin, origin + d * extent), dashed = true),
				ModifierGuide(axis.indices.map { ModifierPoint(waved[it * 2], waved[it * 2 + 1]) }),
				ModifierGuide(listOf(ramp, ramp - across(direction) * (reach / 2)), dashed = true),
			)
		}
		ModifierKinds.Lattice.id -> {
			val bounds = v.getValue("bounds")
			val offsets = v.getValue("offsets")
			val size = ModifierKinds.Lattice.SIZE
			val control = (0 until size * size).map { latticeRest(bounds, it) + point(offsets, it * 2) }
			val rest = listOf(0, 1, 3, 2, 0).map { ModifierPoint(if (it % 2 == 0) bounds[0] else bounds[2], if (it < 2) bounds[1] else bounds[3]) }
			listOf(ModifierGuide(rest, dashed = true)) +
				(0 until size).map { r -> ModifierGuide((0 until size).map { control[r * size + it] }) } +
				(0 until size).map { c -> ModifierGuide((0 until size).map { control[it * size + c] }) } +
				(0 until 4).map { i ->
					val at = ModifierPoint(if (i % 2 == 0) bounds[0] else bounds[2], if (i < 2) bounds[1] else bounds[3])
					ModifierGuide(listOf(at, corner(bounds, i, reach)), dashed = true)
				}
		}
		ModifierKinds.Path.id -> {
			val rest = v.getValue("path")
			val offsets = v.getValue("offsets")
			val moved = FloatArray(rest.size) { rest[it] + offsets[it] }
			val (middle, normal) = pathMiddle(rest)
			fun curve(c: FloatArray) = (0..samples).map { bezier(c, it.toFloat() / samples) }
			listOf(
				ModifierGuide(curve(rest), dashed = true),
				ModifierGuide(curve(moved)),
				ModifierGuide((0 until moved.size / 2).map { point(moved, it * 2) }, dashed = true),
				ModifierGuide(listOf(middle, middle + normal * v.getValue("radius")[0]), dashed = true),
			)
		}
		else -> emptyList()
	}

	/** The value [handle] shows: the settings it edits, at the pose. */
	fun value(handle: ModifierHandle, v: Map<String, FloatArray>): String {
		fun f(x: Float) = "%.1f".format(java.util.Locale.US, x)
		return when (handle.role) {
			ModifierHandle.Role.CONTROL -> v.getValue("offsets").let { "${f(it[handle.index * 2])}, ${f(it[handle.index * 2 + 1])}" }
			ModifierHandle.Role.PATH -> v.getValue("path").let { "${f(it[handle.index * 2])}, ${f(it[handle.index * 2 + 1])}" }
			ModifierHandle.Role.ROTATION, ModifierHandle.Role.BEND, ModifierHandle.Role.TWIST, ModifierHandle.Role.DIRECTION ->
				handle.role.settings.single().let { "${f(v.getValue(it)[0])}°" }
			else -> handle.role.settings.joinToString("  ") { key -> v.getValue(key).joinToString(", ") { f(it) } }
		}
	}
}

/** How an edit of a modifier's settings is sent, by the panel's fields and the canvas handles alike. */
internal object ModifierEdits {
	/**
	 * The `modifier_put` request giving [modifier] the [values] (setting key to whole value): a setting keyed on
	 * parameters takes them as its key at [pose], one that is not as its constant value.
	 */
	fun request(modifier: RigModifier, values: Map<String, FloatArray>, pose: (String) -> Float): JsonObject = buildJsonObject {
		put("id", modifier.id)
		val (keyed, constant) = values.entries.partition { modifier.value(it.key) is ModifierValue.Keyed }
		if (constant.isNotEmpty()) putJsonObject("settings") { for ((key, value) in constant) put(key, JsonArray(value.map(::JsonPrimitive))) }
		if (keyed.isNotEmpty()) putJsonArray("keys") {
			for ((key, value) in keyed) addJsonObject {
				put("setting", key)
				putJsonObject("at") { (modifier.value(key) as ModifierValue.Keyed).axes.forEach { put(it.parameter, pose(it.parameter)) } }
				put("value", JsonArray(value.map(::JsonPrimitive)))
			}
		}
	}

	/** Every setting of [modifier] at [pose]. */
	fun values(modifier: RigModifier, pose: (String) -> Float): Map<String, FloatArray> =
		modifier.definition.settings.associate { it.key to modifier.value(it.key).at(pose) }
}
