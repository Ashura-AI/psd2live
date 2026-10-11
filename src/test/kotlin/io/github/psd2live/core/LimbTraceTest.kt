package io.github.psd2live.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The auto skeleton's reading of a limb's art ([LimbGraph], [LimbTrace]) on shapes whose answer is known. */
class LimbTraceTest {
	/** A mesh of the lattice points (every [step] px) inside [shape], two triangles to each cell that lies inside. */
	private fun mesh(shape: (Float, Float) -> Boolean, width: Int, height: Int, step: Int = 4, originX: Float = 0f, originY: Float = 0f): Pair<FloatArray, IntArray> {
		val columns = width / step + 1
		val rows = height / step + 1
		val id = IntArray(columns * rows) { -1 }
		val points = ArrayList<Float>()
		for (r in 0 until rows) for (c in 0 until columns) {
			val x = originX + c * step; val y = originY + r * step
			if (shape(x, y)) { id[r * columns + c] = points.size / 2; points += x; points += y }
		}
		val indices = ArrayList<Int>()
		for (r in 0 until rows - 1) for (c in 0 until columns - 1) {
			val a = id[r * columns + c]; val b = id[r * columns + c + 1]; val d = id[(r + 1) * columns + c]; val e = id[(r + 1) * columns + c + 1]
			if (a >= 0 && b >= 0 && d >= 0 && e >= 0) { indices += listOf(a, b, d, b, e, d) }
		}
		return points.toFloatArray() to indices.toIntArray()
	}

	private fun trace(parts: List<Pair<FloatArray, IntArray>>, hintX: Float, hintY: Float, forks: Boolean, minLobe: Double = 0.1, centeredTip: Boolean = false): LimbTree {
		val graph = LimbGraph(parts)
		val seeds = graph.axisSlice(hintX, hintY)
		return assertNotNull(LimbTrace.trace(graph, hintX, hintY, seeds, forks, minLobe, centeredTip))
	}

	private fun tube(x0: Float, y0: Float, x1: Float, y1: Float, half: Float): (Float, Float) -> Boolean = { x, y ->
		val dx = x1 - x0; val dy = y1 - y0
		val t = (((x - x0) * dx + (y - y0) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
		hypot(x - (x0 + dx * t), y - (y0 + dy * t)) <= half
	}

	@Test fun aStraightLimbIsOneLineDownItsMiddle() {
		val shape = mesh(tube(100f, 20f, 100f, 220f, 12f), 220, 240)
		val tree = trace(listOf(shape), 100f, 30f, forks = true)
		assertTrue(tree.forks.isEmpty(), "a plain limb has no lobes")
		val line = tree.trunk
		for (i in 1..9) {
			val (x, _) = line.at(i / 10.0)
			assertEquals(100f, x, 3f, "the line runs down the middle")
		}
		assertEquals(200f, line.length, 25f)
		assertEquals(24.0, line.medianWidth(), 5.0)
	}

	@Test fun aLimbsRootCapIsCentredInsideItsEnd() {
		val shape = mesh(tube(100f, 20f, 100f, 220f, 12f), 220, 240)
		val graph = LimbGraph(listOf(shape))
		val ends = assertNotNull(graph.ends(100f, 40f))
		val cap = assertNotNull(graph.capCentre(ends))
		assertEquals(100f, cap.x, 3f)
		// The capsule's round end reaches y = 8; its circle is centred on y = 20, a radius in from that, not on the edge.
		assertTrue(cap.y in 14f..30f, "the joint is at y = ${cap.y}, the circle's centre is at 20 and its edge at 8")
		assertTrue(cap.uy > 0.95f, "the limb leaves its root straight down")
	}

	@Test fun aBranchingLimbIsATrunkAndALobe() {
		// A stalk 24 px across down to a fork at y = 150, then two lobes spreading out to (30, 260) and (170, 260).
		val shape = mesh({ x, y -> tube(100f, 20f, 100f, 150f, 12f)(x, y) || tube(100f, 150f, 30f, 260f, 10f)(x, y) || tube(100f, 150f, 170f, 260f, 10f)(x, y) }, 200, 280)
		val tree = trace(listOf(shape), 100f, 30f, forks = true)
		assertEquals(1, tree.forks.size, "one lobe leaves the trunk")
		val lobe = tree.forks.single()
		val (fx, fy) = tree.trunk.pointAtReach(lobe.reachAt)
		assertTrue(abs(fx - 100f) < 14f && abs(fy - 150f) < 20f, "the fork is at the end of the stalk, not at ($fx, $fy)")
		val tips = listOf(tree.trunk.at(1.0), lobe.line.at(1.0))
		assertTrue(tips.any { hypot(it.first - 30f, it.second - 260f) < 25f }, "a line reaches the left tip: $tips")
		assertTrue(tips.any { hypot(it.first - 170f, it.second - 260f) < 25f }, "a line reaches the right tip: $tips")
		assertTrue(hypot(lobe.line.at(0.0).first - fx, lobe.line.at(0.0).second - fy) < 1f, "the lobe starts on the trunk")
		// An arm or a leg does not branch: with forks off the same art is one line.
		assertTrue(trace(listOf(shape), 100f, 30f, forks = false).forks.isEmpty())
	}

	@Test fun aBumpOnTheOutlineIsNotALobe() {
		val bump = mesh({ x, y -> tube(100f, 20f, 100f, 220f, 12f)(x, y) || tube(112f, 120f, 128f, 120f, 6f)(x, y) }, 220, 240)
		assertTrue(trace(listOf(bump), 100f, 30f, forks = true).forks.isEmpty(), "a nub 16 px long on a limb 24 px across is not a branch")
	}

	@Test fun twoTailsOutOfTheSameHipsAreTwoBranches() {
		val left = mesh(tube(100f, 20f, 40f, 200f, 10f), 220, 240)
		val right = mesh(tube(100f, 20f, 170f, 200f, 10f), 220, 240)
		val tree = trace(listOf(left, right), 100f, 20f, forks = true)
		assertEquals(1, tree.forks.size, "the second tail is a branch of the first")
		val ends = listOf(tree.trunk.at(1.0), tree.forks.single().line.at(1.0))
		assertTrue(ends.any { it.first < 70f } && ends.any { it.first > 140f }, "one line out each way: $ends")
	}

	@Test fun aLimbWithAThinThingHangingOffItEndsInTheLimb() {
		// A limb 24 px across and 200 long with a chain 3 px across swinging out from its middle, further away than its end.
		val shape = mesh({ x, y -> tube(100f, 20f, 100f, 220f, 12f)(x, y) || tube(100f, 130f, 160f, 290f, 2f)(x, y) }, 220, 300, step = 2)
		val tree = trace(listOf(shape), 100f, 30f, forks = false)
		val (tx, ty) = tree.trunk.at(1.0)
		assertTrue(hypot(tx - 100f, ty - 220f) < 25f, "the limb ends where the limb does, not on the chain: ($tx, $ty)")
	}

	@Test fun aHintOffTheArtSnapsNowhere() {
		val shape = mesh(tube(100f, 120f, 100f, 300f, 12f), 220, 320)
		val line = trace(listOf(shape), 100f, 130f, forks = false).trunk
		assertNull(line.snap(260f, 130f), "a point 160 px to the side of a 24 px limb is not on it")
		val on = assertNotNull(line.snap(104f, 130f))
		assertEquals(100f, on.first, 3f, "a point on the limb moves across onto its middle")
		assertEquals(130f, on.second, 1f, "and keeps its place along it")
	}
}
