package io.github.psd2live.core.mesh

import kotlin.math.PI
import kotlin.test.*

class ConstrainedTriangulatorTest {
	private fun square(points: MutableList<Point>, x0: Double, y0: Double, size: Double, steps: Int, reverse: Boolean = false): IntArray {
		val ring = ArrayList<Int>()
		for (side in 0 until 4) for (s in 0 until steps) {
			val t = s.toDouble() / steps * size
			val p = when (side) {
				0 -> Point(x0 + t, y0); 1 -> Point(x0 + size, y0 + t)
				2 -> Point(x0 + size - t, y0 + size); else -> Point(x0, y0 + size - t)
			}
			ring += points.size; points += p
		}
		return (if (reverse) ring.reversed() else ring).toIntArray()
	}

	private fun edges(result: ConstrainedTriangulation): Map<Edge, Int> {
		val counts = HashMap<Edge, Int>()
		result.triangles.forEach { t -> triangleEdges(t).forEach { counts.merge(it, 1, Int::plus) } }
		return counts
	}

	@Test
	fun recoversLoopsHolesAndCrossingInteriorConstraints() {
		val points = ArrayList<Point>()
		val outer = square(points, 0.0, 0.0, 100.0, 10)
		val hole = square(points, 60.0, 60.0, 20.0, 2, reverse = true)
		// A long diagonal crease through a jittered lattice: almost every lattice edge near it must be flipped away.
		val crease = ArrayList<Int>()
		for (i in 0..8) { crease += points.size; points += Point(8.0 + i * 5.5, 12.0 + i * 4.3) }
		for (y in 1 until 20) for (x in 1 until 20) {
			val p = Point(x * 5.0 + (x * 7 + y * 3) % 5 * .13, y * 5.0 + (x * 3 + y * 11) % 5 * .11)
			val loops = listOf(outer.map { points[it] }, hole.map { points[it] })
			if (inDomain(p, loops) && loops.all { distanceSquaredToLoop(p, it) >= 1.0 } &&
				crease.zipWithNext().none { (a, b) -> distanceSquaredToSegment(p, points[a], points[b]) < 1.0 } &&
				points.none { distanceSquared(it, p) < 1.0 }) points += p
		}
		val constraints = crease.zipWithNext().map { (a, b) -> edgeOf(a, b) }
		val attempt = ConstrainedTriangulator.attempt(points, listOf(outer, hole), constraints)
		val result = assertNotNull(attempt.mesh, attempt.failure)
		val counts = edges(result)
		constraints.forEach { assertTrue(it in counts, "constraint $it") }
		for (loop in listOf(outer, hole)) for (i in loop.indices) assertEquals(1, counts[edgeOf(loop[i], loop[(i + 1) % loop.size])])
		assertTrue(counts.values.all { it in 1..2 })
		val boundary = counts.count { it.value == 1 }
		// Euler characteristic of a disc with one hole.
		val used = result.triangles.flatMap { listOf(it.a, it.b, it.c) }.toSet().size
		assertEquals(0, used - counts.size + result.triangles.size)
		assertEquals(outer.size + hole.size, boundary)
		result.triangles.forEach { t ->
			val c = Point((points[t.a].x + points[t.b].x + points[t.c].x) / 3, (points[t.a].y + points[t.b].y + points[t.c].y) / 3)
			assertFalse(pointInPolygon(c, hole.map { points[it] }))
		}
	}

	@Test
	fun refinementSplitsOnlyFreeEdgesAndStaysDeterministic() {
		fun run(): ConstrainedTriangulation {
			val points = ArrayList<Point>()
			val outer = square(points, 0.0, 0.0, 80.0, 10)
			val a = points.size
			for (i in 0..5) points += Point(20.0 + i * 8, 40.0)
			val result = assertNotNull(ConstrainedTriangulator.triangulate(points, listOf(outer),
				(0 until 5).map { edgeOf(a + it, a + it + 1) }))
			result.refine({ 8.0 }, 1.4, 400)
			return result
		}
		val first = run(); val second = run()
		assertEquals(first.points, second.points)
		assertEquals(first.triangles, second.triangles)
		assertTrue(first.points.size > 60)
		assertTrue(edges(first).containsKey(edgeOf(40, 41)))
		val worst = first.triangles.minOf { minimumAngle(first.points[it.a], first.points[it.b], first.points[it.c]) }
		assertTrue(worst > 10 * PI / 180, "min angle $worst")
	}

	@Test
	fun rejectsAConstraintThroughAnotherPoint() {
		val points = ArrayList<Point>()
		val outer = square(points, 0.0, 0.0, 40.0, 2)
		val a = points.size
		points += Point(10.0, 20.0); points += Point(20.0, 20.0); points += Point(30.0, 20.0)
		assertNull(ConstrainedTriangulator.triangulate(points, listOf(outer), listOf(edgeOf(a, a + 2))))
	}
}
