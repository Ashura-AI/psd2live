package org.umamo.edit

import org.umamo.runtime.model.DrawableMesh
import kotlin.math.abs

/**
 * The shapes an edge flip has to stay valid in, beside the rest mesh, all in the mesh's vertex order.
 *
 * A flip re-joins the four corners of a quad across its other diagonal. That is only safe when the quad is convex in
 * EVERY shape the mesh takes: convex at rest but concave in a keyform, the two new triangles overlap and one turns
 * over as the parameter moves. So [forms] carries the rest positions plus each keyform cell's and each blend form's
 * deltas, and a flip that is not convex in one of them is not taken.
 *
 * [judge] is the space triangle quality is judged in - the mesh as it shows on the canvas at the default pose. A mesh
 * under a Warp lives in the lattice's 0..1 square, stretched unevenly; judging Delaunay there would favour the wrong
 * diagonal. Without one, the rest positions are used.
 *
 * @property FloatArray? judge The positions quality is judged in, or null for the rest positions.
 * @property List<FloatArray> forms The positions of every other shape a flip must keep convex.
 */
class MeshShapes(val judge: FloatArray? = null, val forms: List<FloatArray> = emptyList()) {
	/** These shapes for a mesh grown by [sources]: each new vertex is the same weighted combination in every shape. */
	fun derived(sources: List<VertexSource>): MeshShapes =
		MeshShapes(judge?.let { derive(it, sources) }, forms.map { derive(it, sources) })

	private fun derive(frame: FloatArray, sources: List<VertexSource>): FloatArray {
		val out = FloatArray(sources.size * 2)
		fun add(target: Int, index: Int, weight: Float) {
			if (index * 2 + 1 >= frame.size) return
			out[target * 2] += frame[index * 2] * weight
			out[target * 2 + 1] += frame[index * 2 + 1] * weight
		}
		sources.forEachIndexed { target, source ->
			when (source) {
				is VertexSource.FromOld -> add(target, source.oldIndex, 1f)
				is VertexSource.AverageOf -> source.oldIndices.forEach { add(target, it, 1f / source.oldIndices.size) }
				is VertexSource.LerpOf -> { add(target, source.oldA, 1f - source.t); add(target, source.oldB, source.t) }
				is VertexSource.BarycentricOf -> { add(target, source.oldA, source.wa); add(target, source.oldB, source.wb); add(target, source.oldC, source.wc) }
				is VertexSource.WeightedOf -> source.indices.indices.forEach { add(target, source.indices[it], source.weights[it]) }
			}
		}
		return out
	}
}

/**
 * Hand placement of mesh points: the ops a point-by-point mesh tool needs on top of [MeshRefinementOps] and
 * [MeshTopologyOps] - adding free points, deleting edges and re-triangulating - each a pure builder that returns a
 * [TopologyOpResult] for `withMeshTopologyEdit`, or null to refuse.
 *
 * Every result is a legal triangulation by the rules [isLegal] checks: each edge is used by one or two triangles,
 * every vertex is used, no triangle is degenerate and no op pinches a vertex into two fans. An op that would break
 * one of these refuses as a whole rather than committing a partial edit. Flips are judged per quad, whatever way its
 * two triangles are wound, and each new triangle keeps the winding of the one it replaces.
 *
 * 手動でメッシュの頂点を置く操作（点の追加・辺の削除・再三角形分割）。
 */
object MeshManualOps {
	/**
	 * Adds a vertex at each of [points], given in the mesh's own space, and tidies the slivers the inserts make.
	 *
	 * A point inside the mesh splits the triangle or edge it lands on; a point outside grows the silhouette with one
	 * triangle from the nearest visible outline edge. Either way the new vertex is an affine combination of existing
	 * ones, so its UV, keyform deltas and weights follow from the same weights and the picture does not move. A point
	 * on an existing vertex (or repeated in [points]) is skipped. The interior edges around the created vertices are
	 * then flipped toward Delaunay where that keeps the texture mapping exact and every shape in [shapes] convex.
	 *
	 * @param DrawableMesh mesh The mesh to edit.
	 * @param List<Pair<Float, Float>> points The positions to create vertices at, in order.
	 * @param MeshShapes shapes The shapes the tidying flips must keep valid, in the mesh's current vertex order.
	 * @return TopologyOpResult? The edit and the created vertices, or null when no point could be placed.
	 */
	fun addPoints(mesh: DrawableMesh, points: List<Pair<Float, Float>>, shapes: MeshShapes = MeshShapes()): TopologyOpResult? {
		val duplicate = extent(mesh.positions) * 1e-5f
		val accepted = ArrayList<Pair<Float, Float>>(points.size)
		for ((x, y) in points) {
			if (!x.isFinite() || !y.isFinite()) continue
			val onVertex = (0 until mesh.vertexCount).any { abs(mesh.positions[it * 2] - x) <= duplicate && abs(mesh.positions[it * 2 + 1] - y) <= duplicate }
			val repeated = accepted.any { abs(it.first - x) <= duplicate && abs(it.second - y) <= duplicate }
			if (!onVertex && !repeated) accepted += x to y
		}
		if (accepted.isEmpty()) return null
		val inserted = MeshRefinementOps.insertPoints(mesh, mesh.positions, accepted, extend = true, edgeSnap = duplicate) ?: return null
		val edit = inserted.result.edit
		val created = inserted.placed.filterNotNullTo(LinkedHashSet())
		val grown = shapes.derived(edit.vertexSources)
		val tidy = Flips(edit.newMesh, grown).lawson(created)
		val result = DrawableMesh(edit.newMesh.positions, edit.newMesh.uvs, tidy)
		if (!keepsLegal(mesh, result)) return null
		return TopologyOpResult(MeshTopologyEdit(result, edit.vertexSources), created.mapTo(LinkedHashSet()) { MeshElement.Vertex(it) })
	}

	/**
	 * Deletes [edges] while keeping the mesh a triangulation.
	 *
	 * An interior edge is shared by two triangles; deleting it leaves their quad, which is joined again across its
	 * other diagonal - the edge flips. A quad that is concave at rest or in any of [shapes] has no other diagonal
	 * inside it, so that refuses. An outline edge belongs to one triangle, which is removed with it; a corner that no
	 * triangle uses any more goes too. A removal that would pinch a vertex between two separate fans, or remove the last
	 * triangle, refuses.
	 *
	 * Edges are taken in index order, so a batch is deterministic. Vertices that stay keep their order.
	 *
	 * @param DrawableMesh mesh The mesh to edit.
	 * @param Set<MeshElement.Edge> edges The edges to delete; every one must be an edge of the mesh.
	 * @param MeshShapes shapes The shapes a flip must keep convex.
	 * @return TopologyOpResult? The edit, or null when an edge is missing or cannot be deleted.
	 */
	fun deleteEdges(mesh: DrawableMesh, edges: Set<MeshElement.Edge>, shapes: MeshShapes = MeshShapes()): TopologyOpResult? {
		if (edges.isEmpty()) return null
		val flips = Flips(mesh, shapes)
		val pinchedBefore = pinchedVertices(mesh.indices, mesh.vertexCount)
		for (edge in edges.sortedWith(compareBy({ it.endpointLow }, { it.endpointHigh }))) {
			val owners = flips.owners(edge)
			when (owners.size) {
				1 -> flips.remove(owners.single())
				2 -> if (!flips.flip(edge, delaunay = false, exactTexture = false)) return null
				else -> return null
			}
		}
		val triangles = flips.triangles()
		if (triangles.isEmpty()) return null
		val used = BooleanArray(mesh.vertexCount)
		triangles.forEach { used[it] = true }
		val keep = (0 until mesh.vertexCount).filter { used[it] }
		val remap = IntArray(mesh.vertexCount) { -1 }
		keep.forEachIndexed { newIndex, oldIndex -> remap[oldIndex] = newIndex }
		val positions = FloatArray(keep.size * 2)
		val uvs = FloatArray(keep.size * 2)
		keep.forEachIndexed { newIndex, oldIndex ->
			for (component in 0..1) {
				positions[newIndex * 2 + component] = mesh.positions[oldIndex * 2 + component]
				uvs[newIndex * 2 + component] = mesh.uvs.getOrElse(oldIndex * 2 + component) { 0f }
			}
		}
		val indices = IntArray(triangles.size) { remap[triangles[it]] }
		val result = DrawableMesh(positions, uvs, indices)
		if (!keepsLegal(mesh, result)) return null
		// Pinching is judged per old vertex, so a mesh that already had a pinched vertex can still be edited elsewhere.
		val pinchedAfter = pinchedVertices(indices, keep.size).mapTo(HashSet()) { keep[it] }
		if (!pinchedBefore.containsAll(pinchedAfter)) return null
		return TopologyOpResult(MeshTopologyEdit(result, keep.map { VertexSource.FromOld(it) }), emptySet())
	}

	/**
	 * Flips the interior edges around [around] (every vertex when empty) toward a Delaunay triangulation of the
	 * current points, judged in [MeshShapes.judge], keeping the outline and every vertex. Only flips that keep the
	 * texture mapping exact and every shape convex are taken, so the picture does not move and no pose folds.
	 *
	 * @param DrawableMesh mesh The mesh to tidy.
	 * @param Set<Int> around The vertices whose surroundings are re-triangulated; empty for the whole mesh.
	 * @param MeshShapes shapes The shapes a flip must keep convex, and the space quality is judged in.
	 * @return TopologyOpResult? The edit, or null when the triangulation is already as good as it gets.
	 */
	fun retriangulate(mesh: DrawableMesh, around: Set<Int>, shapes: MeshShapes = MeshShapes()): TopologyOpResult? {
		val scope = around.filterTo(LinkedHashSet()) { it in 0 until mesh.vertexCount }.ifEmpty { (0 until mesh.vertexCount).toSet() }
		val indices = Flips(mesh, shapes).lawson(scope)
		if (indices.contentEquals(mesh.indices)) return null
		val result = DrawableMesh(mesh.positions, mesh.uvs, indices)
		if (!keepsLegal(mesh, result)) return null
		return TopologyOpResult(MeshTopologyEdit(result, List(mesh.vertexCount) { VertexSource.FromOld(it) }), emptySet())
	}

	/** The vertices on the outline: the ends of every edge only one triangle uses. */
	fun outlineVertices(indices: IntArray): Set<Int> =
		MeshTopology.edgeUses(indices).filterValues { it.size == 1 }.keys
			.flatMapTo(sortedSetOf()) { listOf(it.endpointLow, it.endpointHigh) }

	/**
	 * Whether [mesh] is a triangulation the manual ops may hand out: at least one triangle, indices in range, every
	 * vertex used, no triangle with a repeated corner or (near) zero area, and no edge used by more than two triangles.
	 */
	fun isLegal(mesh: DrawableMesh): Boolean {
		val count = mesh.vertexCount
		if (mesh.indices.isEmpty() || mesh.indices.size % 3 != 0 || mesh.indices.any { it !in 0 until count }) return false
		val used = BooleanArray(count)
		mesh.indices.forEach { used[it] = true }
		if (!used.all { it }) return false
		val floor = extent(mesh.positions).let { it * it } * 1e-10f
		for (ordinal in 0 until mesh.indices.size / 3) {
			val a = mesh.indices[ordinal * 3]; val b = mesh.indices[ordinal * 3 + 1]; val c = mesh.indices[ordinal * 3 + 2]
			if (a == b || b == c || a == c) return false
			if (abs(area(mesh.positions, a, b, c)) <= floor) return false
		}
		return MeshTopology.edgeUses(mesh.indices).values.all { it.size <= 2 }
	}

	/** A mesh that was not legal to begin with (an old import, say) is judged by the rest of the op, not refused outright. */
	private fun keepsLegal(before: DrawableMesh, after: DrawableMesh): Boolean = isLegal(after) || !isLegal(before)

	/** Vertices whose triangles fall into more than one fan around them - a pinch, or bow-tie. */
	internal fun pinchedVertices(indices: IntArray, vertexCount: Int): Set<Int> {
		val incident = Array(vertexCount) { ArrayList<Int>(6) }
		for (ordinal in 0 until indices.size / 3) for (slot in 0..2) indices[ordinal * 3 + slot].takeIf { it in 0 until vertexCount }?.let { incident[it] += ordinal }
		val pinched = LinkedHashSet<Int>()
		for (vertex in 0 until vertexCount) {
			val fan = incident[vertex]
			if (fan.size < 2) continue
			// Two triangles at a vertex are in one fan when they share an edge through it, i.e. a second corner.
			val reached = HashSet<Int>().apply { add(fan[0]) }
			val queue = ArrayDeque(listOf(fan[0]))
			while (queue.isNotEmpty()) {
				val current = queue.removeFirst()
				val corners = (0..2).map { indices[current * 3 + it] }.filter { it != vertex }
				for (other in fan) if (other !in reached && (0..2).any { indices[other * 3 + it] in corners }) { reached += other; queue += other }
			}
			if (reached.size != fan.size) pinched += vertex
		}
		return pinched
	}

	/**
	 * A triangulation being edited in place: its triangles (a removed one is left null so ordinals stay put) and which
	 * triangles use each edge, kept up to date by every flip, so a flip costs a constant amount of work.
	 */
	private class Flips(private val mesh: DrawableMesh, shapes: MeshShapes) {
		private val tris: MutableList<IntArray?> = mesh.indices.toList().chunked(3).map { it.toIntArray() }.toMutableList()
		private val uses = HashMap<MeshElement.Edge, MutableList<Int>>()
		private val judge = shapes.judge?.takeIf { it.size == mesh.positions.size } ?: mesh.positions
		/** Every shape a flip must keep convex: the rest mesh, the judging space, and each form. */
		private val checks = (listOf(mesh.positions, judge) + shapes.forms.filter { it.size == mesh.positions.size }).distinct()

		init {
			tris.forEachIndexed { ordinal, tri -> tri?.let { for (slot in 0..2) edge(it, slot).let { e -> uses.getOrPut(e) { ArrayList(2) } += ordinal } } }
		}

		private fun edge(tri: IntArray, slot: Int) = MeshElement.Edge.of(tri[slot], tri[(slot + 1) % 3])

		fun owners(edge: MeshElement.Edge): List<Int> = uses[edge].orEmpty()

		fun triangles(): IntArray = tris.filterNotNull().flatMap { it.toList() }.toIntArray()

		fun remove(ordinal: Int) {
			val tri = tris[ordinal] ?: return
			for (slot in 0..2) {
				val e = edge(tri, slot)
				uses[e]?.let { it.remove(ordinal); if (it.isEmpty()) uses.remove(e) }
			}
			tris[ordinal] = null
		}

		/**
		 * Flips [edge] when it is shared by two triangles whose quad is strictly convex in every checked shape, the new
		 * diagonal is not an edge already, and - when asked - the flip improves the triangulation in the judging space
		 * ([delaunay]) and leaves the texture mapping exactly as it was ([exactTexture]).
		 */
		fun flip(edge: MeshElement.Edge, delaunay: Boolean, exactTexture: Boolean): Boolean {
			val owners = uses[edge] ?: return false
			if (owners.size != 2) return false
			val first = owners[0]; val second = owners[1]
			val t1 = tris[first] ?: return false; val t2 = tris[second] ?: return false
			val a = edge.endpointLow; val b = edge.endpointHigh
			val c = t1.firstOrNull { it != a && it != b } ?: return false
			val d = t2.firstOrNull { it != a && it != b } ?: return false
			if (c == d || MeshElement.Edge.of(c, d) in uses) return false
			if (checks.any { !convex(it, a, b, c, d) }) return false
			if (delaunay && !inCircle(judge, a, b, c, d)) return false
			if (exactTexture && !affineUv(a, b, c, d)) return false
			// Each new triangle keeps the winding its predecessor had, so a consistently wound mesh stays consistent.
			tris[first] = wound(intArrayOf(c, d, a), sign(t1))
			tris[second] = wound(intArrayOf(d, c, b), sign(t2))
			uses.remove(edge)
			uses[MeshElement.Edge.of(c, d)] = mutableListOf(first, second)
			uses[MeshElement.Edge.of(b, c)]?.let { list -> list[list.indexOf(first)] = second }
			uses[MeshElement.Edge.of(a, d)]?.let { list -> list[list.indexOf(second)] = first }
			return true
		}

		/**
		 * Lawson's flip: a queue of the interior edges near [around], each flipped toward Delaunay when that is safe;
		 * a flip queues the quad's four outer edges again. Bounded far above what one edit needs.
		 */
		fun lawson(around: Set<Int>): IntArray {
			val queue = ArrayDeque<MeshElement.Edge>()
			val queued = HashSet<MeshElement.Edge>()
			fun push(e: MeshElement.Edge) { if (uses[e]?.size == 2 && queued.add(e)) queue += e }
			uses.keys.sortedWith(compareBy({ it.endpointLow }, { it.endpointHigh }))
				.filter { e -> e.endpointLow in around || e.endpointHigh in around || uses.getValue(e).any { ordinal -> tris[ordinal]!!.any { it in around } } }
				.forEach(::push)
			var budget = maxOf(1024, tris.size * 8)
			while (queue.isNotEmpty() && budget > 0) {
				val e = queue.removeFirst()
				queued -= e
				val owners = uses[e] ?: continue
				if (owners.size != 2) continue
				val c = tris[owners[0]]!!.first { it != e.endpointLow && it != e.endpointHigh }
				val d = tris[owners[1]]!!.first { it != e.endpointLow && it != e.endpointHigh }
				if (!flip(e, delaunay = true, exactTexture = true)) continue
				budget--
				for (corner in listOf(e.endpointLow, e.endpointHigh)) { push(MeshElement.Edge.of(corner, c)); push(MeshElement.Edge.of(corner, d)) }
			}
			return triangles()
		}

		private fun sign(tri: IntArray) = if (area(mesh.positions, tri[0], tri[1], tri[2]) >= 0f) 1f else -1f

		private fun wound(tri: IntArray, sign: Float): IntArray =
			if ((area(mesh.positions, tri[0], tri[1], tri[2]) >= 0f) == (sign > 0f)) tri else intArrayOf(tri[0], tri[2], tri[1])

		/** The quad a-c-b-d is strictly convex in [frame]: each diagonal separates the other two corners. */
		private fun convex(frame: FloatArray, a: Int, b: Int, c: Int, d: Int): Boolean {
			val floor = maxOf(lengthSquared(frame, a, b), lengthSquared(frame, c, d)) * 1e-6f
			val ac = area(frame, a, b, c); val ad = area(frame, a, b, d)
			val ca = area(frame, c, d, a); val cb = area(frame, c, d, b)
			return ac * ad < 0f && ca * cb < 0f && minOf(abs(ac), abs(ad), abs(ca), abs(cb)) > floor
		}

		/** [d] lies strictly inside the circumcircle of (a, b, c), in coordinates normalised to the quad's size. */
		private fun inCircle(frame: FloatArray, a: Int, b: Int, c: Int, d: Int): Boolean {
			val dx = frame[d * 2].toDouble(); val dy = frame[d * 2 + 1].toDouble()
			val scale = listOf(a, b, c).maxOf { maxOf(abs(frame[it * 2] - dx), abs(frame[it * 2 + 1] - dy)) }.takeIf { it > 0.0 } ?: return false
			fun x(i: Int) = (frame[i * 2] - dx) / scale
			fun y(i: Int) = (frame[i * 2 + 1] - dy) / scale
			val det = (x(a) * x(a) + y(a) * y(a)) * (x(b) * y(c) - x(c) * y(b)) - (x(b) * x(b) + y(b) * y(b)) * (x(a) * y(c) - x(c) * y(a)) +
				(x(c) * x(c) + y(c) * y(c)) * (x(a) * y(b) - x(b) * y(a))
			val orientation = (x(b) - x(a)) * (y(c) - y(a)) - (y(b) - y(a)) * (x(c) - x(a))
			return if (orientation > 0.0) det > 1e-9 else det < -1e-9
		}

		/** The fourth UV lies on the affine map of the other three, so the flip samples exactly the texels it did. */
		private fun affineUv(a: Int, b: Int, c: Int, d: Int): Boolean {
			if (mesh.uvs.size != mesh.positions.size) return false
			val p = mesh.positions
			val denominator = (p[b * 2 + 1] - p[c * 2 + 1]) * (p[a * 2] - p[c * 2]) + (p[c * 2] - p[b * 2]) * (p[a * 2 + 1] - p[c * 2 + 1])
			if (abs(denominator) < 1e-12f) return false
			val x = p[d * 2]; val y = p[d * 2 + 1]
			val wa = ((p[b * 2 + 1] - p[c * 2 + 1]) * (x - p[c * 2]) + (p[c * 2] - p[b * 2]) * (y - p[c * 2 + 1])) / denominator
			val wb = ((p[c * 2 + 1] - p[a * 2 + 1]) * (x - p[c * 2]) + (p[a * 2] - p[c * 2]) * (y - p[c * 2 + 1])) / denominator
			val wc = 1f - wa - wb
			val uv = mesh.uvs
			// Tolerance in the UVs' own units, scaled to their extent: page UVs and canvas-unit UVs alike.
			val tolerance = extent(uv) * 2e-4f
			return (0..1).all { k -> abs(uv[a * 2 + k] * wa + uv[b * 2 + k] * wb + uv[c * 2 + k] * wc - uv[d * 2 + k]) <= tolerance }
		}
	}

	private fun lengthSquared(frame: FloatArray, a: Int, b: Int): Float {
		val dx = frame[a * 2] - frame[b * 2]; val dy = frame[a * 2 + 1] - frame[b * 2 + 1]
		return dx * dx + dy * dy
	}

	private fun area(positions: FloatArray, a: Int, b: Int, c: Int): Float =
		(positions[b * 2] - positions[a * 2]) * (positions[c * 2 + 1] - positions[a * 2 + 1]) -
			(positions[b * 2 + 1] - positions[a * 2 + 1]) * (positions[c * 2] - positions[a * 2])

	/** The larger side of the array's bounds, never zero, so tolerances scale with whatever space it lives in. */
	private fun extent(positions: FloatArray): Float {
		if (positions.isEmpty()) return 1f
		var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
		for (index in positions.indices step 2) {
			minX = minOf(minX, positions[index]); maxX = maxOf(maxX, positions[index])
			minY = minOf(minY, positions[index + 1]); maxY = maxOf(maxY, positions[index + 1])
		}
		return maxOf(maxX - minX, maxY - minY, 1e-6f)
	}
}
