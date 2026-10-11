package io.github.psd2live.core

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * A flexible chain lowered to a chain of nested warps, one per joint, for the meshes it carries whole.
 *
 * A mesh keyed on its chain's joints carries a keyform for every combination of their keys: a tail of six joints with
 * a few keys each is already past the keyform budget, the keys are thinned to the ends of each joint's range, and a
 * joint turned halfway draws the mesh on the chord between its rest and its full turn - the tail shrinks, more with
 * every joint turned at once. Nested warps add instead: each is keyed on its own joint alone, as densely as its reach
 * needs, and Cubism carries each through the ones around it.
 *
 * Each warp turns everything past its joint about the joint, the art beside the joint by a share that rises smoothly
 * along the chain from the middle of the bone before it to the middle of the bone after it (from the joint to the middle
 * of the lobe at a fork): an angle-weighted turn, so nothing comes nearer its joint or falls short of the bones, and the
 * bend spreads over both bones. Where along the chain a lattice point lies is the arc length the binding gave the mesh
 * vertex nearest it ([SkeletonFlex.Binding]), found along the mesh and not across it, and the lattice is fine enough
 * that a cell never spans two strands of the art that lie side by side without joining.
 */
internal object SkeletonFlexWarps {
	/** Cell size of the lattices where the art leaves room, canvas pixels. */
	private const val CELL = 16.0

	/** Smallest cell, and the most cells across. */
	private const val MIN_CELL = 3.0
	private const val MAX_CELLS = 64

	/** Joints a mesh must bend with before its chain is lowered to warps rather than its own keyforms. */
	const val MIN_JOINTS = 3

	/** One mesh a chain of warps carries: its drawn and rest vertices on the canvas and its binding to the chain. */
	class Rider(val id: DrawableId, val drawn: FloatArray, val rest: FloatArray, val binding: SkeletonFlex.Binding, val skins: List<VertexSkin>,
		val drawnBones: List<SkinBone>)

	/** Whether ([x], [y]) lies within [reach] of an edge of the mesh of [canvas] and [triangles]. */
	private fun near(canvas: FloatArray, triangles: IntArray, x: Double, y: Double, reach: Double): Boolean {
		for (at in 0 until triangles.size - 2 step 3) for (k in 0..2) {
			val a = triangles[at + k] * 2; val b = triangles[at + (k + 1) % 3] * 2
			val ax = canvas[a].toDouble(); val ay = canvas[a + 1].toDouble()
			val dx = canvas[b] - ax; val dy = canvas[b + 1] - ay
			if (x < minOf(ax, ax + dx) - reach || x > maxOf(ax, ax + dx) + reach || y < minOf(ay, ay + dy) - reach || y > maxOf(ay, ay + dy) + reach) continue
			val t = (((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy).coerceAtLeast(1e-12)).coerceIn(0.0, 1.0)
			if (hypot(x - ax - dx * t, y - ay - dy * t) < reach) return true
		}
		return false
	}

	/** Arc length at which each bone of [chains] starts along its chain, from the bones' [lengths]. */
	private fun starts(chains: SkeletonFlex.Chains, lengths: DoubleArray): DoubleArray {
		val starts = DoubleArray(lengths.size)
		for (i in lengths.indices) { val p = chains.flexParent[i]; if (p >= 0) starts[i] = starts[p] + lengths[p] }
		return starts
	}

	/**
	 * How much of joint [j]'s turn a point at arc length [s] on a path through the bones [path] takes: none short of
	 * the middle of the bone before the joint, all past the middle of the bone after it (at a fork, from the joint on).
	 */
	private fun share(chains: SkeletonFlex.Chains, lengths: DoubleArray, starts: DoubleArray, j: Int, s: Double, path: Set<Int>): Double {
		if (j !in path) return 0.0
		val from: Double; val to: Double
		if (chains.forks(j)) { from = starts[j]; to = starts[j] + lengths[j] * 0.5 }
		else { from = starts[j] - lengths[chains.flexParent[j]] * 0.5; to = starts[j] + lengths[j] * 0.5 }
		return SkeletonWeights.smoothstep((s - from) / (to - from).coerceAtLeast(1e-6))
	}

	/**
	 * Where a mesh drawn at [drawn], bound by [binding], rests once the joints of [tree] turn back by their drawn bends:
	 * the exact inverse of the warps' angle-weighted turns, so posing the drawn bends draws the art again. [restBones]
	 * place the joints.
	 */
	fun unroll(drawn: FloatArray, binding: SkeletonFlex.Binding, tree: List<SkeletonBone>, restBones: List<SkinBone>): FloatArray {
		val chains = binding.chains
		val starts = starts(chains, binding.lengths)
		val joints = tree.indices.filter { chains.flexParent[it] >= 0 && tree[it].drawnBend != 0f }
		val out = drawn.copyOf()
		for (v in 0 until drawn.size / 2) {
			if (!binding.bound(v)) continue
			val path = binding.paths[binding.path[v]].toSet()
			var x = drawn[v * 2].toDouble(); var y = drawn[v * 2 + 1].toDouble()
			// The outermost warp turned last; it is undone first.
			for (j in joints) {
				val w = share(chains, binding.lengths, starts, j, binding.s[v], path)
				if (w == 0.0) continue
				val p = SkeletonIk.rotate(x, y, restBones[j].headX, restBones[j].headY, -tree[j].drawnBend * w)
				x = p[0]; y = p[1]
			}
			out[v * 2] = x.toFloat(); out[v * 2 + 1] = y.toFloat()
		}
		return out
	}

	/**
	 * Whether [binding] and [skins] put every vertex of a mesh on the chain rooted at [tree]'s root, and the mesh bends
	 * with at least [MIN_JOINTS] of its joints.
	 */
	fun carries(tree: List<SkeletonBone>, binding: SkeletonFlex.Binding?, skins: List<VertexSkin>): Boolean {
		if (binding == null || !tree.first().flex || binding.chains.flexParent[0] >= 0) return false
		if (skins.indices.any { v -> !binding.bound(v) || !skins[v].rigid || binding.root(v) != 0 }) return false
		val joints = HashSet<Int>()
		for (v in skins.indices) joints += binding.bending(v)
		return joints.size >= MIN_JOINTS
	}

	/**
	 * [model] with [riders] hung from a chain of warps under the chain root's rotation [rootDeformer]: a warp per joint
	 * of [tree] (rest geometry) the riders bend with, keyed on its bone's parameter and turned by the rig [poses] as
	 * blend shapes. Each rider's vertices rest where [Rider.rest] has them, its earlier keyforms carried along.
	 */
	fun lower(
		model: PuppetModel,
		tree: List<SkeletonBone>,
		restBones: List<SkinBone>,
		rootDeformer: DeformerId,
		riders: List<Rider>,
		poses: List<SkeletonPose>,
		spec: SkeletonSpec,
		sampling: SkeletonSampling,
	): PuppetModel {
		if (riders.isEmpty()) return model
		val chains = riders.first().binding.chains
		val lengths = riders.first().binding.lengths
		val starts = starts(chains, lengths)
		val joints = tree.indices.filter { chains.flexParent[it] >= 0 && riders.any { r -> r.skins.indices.any { v -> it in r.binding.bending(v) } } }
		if (joints.isEmpty()) return model

		// Every rider vertex at rest with where it lies along the chain.
		val px = ArrayList<Double>(); val py = ArrayList<Double>(); val ps = ArrayList<Double>(); val paths = ArrayList<Set<Int>>()
		for (rider in riders) for (v in rider.skins.indices) {
			px += rider.rest[v * 2].toDouble(); py += rider.rest[v * 2 + 1].toDouble()
			ps += rider.binding.s[v]
			paths += rider.binding.paths[rider.binding.path[v]].toSet()
		}
		val count = px.size
		var left = px.min(); var right = px.max(); var top = py.min(); var bottom = py.max()
		val pad = CELL
		left -= pad; right += pad; top -= pad; bottom += pad

		// A cell no wider than the narrowest gap between strands of the art that lie side by side without joining.
		var cell = CELL
		val buckets = HashMap<Long, MutableList<Int>>()
		fun key(x: Double, y: Double) = (Math.floorDiv(x.toLong(), CELL.toLong()) shl 32) xor (Math.floorDiv(y.toLong(), CELL.toLong()) and 0xffffffffL)
		for (v in 0 until count) buckets.getOrPut(key(px[v], py[v])) { ArrayList() } += v
		for (v in 0 until count) {
			val bx = Math.floorDiv(px[v].toLong(), CELL.toLong()); val by = Math.floorDiv(py[v].toLong(), CELL.toLong())
			for (dx in -1L..1L) for (dy in -1L..1L) {
				for (w in buckets[((bx + dx) shl 32) xor ((by + dy) and 0xffffffffL)].orEmpty()) {
					if (w <= v) continue
					val d = hypot(px[v] - px[w], py[v] - py[w])
					if (d >= CELL) continue
					// How far apart along the chain: on one path, the difference; on two lobes, both ways back to the fork.
					val apart = if (paths[v] == paths[w] || paths[v].containsAll(paths[w]) || paths[w].containsAll(paths[v])) abs(ps[v] - ps[w]) else {
						val fork = paths[v].intersect(paths[w]).maxOfOrNull { starts[it] + lengths[it] } ?: 0.0
						(ps[v] - fork).coerceAtLeast(0.0) + (ps[w] - fork).coerceAtLeast(0.0)
					}
					if (apart > 3.0 * d + 2.0 * CELL) cell = minOf(cell, d / 1.5)
				}
			}
		}
		cell = cell.coerceAtLeast(MIN_CELL)
		val columns = ceil((right - left) / cell).toInt().coerceIn(2, MAX_CELLS)
		val rows = ceil((bottom - top) / cell).toInt().coerceIn(2, MAX_CELLS)
		// The lattice's own spacing, which the cap on its cells may have widened past [cell].
		val spacing = maxOf((right - left) / columns, (bottom - top) / rows)

		val triangles = riders.associate { rider -> rider.id to model.drawables.first { it.id == rider.id }.mesh!!.indices }
		// The lattice on the canvas at rest, each point where along the chain the nearest rider vertex lies.
		val lx = DoubleArray((rows + 1) * (columns + 1)); val ly = DoubleArray(lx.size)
		val ls = DoubleArray(lx.size); val lpath = arrayOfNulls<Set<Int>>(lx.size); val bare = BooleanArray(lx.size)
		for (row in 0..rows) for (column in 0..columns) {
			val i = row * (columns + 1) + column
			lx[i] = left + (right - left) * column / columns
			ly[i] = top + (bottom - top) * row / rows
			var best = 0; var bestDistance = Double.MAX_VALUE
			for (v in 0 until count) {
				val d = (px[v] - lx[i]) * (px[v] - lx[i]) + (py[v] - ly[i]) * (py[v] - ly[i])
				if (d < bestDistance) { bestDistance = d; best = v }
			}
			ls[i] = ps[best]; lpath[i] = paths[best]
			// Bare: off every rider's art and clear of its outline.
			bare[i] = bestDistance > (1.5 * spacing) * (1.5 * spacing) && riders.none { rider ->
				val t = triangles.getValue(rider.id)
				SkeletonFlex.inside(rider.rest, t, lx[i], ly[i]) || near(rider.rest, t, lx[i], ly[i], 1.5 * spacing)
			}
		}

		/**
		 * How much of joint [j]'s turn lattice point [i] takes: what the art nearest it takes. A warp reads its field where
		 * the warps inside it have moved the art to, and art they move is past them on the chain; so on bare canvas,
		 * which only art moved there can reach, each point takes what that art takes ([inward] when the warps inside
		 * move none).
		 */
		fun share(j: Int, i: Int, inward: Double): Double = if (bare[i]) inward else share(chains, lengths, starts, j, ls[i], lpath[i]!!)

		/** What the art the warps inside joint [j]'s move takes of [j]'s turn, on the whole. */
		fun inward(j: Int): Double {
			val inner = joints.subList(joints.indexOf(j) + 1, joints.size)
			var sum = 0.0; var n = 0
			for (v in 0 until count) {
				if (inner.none { k -> share(chains, lengths, starts, k, ps[v], paths[v]) > 0.0 }) continue
				sum += share(chains, lengths, starts, j, ps[v], paths[v]); n++
			}
			return if (n == 0) 1.0 else (sum / n).let { if (it >= 0.5) 1.0 else 0.0 }
		}

		var result = model
		var parent = rootDeformer
		val partId = (result.deformers.firstOrNull { it.id == rootDeformer })?.partId
		val defaults = result.parameters.associate { it.id to it.default }
		for (j in joints) {
			val bone = tree[j]
			val pivotX = restBones[j].headX; val pivotY = restBones[j].headY
			val inward = inward(j)
			val shares = DoubleArray(lx.size) { share(j, it, inward) }
			val parentRest = SkeletonRig.worlds(result, emptyMap(), setOf(parent)).getValue(parent)
			val guesses = FloatArray(lx.size * 2)
			// The parent's local point of every lattice point at rest, a start for the inversions at each key.
			for (i in lx.indices) {
				val g = SkeletonRig.inverse(parentRest, lx[i].toFloat(), ly[i].toFloat(), floatArrayOf(((lx[i] - left) / (right - left)).toFloat(), ((ly[i] - top) / (bottom - top)).toFloat()))
				guesses[i * 2] = g[0]; guesses[i * 2 + 1] = g[1]
			}
			fun lattice(degrees: Double): FloatArray {
				val out = FloatArray(lx.size * 2)
				for (i in lx.indices) {
					if (degrees == 0.0 || shares[i] == 0.0) { out[i * 2] = guesses[i * 2]; out[i * 2 + 1] = guesses[i * 2 + 1]; continue }
					val p = SkeletonIk.rotate(lx[i], ly[i], pivotX, pivotY, degrees * shares[i])
					val local = SkeletonRig.inverse(parentRest, p[0].toFloat(), p[1].toFloat(), floatArrayOf(guesses[i * 2], guesses[i * 2 + 1]))
					out[i * 2] = local[0]; out[i * 2 + 1] = local[1]
				}
				return out
			}
			// Keys close enough that the farthest point it turns keeps to its arc, and the drawing's own bend among them.
			val reach = lx.indices.filter { shares[it] > 0.0 }.maxOfOrNull { hypot(lx[it] - pivotX, ly[it] - pivotY) } ?: 0.0
			val step = SkeletonRig.arcStep(reach, sampling)
			val keys = (SkeletonRig.angleKeys(bone.minAngle to bone.maxAngle, step).toList() +
				listOfNotNull((bone.drawnBend * bone.direction).takeIf { bone.drawnBend != 0f && it in bone.minAngle..bone.maxAngle }))
				.distinct().sorted().toFloatArray()
			val parameter = ParameterId(bone.parameterId)
			val grid = KeyformGrid(listOf(KeyformAxis(parameter, keys)), keys.indices.map { k ->
				KeyformCell(intArrayOf(k), WarpLatticeForm(lattice(keys[k].toDouble() * bone.direction)))
			})
			val id = DeformerId(bone.deformerId + "Flex")
			var warp = Deformer.Warp(id, bone.name, parent, partId, rows, columns, true, grid)
			// The rig poses turn the joint as they turn its bone, added on top of its own turn.
			val reference = lattice(((defaults[parameter] ?: 0f) * bone.direction).toDouble())
			val posed = poses.mapNotNull { pose ->
				val turns = pose.keys.map { key -> (SkeletonPoses.rigTurns(spec, pose, key)[bone.id] ?: 0f) * bone.direction }
				if (turns.all { abs(it) < 1e-4f }) return@mapNotNull null
				val rest = lattice(0.0)
				SkeletonRig.blendBinding(pose.id, pose.keys) { ki ->
					val turned = lattice(turns[ki].toDouble())
					WarpForm(FloatArray(reference.size) { reference[it] + turned[it] - rest[it] })
				}
			}
			warp = warp.copy(blendShapes = posed)
			val at = result.deformers.indexOfFirst { it.id == parent } + 1
			result = result.copy(deformers = result.deformers.subList(0, at) + warp + result.deformers.subList(at, result.deformers.size))
			parent = id
		}

		// The riders under the innermost warp, resting where the chain puts them, with what they were keyed on before.
		val inner = SkeletonRig.worlds(result, emptyMap(), setOf(parent)).getValue(parent)
		val worldsAtRest = SkeletonRig.worlds(result, emptyMap())
		for (rider in riders) {
			val drawable = result.drawables.first { it.id == rider.id }
			val mesh = drawable.mesh ?: continue
			fun local(canvas: FloatArray): FloatArray = FloatArray(canvas.size).also { out ->
				for (i in canvas.indices step 2) {
					val p = SkeletonRig.inverse(inner, canvas[i], canvas[i + 1],
						floatArrayOf(((canvas[i] - left) / (right - left)).toFloat(), ((canvas[i + 1] - top) / (bottom - top)).toFloat()))
					out[i] = p[0]; out[i + 1] = p[1]
				}
			}
			val positions = local(rider.rest)
			val oldParent = drawable.parentDeformerId?.let { worldsAtRest[it] }
			val turns = if (rider.drawn === rider.rest) null else rider.binding.restTurns(rider.drawnBones, restBones)
			// A delta keyed before, on the canvas, turned with the chain's frame where the chain unrolled the art.
			fun carried(deltas: FloatArray): FloatArray {
				val canvas = FloatArray(deltas.size)
				val base = FloatArray(2); val moved = FloatArray(2)
				for (i in deltas.indices step 2) {
					if (oldParent != null) {
						oldParent.apply(mesh.positions[i], mesh.positions[i + 1], base, 0)
						oldParent.apply(mesh.positions[i] + deltas[i], mesh.positions[i + 1] + deltas[i + 1], moved, 0)
					} else {
						base[0] = mesh.positions[i]; base[1] = mesh.positions[i + 1]
						moved[0] = base[0] + deltas[i]; moved[1] = base[1] + deltas[i + 1]
					}
					val d = SkeletonIk.rotate((moved[0] - base[0]).toDouble(), (moved[1] - base[1]).toDouble(), 0.0, 0.0, turns?.get(i / 2) ?: 0.0)
					canvas[i] = rider.rest[i] + d[0].toFloat(); canvas[i + 1] = rider.rest[i + 1] + d[1].toFloat()
				}
				val out = local(canvas)
				for (i in out.indices) out[i] -= positions[i]
				return out
			}
			val grid = drawable.geometryGrid?.let { g -> KeyformGrid(g.axes, g.cells.map { KeyformCell(it.coordinate, MeshDeltaForm(carried(it.form.positionDeltas))) }) }
			val blendShapes = drawable.blendShapes.map { binding ->
				binding.copy(forms = binding.forms.map { form -> form?.let { MeshForm(carried(it.positionDeltas), it.drawOrder, it.opacity, it.multiplyColor, it.screenColor) } })
			}
			val skinBones = rider.drawnBones
			val lowered = drawable.copy(parentDeformerId = parent, geometryGrid = grid, blendShapes = blendShapes,
				mesh = DrawableMesh(positions, mesh.uvs, SkeletonRig.foldDrawOrder(rider.drawn, mesh.indices, rider.skins, skinBones)))
			result = result.copy(drawables = result.drawables.map { if (it.id == rider.id) lowered else it })
		}
		return result
	}
}
