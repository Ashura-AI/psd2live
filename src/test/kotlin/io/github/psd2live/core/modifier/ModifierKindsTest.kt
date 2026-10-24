package io.github.psd2live.core.modifier

import kotlin.math.hypot
import kotlin.test.*

class ModifierKindsTest {
	private val bounds = floatArrayOf(0f, 0f, 100f, 200f)
	private val grid = FloatArray(2 * 11 * 21).also { a ->
		var i = 0
		for (y in 0..20) for (x in 0..10) { a[i++] = x * 10f; a[i++] = y * 10f }
	}

	private fun values(kind: ModifierKind, overrides: Map<String, FloatArray>) =
		kind.settings.associate { it.key to it.default } + kind.defaults(bounds) + overrides

	private fun distance(a: FloatArray, b: FloatArray) = (0 until a.size / 2).maxOf { hypot(a[it * 2] - b[it * 2], a[it * 2 + 1] - b[it * 2 + 1]) }

	private fun point(kind: ModifierKind, x: Float, y: Float, overrides: Map<String, FloatArray>) = kind.deform(floatArrayOf(x, y), values(kind, overrides))

	@Test
	fun `every kind moves nothing at its defaults`() {
		for (kind in ModifierKinds.all) assertTrue(distance(grid, kind.deform(grid, values(kind, emptyMap()))) < 1e-4f, kind.id)
	}

	@Test
	fun `a bend curls the axis into a quarter circle and keeps what lies before it`() {
		// Up from the bottom centre over the whole height: 90 degrees clockwise turns the top towards the right.
		val bent = mapOf("angle" to floatArrayOf(90f))
		val radius = 200 / (Math.PI / 2)
		val tip = point(ModifierKinds.Bend, 50f, 0f, bent)
		assertEquals(50 + radius, tip[0].toDouble(), 1e-3)
		assertEquals(200 - radius, tip[1].toDouble(), 1e-3)
		assertContentEquals(floatArrayOf(50f, 230f), point(ModifierKinds.Bend, 50f, 230f, bent))
		// A point across the axis keeps its distance to the arc's centre.
		val beside = point(ModifierKinds.Bend, 40f, 100f, bent)
		assertEquals(radius + 10, hypot(beside[0] - (50 + radius), beside[1] - 200.0), 1e-3)
	}

	@Test
	fun `a twist turns the centre most and leaves the rim`() {
		val twisted = mapOf("angle" to floatArrayOf(90f), "center" to floatArrayOf(50f, 100f), "radius" to floatArrayOf(50f))
		assertContentEquals(floatArrayOf(100f, 100f), point(ModifierKinds.Twist, 100f, 100f, twisted))
		val near = point(ModifierKinds.Twist, 50.001f, 100f, twisted)
		assertEquals(50f, near[0], 1e-2f)
		assertEquals(100.001f, near[1], 1e-2f)
	}

	@Test
	fun `a wave holds its root and crests a quarter wavelength in`() {
		val waved = mapOf("amplitude" to floatArrayOf(10f), "ramp" to floatArrayOf(0f))
		assertContentEquals(floatArrayOf(50f, 0f), point(ModifierKinds.Wave, 50f, 0f, waved))
		// Down from the top centre: the axis's right is the canvas's left.
		val crest = point(ModifierKinds.Wave, 50f, 50f, waved)
		assertEquals(40f, crest[0], 1e-3f)
		assertEquals(50f, crest[1], 1e-3f)
	}

	@Test
	fun `a lattice carries its corners exactly and the inside smoothly`() {
		val offsets = FloatArray(32).also { it[(3 * 4 + 3) * 2] = 20f }
		val moved = mapOf("offsets" to offsets)
		assertContentEquals(floatArrayOf(120f, 200f), point(ModifierKinds.Lattice, 100f, 200f, moved))
		assertContentEquals(floatArrayOf(0f, 0f), point(ModifierKinds.Lattice, 0f, 0f, moved))
		val middle = point(ModifierKinds.Lattice, 50f, 100f, moved)
		assertEquals(20f / 64, middle[0] - 50f, 1e-4f)
		assertContentEquals(floatArrayOf(140f, 260f), point(ModifierKinds.Lattice, 120f, 260f, moved), "outside it follows the nearest edge")
	}

	@Test
	fun `roles put their kinds first`() {
		assertEquals("wave", ModifierKinds.forRole(io.github.psd2live.core.SemanticTag.BACK_HAIR).first { it.id != "bend" }.id)
		assertEquals(ModifierKinds.all.toList(), ModifierKinds.forRole(null))
	}

	@Test
	fun `a path carries the art beside it with its control points`() {
		// Down the middle: moving every control point 10 right moves the art 10 right; moving the bottom one bends it.
		val shifted = mapOf("offsets" to FloatArray(8) { if (it % 2 == 0) 10f else 0f })
		assertEquals(70f, point(ModifierKinds.Path, 60f, 100f, shifted)[0], 1e-3f)
		assertEquals(100f, point(ModifierKinds.Path, 60f, 100f, shifted)[1], 1e-3f)
		val tip = mapOf("offsets" to FloatArray(8).also { it[6] = 30f })
		val top = point(ModifierKinds.Path, 50f, 0f, tip)
		assertEquals(50f, top[0], 1e-3f, "the root stays")
		assertEquals(80f, point(ModifierKinds.Path, 50f, 200f, tip)[0], 1e-2f, "the end follows its point")
		val faded = mapOf("offsets" to FloatArray(8) { if (it % 2 == 0) 10f else 0f }, "radius" to floatArrayOf(20f))
		assertEquals(90f, point(ModifierKinds.Path, 90f, 100f, faded)[0], 1e-4f, "beyond the radius nothing moves")
	}
}
