package io.github.psd2live.scenario

import io.github.psd2live.core.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tails bend as flexible chains ([SkeletonFlex]). A generated tail is one chain that carries its mesh by the mesh's
 * connectivity, so a curled tail lying against itself bends one strand without dragging the other; a fish tail forks,
 * each lobe bending on its own; a chain straightens to rest in a line with its art unrolled, the drawing a pose it
 * returns to; and older projects' rigid tails open as flexible chains.
 */
class FlexTailScenario {
	@TempDir lateinit var temp: Path

	/**
	 * The curled tail's chain laid along its band: out of the hips and along the lower strand, up the turn at the right
	 * end in a short bone, and back along the upper strand. The skin is what these scenarios look at, so the chain is
	 * given rather than proposed; [theProposedChainRunsAlongTheCurl] looks at the proposal.
	 */
	private fun Studio.layCurledChain() {
		val base = skeleton()
		val hips = base.bones.single { it.role == BoneRole.LOWER_BODY }
		val tail = mesh("tail")
		fun bone(index: Int, x0: Float, y0: Float, x1: Float, y1: Float) = SkeletonBone("tail_$index", "tail_$index",
			if (index == 1) hips.id else "tail_${index - 1}", BoneRole.TAIL, Side.NONE, x0, y0, x1, y1,
			if (index == 1) listOf(tail) else emptyList(), chainIndex = index, flex = true)
		val curled = base.copy(bones = base.bones.filter { it.role != BoneRole.TAIL } + listOf(
			bone(1, 210f, 248f, 263.88617f, 273.98047f), bone(2, 263.88617f, 273.98047f, 352.26425f, 270.26917f),
			bone(3, 352.26425f, 270.26917f, 352.31488f, 250.47675f), bone(4, 352.31488f, 250.47675f, 278.79437f, 241.68338f)))
		edit("lay the chain along the band", "skeleton_put", req("spec" to curled.toJson()))
	}

	@Test fun theProposedChainRunsAlongTheCurl() = Studio.run(temp, Characters.tailed(curled = true)) {
		edit("apply the proposed skeleton", "skeleton_auto", req())
		val tails = skeleton().bones.filter { it.role == BoneRole.TAIL }.sortedBy { it.chainIndex }
		expect("chain", tails.size >= 3 && tails.all { it.flex }) { "the generated tail is not one flexible chain: $tails" }
		expect("one chain", tails.zipWithNext().all { (a, b) -> b.parentId == a.id }) { "the generated tail forks or breaks: $tails" }
		// Joints in the turns: the bones follow the band out along the lower strand, round the turn and back above it.
		val drawn = positions(mesh("tail"))
		val joints = tails.drop(1).map { it.headX to it.headY }
		for ((x, y) in joints) {
			val gap = (0 until drawn.size / 2).minOf { hypot(drawn[it * 2] - x, drawn[it * 2 + 1] - y) }
			expect("on the art", gap < 8.0) { "a joint at ($x, $y) is $gap px from the drawing" }
		}
		expect("round the turn", tails.last().tailX < tails.first().tailX + 120f && tails.last().tailY < tails.first().tailY - 15f) {
			"the chain does not come back above itself: $tails"
		}
	}

	@Test fun theProposedChainForksWhereTheTailForks() = Studio.run(temp, Characters.tailed(Characters.TailShape.FORKED)) {
		edit("apply the proposed skeleton", "skeleton_auto", req())
		val tails = skeleton().bones.filter { it.role == BoneRole.TAIL }
		expect("flexible", tails.all { it.flex }) { "the generated tail is not flexible: $tails" }
		val forks = tails.groupBy { it.parentId }.filterValues { it.size > 1 }
		expect("one fork", forks.size == 1) { "the stalk should fork once into its two lobes: $tails" }
		val (stalkEnd, lobes) = forks.entries.single()
		expect("parameters", tails.map { it.parameterId }.toSet().size == tails.size) { "two bones drive one parameter: ${tails.map { it.parameterId }}" }
		// The lobes reach out to the upper (390, 223) and lower (390, 319) tips, from the end of the stalk.
		val tips = tails.filter { b -> tails.none { it.parentId == b.id } }.map { it.tailX to it.tailY }
		expect("upper lobe", tips.any { (x, y) -> hypot(x - 390f, y - 223f) < 20f }) { "no bone reaches the upper lobe: $tips" }
		expect("lower lobe", tips.any { (x, y) -> hypot(x - 390f, y - 319f) < 20f }) { "no bone reaches the lower lobe: $tips" }
		val stalk = tails.single { it.id == stalkEnd }
		expect("fork", hypot(stalk.tailX - 328f, stalk.tailY - 271f) < 24f) { "the fork joint (${stalk.tailX}, ${stalk.tailY}) is not at the end of the stalk" }
		expect("lobes", lobes.all { it.headX == stalk.tailX && it.headY == stalk.tailY }) { "the lobes do not start at the fork: $lobes" }
	}

	@Test fun aCurledTailBendsOneStrandAlone() = Studio.run(temp, Characters.tailed(curled = true)) {
		edit("apply the proposed skeleton", "skeleton_auto", req())
		layCurledChain()
		val tails = skeleton().bones.filter { it.role == BoneRole.TAIL }
		expect("chain", tails.size >= 3 && tails.all { it.flex }) { "the tail is not one flexible chain: $tails" }
		val tail = mesh("tail")
		val drawn = positions(tail)
		val last = tails.maxBy { it.chainIndex }
		val moved = displacement(tail, mapOf(last.parameterId to 60f))
		// The lower strand runs out of the hips under the upper one, 4 px from it on the canvas.
		val lower = vertices(drawn) { x, y -> y >= 266f && x < 330f }
		val upper = vertices(drawn) { x, y -> y < 262f && x < 320f }
		expect("lower strand", lower.isNotEmpty() && lower.maxOf { moved[it] } < 0.5) {
			"bending the tip moves the strand beside it by ${lower.maxOfOrNull { moved[it] }} px"
		}
		expect("upper strand", upper.isNotEmpty() && upper.maxOf { moved[it] } > 10.0) {
			"bending the tip moves the tip's own strand only ${upper.maxOfOrNull { moved[it] }} px"
		}
		// A gently drawn joint bends either way without folding. (A joint drawn folded back on itself - this tail's
		// turn, a 16 px band turning round within 20 px - has vertices past its centre of curvature, which fold over
		// when it opens or closes further.)
		val bends = tails.drop(1).map { bone ->
			val parent = tails.single { it.id == bone.parentId }
			bone to SkeletonIk.wrap(SkeletonRig.restHeading(bone) - SkeletonRig.restHeading(parent))
		}
		for ((bone, drawnBend) in bends.filter { abs(it.second) < 45.0 }) for (angle in listOf(-60f, 60f)) {
			val (flipped, stretch) = smoothness(tail, mapOf(bone.parameterId to angle))
			expect("smooth", flipped == 0 && stretch < 0.75) { "bent ${bone.id} by $angle, the tail folds $flipped triangles and stretches an edge by $stretch" }
		}
		val exported = moc3()
		expect("export", Oracles.motion(exported, tail, mapOf(last.parameterId to 60f)) > 2.0) { "the exported tail does not bend" }
	}

	/**
	 * A straight tail bent at its joints curves one way only, like a rope: no stretch of it swings out against the turn
	 * on its way round (a joint turning the art behind it about itself would kick the art just before it outward, and a
	 * bent tail would ripple like a kris).
	 */
	@Test fun aBentTailCurvesOneWay() = Studio.run(temp, Characters.tailed(Characters.TailShape.ARC)) {
		edit("apply the proposed skeleton", "skeleton_auto", req())
		val tail = mesh("tail")
		val base = skeleton()
		val hips = base.bones.single { it.role == BoneRole.LOWER_BODY }
		val joints = (0..4).map { k -> Math.toRadians(90.0 - 22.5 * k).let { 240f + 100f * cos(it).toFloat() to 170f + 100f * sin(it).toFloat() } }
		val arc = base.copy(bones = base.bones.filter { it.role != BoneRole.TAIL } + (1..4).map { i ->
			SkeletonBone("tail_$i", "tail_$i", if (i == 1) hips.id else "tail_${i - 1}", BoneRole.TAIL, Side.NONE,
				joints[i - 1].first, joints[i - 1].second, joints[i].first, joints[i].second,
				if (i == 1) listOf(tail) else emptyList(), chainIndex = i, minAngle = -45f, maxAngle = 45f, flex = true, connected = i > 1)
		})
		edit("lay the tail along the band", "skeleton_put", req("spec" to arc.toJson()))
		edit("straighten the tail", "skeleton_put", req("spec" to SkeletonFlex.straightenOnArt(skeleton(), "tail_1", "tail drawn", puppet).toJson()))
		val root = skeleton().bone("tail_1")!!
		val heading = Math.toRadians(SkeletonRig.restHeading(root))
		val rest = positions(tail)
		fun along(p: FloatArray, v: Int) = (p[v * 2] - root.headX) * cos(heading) + (p[v * 2 + 1] - root.headY) * sin(heading)
		fun across(p: FloatArray, v: Int) = -(p[v * 2] - root.headX) * sin(heading) + (p[v * 2 + 1] - root.headY) * cos(heading)
		val middle = (0 until rest.size / 2).filter { abs(across(rest, it)) < 4.0 }.sortedBy { along(rest, it) }
		val tails = skeleton().bones.filter { it.role == BoneRole.TAIL }.sortedBy { it.chainIndex }
		val poses = tails.drop(1).flatMap { b -> listOf(mapOf(b.parameterId to 30f), mapOf(b.parameterId to -30f)) } +
			listOf(1f, -1f).map { sign -> tails.drop(1).associate { it.parameterId to 30f * sign } }
		for (pose in poses) {
			val bent = positions(tail, pose)
			// Every vertex of the middle swings to the side the tail turns to, or stays.
			val side = middle.map { across(bent, it) - across(rest, it) }
			val turn = side.maxBy { abs(it) }
			val against = side.maxOf { -it * kotlin.math.sign(turn) }
			expect("one way", against < 0.5) { "bent $pose, the middle of the tail swings ${"%.1f".format(against)} px against the turn" }
			// And its heading turns steadily: no bend in it doubles back.
			val headings = middle.zipWithNext().filter { (a, b) -> along(rest, b) - along(rest, a) > 4.0 }.map { (a, b) ->
				Math.toDegrees(kotlin.math.atan2(across(bent, b) - across(bent, a), along(bent, b) - along(bent, a)))
			}
			val back = headings.zipWithNext().maxOfOrNull { (a, b) -> -(b - a) * kotlin.math.sign(turn) } ?: 0.0
			expect("steady", back < 3.0) { "bent $pose, the tail's heading turns back by ${"%.1f".format(back)}°" }
		}
	}

	@Test fun aFishTailForks() = Studio.run(temp, Characters.tailed(curled = false)) {
		edit("apply the proposed skeleton", "skeleton_auto", req())
		val tail = mesh("tail")
		val base = skeleton()
		val hips = base.bones.single { it.role == BoneRole.LOWER_BODY }
		fun bone(id: String, parent: String, x0: Float, y0: Float, x1: Float, y1: Float, index: Int, parameter: String? = null, meshes: List<String> = emptyList()) =
			SkeletonBone(id, id, parent, BoneRole.TAIL, Side.NONE, x0, y0, x1, y1, meshes, chainIndex = index, flex = true, parameterOverride = parameter,
				connected = parent != hips.id)
		val fish = base.copy(bones = base.bones.filter { it.role != BoneRole.TAIL } + listOf(
			bone("tail_1", hips.id, 242f, 271f, 284f, 271f, 1, meshes = listOf(tail)),
			bone("tail_2", "tail_1", 284f, 271f, 326f, 271f, 2),
			bone("fin_a", "tail_2", 326f, 271f, 388f, 225f, 3, "ParamFinA"),
			bone("fin_b", "tail_2", 326f, 271f, 388f, 317f, 3, "ParamFinB"),
		))
		edit("fork the tail", "skeleton_put", req("spec" to fish.toJson()))
		val drawn = positions(tail)
		val moved = displacement(tail, mapOf("ParamFinA" to 40f))
		val lobeA = vertices(drawn) { x, y -> x > 360f && y < 250f }
		val lobeB = vertices(drawn) { x, y -> x > 345f && y > 292f }
		val stalk = vertices(drawn) { x, _ -> x < 315f }
		expect("lobe a", lobeA.isNotEmpty() && lobeA.maxOf { moved[it] } > 10.0) { "its own lobe moves only ${lobeA.maxOfOrNull { moved[it] }} px" }
		expect("lobe b", lobeB.isNotEmpty() && lobeB.maxOf { moved[it] } < 0.5) { "the other lobe moves ${lobeB.maxOfOrNull { moved[it] }} px" }
		expect("stalk", stalk.isNotEmpty() && stalk.maxOf { moved[it] } < 0.5) { "the stalk moves ${stalk.maxOfOrNull { moved[it] }} px" }
		// Bones laid along the art bend it a long way without folding.
		for ((parameter, angle) in listOf("ParamTail2" to -60f, "ParamTail2" to 60f, "ParamFinA" to -40f, "ParamFinA" to 40f)) {
			val (flipped, stretch) = smoothness(tail, mapOf(parameter to angle))
			expect("smooth", flipped == 0 && stretch < 0.6) { "bent $parameter by $angle, the tail folds $flipped triangles and stretches an edge by $stretch" }
		}
		// The mesh keeps up with its bones: bent at the stalk's joint, the art at the fork lands where the bone puts the
		// fork, neither short of it nor cutting inside the turn.
		val fork = (0 until drawn.size / 2).minBy { hypot(drawn[it * 2] - 326.0, drawn[it * 2 + 1] - 271.0) }
		for (angle in listOf(-40f, 40f)) {
			val bent = positions(tail, mapOf(fish.bone("tail_2")!!.parameterId to angle))
			val expected = SkeletonIk.rotate(drawn[fork * 2].toDouble(), drawn[fork * 2 + 1].toDouble(), 284.0, 271.0, angle.toDouble())
			val miss = hypot(bent[fork * 2] - expected[0], bent[fork * 2 + 1] - expected[1])
			expect("keeps up", miss < 1.5) { "bent $angle°, the art at the fork lands $miss px from where the bone puts it" }
		}
	}

	@Test fun olderRigidTailsOpenFlexible() {
		val rigid = SkeletonSpec(bones = listOf(
			SkeletonBone("lower_body", "Lower", null, BoneRole.LOWER_BODY, Side.NONE, 0f, 0f, 0f, 50f),
			SkeletonBone("tail_1", "Tail 1", "lower_body", BoneRole.TAIL, Side.NONE, 0f, 40f, 40f, 40f, chainIndex = 1),
			SkeletonBone("tail_2", "Tail 2", "tail_1", BoneRole.TAIL, Side.NONE, 40f, 40f, 80f, 40f, chainIndex = 2),
		))
		val legacy = JsonObject(rigid.toJson() + ("version" to JsonPrimitive(10)))
		assertTrue(SkeletonSpec.fromJson(legacy).bones.filter { it.role == BoneRole.TAIL }.all { it.flex })
		assertFalse(SkeletonSpec.fromJson(rigid.toJson()).bones.any { it.flex }, "a rigid tail saved by this version opened flexible")
		val flexible = rigid.copy(bones = rigid.bones.map { if (it.role == BoneRole.TAIL) it.copy(flex = true) else it })
		val bent = flexible.copy(bones = flexible.bones.map { if (it.id == "tail_2") it.copy(drawnBend = 30f) else it })
		assertEquals(bent, SkeletonSpec.fromJson(bent.toJson()))
	}

	@Test fun straighteningKeepsTheArtAndForks() {
		val spec = SkeletonSpec(bones = listOf(
			SkeletonBone("lower_body", "Lower", null, BoneRole.LOWER_BODY, Side.NONE, 0f, 0f, 0f, 50f),
			SkeletonBone("t1", "t1", "lower_body", BoneRole.TAIL, Side.NONE, 0f, 40f, 40f, 40f, chainIndex = 1, flex = true),
			SkeletonBone("t2", "t2", "t1", BoneRole.TAIL, Side.NONE, 40f, 40f, 60f, 75f, chainIndex = 2, flex = true, connected = true),
			SkeletonBone("a", "a", "t2", BoneRole.TAIL, Side.NONE, 60f, 75f, 90f, 80f, chainIndex = 3, flex = true, connected = true),
			SkeletonBone("b", "b", "t2", BoneRole.TAIL, Side.NONE, 60f, 75f, 70f, 110f, chainIndex = 3, flex = true, connected = true),
		))
		val straight = SkeletonFlex.straighten(spec, "t2")
		val t2 = straight.bone("t2")!!
		assertTrue(abs(SkeletonRig.restHeading(t2)) < 1e-3, "the joint did not straighten")
		// A fork keeps its spread, and the drawing is where it was.
		val drawn = SkeletonFlex.bound(straight)
		for (bone in spec.bones) {
			val d = drawn.bone(bone.id)!!
			assertTrue(hypot(d.tailX - bone.tailX, d.tailY - bone.tailY) < 1e-3, "${bone.id} drawn somewhere else")
		}
		assertTrue(straight.bone("a")!!.drawnBend == 0f && straight.bone("b")!!.drawnBend == 0f, "a fork straightened")
		assertEquals(spec.bones, SkeletonFlex.unstraighten(straight, "t1").bones.map { b -> spec.bone(b.id)!!.let { o ->
			b.copy(headX = o.headX, headY = o.headY, tailX = o.tailX, tailY = o.tailY, minAngle = o.minAngle, maxAngle = o.maxAngle) } })
	}

	private fun Studio.skeleton(): SkeletonSpec = now.document.rigEdits.skeleton!!

	/** [mesh]'s vertices on the canvas (y down) at [pose]. */
	private fun Studio.positions(mesh: String, pose: Map<String, Float> = emptyMap(), model: PuppetModel = puppet): FloatArray {
		val world = CpuDeformationEvaluator().evaluate(model, pose.mapKeys { ParameterId(it.key) }).worldPositions.getValue(DrawableId(mesh))
		return FloatArray(world.size) { if (it % 2 == 1) -world[it] else world[it] }
	}

	/** How far each vertex of [mesh] moves from rest to [pose], pixels. */
	private fun Studio.displacement(mesh: String, pose: Map<String, Float>): DoubleArray {
		val rest = positions(mesh)
		val moved = positions(mesh, pose)
		return DoubleArray(rest.size / 2) { hypot((moved[it * 2] - rest[it * 2]).toDouble(), (moved[it * 2 + 1] - rest[it * 2 + 1]).toDouble()) }
	}

	/**
	 * How [mesh] holds up at [pose]: how many triangles turn over, and the largest relative change of an edge's length.
	 * A rigid chain folds the inside of its joint over itself; a flexible one bends every edge a little.
	 */
	private fun Studio.smoothness(mesh: String, pose: Map<String, Float>): Pair<Int, Double> {
		val rest = positions(mesh)
		val moved = positions(mesh, pose)
		val indices = puppet.drawables.single { it.id.raw == mesh }.mesh!!.indices
		fun area(p: FloatArray, t: Int): Double {
			val a = indices[t]; val b = indices[t + 1]; val c = indices[t + 2]
			return ((p[b * 2] - p[a * 2]) * (p[c * 2 + 1] - p[a * 2 + 1]) - (p[b * 2 + 1] - p[a * 2 + 1]) * (p[c * 2] - p[a * 2])).toDouble()
		}
		var flipped = 0
		var worst = 0.0
		for (t in indices.indices step 3) {
			val before = area(rest, t)
			// Turned over, not just a sliver squeezed flat through zero; slivers themselves are left out.
			val after = area(moved, t)
			val longest = (0..2).maxOf { k -> val a = indices[t + k]; val b = indices[t + (k + 1) % 3]
				((rest[a * 2] - rest[b * 2]) * (rest[a * 2] - rest[b * 2]) + (rest[a * 2 + 1] - rest[b * 2 + 1]) * (rest[a * 2 + 1] - rest[b * 2 + 1])).toDouble() }
			if (abs(before) > 0.1 * longest && before * after < 0.0 && abs(after) > 0.25 * abs(before)) flipped++
			for (k in 0..2) {
				val a = indices[t + k]; val b = indices[t + (k + 1) % 3]
				val length = hypot((rest[a * 2] - rest[b * 2]).toDouble(), (rest[a * 2 + 1] - rest[b * 2 + 1]).toDouble())
				if (length < 4.0) continue
				val after = hypot((moved[a * 2] - moved[b * 2]).toDouble(), (moved[a * 2 + 1] - moved[b * 2 + 1]).toDouble())
				worst = maxOf(worst, abs(after - length) / length)
			}
		}
		return flipped to worst
	}

	private fun vertices(points: FloatArray, where: (Float, Float) -> Boolean) = (0 until points.size / 2).filter { where(points[it * 2], points[it * 2 + 1]) }
}
