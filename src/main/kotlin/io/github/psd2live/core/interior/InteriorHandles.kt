package io.github.psd2live.core.interior

import io.github.psd2live.core.DeformPathTools
import io.github.psd2live.core.LayerTexture
import kotlin.math.*
import org.umamo.runtime.model.DeformPathPoint
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.PuppetModel

/*
 * The canvas handles of an interior effect, as pure geometry in source-raster pixels: where each handle sits for the
 * saved values, and what values a handle dragged to a point stands for. The canvas only maps these points to and from
 * the screen ([InteriorCanvasMap]); every value it shows or commits comes from here, so the panel, the canvas and MCP
 * read the same numbers.
 */

/** An axis-aligned rectangle in source-raster pixels. */
internal data class InteriorFrame(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
	init {
		require(listOf(x0, y0, x1, y1).all(Float::isFinite) && x1 > x0 && y1 > y0) { "Empty interior frame" }
	}

	val cx get() = (x0 + x1) / 2
	val cy get() = (y0 + y1) / 2
	val halfWidth get() = (x1 - x0) / 2
	val halfHeight get() = (y1 - y0) / 2

	/** The point at [u], [v] of the frame, 0..1 each way. */
	fun at(u: Float, v: Float) = InteriorPoint(x0 + u * (x1 - x0), y0 + v * (y1 - y0))

	/** [p] as a fraction of the frame, unclamped. */
	fun fraction(p: InteriorPoint) = InteriorPoint((p.x - x0) / (x1 - x0), (p.y - y0) / (y1 - y0))

	companion object {
		/** The visible pixels' bounds, the extent the virtual surface spans ([InteriorSurfaceCompiler]). */
		fun ofAlpha(width: Int, height: Int, rgba: ByteArray): InteriorFrame? {
			require(width > 0 && height > 0 && rgba.size.toLong() == width.toLong() * height * 4)
			var x0 = width; var x1 = -1; var y0 = height; var y1 = -1
			for (y in 0 until height) for (x in 0 until width) if (rgba[(y * width + x) * 4 + 3].toInt() != 0) {
				x0 = min(x0, x); x1 = max(x1, x + 1); y0 = min(y0, y); y1 = max(y1, y + 1)
			}
			return if (x1 > x0 && y1 > y0) InteriorFrame(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat()) else null
		}

		/** The bounds of a mesh's raster positions, the extent the propagation direction is normalized to ([InteriorMotionGenerator]). */
		fun ofPoints(xy: FloatArray): InteriorFrame? {
			if (xy.size < 2) return null
			val xs = xy.indices.step(2).map { xy[it] }
			val ys = xy.indices.step(2).map { xy[it + 1] }
			return if (xs.max() > xs.min() && ys.max() > ys.min()) InteriorFrame(xs.min(), ys.min(), xs.max(), ys.max()) else null
		}
	}
}

/** One draggable handle of the surface or the propagation direction, with the setting it edits. */
internal enum class InteriorHandle(val labelKey: String) {
	BULGE("interior.bulge"),
	PRESS("interior.press"),
	HORIZONTAL("interior.horizontal"),
	VERTICAL("interior.vertical"),
	FROM("interior.from"),
	TO("interior.to"),
	DIRECTION("interior.direction"),
	;

	val surface get() = this in SURFACE
	companion object {
		val SURFACE = listOf(BULGE, PRESS, HORIZONTAL, VERTICAL)
		val PROPAGATION = listOf(FROM, TO, DIRECTION)
	}
}

internal object InteriorHandles {
	/** Where an arm starts, as a share of the half frame: a zero value keeps its handle clear of the centre and of the other arms. */
	const val INNER = .18f

	/** The largest gain an arm reaches at the frame's edge: every shaping amount runs 0..2 ([InteriorSurfaceSettings]). */
	const val MAX = 2f

	/** The least distance between the propagation ends, as a share of the mesh frame ([InteriorMotionSettings]). */
	const val MIN_SPAN = .05f

	private fun arm(value: Float, half: Float) = half * (INNER + (1 - INNER) * value / MAX)

	private fun armValue(distance: Float, half: Float) =
		round((((distance / half) - INNER) / (1 - INNER) * MAX).coerceIn(0f, MAX) * 100f) / 100f

	/**
	 * The surface handles: a cross over the visible frame's centre. Up is the bulge, left the press, right and down the
	 * horizontal and vertical gains; each arm is longer the larger its value, reaching the frame's edge at 2.
	 */
	fun surface(frame: InteriorFrame, s: InteriorSurfaceSettings): Map<InteriorHandle, InteriorPoint> = mapOf(
		InteriorHandle.BULGE to InteriorPoint(frame.cx, frame.cy - arm(s.bulge, frame.halfHeight)),
		InteriorHandle.PRESS to InteriorPoint(frame.cx - arm(s.press, frame.halfWidth), frame.cy),
		InteriorHandle.HORIZONTAL to InteriorPoint(frame.cx + arm(s.horizontal, frame.halfWidth), frame.cy),
		InteriorHandle.VERTICAL to InteriorPoint(frame.cx, frame.cy + arm(s.vertical, frame.halfHeight)),
	)

	/** [s] with [handle]'s value read from [p], projected on its arm; to 0.01, as the panel's sliders round. */
	fun dragSurface(handle: InteriorHandle, frame: InteriorFrame, s: InteriorSurfaceSettings, p: InteriorPoint): InteriorSurfaceSettings =
		when (handle) {
			InteriorHandle.BULGE -> s.copy(bulge = armValue(frame.cy - p.y, frame.halfHeight))
			InteriorHandle.PRESS -> s.copy(press = armValue(frame.cx - p.x, frame.halfWidth))
			InteriorHandle.HORIZONTAL -> s.copy(horizontal = armValue(p.x - frame.cx, frame.halfWidth))
			InteriorHandle.VERTICAL -> s.copy(vertical = armValue(p.y - frame.cy, frame.halfHeight))
			else -> s
		}

	/** The value [handle] shows: the setting it edits. */
	fun value(handle: InteriorHandle, s: InteriorSurfaceSettings, m: InteriorMotionSettings): String = when (handle) {
		InteriorHandle.BULGE -> "%.2f".format(java.util.Locale.US, s.bulge)
		InteriorHandle.PRESS -> "%.2f".format(java.util.Locale.US, s.press)
		InteriorHandle.HORIZONTAL -> "%.2f".format(java.util.Locale.US, s.horizontal)
		InteriorHandle.VERTICAL -> "%.2f".format(java.util.Locale.US, s.vertical)
		InteriorHandle.FROM -> "%.2f, %.2f".format(java.util.Locale.US, m.from.x, m.from.y)
		InteriorHandle.TO, InteriorHandle.DIRECTION -> "%.2f, %.2f".format(java.util.Locale.US, m.to.x, m.to.y)
	}

	/**
	 * The virtual arch through the frame's centre line, [samples] + 1 points from left to right: at rest, or ([pressed])
	 * leaning as a full horizontal input leans it. The height follows the bulge, the lean the press, as the solver's
	 * section does ([InteriorSurface]); the drawing is a picture of the arch, not of the displacement.
	 */
	fun arch(frame: InteriorFrame, s: InteriorSurfaceSettings, pressed: Boolean, vertical: Boolean = false, samples: Int = 32): List<InteriorPoint> {
		val half = if (vertical) frame.halfWidth else frame.halfHeight
		val height = half * .9f * s.bulge / MAX
		val skew = if (pressed) .235f * tanh(s.press * s.bulge * 1.4f) else 0f
		return (0..samples).map { i ->
			val t = i.toFloat() / samples
			val rise = 4 * t * (1 - t)
			val along = (t + skew * rise).coerceIn(0f, 1f)
			if (vertical) InteriorPoint(frame.cx - height * rise, frame.y0 + along * (frame.y1 - frame.y0))
			else InteriorPoint(frame.x0 + along * (frame.x1 - frame.x0), frame.cy - height * rise)
		}
	}

	/** The propagation handles over the mesh frame: its two ends and the middle that moves both. */
	fun propagation(frame: InteriorFrame, m: InteriorMotionSettings): Map<InteriorHandle, InteriorPoint> {
		val from = frame.at(m.from.x, m.from.y)
		val to = frame.at(m.to.x, m.to.y)
		return mapOf(
			InteriorHandle.FROM to from,
			InteriorHandle.TO to to,
			InteriorHandle.DIRECTION to InteriorPoint((from.x + to.x) / 2, (from.y + to.y) / 2),
		)
	}

	/**
	 * [start]'s propagation with [handle] dragged from [grab] to [p], or null when the ends would come closer than
	 * [MIN_SPAN] and the direction would be lost. An end stays inside the frame; the middle moves both ends by the same
	 * amount, stopping at the frame's edge.
	 */
	fun dragPropagation(
		handle: InteriorHandle,
		frame: InteriorFrame,
		start: InteriorMotionSettings,
		grab: InteriorPoint,
		p: InteriorPoint,
	): InteriorMotionSettings? {
		fun round2(v: Float) = round(v.coerceIn(0f, 1f) * 100f) / 100f
		val f = frame.fraction(p)
		val g = frame.fraction(grab)
		fun shifted(p0: InteriorPoint) = InteriorPoint(round2(p0.x + f.x - g.x), round2(p0.y + f.y - g.y))
		val (from, to) = when (handle) {
			InteriorHandle.FROM -> shifted(start.from) to start.to
			InteriorHandle.TO -> start.from to shifted(start.to)
			InteriorHandle.DIRECTION -> {
				val lo = InteriorPoint(min(start.from.x, start.to.x), min(start.from.y, start.to.y))
				val hi = InteriorPoint(max(start.from.x, start.to.x), max(start.from.y, start.to.y))
				val dx = (f.x - g.x).coerceIn(-lo.x, 1 - hi.x)
				val dy = (f.y - g.y).coerceIn(-lo.y, 1 - hi.y)
				InteriorPoint(round2(start.from.x + dx), round2(start.from.y + dy)) to
					InteriorPoint(round2(start.to.x + dx), round2(start.to.y + dy))
			}
			else -> return start
		}
		// Checked before the settings are built: they refuse ends this close.
		val span = hypot((to.x - from.x).toDouble(), (to.y - from.y).toDouble())
		return if (span >= MIN_SPAN) start.copy(from = from, to = to) else null
	}
}

/**
 * Point edits of the guide list, each returning the list to commit or null when the edit would break a guide's
 * contract ([InteriorGuide]): consecutive points must differ, a guide keeps 2..256 points, an effect at most 64 guides.
 */
internal object InteriorGuideEdits {
	const val MAX_GUIDES = 64
	const val MAX_POINTS = 256

	private fun List<InteriorGuide>.with(index: Int, points: List<InteriorPoint>): List<InteriorGuide>? {
		if (points.size !in 2..MAX_POINTS || points.zipWithNext().any { (a, b) -> a == b }) return null
		return toMutableList().also { it[index] = it[index].copy(points = points) }
	}

	fun move(guides: List<InteriorGuide>, guide: Int, point: Int, to: InteriorPoint): List<InteriorGuide>? =
		guides.with(guide, guides[guide].points.toMutableList().also { it[point] = to })

	/** [at] inserted after the point [segment] starts at. */
	fun insert(guides: List<InteriorGuide>, guide: Int, segment: Int, at: InteriorPoint): List<InteriorGuide>? =
		guides.with(guide, guides[guide].points.toMutableList().also { it.add(segment + 1, at) })

	/** [point] removed; a guide left with fewer than two points goes with it. */
	fun remove(guides: List<InteriorGuide>, guide: Int, point: Int): List<InteriorGuide> {
		val points = guides[guide].points
		if (points.size <= 2) return removeGuide(guides, guide)
		// Removing a point can bring two equal neighbours together; drop the repeat rather than refuse.
		val next = points.filterIndexed { i, _ -> i != point }.fold(ArrayList<InteriorPoint>()) { list, p ->
			if (list.lastOrNull() != p) list += p
			list
		}
		return if (next.size < 2) removeGuide(guides, guide) else guides.toMutableList().also { it[guide] = it[guide].copy(points = next) }
	}

	fun removeGuide(guides: List<InteriorGuide>, guide: Int): List<InteriorGuide> = guides.filterIndexed { i, _ -> i != guide }

	/** A drawn fold through [points], with the first free `fold<n>` ID; repeats in a row are dropped. */
	fun add(guides: List<InteriorGuide>, points: List<InteriorPoint>, radius: Float = 16f): List<InteriorGuide>? {
		if (guides.size >= MAX_GUIDES) return null
		val distinct = points.fold(ArrayList<InteriorPoint>()) { list, p -> if (list.lastOrNull() != p) list += p; list }
		if (distinct.size !in 2..MAX_POINTS) return null
		return guides + InteriorGuide(nextInteriorFoldId(guides), distinct, InteriorGuidePurpose.FOLD, radius)
	}

	/**
	 * Where the radius handle of [g] sits: off the middle of its longest segment, along the segment's normal, at the
	 * radius - the reach of the fold drawn as a distance from the line.
	 */
	fun radiusHandle(g: InteriorGuide): Pair<InteriorPoint, InteriorPoint> {
		val (a, b) = g.points.zipWithNext().maxBy { (a, b) -> hypot(b.x - a.x, b.y - a.y) }
		val length = max(1e-6f, hypot(b.x - a.x, b.y - a.y))
		val mid = InteriorPoint((a.x + b.x) / 2, (a.y + b.y) / 2)
		return mid to InteriorPoint(mid.x - (b.y - a.y) / length * g.radius, mid.y + (b.x - a.x) / length * g.radius)
	}

	/** [g]'s radius read from a radius handle dragged to [p]: its distance from the segment's middle, to a whole pixel. */
	fun dragRadius(guides: List<InteriorGuide>, guide: Int, p: InteriorPoint): List<InteriorGuide> {
		val (mid, _) = radiusHandle(guides[guide])
		val radius = max(1f, round(hypot(p.x - mid.x, p.y - mid.y)))
		return guides.toMutableList().also { it[guide] = it[guide].copy(radius = radius) }
	}
}

/** What a canvas position lands on among a set of screen polylines. */
internal data class InteriorPolylineHit(val line: Int, val point: Int, val segment: Boolean, val distance: Float)

internal object InteriorPolylines {
	/**
	 * The nearest point within [tolerance] of ([x], [y]); failing that the nearest segment, with [InteriorPolylineHit.point]
	 * the index the segment starts at. Points win over segments so a point on a line can always be grabbed.
	 */
	fun hit(lines: List<FloatArray>, x: Float, y: Float, tolerance: Float): InteriorPolylineHit? {
		var best: InteriorPolylineHit? = null
		for ((l, line) in lines.withIndex()) for (i in 0 until line.size / 2) {
			val d = hypot(line[i * 2] - x, line[i * 2 + 1] - y)
			if (d <= tolerance && (best == null || d < best.distance)) best = InteriorPolylineHit(l, i, false, d)
		}
		if (best != null) return best
		for ((l, line) in lines.withIndex()) for (i in 0 until line.size / 2 - 1) {
			val ax = line[i * 2]; val ay = line[i * 2 + 1]; val bx = line[i * 2 + 2]; val by = line[i * 2 + 3]
			val dx = bx - ax; val dy = by - ay
			val length = dx * dx + dy * dy
			val t = if (length <= 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / length).coerceIn(0f, 1f)
			val d = hypot(ax + t * dx - x, ay + t * dy - y)
			if (d <= tolerance && (best == null || d < best.distance)) best = InteriorPolylineHit(l, i, true, d)
		}
		return best
	}
}

/**
 * The map between a mesh's source-raster positions and any other placement of the same vertices (its local or its
 * screen positions), through the triangle a point falls in; a point off the mesh extends the nearest triangle. Guides
 * and handles live in raster pixels, so they ride the art wherever the mesh is drawn.
 */
internal class InteriorCanvasMap(val raster: FloatArray, val indices: IntArray) {
	init {
		require(raster.size % 2 == 0 && indices.size % 3 == 0 && indices.isNotEmpty())
	}

	val vertexCount get() = raster.size / 2

	/** Where [p] sits in the mesh, for placing it over [position]'s vertices. */
	fun bind(p: InteriorPoint): DeformPathPoint = DeformPathTools.bind(raster, indices, p.x, p.y)

	fun place(binding: DeformPathPoint, positions: FloatArray): Pair<Float, Float> = binding.position(positions)

	/** The raster point under ([x], [y]) where the vertices sit at [positions]. */
	fun toRaster(positions: FloatArray, x: Float, y: Float): InteriorPoint {
		require(positions.size == raster.size)
		val w = DeformPathTools.bind(positions, indices, x, y)
		val (rx, ry) = w.position(raster)
		return InteriorPoint(rx, ry)
	}

	companion object {
		/** [drawable]'s vertices at the source-raster pixels their texture coordinates sample, as the bake reads them. */
		fun of(model: PuppetModel, drawable: Drawable): InteriorCanvasMap? {
			val mesh = drawable.mesh ?: return null
			val texture = runCatching { LayerTexture.of(model, drawable).texture }.getOrNull() ?: return null
			val xy = FloatArray(mesh.vertexCount * 2)
			for (i in 0 until mesh.vertexCount) {
				val p = texture.rasterOfUv(mesh.uvs[i * 2], mesh.uvs[i * 2 + 1])
				xy[i * 2] = p[0]; xy[i * 2 + 1] = p[1]
			}
			return if (mesh.indices.isEmpty()) null else InteriorCanvasMap(xy, mesh.indices)
		}
	}
}
