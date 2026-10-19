package io.github.psd2live.core.mesh.structure

import io.github.psd2live.core.mesh.Point
import io.github.psd2live.core.mesh.distance
import io.github.psd2live.core.mesh.distanceSquaredToSegment
import kotlin.math.*

/** What a structure line most likely is; only the author decides that a line folds the material. */
internal enum class StructureKind {
	/** A crease: a dark stroke with free or tapered ends or shading on one side, or the axis of a shadow wedge. */
	FOLD,
	/** The boundary of a shading tone. */
	SHADE,
	/** A printed or drawn line: colour boundaries, closed rings, evenly repeated strokes, trims along the outline. */
	PATTERN,
}

/**
 * A polyline in source pixels (pixel centres at `i + 0.5`). [width] is the stroke scale the line was found at,
 * [freeEnds] the ends that stop inside the material (not at the outline or another line), and [banded] whether the
 * line itself is a stroke the mesh should straddle (a wedge axis already has the wedge sides around it).
 */
internal data class StructureLine(val id: String, val points: List<Point>, val kind: StructureKind, val strength: Double,
                                  val width: Double, val closed: Boolean, val freeEnds: Int, val banded: Boolean) {
	val length: Double get() = points.zipWithNext().sumOf { (a, b) -> distance(a, b) } +
		if (closed && points.size > 2) distance(points.last(), points.first()) else 0.0
}

internal data class StructureSettings(
	/** 0 keeps only strong, high-contrast structure; 1 also keeps faint lines. */
	val sensitivity: Double = .5,
	/** Shortest line kept, in source pixels. */
	val minLength: Double = 16.0,
	val kinds: Set<StructureKind> = StructureKind.entries.toSet(),
	val maxLines: Int = 48,
) {
	init {
		require(sensitivity in 0.0..1.0) { "sensitivity must be within 0..1" }
		require(minLength in 2.0..512.0) { "min_length must be within 2..512" }
		require(maxLines in 1..64) { "max_lines must be within 1..64" }
	}
}

internal class StructureAnalysis(val width: Int, val height: Int, val scale: Int, val lines: List<StructureLine>)

/**
 * Texture structure of one layer: where the drawing has creases, shading boundaries and pattern lines. Reads pixels
 * only; never edits them. Deterministic, bounded to [MAX_SIDE] working pixels per side, and checks [checkpoint]
 * between rows and stages.
 *
 * Responses are measured in contrast units (OKLab lightness steps) at four scales - the widest for folds painted as
 * soft shading rather than drawn: Hessian valleys for dark strokes,
 * Gaussian gradients for tone steps and Di Zenzo chroma gradients for colour boundaries, each corrected for the
 * others' side lobes (a step edge has valley lobes on its flanks, a stroke has gradient lobes on its), then weighted
 * by structure-tensor coherence so noise, dots and grain fall away.
 */
internal object TextureStructure {
	const val MAX_SIDE = 1024
	private val SCALES = doubleArrayOf(1.0, 2.0, 4.0, 8.0)
	private val ROOT_TWO_PI = sqrt(2 * PI)

	private enum class Label { RIDGE, STEP, CHROMA }

	fun analyze(width: Int, height: Int, rgba: ByteArray, settings: StructureSettings = StructureSettings(),
	            domain: List<List<Point>>? = null, checkpoint: () -> Unit = {}): StructureAnalysis {
		require(width > 0 && height > 0 && rgba.size.toLong() == width.toLong() * height * 4) { "Invalid raster" }
		val f = ceil(max(width, height) / MAX_SIDE.toDouble()).toInt().coerceAtLeast(1)
		val image = Working.of(width, height, rgba, f, domain, checkpoint)
		val maps = Responses.measure(image, checkpoint)
		val skeleton = Skeleton.extract(image, maps, settings.sensitivity, checkpoint)
		val chains = Tracing.chains(image, maps, skeleton, checkpoint)
		val lines = Classifier.classify(image, maps, chains, settings, checkpoint)
		val scaled = lines.map { line -> line.copy(points = line.points.map { Point(it.x * f, it.y * f) }, width = line.width * f) }
			.filter { it.length >= settings.minLength && it.kind in settings.kinds }
			.sortedWith(compareByDescending<StructureLine> { it.strength }.thenBy { it.points.first().y }.thenBy { it.points.first().x })
			.take(settings.maxLines)
			.mapIndexed { index, line -> line.copy(id = "s${index + 1}") }
		return StructureAnalysis(width, height, f, scaled)
	}

	/** Working planes: OKLab channels with the outside filled from the inside, and the material and its depth. */
	internal class Working(val w: Int, val h: Int, val l: FloatArray, val a: FloatArray, val b: FloatArray,
	                       val inside: BooleanArray, val depth: FloatArray) {
		companion object {
			fun of(width: Int, height: Int, rgba: ByteArray, f: Int, domain: List<List<Point>>?, checkpoint: () -> Unit): Working {
				val w = (width + f - 1) / f; val h = (height + f - 1) / f
				val n = w * h
				val alpha = FloatArray(n); val l = FloatArray(n); val ca = FloatArray(n); val cb = FloatArray(n)
				val sums = DoubleArray(4)
				for (y in 0 until h) {
					checkpoint()
					for (x in 0 until w) {
						sums.fill(0.0); var count = 0
						for (sy in y * f until min(height, (y + 1) * f)) for (sx in x * f until min(width, (x + 1) * f)) {
							val i = (sy * width + sx) * 4
							val al = (rgba[i + 3].toInt() and 255) / 255.0
							sums[0] += linear(rgba[i]) * al; sums[1] += linear(rgba[i + 1]) * al; sums[2] += linear(rgba[i + 2]) * al
							sums[3] += al; count++
						}
						val index = y * w + x
						alpha[index] = (sums[3] / count).toFloat()
						if (sums[3] > 1e-6) {
							val lab = oklab(sums[0] / sums[3], sums[1] / sums[3], sums[2] / sums[3])
							l[index] = lab[0].toFloat(); ca[index] = lab[1].toFloat(); cb[index] = lab[2].toFloat()
						}
					}
				}
				val solid = BooleanArray(n) { alpha[it] >= .5f }
				val inside = if (domain == null) solid else rasterize(domain, w, h, f).let { d -> BooleanArray(n) { solid[it] && d[it] } }
				// The silhouette is not texture: outside pixels take the material's own colours so that no blur
				// sees the outline as a step.
				for (plane in arrayOf(l, ca, cb)) fillOutside(plane, solid, w, h, checkpoint)
				return Working(w, h, l, ca, cb, inside, chamfer(inside, w, h))
			}

			private fun fillOutside(plane: FloatArray, solid: BooleanArray, w: Int, h: Int, checkpoint: () -> Unit) {
				val weight = FloatArray(plane.size) { if (solid[it]) 1f else 0f }
				val weighted = FloatArray(plane.size) { plane[it] * weight[it] }
				val num = Filters.blur(weighted, w, h, 6.0, checkpoint); val den = Filters.blur(weight, w, h, 6.0, checkpoint)
				var total = 0.0; var count = 0
				for (i in plane.indices) if (solid[i]) { total += plane[i]; count++ }
				val mean = if (count == 0) 0f else (total / count).toFloat()
				for (i in plane.indices) if (!solid[i]) plane[i] = if (den[i] > 1e-4f) num[i] / den[i] else mean
			}
		}
	}

	/** Per-pixel best responses in contrast units, their normals and the stroke scale. */
	internal class Responses(val ridge: FloatArray, val step: FloatArray, val chroma: FloatArray, val nx: FloatArray,
	                         val ny: FloatArray, val scale: FloatArray, val best: FloatArray, val label: ByteArray,
	                         val smooth: FloatArray, val smoothA: FloatArray, val smoothB: FloatArray) {
		companion object {
			fun measure(image: Working, checkpoint: () -> Unit): Responses {
				val w = image.w; val h = image.h; val n = w * h
				val ridge = FloatArray(n); val ridgeNx = FloatArray(n); val ridgeNy = FloatArray(n); val scale = FloatArray(n) { 1f }
				val step = FloatArray(n); val stepNx = FloatArray(n); val stepNy = FloatArray(n)
				val chroma = FloatArray(n); val chromaNx = FloatArray(n); val chromaNy = FloatArray(n)
				var smooth = image.l; var smoothA = image.a; var smoothB = image.b
				for (sigma in SCALES) {
					val ls = Filters.blur(image.l, w, h, sigma, checkpoint)
					val chromaA = if (sigma <= 2.0) Filters.blur(image.a, w, h, sigma, checkpoint) else null
					val chromaB = if (sigma <= 2.0) Filters.blur(image.b, w, h, sigma, checkpoint) else null
					if (sigma == SCALES[0]) { smooth = ls; smoothA = chromaA!!; smoothB = chromaB!! }
					for (y in 0 until h) {
						checkpoint()
						for (x in 0 until w) {
							val i = y * w + x
							// A response at this scale needs the whole kernel inside the material.
							if (!image.inside[i] || image.depth[i] <= 2 * sigma + 1) continue
							val gx = Filters.dx(ls, w, h, x, y); val gy = Filters.dy(ls, w, h, x, y)
							val gradient = (hypot(gx, gy) * sigma * ROOT_TWO_PI).toFloat()
							val xx = Filters.dxx(ls, w, h, x, y); val yy = Filters.dyy(ls, w, h, x, y); val xy = Filters.dxy(ls, w, h, x, y)
							val root = sqrt((xx - yy) * (xx - yy) + 4 * xy * xy)
							val l1 = (xx + yy + root) / 2; val l2 = (xx + yy - root) / 2
							// A dark stroke curves up across itself: a large positive eigenvalue, a flat one along it.
							// The gradient term cancels the valley lobes that flank a tone step.
							val valley = ((l1 - abs(l2)) * sigma * sigma * ROOT_TWO_PI - 1.2 * gradient).toFloat()
							if (valley > ridge[i]) {
								ridge[i] = valley; scale[i] = sigma.toFloat()
								val vx: Double; val vy: Double
								if (abs(xy) > 1e-12) { vx = l1 - yy; vy = xy } else if (xx >= yy) { vx = 1.0; vy = 0.0 } else { vx = 0.0; vy = 1.0 }
								val norm = hypot(vx, vy).coerceAtLeast(1e-12)
								ridgeNx[i] = (vx / norm).toFloat(); ridgeNy[i] = (vy / norm).toFloat()
							}
							if (sigma <= 2.0 && gradient > step[i]) {
								step[i] = gradient
								val norm = hypot(gx, gy).coerceAtLeast(1e-12)
								stepNx[i] = (gx / norm).toFloat(); stepNy[i] = (gy / norm).toFloat()
							}
							if (chromaA != null && chromaB != null) {
								val ax = Filters.dx(chromaA, w, h, x, y); val ay = Filters.dy(chromaA, w, h, x, y)
								val bx = Filters.dx(chromaB, w, h, x, y); val by = Filters.dy(chromaB, w, h, x, y)
								val gxx = ax * ax + bx * bx; val gyy = ay * ay + by * by; val gxy = ax * ay + bx * by
								val lambda = (gxx + gyy + sqrt((gxx - gyy) * (gxx - gyy) + 4 * gxy * gxy)) / 2
								val colour = (sqrt(lambda) * sigma * ROOT_TWO_PI * 1.5).toFloat()
								if (colour > chroma[i]) {
									chroma[i] = colour
									val theta = atan2(2 * gxy, gxx - gyy) / 2
									chromaNx[i] = cos(theta).toFloat(); chromaNy[i] = sin(theta).toFloat()
								}
							}
						}
					}
				}
				val coherence = coherence(smooth, w, h, checkpoint)
				// Contrast is read against its surroundings: a fold drawn faintly on dark cloth counts as much as one
				// drawn boldly on light cloth, within limits that keep grain from being lifted into lines.
				val mean = Filters.blur(smooth, w, h, 12.0, checkpoint)
				val variance = Filters.blur(FloatArray(n) { (smooth[it] - mean[it]).let { d -> d * d } }, w, h, 12.0, checkpoint)
				// A stroke has gradient lobes on both flanks; they are not tone steps.
				val ridgeNear = Filters.dilate(ridge, w, h, 2)
				val best = FloatArray(n); val label = ByteArray(n); val nx = FloatArray(n); val ny = FloatArray(n)
				for (i in 0 until n) {
					val gate = smoothstep(.12f, .45f, coherence[i]) * (.12f / (sqrt(variance[i]) + .03f)).coerceIn(.6f, 2.5f)
					val r = max(0f, ridge[i]) * gate
					val s = max(0f, step[i] - 1.2f * max(0f, ridgeNear[i])) * gate
					val c = max(0f, chroma[i] - .5f * max(0f, ridgeNear[i])) * gate
					ridge[i] = r; step[i] = s; chroma[i] = c
					var kind = Label.RIDGE; var value = r
					if (s > value) { kind = Label.STEP; value = s }
					// A tone step that also changes hue is a colour boundary.
					if (c > value || (kind == Label.STEP && c >= .8f * s)) { kind = Label.CHROMA; value = max(value, c) }
					best[i] = value; label[i] = kind.ordinal.toByte()
					when (kind) {
						Label.RIDGE -> { nx[i] = ridgeNx[i]; ny[i] = ridgeNy[i] }
						Label.STEP -> { nx[i] = stepNx[i]; ny[i] = stepNy[i] }
						Label.CHROMA -> { nx[i] = chromaNx[i]; ny[i] = chromaNy[i] }
					}
				}
				return Responses(ridge, step, chroma, nx, ny, scale, best, label, smooth, smoothA, smoothB)
			}

			private fun coherence(plane: FloatArray, w: Int, h: Int, checkpoint: () -> Unit): FloatArray {
				val jxx = FloatArray(plane.size); val jyy = FloatArray(plane.size); val jxy = FloatArray(plane.size)
				for (y in 0 until h) for (x in 0 until w) {
					val gx = Filters.dx(plane, w, h, x, y); val gy = Filters.dy(plane, w, h, x, y)
					val i = y * w + x
					jxx[i] = (gx * gx).toFloat(); jyy[i] = (gy * gy).toFloat(); jxy[i] = (gx * gy).toFloat()
				}
				val sxx = Filters.blur(jxx, w, h, 3.0, checkpoint); val syy = Filters.blur(jyy, w, h, 3.0, checkpoint)
				val sxy = Filters.blur(jxy, w, h, 3.0, checkpoint)
				return FloatArray(plane.size) { i ->
					val root = sqrt((sxx[i] - syy[i]) * (sxx[i] - syy[i]) + 4 * sxy[i] * sxy[i])
					val sum = sxx[i] + syy[i]
					if (sum <= 1e-12f) 0f else (root / sum).let { it * it }
				}
			}
		}
	}

	/** One-pixel skeleton of the responses that survive non-maximum suppression and hysteresis. */
	internal object Skeleton {
		fun extract(image: Working, maps: Responses, sensitivity: Double, checkpoint: () -> Unit): BooleanArray {
			val w = image.w; val h = image.h
			val high = (.22 + (.05 - .22) * sensitivity).toFloat(); val low = high * .5f
			val kept = BooleanArray(w * h)
			for (y in 1 until h - 1) {
				checkpoint()
				for (x in 1 until w - 1) {
					val i = y * w + x
					val r = maps.best[i]
					if (r < low) continue
					val ox = round(maps.nx[i]).toInt(); val oy = round(maps.ny[i]).toInt()
					if (ox == 0 && oy == 0) continue
					val ahead = maps.best[(y + oy) * w + x + ox]; val behind = maps.best[(y - oy) * w + x - ox]
					if (r >= ahead && r > behind) kept[i] = true
				}
			}
			val strong = BooleanArray(w * h)
			val queue = ArrayDeque<Int>()
			for (i in kept.indices) if (kept[i] && maps.best[i] >= high) { strong[i] = true; queue += i }
			while (queue.isNotEmpty()) {
				val i = queue.removeFirst(); val x = i % w; val y = i / w
				for (dy in -1..1) for (dx in -1..1) {
					val nx = x + dx; val ny = y + dy
					if (nx !in 0 until w || ny !in 0 until h) continue
					val j = ny * w + nx
					if (kept[j] && !strong[j]) { strong[j] = true; queue += j }
				}
			}
			thin(strong, w, h, checkpoint)
			return strong
		}

		/** Zhang-Suen thinning. */
		private fun thin(set: BooleanArray, w: Int, h: Int, checkpoint: () -> Unit) {
			var changed = true
			val remove = ArrayList<Int>()
			while (changed) {
				changed = false
				for (pass in 0..1) {
					checkpoint()
					remove.clear()
					for (y in 1 until h - 1) for (x in 1 until w - 1) {
						val i = y * w + x
						if (!set[i]) continue
						val p = intArrayOf(i - w, i - w + 1, i + 1, i + w + 1, i + w, i + w - 1, i - 1, i - w - 1).map { set[it] }
						val count = p.count { it }
						if (count < 2 || count > 6) continue
						val transitions = (0 until 8).count { !p[it] && p[(it + 1) % 8] }
						if (transitions != 1) continue
						if (pass == 0 && (p[0] && p[2] && p[4] || p[2] && p[4] && p[6])) continue
						if (pass == 1 && (p[0] && p[2] && p[6] || p[0] && p[4] && p[6])) continue
						remove += i
					}
					if (remove.isNotEmpty()) { changed = true; remove.forEach { set[it] = false } }
				}
			}
		}
	}

	/** A traced run of skeleton pixels, as working coordinates, with how each end finishes. */
	internal class Chain(var points: MutableList<Point>, var closed: Boolean, var startEnd: End, var endEnd: End)

	internal enum class End { FREE, JUNCTION, OUTLINE }

	internal object Tracing {
		private val OFFSETS = arrayOf(intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(-1, 1),
			intArrayOf(-1, 0), intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1))

		fun chains(image: Working, maps: Responses, set: BooleanArray, checkpoint: () -> Unit): List<Chain> {
			val w = image.w; val h = image.h
			fun on(x: Int, y: Int) = x in 0 until w && y in 0 until h && set[y * w + x]
			// m-adjacency: a diagonal neighbour also reachable through a shared side neighbour is not a link.
			fun neighbours(i: Int): List<Int> {
				val x = i % w; val y = i / w
				return OFFSETS.filter { (dx, dy) ->
					on(x + dx, y + dy) && (dx == 0 || dy == 0 || (!on(x + dx, y) && !on(x, y + dy)))
				}.map { (dx, dy) -> (y + dy) * w + x + dx }
			}
			val degree = HashMap<Int, Int>()
			for (i in set.indices) if (set[i]) degree[i] = neighbours(i).size
			val visited = HashSet<Long>()
			fun link(a: Int, b: Int) = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
			val raw = ArrayList<Triple<MutableList<Int>, Int, Int>>()
			val nodes = degree.keys.filter { degree[it] != 2 }.sorted()
			for (node in nodes) {
				checkpoint()
				for (next in neighbours(node)) {
					if (!visited.add(link(node, next))) continue
					val run = mutableListOf(node, next)
					var previous = node; var current = next
					while (degree[current] == 2) {
						val step = neighbours(current).firstOrNull { it != previous && link(current, it) !in visited } ?: break
						visited += link(current, step)
						previous = current; current = step; run += current
					}
					raw += Triple(run, node, current)
				}
			}
			// Rings of degree-2 pixels have no node.
			for (start in degree.keys.sorted()) {
				if (degree[start] != 2 || neighbours(start).any { link(start, it) in visited }) continue
				checkpoint()
				val run = mutableListOf(start)
				var previous = -1; var current = start
				while (true) {
					val step = neighbours(current).firstOrNull { it != previous && link(current, it) !in visited } ?: break
					visited += link(current, step)
					if (step == start) break
					previous = current; current = step; run += current
				}
				if (run.size >= 3) raw += Triple(run, -1, -1)
			}
			fun point(i: Int) = Point(i % w + .5, i / w + .5)
			fun endOf(node: Int): End = when {
				node < 0 -> End.FREE
				(degree[node] ?: 0) >= 3 -> End.JUNCTION
				image.depth[node] <= 2 * maps.scale[node] + 4 -> End.OUTLINE
				else -> End.FREE
			}
			val chains = raw.map { (run, a, b) ->
				Chain(run.map(::point).toMutableList(), a < 0, endOf(a), endOf(b)).also { if (a < 0) { it.startEnd = End.FREE; it.endEnd = End.FREE } }
			}.filter { it.points.size >= 2 }.toMutableList()
			pairAtJunctions(chains, checkpoint)
			bridge(chains, maps, w, checkpoint)
			return chains
		}

		private fun direction(points: List<Point>, fromStart: Boolean, reach: Double = 5.0): Point {
			val origin = if (fromStart) points.first() else points.last()
			val ordered = if (fromStart) points else points.asReversed()
			val target = ordered.firstOrNull { distance(it, origin) >= reach } ?: ordered.last()
			val dx = target.x - origin.x; val dy = target.y - origin.y
			val norm = hypot(dx, dy).coerceAtLeast(1e-9)
			return Point(dx / norm, dy / norm)
		}

		/** Continues each line straight through a crossing; the branch left over meets it as a T. */
		private fun pairAtJunctions(chains: MutableList<Chain>, checkpoint: () -> Unit) {
			var merged = true
			while (merged) {
				merged = false
				checkpoint()
				data class Tip(val chain: Chain, val start: Boolean) {
					val at: Point get() = if (start) chain.points.first() else chain.points.last()
				}
				val tips = chains.flatMap { c -> listOfNotNull(Tip(c, true).takeIf { c.startEnd == End.JUNCTION && !c.closed },
					Tip(c, false).takeIf { c.endEnd == End.JUNCTION && !c.closed }) }
				val byNode = tips.groupBy { it.at }.toSortedMap(compareBy<Point> { it.y }.thenBy { it.x })
				for ((_, group) in byNode) {
					if (group.size < 2) continue
					val pairs = ArrayList<Triple<Double, Tip, Tip>>()
					for (i in group.indices) for (j in i + 1 until group.size) {
						val a = group[i]; val b = group[j]
						val da = direction(a.chain.points, a.start); val db = direction(b.chain.points, b.start)
						val dot = da.x * db.x + da.y * db.y
						if (dot < -cos(35 * PI / 180)) pairs += Triple(dot, a, b)
					}
					val best = pairs.minByOrNull { it.first } ?: continue
					val (_, a, b) = best
					if (a.chain === b.chain) {
						a.chain.closed = true; a.chain.points.removeAt(a.chain.points.lastIndex)
						a.chain.startEnd = End.FREE; a.chain.endEnd = End.FREE
					} else {
						val first = if (a.start) a.chain.points.asReversed().toMutableList() else a.chain.points
						val second = if (b.start) b.chain.points else b.chain.points.asReversed().toMutableList()
						val farA = if (a.start) a.chain.endEnd else a.chain.startEnd
						val farB = if (b.start) b.chain.endEnd else b.chain.startEnd
						chains.remove(a.chain); chains.remove(b.chain)
						chains += Chain((first + second.drop(1)).toMutableList(), false, farA, farB)
					}
					merged = true
					break
				}
			}
		}

		/** Joins free ends that continue each other across a short gap. */
		private fun bridge(chains: MutableList<Chain>, maps: Responses, w: Int, checkpoint: () -> Unit) {
			var joined = true
			while (joined) {
				joined = false
				checkpoint()
				data class Tip(val chain: Chain, val start: Boolean) {
					val at: Point get() = if (start) chain.points.first() else chain.points.last()
					val outward: Point get() = direction(chain.points, start).let { Point(-it.x, -it.y) }
				}
				fun labelOf(c: Chain): Int = c.points.groupingBy { p ->
					maps.label[(p.y.toInt()) * w + p.x.toInt()].toInt() }.eachCount().maxBy { it.value }.key
				val tips = chains.flatMap { c -> listOfNotNull(Tip(c, true).takeIf { c.startEnd == End.FREE && !c.closed },
					Tip(c, false).takeIf { c.endEnd == End.FREE && !c.closed }) }
				var bestCost = Double.POSITIVE_INFINITY; var best: Pair<Tip, Tip>? = null
				for (i in tips.indices) for (j in i + 1 until tips.size) {
					val a = tips[i]; val b = tips[j]
					if (a.chain === b.chain) continue
					val gap = distance(a.at, b.at)
					val reach = max(6.0, 4.0 * max(maps.scale[a.at.y.toInt() * w + a.at.x.toInt()], maps.scale[b.at.y.toInt() * w + b.at.x.toInt()]))
					if (gap > reach || gap < 1e-9) continue
					val ux = (b.at.x - a.at.x) / gap; val uy = (b.at.y - a.at.y) / gap
					val ta = a.outward; val tb = b.outward
					val alongA = ta.x * ux + ta.y * uy; val alongB = -(tb.x * ux + tb.y * uy)
					if (alongA < cos(30 * PI / 180) || alongB < cos(30 * PI / 180)) continue
					if (labelOf(a.chain) != labelOf(b.chain)) continue
					val cost = gap * (3 - alongA - alongB)
					if (cost < bestCost) { bestCost = cost; best = a to b }
				}
				val (a, b) = best ?: break
				val first = if (a.start) a.chain.points.asReversed().toMutableList() else a.chain.points
				val second = if (b.start) b.chain.points else b.chain.points.asReversed().toMutableList()
				val farA = if (a.start) a.chain.endEnd else a.chain.startEnd
				val farB = if (b.start) b.chain.endEnd else b.chain.startEnd
				chains.remove(a.chain); chains.remove(b.chain)
				chains += Chain((first + second).toMutableList(), false, farA, farB)
				joined = true
			}
		}
	}

	/**
	 * Smooths the traced chains and decides what each one is. Strokes become creases only on evidence a crease
	 * leaves: an end that stops inside the material, a stroke that tapers, or shading on one side. A tone boundary
	 * that turns sharply back on itself is a shadow wedge; the wedge's axis is a crease of its own.
	 */
	internal object Classifier {
		fun classify(image: Working, maps: Responses, chains: List<Chain>, settings: StructureSettings,
		             checkpoint: () -> Unit): List<StructureLine> {
			val w = image.w
			fun index(p: Point) = p.y.toInt().coerceIn(0, image.h - 1) * w + p.x.toInt().coerceIn(0, w - 1)
			val minimum = settings.minLength / max(1, ceil(max(image.w, image.h) / MAX_SIDE.toDouble()).toInt())
			val out = ArrayList<StructureLine>()
			val strokes = ArrayList<Pair<StructureLine, Boolean>>()
			val arms = ArrayList<Arm>()
			for (chain in chains.sortedWith(compareBy<Chain> { it.points.first().y }.thenBy { it.points.first().x })) {
				checkpoint()
				val points = smooth(chain.points, chain.closed)
				if (pathLength(points, chain.closed) < minimum) continue
				val votes = DoubleArray(3)
				var response = 0.0; var widths = 0.0
				for (p in points) { val i = index(p); votes[maps.label[i].toInt()] += maps.best[i].toDouble(); response += maps.best[i]; widths += maps.scale[i] }
				response /= points.size; widths /= points.size
				val label = Label.entries[votes.indices.maxBy { votes[it] }]
				val free = (if (!chain.closed && chain.startEnd == End.FREE) 1 else 0) +
					(if (!chain.closed && chain.endEnd == End.FREE) 1 else 0)
				val strength = response * sqrt(pathLength(points, chain.closed) / minimum).coerceAtMost(4.0)
				when (label) {
					Label.CHROMA -> out += line(points, StructureKind.PATTERN, strength, widths, chain.closed, free, false)
					Label.STEP -> {
						val pieces = if (chain.closed) listOf(points) else splitAtCorners(points, widths)
						for ((k, piece) in pieces.withIndex()) {
							if (pathLength(piece, false) < minimum * .5) continue
							val shade = line(piece, StructureKind.SHADE, strength, widths, chain.closed, free, false)
							out += shade
							// A corner is a tip; so is an end that stops inside the material, as a broken apex does.
							if (!chain.closed) arms += Arm(shade, k > 0 || chain.startEnd != End.OUTLINE,
								k < pieces.lastIndex || chain.endEnd != End.OUTLINE)
						}
					}
					Label.RIDGE -> {
						val taper = tapers(points, chain, widths, maps, image)
						val oneSided = oneSided(points, widths, maps, image)
						// Ink between two different materials is a seam or an edge, however it is drawn.
						// A crease runs fairly straight; a stroke that wraps round is the outline of a shape on the cloth.
						val fold = !chain.closed && (taper || oneSided) && turning(points, widths) < 120 * PI / 180 &&
							!separates(points, widths, maps, image) &&
							!alongOutline(points, image, ::index, widths)
						val kind = if (fold) StructureKind.FOLD else StructureKind.PATTERN
						val l = line(points, kind, strength * (if (oneSided) 1.2 else 1.0), widths, chain.closed, free, true)
						out += l
						if (!chain.closed) strokes += l to taper
					}
				}
			}
			repeated(strokes.filter { !it.second }.map { it.first }).forEach { repeated ->
				val at = out.indexOf(repeated)
				if (at >= 0) out[at] = repeated.copy(kind = StructureKind.PATTERN)
			}
			// A thick stroke has tone steps along both of its sides; they belong to the stroke.
			val drawn = out.filter { it.banded }
			val flanks = out.filter { shade -> shade.kind == StructureKind.SHADE && drawn.any { stroke ->
				val reach = 3 * stroke.width + 3
				shade.points.count { p -> stroke.points.zipWithNext().any { (a, b) -> distanceSquaredToSegment(p, a, b) < reach * reach } } >=
					.6 * shade.points.size } }.toSet()
			out.removeAll(flanks)
			arms.removeAll { it.line in flanks }
			for (axis in wedges(arms, minimum, image, maps, checkpoint)) {
				// The narrow apex of a wedge, and at a wide scale its whole shadow, also reads as a stroke; the axis is the
				// better line for it.
				out.removeAll { other -> other.kind == StructureKind.FOLD && other.banded &&
					other.points.count { p -> axis.points.zipWithNext().any { (a, b) ->
						distanceSquaredToSegment(p, a, b) < max(6.25, 2.25 * other.width * other.width) } } >=
						.7 * other.points.size }
				out += axis
			}
			return out.map { it.copy(points = simplify(it.points, .35, it.closed)) }
		}

		/** A shading boundary with the ends that may be the tip of a wedge. */
		private class Arm(val line: StructureLine, val startTip: Boolean, val endTip: Boolean)

		/**
		 * Pairs tips of shading boundaries that meet - at a corner of one boundary or across a small gap between two -
		 * and leave in nearly the same direction: the two sides of one fold's shadow.
		 */
		private fun wedges(arms: List<Arm>, minimum: Double, image: Working, maps: Responses, checkpoint: () -> Unit): List<StructureLine> {
			class Tip(val arm: Int, val start: Boolean, val at: Point, val path: List<Point>)
			val tips = arms.flatMapIndexed { i, arm -> listOfNotNull(
				Tip(i, true, arm.line.points.first(), arm.line.points).takeIf { arm.startTip },
				Tip(i, false, arm.line.points.last(), arm.line.points.asReversed()).takeIf { arm.endTip }) }
			val candidates = ArrayList<Triple<Double, Tip, Tip>>()
			for (i in tips.indices) for (j in i + 1 until tips.size) {
				val a = tips[i]; val b = tips[j]
				if (a.arm == b.arm) continue
				val gap = distance(a.at, b.at)
				if (gap > max(10.0, 4 * max(arms[a.arm].line.width, arms[b.arm].line.width))) continue
				val da = directionFrom(a.path); val db = directionFrom(b.path)
				// Fold shadows are sharp; a blunter corner is where a shadow meets another edge.
				if (da.x * db.x + da.y * db.y < cos(40 * PI / 180)) continue
				candidates += Triple(gap, a, b)
			}
			val used = HashSet<Pair<Int, Boolean>>()
			val result = ArrayList<StructureLine>()
			for ((_, a, b) in candidates.sortedWith(compareBy<Triple<Double, Tip, Tip>> { it.first }.thenBy { it.second.arm }.thenBy { it.third.arm })) {
				checkpoint()
				if ((a.arm to a.start) in used || (b.arm to b.start) in used) continue
				val tip = Point((a.at.x + b.at.x) / 2, (a.at.y + b.at.y) / 2)
				val width = max(arms[a.arm].line.width, arms[b.arm].line.width)
				val axis = wedgeAxis(a.path, b.path, tip, width, image, maps) ?: continue
				if (pathLength(axis, false) < minimum) continue
				used += a.arm to a.start; used += b.arm to b.start
				result += line(axis, StructureKind.FOLD, max(arms[a.arm].line.strength, arms[b.arm].line.strength), width, false, 1, false)
			}
			return result
		}

		private fun line(points: List<Point>, kind: StructureKind, strength: Double, width: Double, closed: Boolean,
		                 free: Int, banded: Boolean) = StructureLine("", points, kind, strength, width, closed, free, banded)

		private fun pathLength(points: List<Point>, closed: Boolean) = points.zipWithNext().sumOf { (a, b) -> distance(a, b) } +
			if (closed && points.size > 2) distance(points.last(), points.first()) else 0.0

		private fun smooth(points: List<Point>, closed: Boolean): List<Point> {
			var current = points
			repeat(3) {
				current = current.indices.map { i ->
					val ends = !closed && (i == 0 || i == current.lastIndex)
					if (ends) current[i] else {
						val a = current[(i - 1 + current.size) % current.size]; val b = current[(i + 1) % current.size]
						Point(current[i].x * .5 + (a.x + b.x) * .25, current[i].y * .5 + (a.y + b.y) * .25)
					}
				}
			}
			return current
		}

		/** Splits where the line turns back by more than 100 degrees: the tips of a wedge. */
		private fun splitAtCorners(points: List<Point>, width: Double): List<List<Point>> {
			val reach = max(3.0, 2 * width)
			val turn = DoubleArray(points.size)
			for (i in points.indices) {
				val before = (i downTo 0).firstOrNull { distance(points[it], points[i]) >= reach } ?: continue
				val after = (i until points.size).firstOrNull { distance(points[it], points[i]) >= reach } ?: continue
				val ax = points[i].x - points[before].x; val ay = points[i].y - points[before].y
				val bx = points[after].x - points[i].x; val by = points[after].y - points[i].y
				turn[i] = acos(((ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by)).coerceAtLeast(1e-9)).coerceIn(-1.0, 1.0))
			}
			val corners = points.indices.filter { i ->
				turn[i] > 100 * PI / 180 && (i == 0 || turn[i] >= turn[i - 1]) && (i == points.lastIndex || turn[i] > turn[i + 1])
			}
			if (corners.isEmpty()) return listOf(points)
			val cuts = listOf(0) + corners + listOf(points.lastIndex)
			return cuts.zipWithNext().map { (a, b) -> points.subList(a, b + 1) }.filter { it.size >= 2 }
		}

		/**
		 * The axis of the wedge two arms make where they meet: midpoints of points at equal distance from the tip,
		 * while the wedge keeps opening and stays narrow enough to be one fold's shadow.
		 */
		private fun wedgeAxis(a: List<Point>, b: List<Point>, tip: Point, width: Double, image: Working, maps: Responses): List<Point>? {
			val total = min(pathLength(a, false), pathLength(b, false))
			val axis = mutableListOf(tip)
			var lastWidth = 0.0
			var s = 2.0
			val da = directionFrom(a); val db = directionFrom(b)
			fun bends(path: List<Point>, start: Point, s: Double): Boolean {
				val p = at(path, s); val q = at(path, (s - 4).coerceAtLeast(0.0))
				val d = distance(p, q).coerceAtLeast(1e-9)
				return ((p.x - q.x) * start.x + (p.y - q.y) * start.y) / d < cos(35 * PI / 180)
			}
			while (s <= total) {
				// Past the wedge the sides turn away along other edges.
				if (s > 6 && (bends(a, da, s) || bends(b, db, s))) break
				val pa = at(a, s); val pb = at(b, s)
				val opening = distance(pa, pb)
				if (opening + 1e-6 < lastWidth * .9) break
				lastWidth = opening
				axis += Point((pa.x + pb.x) / 2, (pa.y + pb.y) / 2)
				s += 2.0
			}
			if (axis.size < 3) return null
			// The wedge is a shadow or a highlight: inside it the tone differs from both sides.
			val mid = axis[axis.size / 2]
			val inner = maps.smooth[mid.y.toInt().coerceIn(0, image.h - 1) * image.w + mid.x.toInt().coerceIn(0, image.w - 1)]
			val outerA = sample(maps.smooth, image, at(a, min(total, s)), mid, 1.6)
			val outerB = sample(maps.smooth, image, at(b, min(total, s)), mid, 1.6)
			val differs = (inner - outerA) * (inner - outerB) > 0 && min(abs(inner - outerA), abs(inner - outerB)) > .03
			return axis.takeIf { differs }
		}

		private fun sample(plane: FloatArray, image: Working, p: Point, from: Point, factor: Double): Float {
			val q = Point(from.x + (p.x - from.x) * factor, from.y + (p.y - from.y) * factor)
			return plane[q.y.toInt().coerceIn(0, image.h - 1) * image.w + q.x.toInt().coerceIn(0, image.w - 1)]
		}

		private fun directionFrom(points: List<Point>): Point {
			val origin = points.first()
			val target = points.firstOrNull { distance(it, origin) >= 5.0 } ?: points.last()
			val norm = distance(origin, target).coerceAtLeast(1e-9)
			return Point((target.x - origin.x) / norm, (target.y - origin.y) / norm)
		}

		private fun at(points: List<Point>, s: Double): Point {
			var left = s
			for ((a, b) in points.zipWithNext()) {
				val d = distance(a, b)
				if (left <= d) return io.github.psd2live.core.mesh.lerp(a, b, if (d < 1e-9) 0.0 else left / d)
				left -= d
			}
			return points.last()
		}

		/** Whether a free end of the stroke fades or narrows: drawn creases taper, printed lines do not. */
		private fun tapers(points: List<Point>, chain: Chain, width: Double, maps: Responses, image: Working): Boolean {
			if (chain.closed || points.size < 8) return false
			// How much darker the stroke is than the cloth either side of it, along its length.
			val reach = 2 * width + 2
			val depth = points.indices.map { i ->
				val a = points[(i - 1).coerceAtLeast(0)]; val b = points[(i + 1).coerceAtMost(points.lastIndex)]
				val norm = hypot(b.x - a.x, b.y - a.y).coerceAtLeast(1e-9)
				val nx = -(b.y - a.y) / norm; val ny = (b.x - a.x) / norm
				fun at(x: Double, y: Double) = maps.smooth[y.toInt().coerceIn(0, image.h - 1) * image.w + x.toInt().coerceIn(0, image.w - 1)]
				val p = points[i]
				((at(p.x + nx * reach, p.y + ny * reach) + at(p.x - nx * reach, p.y - ny * reach)) / 2 - at(p.x, p.y)).toDouble()
			}
			val middle = depth.subList(points.size / 4, points.size * 3 / 4).sorted().let { it[it.size / 2] }
			if (middle <= .01) return false
			val tail = max(2, points.size * 15 / 100)
			fun fades(range: IntRange) = range.map { depth[it] }.average() < .55 * middle
			return (chain.startEnd == End.FREE && fades(0 until tail)) ||
				(chain.endEnd == End.FREE && fades(points.size - tail until points.size))
		}

		/** Whether one side of the stroke is consistently darker: a crease with its shadow. */
		private fun oneSided(points: List<Point>, width: Double, maps: Responses, image: Working): Boolean {
			if (points.size < 4) return false
			val offset = 2 * width + 1.5
			var sum = 0.0; var positive = 0; var negative = 0
			for (i in 1 until points.size - 1) {
				val dx = points[i + 1].x - points[i - 1].x; val dy = points[i + 1].y - points[i - 1].y
				val norm = hypot(dx, dy).coerceAtLeast(1e-9)
				val nx = -dy / norm; val ny = dx / norm
				fun at(sign: Int): Float {
					val x = (points[i].x + sign * nx * offset).toInt().coerceIn(0, image.w - 1)
					val y = (points[i].y + sign * ny * offset).toInt().coerceIn(0, image.h - 1)
					return maps.smooth[y * image.w + x]
				}
				val diff = (at(1) - at(-1)).toDouble()
				sum += diff
				if (diff > .01) positive++ else if (diff < -.01) negative++
			}
			val count = points.size - 2
			return abs(sum / count) > .04 && max(positive, negative) > .75 * count
		}

		/** Total absolute turn along the line, measured over steps of a few widths so pixel jitter does not add up. */
		private fun turning(points: List<Point>, width: Double): Double {
			val step = max(4.0, 3 * width)
			val samples = ArrayList<Point>()
			for (p in points) if (samples.isEmpty() || distance(samples.last(), p) >= step) samples += p
			var total = 0.0
			for (i in 1 until samples.size - 1) {
				val a = atan2(samples[i].y - samples[i - 1].y, samples[i].x - samples[i - 1].x)
				val b = atan2(samples[i + 1].y - samples[i].y, samples[i + 1].x - samples[i].x)
				total += abs(((b - a + 3 * PI) % (2 * PI)) - PI)
			}
			return total
		}

		/**
		 * Whether the colours on the two sides of the stroke differ like two materials rather than two shades of one:
		 * a large step in lightness or any clear step in hue.
		 */
		private fun separates(points: List<Point>, width: Double, maps: Responses, image: Working): Boolean {
			if (points.size < 4) return false
			val offset = 2 * width + 3
			val steps = ArrayList<Double>(); val hues = ArrayList<Double>()
			for (i in 1 until points.size - 1) {
				val dx = points[i + 1].x - points[i - 1].x; val dy = points[i + 1].y - points[i - 1].y
				val norm = hypot(dx, dy).coerceAtLeast(1e-9)
				val nx = -dy / norm; val ny = dx / norm
				fun at(sign: Int): Int {
					val x = (points[i].x + sign * nx * offset).toInt().coerceIn(0, image.w - 1)
					val y = (points[i].y + sign * ny * offset).toInt().coerceIn(0, image.h - 1)
					return y * image.w + x
				}
				val a = at(1); val b = at(-1)
				steps += abs(maps.smooth[a] - maps.smooth[b]).toDouble()
				hues += hypot((maps.smoothA[a] - maps.smoothA[b]).toDouble(), (maps.smoothB[a] - maps.smoothB[b]).toDouble())
			}
			fun median(list: List<Double>) = list.sorted()[list.size / 2]
			return median(steps) > .2 || median(hues) > .05
		}

		/** A stroke running beside the outline at a steady distance is a trim or a seam, not a crease. */
		private fun alongOutline(points: List<Point>, image: Working, index: (Point) -> Int, width: Double): Boolean {
			val depths = points.map { image.depth[index(it)].toDouble() }
			val mean = depths.average()
			val spread = sqrt(depths.sumOf { (it - mean) * (it - mean) } / depths.size)
			return mean < 4 * width + 12 && spread < 1.5 && pathLength(points, false) > 3 * mean
		}

		/** Three or more near-parallel strokes of similar length at an even spacing are a printed pattern. */
		private fun repeated(strokes: List<StructureLine>): List<StructureLine> {
			data class Axis(val line: StructureLine, val angle: Double, val offset: Double, val length: Double)
			val axes = strokes.map { l ->
				val a = l.points.first(); val b = l.points.last()
				val angle = atan2(b.y - a.y, b.x - a.x).let { if (it < 0) it + PI else it }.let { if (it >= PI) it - PI else it }
				val cx = l.points.sumOf { it.x } / l.points.size; val cy = l.points.sumOf { it.y } / l.points.size
				Axis(l, angle, -sin(angle) * cx + cos(angle) * cy, l.length)
			}
			val result = LinkedHashSet<StructureLine>()
			for (seed in axes) {
				val group = axes.filter { other ->
					val diff = abs(other.angle - seed.angle).let { min(it, PI - it) }
					diff < 12 * PI / 180 && other.length / seed.length in .6..1.67
				}.sortedBy { it.offset }
				if (group.size < 3) continue
				val gaps = group.zipWithNext().map { (p, q) -> q.offset - p.offset }
				val mean = gaps.average()
				if (mean <= 1e-6) continue
				val spread = sqrt(gaps.sumOf { (it - mean) * (it - mean) } / gaps.size)
				if (spread / mean < .25) group.forEach { result += it.line }
			}
			return result.toList()
		}

		/** Douglas-Peucker. */
		private fun simplify(points: List<Point>, epsilon: Double, closed: Boolean): List<Point> {
			if (points.size < 3) return points
			if (closed) {
				val far = points.indices.maxBy { distance(points[it], points[0]) }
				val a = simplify(points.subList(0, far + 1), epsilon, false)
				val b = simplify(points.subList(far, points.size) + points[0], epsilon, false)
				return (a + b.drop(1).dropLast(1)).takeIf { it.size >= 3 } ?: points
			}
			val keep = BooleanArray(points.size); keep[0] = true; keep[points.lastIndex] = true
			val stack = ArrayDeque<Pair<Int, Int>>(); stack += 0 to points.lastIndex
			while (stack.isNotEmpty()) {
				val (s, e) = stack.removeLast()
				var worst = -1; var worstDistance = epsilon
				for (i in s + 1 until e) {
					val d = sqrt(io.github.psd2live.core.mesh.distanceSquaredToSegment(points[i], points[s], points[e]))
					if (d > worstDistance) { worstDistance = d; worst = i }
				}
				if (worst > 0) { keep[worst] = true; stack += s to worst; stack += worst to e }
			}
			return points.filterIndexed { i, _ -> keep[i] }
		}
	}

	/** Even-odd scanline fill of [loops] (source pixels) at working pixel centres. */
	internal fun rasterize(loops: List<List<Point>>, w: Int, h: Int, f: Int): BooleanArray {
		val out = BooleanArray(w * h)
		val crossings = ArrayList<Double>()
		for (y in 0 until h) {
			val sy = (y + .5) * f
			crossings.clear()
			for (loop in loops) for (i in loop.indices) {
				val a = loop[i]; val b = loop[(i + 1) % loop.size]
				if ((a.y > sy) != (b.y > sy)) crossings += a.x + (sy - a.y) / (b.y - a.y) * (b.x - a.x)
			}
			crossings.sort()
			for (k in 0 until crossings.size - 1 step 2) {
				val from = ceil(crossings[k] / f - .5).toInt().coerceAtLeast(0)
				val to = floor(crossings[k + 1] / f - .5).toInt().coerceAtMost(w - 1)
				for (x in from..to) out[y * w + x] = true
			}
		}
		return out
	}

	/** 3-4 chamfer distance (in pixels) to the nearest pixel outside [inside]. */
	internal fun chamfer(inside: BooleanArray, w: Int, h: Int): FloatArray {
		val big = 1e9f
		val d = FloatArray(w * h) { if (inside[it]) big else 0f }
		for (y in 0 until h) for (x in 0 until w) {
			val i = y * w + x
			if (d[i] == 0f) continue
			var v = d[i]
			if (x == 0 || y == 0) v = min(v, 3f)
			if (x > 0) v = min(v, d[i - 1] + 3)
			if (y > 0) { v = min(v, d[i - w] + 3); if (x > 0) v = min(v, d[i - w - 1] + 4); if (x < w - 1) v = min(v, d[i - w + 1] + 4) }
			d[i] = v
		}
		for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
			val i = y * w + x
			if (d[i] == 0f) continue
			var v = d[i]
			if (x == w - 1 || y == h - 1) v = min(v, 3f)
			if (x < w - 1) v = min(v, d[i + 1] + 3)
			if (y < h - 1) { v = min(v, d[i + w] + 3); if (x < w - 1) v = min(v, d[i + w + 1] + 4); if (x > 0) v = min(v, d[i + w - 1] + 4) }
			d[i] = v
		}
		for (i in d.indices) d[i] /= 3f
		return d
	}

	private fun smoothstep(a: Float, b: Float, x: Float): Float {
		val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
		return t * t * (3 - 2 * t)
	}

	private fun linear(value: Byte): Double {
		val c = (value.toInt() and 255) / 255.0
		return if (c <= .04045) c / 12.92 else ((c + .055) / 1.055).pow(2.4)
	}

	private fun oklab(r: Double, g: Double, b: Double): DoubleArray {
		val l = cbrt(.4122214708 * r + .5363325363 * g + .0514459929 * b)
		val m = cbrt(.2119034982 * r + .6806995451 * g + .1073969566 * b)
		val s = cbrt(.0883024619 * r + .2817188376 * g + .6299787005 * b)
		return doubleArrayOf(.2104542553 * l + .7936177850 * m - .0040720468 * s,
			1.9779984951 * l - 2.4285922050 * m + .4505937099 * s,
			.0259040371 * l + .7827717662 * m - .8086757660 * s)
	}
}

/** Separable Gaussian blur and finite differences with clamped borders. */
internal object Filters {
	fun blur(src: FloatArray, w: Int, h: Int, sigma: Double, checkpoint: () -> Unit = {}): FloatArray {
		val radius = ceil(3 * sigma).toInt().coerceAtLeast(1)
		val kernel = FloatArray(2 * radius + 1) { exp(-((it - radius) * (it - radius)) / (2 * sigma * sigma)).toFloat() }
		val total = kernel.sum(); for (i in kernel.indices) kernel[i] /= total
		val tmp = FloatArray(src.size); val out = FloatArray(src.size)
		for (y in 0 until h) {
			if (y % 64 == 0) checkpoint()
			val row = y * w
			for (x in 0 until w) {
				var acc = 0f
				for (k in -radius..radius) acc += kernel[k + radius] * src[row + (x + k).coerceIn(0, w - 1)]
				tmp[row + x] = acc
			}
		}
		for (y in 0 until h) {
			if (y % 64 == 0) checkpoint()
			for (x in 0 until w) {
				var acc = 0f
				for (k in -radius..radius) acc += kernel[k + radius] * tmp[(y + k).coerceIn(0, h - 1) * w + x]
				out[y * w + x] = acc
			}
		}
		return out
	}

	fun dilate(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
		val tmp = FloatArray(src.size) { i ->
			val x = i % w; val row = i - x
			var m = Float.NEGATIVE_INFINITY
			for (k in max(0, x - radius)..min(w - 1, x + radius)) m = max(m, src[row + k])
			m
		}
		return FloatArray(src.size) { i ->
			val x = i % w; val y = i / w
			var m = Float.NEGATIVE_INFINITY
			for (k in max(0, y - radius)..min(h - 1, y + radius)) m = max(m, tmp[k * w + x])
			m
		}
	}

	private fun at(p: FloatArray, w: Int, h: Int, x: Int, y: Int) = p[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)].toDouble()
	fun dx(p: FloatArray, w: Int, h: Int, x: Int, y: Int) = (at(p, w, h, x + 1, y) - at(p, w, h, x - 1, y)) / 2
	fun dy(p: FloatArray, w: Int, h: Int, x: Int, y: Int) = (at(p, w, h, x, y + 1) - at(p, w, h, x, y - 1)) / 2
	fun dxx(p: FloatArray, w: Int, h: Int, x: Int, y: Int) = at(p, w, h, x + 1, y) - 2 * at(p, w, h, x, y) + at(p, w, h, x - 1, y)
	fun dyy(p: FloatArray, w: Int, h: Int, x: Int, y: Int) = at(p, w, h, x, y + 1) - 2 * at(p, w, h, x, y) + at(p, w, h, x, y - 1)
	fun dxy(p: FloatArray, w: Int, h: Int, x: Int, y: Int) =
		(at(p, w, h, x + 1, y + 1) - at(p, w, h, x + 1, y - 1) - at(p, w, h, x - 1, y + 1) + at(p, w, h, x - 1, y - 1)) / 4
}
