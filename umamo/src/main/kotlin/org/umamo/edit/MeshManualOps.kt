package org.umamo.edit

import org.umamo.runtime.model.DrawableMesh
import kotlin.math.abs

/**
 * Hand placement of mesh points: the ops a point-by-point mesh tool needs on top of [MeshRefinementOps] and
 * [MeshTopologyOps] - adding free points, deleting edges and re-triangulating - each a pure builder that returns a
 * [TopologyOpResult] for `withMeshTopologyEdit`, or null to refuse.
 *
 * Every result is a legal triangulation by the rules [isLegal] checks: each edge is used by one or two triangles,
 * every vertex is used, no triangle is degenerate and no op pinches a vertex into two fans. An op that would break
 * one of these refuses as a whole rather than committing a partial edit.
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
	 * then flipped toward Delaunay where that keeps the texture mapping exact.
	 *
	 * @param DrawableMesh mesh The mesh to edit.
	 * @param List<Pair<Float, Float>> points The positions to create vertices at, in order.
	 * @return TopologyOpResult? The edit and the created vertices, or null when no point could be placed.
	 */
	fun addPoints(mesh: DrawableMesh, points: List<Pair<Float, Float>>): TopologyOpResult? {
		val extent = extent(mesh.positions)
		val duplicate = extent * 1e-5f
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
		val tidy = MeshRefinementOps.flipTowardDelaunay(edit.newMesh, edit.newMesh.positions, created)
		val result = DrawableMesh(edit.newMesh.positions, edit.newMesh.uvs, tidy)
		if (!keepsLegal(mesh, result)) return null
		return TopologyOpResult(MeshTopologyEdit(result, edit.vertexSources), created.mapTo(LinkedHashSet()) { MeshElement.Vertex(it) })
	}

	/**
	 * Deletes [edges] while keeping the mesh a triangulation.
	 *
	 * An interior edge is shared by two triangles; deleting it leaves their quad, which is joined again across its
	 * other diagonal - the edge flips. A concave quad has no other diagonal inside it, so that refuses. An outline
	 * edge belongs to one triangle, which is removed with it; a corner that no triangle uses any more goes too. A
	 * removal that would pinch a vertex between two separate fans, or remove the last triangle, refuses.
	 *
	 * Edges are taken in index order, so a batch is deterministic. Vertices that stay keep their order.
	 *
	 * @param DrawableMesh mesh The mesh to edit.
	 * @param Set<MeshElement.Edge> edges The edges to delete; every one must be an edge of the mesh.
	 * @return TopologyOpResult? The edit, or null when an edge is missing or cannot be deleted.
	 */
	fun deleteEdges(mesh: DrawableMesh, edges: Set<MeshElement.Edge>): TopologyOpResult? {
		if (edges.isEmpty()) return null
		val triangles = mesh.indices.toList().chunked(3).map { it.toIntArray() }.toMutableList()
		val pinchedBefore = pinchedVertices(mesh.indices, mesh.vertexCount)
		for (edge in edges.sortedWith(compareBy({ it.endpointLow }, { it.endpointHigh }))) {
			val owners = triangles.indices.filter { ordinal -> slotOf(triangles[ordinal], edge.endpointLow, edge.endpointHigh) >= 0 }
			when (owners.size) {
				1 -> triangles.removeAt(owners.single())
				2 -> if (!flip(triangles, owners[0], owners[1], edge, mesh.positions)) return null
				else -> return null
			}
		}
		if (triangles.isEmpty()) return null
		val used = BooleanArray(mesh.vertexCount)
		triangles.forEach { triangle -> triangle.forEach { used[it] = true } }
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
		val indices = IntArray(triangles.size * 3) { remap[triangles[it / 3][it % 3]] }
		val result = DrawableMesh(positions, uvs, indices)
		if (!keepsLegal(mesh, result)) return null
		// Pinching is judged per old vertex, so a mesh that already had a pinched vertex can still be edited elsewhere.
		val pinchedAfter = pinchedVertices(indices, keep.size).mapTo(HashSet()) { keep[it] }
		if (!pinchedBefore.containsAll(pinchedAfter)) return null
		return TopologyOpResult(MeshTopologyEdit(result, keep.map { VertexSource.FromOld(it) }), emptySet())
	}

	/**
	 * Flips the interior edges around [around] (every vertex when empty) toward a Delaunay triangulation of the
	 * current points, keeping the outline and every vertex. Only flips that keep the texture mapping exact are taken
	 * ([MeshRefinementOps.flipTowardDelaunay]), so the picture does not move.
	 *
	 * @param DrawableMesh mesh The mesh to tidy.
	 * @param Set<Int> around The vertices whose surroundings are re-triangulated; empty for the whole mesh.
	 * @return TopologyOpResult? The edit, or null when the triangulation is already as good as it gets.
	 */
	fun retriangulate(mesh: DrawableMesh, around: Set<Int>): TopologyOpResult? {
		val scope = around.filterTo(LinkedHashSet()) { it in 0 until mesh.vertexCount }.ifEmpty { (0 until mesh.vertexCount).toSet() }
		val budget = maxOf(512, mesh.indices.size * 4)
		val indices = MeshRefinementOps.flipTowardDelaunay(mesh, mesh.positions, scope, maxFlips = budget)
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

	/** Flips the edge two triangles share, when their quad is strictly convex and the new diagonal is not an edge yet. */
	private fun flip(triangles: MutableList<IntArray>, firstOrdinal: Int, secondOrdinal: Int, edge: MeshElement.Edge, positions: FloatArray): Boolean {
		val first = triangles[firstOrdinal]
		val second = triangles[secondOrdinal]
		val slot = slotOf(first, edge.endpointLow, edge.endpointHigh)
		val a = first[slot]; val b = first[(slot + 1) % 3]; val c = first[(slot + 2) % 3]
		val d = second.firstOrNull { it != a && it != b } ?: return false
		// The shared edge runs b -> a in a consistently wound neighbour; anything else is not a proper quad.
		val otherSlot = slotOf(second, b, a)
		if (otherSlot < 0 || second[otherSlot] != b) return false
		if (triangles.any { triangle -> slotOf(triangle, c, d) >= 0 }) return false
		val keep = area(positions, a, b, c)
		val made = listOf(intArrayOf(a, d, c), intArrayOf(d, b, c))
		if (made.any { tri -> val value = area(positions, tri[0], tri[1], tri[2]); value * keep <= 0f || abs(value) <= abs(keep) * 1e-4f }) return false
		triangles[firstOrdinal] = made[0]
		triangles[secondOrdinal] = made[1]
		return true
	}

	/** Where [a] and [b] are consecutive corners of [triangle], in either direction, or -1. */
	private fun slotOf(triangle: IntArray, a: Int, b: Int): Int {
		for (slot in 0..2) {
			val from = triangle[slot]; val to = triangle[(slot + 1) % 3]
			if ((from == a && to == b) || (from == b && to == a)) return slot
		}
		return -1
	}

	private fun area(positions: FloatArray, a: Int, b: Int, c: Int): Float =
		(positions[b * 2] - positions[a * 2]) * (positions[c * 2 + 1] - positions[a * 2 + 1]) -
			(positions[b * 2 + 1] - positions[a * 2 + 1]) * (positions[c * 2] - positions[a * 2])

	/** The larger side of the mesh's bounds, never zero, so tolerances scale with whatever space it lives in. */
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
