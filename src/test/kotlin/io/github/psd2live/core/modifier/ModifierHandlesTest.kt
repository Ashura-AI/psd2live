package io.github.psd2live.core.modifier

import kotlinx.serialization.json.*
import kotlin.math.hypot
import kotlin.test.*

class ModifierHandlesTest {
	private val bounds = floatArrayOf(0f, 0f, 100f, 200f)
	private val reach = 20f

	private fun values(kind: ModifierKind, overrides: Map<String, FloatArray> = emptyMap()) =
		kind.settings.associate { it.key to it.default } + kind.defaults(bounds) + overrides

	private fun handle(role: ModifierHandle.Role, index: Int = 0) = ModifierHandle(role, index)

	private fun at(kind: ModifierKind, v: Map<String, FloatArray>, role: ModifierHandle.Role, index: Int = 0) =
		ModifierHandles.handles(kind.id, v, reach).getValue(handle(role, index))

	/** [role] dragged from where it sits to [to] in [steps] moves, as the canvas sends them. */
	private fun drag(kind: ModifierKind, v: Map<String, FloatArray>, role: ModifierHandle.Role, to: ModifierPoint, index: Int = 0, steps: Int = 8): Map<String, FloatArray> {
		val grab = at(kind, v, role, index)
		var current = v
		var previous = grab
		for (i in 1..steps) {
			val p = grab + (to - grab) * (i.toFloat() / steps)
			current = current + ModifierHandles.drag(kind.id, handle(role, index), v, current, grab, previous, p, reach)
			previous = p
		}
		return current
	}

	private fun assertNear(expected: ModifierPoint, actual: ModifierPoint, tolerance: Float = 0.2f) =
		assertTrue(hypot(expected.x - actual.x, expected.y - actual.y) <= tolerance, "$expected vs $actual")

	@Test
	fun `transform handles move the pivot and the offset and turn about the moved pivot`() {
		val kind = ModifierKinds.Transform
		val v = values(kind, mapOf("offset" to floatArrayOf(10f, 0f)))
		assertNear(ModifierPoint(50f, 100f), at(kind, v, ModifierHandle.Role.PIVOT))
		assertNear(ModifierPoint(60f, 120f), at(kind, v, ModifierHandle.Role.OFFSET))
		assertNear(ModifierPoint(80f, 100f), at(kind, v, ModifierHandle.Role.ROTATION))
		assertContentEquals(floatArrayOf(15f, -5f), drag(kind, v, ModifierHandle.Role.OFFSET, ModifierPoint(65f, 115f)).getValue("offset"))
		assertContentEquals(floatArrayOf(40f, 110f), drag(kind, v, ModifierHandle.Role.PIVOT, ModifierPoint(40f, 110f)).getValue("pivot"))
		// A quarter turn clockwise on the canvas (y down) is +90.
		val turned = drag(kind, v, ModifierHandle.Role.ROTATION, ModifierPoint(60f, 130f))
		assertEquals(90f, turned.getValue("rotation")[0], 0.11f)
		assertNear(ModifierPoint(60f, 120f), at(kind, turned, ModifierHandle.Role.ROTATION))
	}

	@Test
	fun `a turn dragged round and round winds past a whole turn`() {
		val kind = ModifierKinds.Transform
		var v = values(kind)
		val center = ModifierPoint(50f, 100f)
		var previous = at(kind, v, ModifierHandle.Role.ROTATION)
		val grab = previous
		for (i in 1..72) {
			val a = Math.toRadians(i * 10.0)
			val p = center + ModifierPoint(Math.cos(a).toFloat(), Math.sin(a).toFloat()) * 20f
			v = v + ModifierHandles.drag(kind.id, handle(ModifierHandle.Role.ROTATION), v, v, grab, previous, p, reach)
			previous = p
		}
		assertEquals(720f, v.getValue("rotation")[0], 0.5f)
	}

	@Test
	fun `bend handles set the axis and steer the arc's end`() {
		val kind = ModifierKinds.Bend
		val v = values(kind)
		assertNear(ModifierPoint(50f, 200f), at(kind, v, ModifierHandle.Role.ORIGIN))
		assertNear(ModifierPoint(50f, 0f), at(kind, v, ModifierHandle.Role.AXIS))
		// Unbent, the steering arm carries the axis straight on past its end.
		assertNear(ModifierPoint(50f, -20f), at(kind, v, ModifierHandle.Role.BEND))
		val axis = drag(kind, v, ModifierHandle.Role.AXIS, ModifierPoint(150f, 200f))
		assertEquals(0f, axis.getValue("direction")[0], 0.11f)
		assertEquals(100f, axis.getValue("length")[0], 0.11f)
		// Dragged to where the arm sits at 120 degrees, the arc bends that far and the handle comes under the pointer.
		val target = at(kind, values(kind, mapOf("angle" to floatArrayOf(120f))), ModifierHandle.Role.BEND)
		val bent = drag(kind, v, ModifierHandle.Role.BEND, target, steps = 12)
		assertEquals(120f, bent.getValue("angle")[0], 0.11f)
		val end = at(kind, bent, ModifierHandle.Role.BEND)
		assertNear(target, end)
		// The arm stands off the arc's end by the reach.
		val tip = ModifierKinds.Bend.deform(floatArrayOf(50f, 0f), bent)
		assertEquals(20f, hypot(end.x - tip[0], end.y - tip[1]), 0.01f)
	}

	@Test
	fun `twist handles set the centre, the radius and the turn`() {
		val kind = ModifierKinds.Twist
		val v = values(kind, mapOf("radius" to floatArrayOf(80f)))
		assertNear(ModifierPoint(130f, 100f), at(kind, v, ModifierHandle.Role.RADIUS))
		assertNear(ModifierPoint(90f, 100f), at(kind, v, ModifierHandle.Role.TWIST))
		assertEquals(30f, drag(kind, v, ModifierHandle.Role.RADIUS, ModifierPoint(50f, 130f)).getValue("radius")[0], 0.11f)
		assertEquals(-90f, drag(kind, v, ModifierHandle.Role.TWIST, ModifierPoint(50f, 60f)).getValue("angle")[0], 0.11f)
		assertContentEquals(floatArrayOf(60f, 90f), drag(kind, v, ModifierHandle.Role.CENTER, ModifierPoint(60f, 90f)).getValue("center"))
		// The radius keeps the setting's floor rather than reaching zero.
		assertEquals(1f, drag(kind, v, ModifierHandle.Role.RADIUS, ModifierPoint(50f, 100f)).getValue("radius")[0])
	}

	@Test
	fun `wave handles read lengths along the axis and the amplitude across it`() {
		val kind = ModifierKinds.Wave
		// Down from the top centre: the axis's right is the canvas's left.
		val v = values(kind, mapOf("amplitude" to floatArrayOf(10f)))
		assertNear(ModifierPoint(50f, 200f), at(kind, v, ModifierHandle.Role.WAVELENGTH))
		assertNear(ModifierPoint(40f, 50f), at(kind, v, ModifierHandle.Role.AMPLITUDE))
		assertNear(ModifierPoint(60f, 100f), at(kind, v, ModifierHandle.Role.RAMP))
		assertNear(ModifierPoint(50f, 220f), at(kind, v, ModifierHandle.Role.DIRECTION))
		assertEquals(150f, drag(kind, v, ModifierHandle.Role.WAVELENGTH, ModifierPoint(70f, 150f)).getValue("wavelength")[0], 0.11f)
		assertEquals(-25f, drag(kind, v, ModifierHandle.Role.AMPLITUDE, ModifierPoint(75f, 40f)).getValue("amplitude")[0], 0.11f)
		assertEquals(0f, drag(kind, v, ModifierHandle.Role.RAMP, ModifierPoint(60f, -40f)).getValue("ramp")[0])
		assertEquals(0f, drag(kind, v, ModifierHandle.Role.DIRECTION, ModifierPoint(270f, 0f)).getValue("direction")[0], 0.11f)
		// The drawn wave is the modifier's own: it passes through the amplitude handle at no phase and no ramp.
		val crest = values(kind, mapOf("amplitude" to floatArrayOf(10f), "ramp" to floatArrayOf(0f)))
		val curve = ModifierHandles.guides(kind.id, crest, reach)[1].points
		assertTrue(curve.any { hypot(it.x - 40f, it.y - 50f) < 1f })
	}

	@Test
	fun `lattice corners reshape the cage and control points carry their offsets`() {
		val kind = ModifierKinds.Lattice
		val v = values(kind)
		// A corner's handle sits off the corner, clear of the control point there.
		assertNear(ModifierPoint(-10f, -10f), at(kind, v, ModifierHandle.Role.CORNER, 0))
		assertNear(ModifierPoint(0f, 0f), at(kind, v, ModifierHandle.Role.CONTROL, 0))
		assertNear(ModifierPoint(100f / 3, 200f / 3), at(kind, v, ModifierHandle.Role.CONTROL, 5))
		val corner = drag(kind, v, ModifierHandle.Role.CORNER, ModifierPoint(130f, 230f), index = 3)
		assertContentEquals(floatArrayOf(0f, 0f, 120f, 220f), corner.getValue("bounds"))
		// The cage does not turn inside out.
		val crossed = drag(kind, v, ModifierHandle.Role.CORNER, ModifierPoint(500f, 500f), index = 0)
		assertContentEquals(floatArrayOf(99f, 199f, 100f, 200f), crossed.getValue("bounds"))
		val moved = drag(kind, v, ModifierHandle.Role.CONTROL, ModifierPoint(100f / 3 + 5, 200f / 3 - 7), index = 5)
		val offsets = moved.getValue("offsets")
		assertEquals(5f, offsets[10], 0.11f); assertEquals(-7f, offsets[11], 0.11f)
		assertEquals(32, offsets.size)
		assertTrue(offsets.filterIndexed { i, _ -> i != 10 && i != 11 }.all { it == 0f })
		// The rest cage, a line per row and per column of control points, and each corner's tie.
		assertEquals(1 + 4 + 4 + 4, ModifierHandles.guides(kind.id, moved, reach).size)
	}

	@Test
	fun `path handles move the control points, their rest places and the fade`() {
		val kind = ModifierKinds.Path
		val v = values(kind)
		// Down the middle: control points at a third of the height each.
		assertNear(ModifierPoint(50f, 200f / 3), at(kind, v, ModifierHandle.Role.CONTROL, 1))
		assertNear(ModifierPoint(50f, 200f / 3), at(kind, v, ModifierHandle.Role.PATH, 1))
		assertNear(ModifierPoint(50f, 100f), at(kind, v, ModifierHandle.Role.FADE))
		val moved = drag(kind, v, ModifierHandle.Role.CONTROL, ModifierPoint(70f, 200f / 3 + 5), index = 1)
		assertEquals(20f, moved.getValue("offsets")[2], 0.11f); assertEquals(5f, moved.getValue("offsets")[3], 0.11f)
		assertContentEquals(v.getValue("path"), moved.getValue("path"))
		// The rest point moves alone; the moved point keeps its offset from it.
		val rest = drag(kind, moved, ModifierHandle.Role.PATH, ModifierPoint(40f, 200f / 3), index = 1)
		assertEquals(40f, rest.getValue("path")[2], 0.11f)
		assertContentEquals(moved.getValue("offsets"), rest.getValue("offsets"))
		assertNear(ModifierPoint(60f, 200f / 3 + 5), at(kind, rest, ModifierHandle.Role.CONTROL, 1))
		// The fade is the distance across the rest curve, either side.
		assertEquals(30f, drag(kind, v, ModifierHandle.Role.FADE, ModifierPoint(80f, 110f)).getValue("radius")[0], 0.11f)
		// The moved curve drawn is the Bézier through the moved points.
		val curve = ModifierHandles.guides(kind.id, moved, reach)[1].points
		assertNear(ModifierHandles.bezier(FloatArray(8) { v.getValue("path")[it] + moved.getValue("offsets")[it] }, .5f), curve[curve.size / 2])
	}

	@Test
	fun `every kind has its handles and lines, and each handle names its value`() {
		for (kind in ModifierKinds.all) {
			val v = values(kind)
			val handles = ModifierHandles.handles(kind.id, v, reach)
			assertTrue(handles.isNotEmpty(), kind.id)
			assertTrue(ModifierHandles.guides(kind.id, v, reach).isNotEmpty(), kind.id)
			for (h in handles.keys) {
				assertTrue(ModifierHandles.value(h, v).isNotBlank())
				assertTrue(h.role.settings.all { s -> kind.settings.any { it.key == s } }, "${kind.id} ${h.role}")
			}
			// Handles of one kind start apart, so each can be grabbed - but a path's rest point, under its moved one until it moves.
			val entries = handles.entries.toList()
			for (i in entries.indices) for (j in i + 1 until entries.size) {
				val (a, pa) = entries[i]; val (b, pb) = entries[j]
				if (setOf(a.role, b.role) == setOf(ModifierHandle.Role.CONTROL, ModifierHandle.Role.PATH) && a.index == b.index) continue
				assertTrue(hypot(pa.x - pb.x, pa.y - pb.y) > 1f, "${kind.id} $a $b")
			}
		}
	}

	@Test
	fun `an edit keys a keyed setting at the pose and sets a constant one`() {
		val keyed = ModifierValue.Keyed(listOf(ModifierValue.Keyed.Axis("P", floatArrayOf(0f, 1f))), listOf(floatArrayOf(0f), floatArrayOf(30f)))
		val modifier = RigModifier("m", ModifierKinds.Transform.id, "Transform", ModifierTarget(listOf("a")), settings = mapOf("rotation" to keyed))
		val request = ModifierEdits.request(modifier, mapOf("rotation" to floatArrayOf(45f), "pivot" to floatArrayOf(1f, 2f))) { if (it == "P") 1f else 0f }
		assertEquals("m", request.getValue("id").jsonPrimitive.content)
		assertEquals(JsonArray(listOf(JsonPrimitive(1f), JsonPrimitive(2f))), request.getValue("settings").jsonObject.getValue("pivot"))
		val key = request.getValue("keys").jsonArray.single().jsonObject
		assertEquals("rotation", key.getValue("setting").jsonPrimitive.content)
		assertEquals(1f, key.getValue("at").jsonObject.getValue("P").jsonPrimitive.float)
		assertEquals(45f, key.getValue("value").jsonArray.single().jsonPrimitive.float)
		assertEquals(15f, ModifierEdits.values(modifier) { 0.5f }.getValue("rotation")[0])
	}
}
