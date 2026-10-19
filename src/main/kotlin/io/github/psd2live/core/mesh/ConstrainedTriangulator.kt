package io.github.psd2live.core.mesh

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Constrained Delaunay triangulation over indexed points: [loops] bound the domain (the first outer, the rest holes),
 * [constraints] are interior edges that must appear, and every other point is a Steiner point. Points are never moved;
 * [ConstrainedTriangulation.refine] is the only step that adds any. Deterministic for the same input order.
 */
internal object ConstrainedTriangulator {
	fun triangulate(points: List<Point>, loops: List<IntArray>, constraints: List<Edge>,
	                checkpoint: () -> Unit = {}): ConstrainedTriangulation? = attempt(points, loops, constraints, checkpoint).mesh

	/** [mesh] when the domain triangulates, otherwise why it did not. */
	class Attempt(val mesh: ConstrainedTriangulation?, val failure: String?)

	fun attempt(points: List<Point>, loops: List<IntArray>, constraints: List<Edge>, checkpoint: () -> Unit = {}): Attempt {
		fun fail(reason: String) = Attempt(null, reason)
		if (points.size < 3 || loops.isEmpty() || loops.any { it.size < 3 }) return fail("empty domain")
		val work = ConstrainedTriangulation(points.toMutableList(), loops)
		if (!work.build(checkpoint)) return fail("duplicate or degenerate points")
		val protected = LinkedHashSet<Edge>()
		for (loop in loops) for (i in loop.indices) protected += edgeOf(loop[i], loop[(i + 1) % loop.size])
		protected += constraints
		if (protected.any { it.low == it.high }) return fail("zero-length constraint")
		for (edge in protected) {
			checkpoint()
			if (!work.recover(edge, checkpoint)) return fail("constraint ${edge.low}-${edge.high} cannot be recovered")
		}
		work.protectedEdges += protected
		work.carve()
		work.legalize(checkpoint)
		return if (work.valid()) Attempt(work, null) else fail("invalid triangulation")
	}
}

internal class ConstrainedTriangulation(points: MutableList<Point>, private val loops: List<IntArray>) {
	private val pointList = points
	val points: List<Point> get() = pointList
	val protectedEdges = LinkedHashSet<Edge>()
	private val tris = ArrayList<IntArray?>()
	private val edges = HashMap<Long, IntArray>()
	private var realCount = points.size

	val triangles: List<Triangle>
		get() = tris.filterNotNull().map { Triangle(it[0], it[1], it[2]) }

	fun build(checkpoint: () -> Unit): Boolean {
		var lowX = Double.POSITIVE_INFINITY; var lowY = Double.POSITIVE_INFINITY
		var highX = Double.NEGATIVE_INFINITY; var highY = Double.NEGATIVE_INFINITY
		for (p in pointList) { lowX = min(lowX, p.x); lowY = min(lowY, p.y); highX = max(highX, p.x); highY = max(highY, p.y) }
		val span = max(highX - lowX, highY - lowY).coerceAtLeast(1.0) * 64
		val cx = (lowX + highX) / 2; val cy = (lowY + highY) / 2
		realCount = pointList.size
		pointList += Point(cx - span, cy - span); pointList += Point(cx + span, cy - span); pointList += Point(cx, cy + span)
		// Bowyer-Watson with cached circumcircles: the cavity of each insertion is the set of triangles whose circle
		// strictly holds the point, grown across any of its edges the point does not strictly see (collinear runs of
		// boundary points), then retriangulated as a fan from the point.
		val circles = ArrayList<DoubleArray?>()
		fun add(a: Int, b: Int, c: Int) { val t = ccw(a, b, c); circles += circumcircle(t); insert(t) }
		add(realCount, realCount + 1, realCount + 2)
		for (index in 0 until realCount) {
			if (index % 64 == 0) checkpoint()
			val p = pointList[index]
			val bad = LinkedHashSet<Int>()
			for (t in tris.indices) {
				val circle = circles[t] ?: continue
				val dx = p.x - circle[0]; val dy = p.y - circle[1]
				if (dx * dx + dy * dy < circle[2] * (1 - 1e-12)) bad += t
			}
			if (bad.isEmpty()) return false
			var rim: List<IntArray>
			while (true) {
				rim = bad.flatMap { t -> val tri = tris[t]!!; (0..2).map { intArrayOf(tri[it], tri[(it + 1) % 3]) } }
					.filter { e -> edges.getValue(key(e[0], e[1])).count { it >= 0 && it in bad } == 1 }
				val blind = rim.firstOrNull { cross(pointList[it[0]], pointList[it[1]], p) <= 1e-9 } ?: break
				val across = edges.getValue(key(blind[0], blind[1])).firstOrNull { it >= 0 && it !in bad } ?: return false
				bad += across
			}
			for (t in bad) { remove(t); circles[t] = null }
			for (edge in rim) add(edge[0], edge[1], index)
			if (index % 256 == 255) {
				val alive = tris.indices.filter { tris[it] != null }
				val kept = alive.map { circles[it] }
				compact(); circles.clear(); circles.addAll(kept)
			}
		}
		compact()
		return true
	}

	private fun compact() {
		val alive = tris.filterNotNull()
		tris.clear(); edges.clear()
		alive.forEach { insert(it) }
	}

	private fun insert(t: IntArray): Int {
		val id = tris.size
		tris += t
		for (k in 0..2) {
			val entry = edges.getOrPut(key(t[k], t[(k + 1) % 3])) { intArrayOf(-1, -1) }
			if (entry[0] < 0) entry[0] = id else entry[1] = id
		}
		return id
	}

	private fun remove(id: Int) {
		val t = tris[id] ?: return
		for (k in 0..2) {
			val edgeKey = key(t[k], t[(k + 1) % 3])
			val entry = edges[edgeKey] ?: continue
			if (entry[0] == id) { entry[0] = entry[1]; entry[1] = -1 } else if (entry[1] == id) entry[1] = -1
			if (entry[0] < 0) edges.remove(edgeKey)
		}
		tris[id] = null
	}

	private fun ccw(a: Int, b: Int, c: Int): IntArray =
		if (cross(pointList[a], pointList[b], pointList[c]) >= 0) intArrayOf(a, b, c) else intArrayOf(a, c, b)

	private fun circumcircle(t: IntArray): DoubleArray? {
		val a = pointList[t[0]]; val b = pointList[t[1]]; val c = pointList[t[2]]
		val d = 2 * (a.x * (b.y - c.y) + b.x * (c.y - a.y) + c.x * (a.y - b.y))
		if (abs(d) < 1e-14) return null
		val a2 = a.x * a.x + a.y * a.y; val b2 = b.x * b.x + b.y * b.y; val c2 = c.x * c.x + c.y * c.y
		val ux = (a2 * (b.y - c.y) + b2 * (c.y - a.y) + c2 * (a.y - b.y)) / d
		val uy = (a2 * (c.x - b.x) + b2 * (a.x - c.x) + c2 * (b.x - a.x)) / d
		return doubleArrayOf(ux, uy, (a.x - ux) * (a.x - ux) + (a.y - uy) * (a.y - uy))
	}

	/** Makes [edge] an edge of the triangulation by flipping the edges it crosses (Sloan). */
	fun recover(edge: Edge, checkpoint: () -> Unit): Boolean {
		if (key(edge.low, edge.high) in edges) return true
		val u = pointList[edge.low]; val v = pointList[edge.high]
		// A point on the open segment would have to split it; the caller places its points so none does.
		for (i in pointList.indices) if (i != edge.low && i != edge.high && strictlyOnSegment(pointList[i], u, v)) return false
		val queue = ArrayDeque(edges.keys.filter { k ->
			val a = (k ushr 32).toInt(); val b = k.toInt()
			a != edge.low && a != edge.high && b != edge.low && b != edge.high &&
				segmentsProperlyIntersect(u, v, pointList[a], pointList[b])
		}.sorted())
		var guard = queue.size * queue.size * 4 + 64
		while (queue.isNotEmpty()) {
			if (--guard < 0) return false
			if (guard % 256 == 0) checkpoint()
			val k = queue.removeFirst()
			val entry = edges[k] ?: continue
			if (entry[1] < 0) return false
			val a = (k ushr 32).toInt(); val b = k.toInt()
			val c = opposite(tris[entry[0]]!!, a, b); val d = opposite(tris[entry[1]]!!, a, b)
			if (!convexQuad(a, c, b, d)) { queue += k; continue }
			flip(k)
			val flipped = key(c, d)
			if (c != edge.low && c != edge.high && d != edge.low && d != edge.high &&
				segmentsProperlyIntersect(u, v, pointList[c], pointList[d])) queue += flipped
		}
		return key(edge.low, edge.high) in edges
	}

	private fun opposite(t: IntArray, a: Int, b: Int): Int = t.first { it != a && it != b }

	private fun convexQuad(a: Int, c: Int, b: Int, d: Int): Boolean {
		// Diagonal c-d must cross a-b strictly for the quad a,c,b,d to be convex.
		return segmentsProperlyIntersect(pointList[a], pointList[b], pointList[c], pointList[d])
	}

	private fun flip(k: Long) {
		val entry = edges.getValue(k)
		val t1 = entry[0]; val t2 = entry[1]
		val a = (k ushr 32).toInt(); val b = k.toInt()
		val c = opposite(tris[t1]!!, a, b); val d = opposite(tris[t2]!!, a, b)
		remove(t1); remove(t2)
		insert(ccw(c, d, a)); insert(ccw(d, c, b))
	}

	/** Drops the super triangle and everything outside the loops. */
	fun carve() {
		for (id in tris.indices) {
			val t = tris[id] ?: continue
			if (t.any { it >= realCount }) { remove(id); continue }
			val centroid = Point((pointList[t[0]].x + pointList[t[1]].x + pointList[t[2]].x) / 3,
				(pointList[t[0]].y + pointList[t[1]].y + pointList[t[2]].y) / 3)
			if (!inDomain(centroid, loops.map { loop -> loop.map { pointList[it] } })) remove(id)
		}
		repeat(3) { pointList.removeAt(pointList.lastIndex) }
		compact()
	}

	/** Lawson flips of unprotected edges until every one is locally Delaunay. */
	fun legalize(checkpoint: () -> Unit) {
		var guard = edges.size * 32 + 64
		var changed = true
		while (changed && guard > 0) {
			changed = false
			checkpoint()
			for (k in edges.keys.sorted()) {
				val entry = edges[k] ?: continue
				if (entry[1] < 0) continue
				val a = (k ushr 32).toInt(); val b = k.toInt()
				if (edgeOf(a, b) in protectedEdges) continue
				val c = opposite(tris[entry[0]]!!, a, b); val d = opposite(tris[entry[1]]!!, a, b)
				if (!convexQuad(a, c, b, d)) continue
				val circle = circumcircle(tris[entry[0]]!!) ?: continue
				val p = pointList[d]
				val dx = p.x - circle[0]; val dy = p.y - circle[1]
				if (dx * dx + dy * dy < circle[2] * (1 - 1e-9)) { flip(k); changed = true; if (--guard <= 0) break }
			}
		}
	}

	/**
	 * Splits unprotected interior edges longer than [ratio] times [size] at their midpoint, longest first, up to
	 * [budget] new points, keeping the triangulation Delaunay between passes. Returns the number of points added.
	 */
	fun refine(size: (Point) -> Double, ratio: Double, budget: Int, checkpoint: () -> Unit = {}): Int {
		var added = 0
		repeat(6) {
			checkpoint()
			val long = edges.entries.filter { (k, entry) ->
				val a = (k ushr 32).toInt(); val b = k.toInt()
				entry[1] >= 0 && edgeOf(a, b) !in protectedEdges &&
					distance(pointList[a], pointList[b]) > ratio * size(lerp(pointList[a], pointList[b], .5))
			}.map { it.key }.sortedWith(compareByDescending<Long> { k ->
				distance(pointList[(k ushr 32).toInt()], pointList[k.toInt()]) }.thenBy { it })
			if (long.isEmpty()) return added
			for (k in long) {
				if (added >= budget) return added
				if (split(k)) added++
			}
			legalize(checkpoint)
		}
		return added
	}

	private fun split(k: Long): Boolean {
		val entry = edges[k] ?: return false
		if (entry[1] < 0) return false
		val a = (k ushr 32).toInt(); val b = k.toInt()
		val c = opposite(tris[entry[0]]!!, a, b); val d = opposite(tris[entry[1]]!!, a, b)
		val m = pointList.size
		pointList += snap(lerp(pointList[a], pointList[b], .5))
		val t1 = entry[0]; val t2 = entry[1]
		remove(t1); remove(t2)
		insert(ccw(a, m, c)); insert(ccw(m, b, c)); insert(ccw(b, m, d)); insert(ccw(m, a, d))
		return true
	}

	/** Every triangle has area, every edge is used at most twice, and every protected edge exists. */
	fun valid(): Boolean {
		for (t in tris) if (t != null && cross(pointList[t[0]], pointList[t[1]], pointList[t[2]]) <= 1e-9) return false
		if (edges.values.any { it[0] < 0 }) return false
		return protectedEdges.all { key(it.low, it.high) in edges }
	}

	private fun strictlyOnSegment(p: Point, a: Point, b: Point): Boolean {
		if (distanceSquared(p, a) < 1e-12 || distanceSquared(p, b) < 1e-12) return false
		return pointOnSegment(p, a, b)
	}

	private fun key(a: Int, b: Int): Long {
		val low = min(a, b); val high = max(a, b)
		return (low.toLong() shl 32) or high.toLong()
	}
}
