package io.github.psd2live.core.mesh.structure

import io.github.psd2live.core.mesh.*
import kotlin.math.*

/**
 * The part of an existing mesh that a structure remesh keeps: every triangle whose vertices all lie within [rows]
 * rings of the boundary (the outline row and, for edge bands, the rows inside it). The rest is rebuilt one connected
 * [Component] at a time inside loops of kept vertices, so the kept part never moves.
 */
internal class PreservedRim private constructor(val positions: List<Point>, val indices: IntArray, val rows: Int,
                                                val keptTriangles: List<Int>, val components: List<Component>) {
	/** Removed triangles that share edges, bounded by [loops] of old vertex indices (outer first). */
	/** [spacing]: the region's inner edge length; [rimSpacing]: that of the rows around it. */
	class Component(val triangles: List<Int>, val loops: List<IntArray>, val spacing: Double, val rimSpacing: Double)

	val domain: List<List<Point>> get() = components.flatMap { c -> c.loops.map { loop -> loop.map { positions[it] } } }

	companion object {
		/** [positions] are source pixels (x, y pairs); [rows] edge-band rows to keep inside the outline row. */
		fun of(positions: FloatArray, indices: IntArray, rows: Int): PreservedRim {
			require(rows in 0..4) { "rows must be within 0..4" }
			val points = List(positions.size / 2) { Point(positions[it * 2].toDouble(), positions[it * 2 + 1].toDouble()) }
			val triangles = indices.size / 3
			val uses = HashMap<Edge, Int>()
			for (t in 0 until triangles) for (k in 0..2) uses.merge(edgeOf(indices[t * 3 + k], indices[t * 3 + (k + 1) % 3]), 1, Int::plus)
			val neighbours = Array(points.size) { sortedSetOf<Int>() }
			for (edge in uses.keys) { neighbours[edge.low] += edge.high; neighbours[edge.high] += edge.low }
			val depth = IntArray(points.size) { Int.MAX_VALUE }
			val queue = ArrayDeque<Int>()
			for ((edge, count) in uses) if (count == 1) for (v in listOf(edge.low, edge.high)) if (depth[v] != 0) { depth[v] = 0; queue += v }
			while (queue.isNotEmpty()) {
				val v = queue.removeFirst()
				for (n in neighbours[v]) if (depth[n] == Int.MAX_VALUE) { depth[n] = depth[v] + 1; queue += n }
			}
			val kept = ArrayList<Int>(); val removed = ArrayList<Int>()
			for (t in 0 until triangles) {
				if ((0..2).all { depth[indices[t * 3 + it]] <= rows }) kept += t else removed += t
			}
			// Components of removed triangles across shared edges.
			val byEdge = HashMap<Edge, MutableList<Int>>()
			for (t in removed) for (k in 0..2) byEdge.getOrPut(edgeOf(indices[t * 3 + k], indices[t * 3 + (k + 1) % 3])) { ArrayList() } += t
			val component = HashMap<Int, Int>()
			val groups = ArrayList<List<Int>>()
			for (seed in removed) {
				if (seed in component) continue
				val group = ArrayList<Int>(); val stack = ArrayDeque(listOf(seed)); component[seed] = groups.size
				while (stack.isNotEmpty()) {
					val t = stack.removeLast(); group += t
					for (k in 0..2) for (o in byEdge.getValue(edgeOf(indices[t * 3 + k], indices[t * 3 + (k + 1) % 3])))
						if (o !in component) { component[o] = groups.size; stack += o }
				}
				groups += group.sorted()
			}
			val components = ArrayList<Component>()
			val keptAll = ArrayList(kept)
			for (group in groups) {
				val loops = loops(group, indices, points)
				if (loops == null) { keptAll += group; continue }
				// The region's own spacing is that of its inner edges; the loop rows are traced finer.
				val rim = loops.flatMap { it.toList() }.toSet()
				val edges = group.flatMap { t -> (0..2).map { k -> indices[t * 3 + k] to indices[t * 3 + (k + 1) % 3] } }
				val inner = edges.filter { (a, b) -> a !in rim && b !in rim }.ifEmpty { edges }
				val lengths = inner.map { (a, b) -> distance(points[a], points[b]) }.sorted()
				val rimLengths = loops.flatMap { loop -> loop.indices.map { distance(points[loop[it]], points[loop[(it + 1) % loop.size]]) } }.sorted()
				components += Component(group, loops, lengths[lengths.size / 2], rimLengths[rimLengths.size / 2])
			}
			return PreservedRim(points, indices, rows, keptAll.sorted(), components)
		}

		/** Boundary loops of a triangle set, outer first, or null when a vertex pinches the region. */
		private fun loops(group: List<Int>, indices: IntArray, points: List<Point>): List<IntArray>? {
			val directed = HashMap<Edge, Pair<Int, Int>>()
			val count = HashMap<Edge, Int>()
			for (t in group) for (k in 0..2) {
				val a = indices[t * 3 + k]; val b = indices[t * 3 + (k + 1) % 3]
				count.merge(edgeOf(a, b), 1, Int::plus); directed[edgeOf(a, b)] = a to b
			}
			val next = HashMap<Int, Int>()
			for ((edge, c) in count) if (c == 1) {
				val (a, b) = directed.getValue(edge)
				if (a in next) return null
				next[a] = b
			}
			val loops = ArrayList<IntArray>()
			val seen = HashSet<Int>()
			for (start in next.keys.sorted()) {
				if (start in seen) continue
				val loop = ArrayList<Int>()
				var v = start
				while (v !in seen) { seen += v; loop += v; v = next[v] ?: return null }
				if (v != start || loop.size < 3) return null
				loops += loop.toIntArray()
			}
			if (loops.isEmpty()) return null
			val outer = loops.indices.maxBy { abs(signedAreaTwice(loops[it].map(points::get))) }
			return listOf(loops[outer]) + loops.filterIndexed { i, _ -> i != outer }
		}
	}
}

internal data class StructureMeshSettings(
	val structure: StructureSettings = StructureSettings(),
	/** Vertex spacing along structure lines, relative to the spacing of the kept edge rows. */
	val near: Double = 1.0,
	/** With [fill], edge length away from the lines, relative to the region's former inner edge length. */
	val far: Double = 1.0,
	/**
	 * Steiner points between the lines. Off by default: the mesh then has vertices only on the edge rows and on the
	 * lines, as hand-made meshes do, and its triangles span from line to line.
	 */
	val fill: Boolean = false,
	/** A row on each side of a drawn crease with rungs across, so the crease can fold; off, a crease is one row. */
	val bands: Boolean = false,
	val maxVertices: Int = 4000,
) {
	init {
		require(near in .25..4.0) { "near_density must be within 0.25..4" }
		require(far in .5..3.0) { "far_density must be within 0.5..3" }
		require(maxVertices in 16..16_000) { "max_vertices must be within 16..16000" }
	}
}

/**
 * A new triangle set over [points] (source pixels): the old vertices that were kept keep their old index in [kept]
 * (-1 for new points). [lines] lists, for each structure line the mesh follows, the vertex indices of its row.
 */
internal class StructureMesh(val points: List<Point>, val triangles: IntArray, val kept: IntArray,
                             val lines: List<Placed>, val bands: Int, val rows: Int) {
	class Placed(val line: StructureLine, val vertices: IntArray, val banded: Boolean)
}

/**
 * Rebuilds the removed part of a [PreservedRim] so the topology follows [StructureAnalysis] lines: each line becomes
 * a row of vertices whose edges run along it; a stroke crease gets a row on each side and rungs across, split by
 * diagonals that all lean the same way, so the material can bend along the crease; elsewhere vertices thin out with
 * distance from the lines. Weaker lines stop short of stronger ones rather than cross them.
 */
internal object StructureMesher {
	class Failure(message: String) : IllegalArgumentException(message)

	fun mesh(rim: PreservedRim, analysis: StructureAnalysis, settings: StructureMeshSettings = StructureMeshSettings(),
	         checkpoint: () -> Unit = {}): StructureMesh {
		if (rim.components.isEmpty()) throw Failure("Structure remesh failed: the mesh has no interior to rebuild")
		val points = ArrayList<Point>()
		val kept = ArrayList<Int>()
		val oldToNew = HashMap<Int, Int>()
		fun keep(old: Int): Int = oldToNew.getOrPut(old) { points += rim.positions[old]; kept += old; points.size - 1 }
		for (t in rim.keptTriangles) for (k in 0..2) keep(rim.indices[t * 3 + k])
		for (c in rim.components) for (loop in c.loops) loop.forEach { keep(it) }
		val triangles = ArrayList<Int>()
		for (t in rim.keptTriangles) for (k in 0..2) triangles += oldToNew.getValue(rim.indices[t * 3 + k])
		val placed = ArrayList<StructureMesh.Placed>()
		var bands = 0
		val budget = settings.maxVertices
		for (component in rim.components) {
			checkpoint()
			val built = component(component, rim, analysis.lines, settings, budget - points.size, checkpoint)
				?: throw Failure("Structure remesh failed: an interior region cannot be triangulated")
			val local = IntArray(built.mesh.points.size) { i ->
				if (i < built.loopCount) oldToNew.getValue(built.loopOld[i]) else { points += built.mesh.points[i]; kept += -1; points.size - 1 }
			}
			for (t in built.mesh.triangles) { triangles += local[t.a]; triangles += local[t.b]; triangles += local[t.c] }
			placed += built.placed.map { StructureMesh.Placed(it.line, it.vertices.map { v -> local[v] }.toIntArray(), it.banded) }
			bands += built.placed.count { it.banded }
		}
		if (points.size > budget) throw Failure("Structure remesh failed: ${points.size} vertices exceed the limit of $budget")
		if (points.size >= 65_535) throw Failure("Structure remesh failed: too many vertices")
		val result = orient(points, triangles.toIntArray(), rim)
		validate(points, result, rim)
		return StructureMesh(points, result, kept.toIntArray(), placed, bands, rim.rows)
	}

	private class Built(val mesh: ConstrainedTriangulation, val loopCount: Int, val loopOld: IntArray, val placed: List<Placed>)
	private class Placed(val line: StructureLine, val vertices: IntArray, val banded: Boolean)

	/** A line as the mesh will follow it: stations along it, and when banded the rows on either side. */
	private class Run(val line: StructureLine, val stations: List<Point>, val left: List<Point>?, val right: List<Point>?) {
		val segments: List<Pair<Point, Point>> get() = buildList {
			addAll(stations.zipWithNext())
			if (left != null && right != null) {
				for (side in listOf(left, right)) {
					addAll(side.zipWithNext())
					add(stations.first() to side.first()); add(side.last() to stations.last())
					for (i in side.indices) add(side[i] to stations[i + 1])
					for (i in 0 until side.size - 1) add(side[i] to stations[i + 2])
				}
			}
		}
		val all: List<Point> get() = stations + (left ?: emptyList()) + (right ?: emptyList())
	}

	private fun component(component: PreservedRim.Component, rim: PreservedRim, lines: List<StructureLine>,
	                      settings: StructureMeshSettings, budget: Int, checkpoint: () -> Unit): Built? {
		val loopOld = component.loops.flatMap { it.toList() }.toIntArray()
		val loopPoints = component.loops.map { loop -> loop.map { rim.positions[it] } }
		val near = component.rimSpacing * settings.near
		val far = max(near, component.spacing * settings.far)
		val boundary = SegmentIndex(loopPoints, far)
		val order = lines.sortedWith(compareByDescending<StructureLine> { it.strength }.thenBy { it.id })
		// Bands first, then single rows, then no lines at all: the first that triangulates wins.
		for (mode in (if (settings.bands) 0 else 1)..2) {
			checkpoint()
			val runs = if (mode == 2) emptyList() else place(order, loopPoints, boundary, near, mode == 0, checkpoint)
			val attempt = triangulate(component, rim, loopPoints, loopOld, runs, near, far, settings.fill, budget, checkpoint)
			if (attempt != null) return attempt
		}
		return null
	}

	private fun place(lines: List<StructureLine>, loops: List<List<Point>>, boundary: SegmentIndex, near: Double,
	                  bands: Boolean, checkpoint: () -> Unit): List<Run> {
		val accepted = ArrayList<Run>()
		val segments = ArrayList<Pair<Point, Point>>()
		fun clearOfOthers(p: Point, gap: Double) = segments.none { (a, b) -> distanceSquaredToSegment(p, a, b) < gap * gap }
		for (line in lines) {
			checkpoint()
			val banded = bands && line.banded && line.kind == StructureKind.FOLD
			val offset = (1.5 * line.width).coerceIn(.35 * near, .8 * near)
			val clearance = .6 * near + if (banded) offset else 0.0
			// Fine samples along the line, cut wherever the line leaves the region or nears a stronger line.
			val fine = resample(line.points, line.closed, near / 4)
			val pieces = ArrayList<List<Point>>()
			var current = ArrayList<Point>()
			for (p in fine) {
				val ok = inDomain(p, loops) && boundary.distance(p) >= clearance && clearOfOthers(p, .75 * near + if (banded) offset else 0.0)
				if (ok) current += p else { if (current.size > 1) pieces += current; current = ArrayList() }
			}
			if (current.size > 1) pieces += current
			for (piece in pieces) {
				val stations = resample(piece, false, near)
				if (stations.size < 3 || pathLength(stations) < 2 * near) continue
				var run = Run(line, stations, null, null)
				if (banded && stations.size >= 4) run = band(line, stations, offset, loops, boundary, segments, near) ?: run
				if (!selfClear(run)) continue
				accepted += run
				segments += run.segments
			}
		}
		return accepted
	}

	/** Side rows at [offset] beside the inner stations; the end stations stay single, so the band tapers to tips. */
	private fun band(line: StructureLine, stations: List<Point>, offset: Double, loops: List<List<Point>>, boundary: SegmentIndex,
	                 others: List<Pair<Point, Point>>, near: Double): Run? {
		val left = ArrayList<Point>(); val right = ArrayList<Point>()
		for (i in 1 until stations.size - 1) {
			val a = stations[i - 1]; val b = stations[i + 1]
			val dx = b.x - a.x; val dy = b.y - a.y
			val norm = hypot(dx, dy).coerceAtLeast(1e-9)
			val nx = -dy / norm * offset; val ny = dx / norm * offset
			val l = snap(Point(stations[i].x + nx, stations[i].y + ny)); val r = snap(Point(stations[i].x - nx, stations[i].y - ny))
			for (p in listOf(l, r)) if (!inDomain(p, loops) || boundary.distance(p) < .4 * near ||
				others.any { (u, v) -> distanceSquaredToSegment(p, u, v) < .16 * near * near }) return null
			left += l; right += r
		}
		return Run(line, stations, left, right)
	}

	/** No two segments of a run cross, and its rows keep apart where the line curves tightly. */
	private fun selfClear(run: Run): Boolean {
		val segments = run.segments
		for (i in segments.indices) for (j in i + 1 until segments.size) {
			val (a, b) = segments[i]; val (c, d) = segments[j]
			if (segmentsProperlyIntersect(a, b, c, d)) return false
		}
		val all = run.all
		for (i in all.indices) for (j in i + 1 until all.size) if (distanceSquared(all[i], all[j]) < 1e-6) return false
		return true
	}

	private fun triangulate(component: PreservedRim.Component, rim: PreservedRim, loops: List<List<Point>>, loopOld: IntArray,
	                        runs: List<Run>, near: Double, far: Double, fill: Boolean, budget: Int, checkpoint: () -> Unit): Built? {
		val points = ArrayList<Point>(loopOld.map { rim.positions[it] })
		val loopIndices = ArrayList<IntArray>()
		var offset = 0
		for (loop in component.loops) { loopIndices += IntArray(loop.size) { offset + it }; offset += loop.size }
		val constraints = ArrayList<Edge>()
		val placed = ArrayList<Placed>()
		for (run in runs) {
			fun add(list: List<Point>): IntArray = IntArray(list.size) { points += list[it]; points.size - 1 }
			val center = add(run.stations)
			for (i in 0 until center.size - 1) constraints += edgeOf(center[i], center[i + 1])
			if (run.left != null && run.right != null) {
				for (side in listOf(add(run.left), add(run.right))) {
					for (i in 0 until side.size - 1) constraints += edgeOf(side[i], side[i + 1])
					constraints += edgeOf(center.first(), side.first()); constraints += edgeOf(side.last(), center.last())
					// Rungs straight across the crease, and diagonals that all lean forward.
					for (i in side.indices) constraints += edgeOf(side[i], center[i + 1])
					for (i in 0 until side.size - 1) constraints += edgeOf(side[i], center[i + 2])
				}
			}
			placed += Placed(run.line, center, run.left != null)
		}
		val lineSegments = runs.flatMap { it.segments }
		val structure = if (lineSegments.isEmpty()) null else SegmentIndex(lineSegments.map { listOf(it.first, it.second) }, near)
		val boundary = SegmentIndex(loops, far)
		val falloff = 2.5 * far
		fun size(p: Point): Double {
			val d = structure?.distance(p) ?: Double.POSITIVE_INFINITY
			val t = (d / falloff).coerceIn(0.0, 1.0)
			return near + (far - near) * t * t * (3 - 2 * t)
		}
		if (fill) points += fill(loops, boundary, structure, ::size, near, budget - points.size, checkpoint)
		if (points.size > budget) return null
		val attempt = ConstrainedTriangulator.attempt(points, loopIndices, constraints, checkpoint)
		val mesh = attempt.mesh ?: return null
		if (fill) mesh.refine(::size, 1.5, (budget - mesh.points.size).coerceAtLeast(0), checkpoint)
		if (!mesh.valid()) return null
		return Built(mesh, loopOld.size, loopOld, placed)
	}

	/**
	 * Steiner points thinning out with distance from the lines: candidates on a fine lattice, jittered and ordered by a
	 * hash so the result is the same every time, nearest the structure first, each kept when no kept point, line or
	 * loop lies within its own spacing.
	 */
	private fun fill(loops: List<List<Point>>, boundary: SegmentIndex, structure: SegmentIndex?, size: (Point) -> Double,
	                 near: Double, budget: Int, checkpoint: () -> Unit): List<Point> {
		val all = loops.flatten()
		val minX = all.minOf { it.x }; val maxX = all.maxOf { it.x }; val minY = all.minOf { it.y }; val maxY = all.maxOf { it.y }
		val step = near / 2
		class Candidate(val p: Point, val d: Double, val key: Long)
		val candidates = ArrayList<Candidate>()
		var gy = 0
		var y = minY + step / 2
		while (y < maxY) {
			checkpoint()
			var gx = 0
			var x = minX + step / 2
			while (x < maxX) {
				val h = hash(gx, gy)
				val p = snap(Point(x + ((h and 1023) / 1023.0 - .5) * step * .8, y + (((h shr 10) and 1023) / 1023.0 - .5) * step * .8))
				if (inDomain(p, loops)) candidates += Candidate(p, structure?.distance(p) ?: Double.POSITIVE_INFINITY, h)
				x += step; gx++
			}
			y += step; gy++
		}
		candidates.sortWith(compareBy<Candidate> { floor(min(it.d, 1e9) / near) }.thenBy { it.key })
		val cell = near * .5
		val grid = HashMap<Long, MutableList<Point>>()
		fun cellOf(v: Double, origin: Double) = floor((v - origin) / cell).toLong()
		val kept = ArrayList<Point>()
		for (c in candidates) {
			if (kept.size >= budget) break
			val h = size(c.p)
			if (boundary.distance(c.p) < .6 * h) continue
			if (c.d < max(.55 * h, .5 * near)) continue
			val radius = h
			val cx = cellOf(c.p.x, minX); val cy = cellOf(c.p.y, minY)
			val reach = ceil(radius / cell).toLong()
			var clear = true
			loop@ for (yy in cy - reach..cy + reach) for (xx in cx - reach..cx + reach) {
				for (q in grid[(yy shl 32) xor (xx and 0xffffffffL)] ?: continue) if (distanceSquared(q, c.p) < radius * radius) { clear = false; break@loop }
			}
			if (!clear) continue
			kept += c.p
			grid.getOrPut((cy shl 32) xor (cx and 0xffffffffL)) { ArrayList() } += c.p
		}
		return kept
	}

	private fun hash(x: Int, y: Int): Long {
		var h = x.toLong() * 0x9E3779B97F4A7C15uL.toLong() xor (y.toLong() * 0xC2B2AE3D27D4EB4FuL.toLong())
		h = (h xor (h ushr 31)) * 0xBF58476D1CE4E5B9uL.toLong()
		return (h xor (h ushr 29)) and Long.MAX_VALUE
	}

	private fun resample(points: List<Point>, closed: Boolean, step: Double): List<Point> {
		val path = if (closed) points + points.first() else points
		val total = pathLength(path)
		if (total < 1e-9) return listOf(snap(points.first()))
		val count = max(1, (total / step).roundToInt())
		val out = ArrayList<Point>()
		var segment = 0; var walked = 0.0
		for (k in 0..count) {
			if (closed && k == count) break
			val target = total * k / count
			while (segment < path.size - 2 && walked + distance(path[segment], path[segment + 1]) < target) {
				walked += distance(path[segment], path[segment + 1]); segment++
			}
			val length = distance(path[segment], path[segment + 1])
			out += snap(lerp(path[segment], path[segment + 1], if (length < 1e-12) 0.0 else ((target - walked) / length).coerceIn(0.0, 1.0)))
		}
		return out
	}

	private fun pathLength(points: List<Point>) = points.zipWithNext().sumOf { (a, b) -> distance(a, b) }

	/** Every new triangle winds the way the kept ones (or most old ones) do. */
	private fun orient(points: List<Point>, triangles: IntArray, rim: PreservedRim): IntArray {
		var sign = 0.0
		for (t in 0 until rim.indices.size / 3) sign += cross(rim.positions[rim.indices[t * 3]], rim.positions[rim.indices[t * 3 + 1]], rim.positions[rim.indices[t * 3 + 2]]).sign
		val positive = sign >= 0
		val out = triangles.copyOf()
		for (t in 0 until out.size / 3) {
			val area = cross(points[out[t * 3]], points[out[t * 3 + 1]], points[out[t * 3 + 2]])
			if ((area > 0) != positive) { val swap = out[t * 3 + 1]; out[t * 3 + 1] = out[t * 3 + 2]; out[t * 3 + 2] = swap }
		}
		return out
	}

	/** Same Euler characteristic as before, no degenerate face, every edge used once or twice. */
	private fun validate(points: List<Point>, triangles: IntArray, rim: PreservedRim) {
		fun euler(vertices: Int, indices: IntArray): Int {
			val edges = HashMap<Edge, Int>()
			for (t in 0 until indices.size / 3) for (k in 0..2) edges.merge(edgeOf(indices[t * 3 + k], indices[t * 3 + (k + 1) % 3]), 1, Int::plus)
			if (edges.values.any { it > 2 }) throw Failure("Structure remesh failed: a non-manifold edge")
			return vertices - edges.size + indices.size / 3
		}
		for (t in 0 until triangles.size / 3) {
			if (abs(cross(points[triangles[t * 3]], points[triangles[t * 3 + 1]], points[triangles[t * 3 + 2]])) < 1e-9)
				throw Failure("Structure remesh failed: a degenerate triangle")
		}
		val used = rim.indices.toSet().size
		if (euler(points.size, triangles) != euler(used, rim.indices)) throw Failure("Structure remesh failed: the topology changed")
	}
}
