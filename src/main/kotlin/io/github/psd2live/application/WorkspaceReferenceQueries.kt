package io.github.psd2live.application

import io.github.psd2live.project.LayerCanvasRect
import kotlinx.serialization.json.*
import org.umamo.format.art.LayerRaster
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Reading the reference image to draw from it, in canvas units: colours at points and the palette of an area
 * ([sample]), the outline of a flat area as a path ([trace]), and points moved onto the nearest line ([snap]). They
 * read the reference's pixels where they sit on the canvas, so what they return goes straight into a drawing.
 */
internal object WorkspaceReferenceQueries {
    const val SAMPLE = "reference_sample"
    const val TRACE = "reference_trace"
    const val SNAP = "reference_snap"

    /** The reference's pixels and where they sit. */
    private class Reference(val raster: LayerRaster, val rect: LayerCanvasRect) {
        val sx = raster.width / rect.width
        val sy = raster.height / rect.height
        fun u(x: Float) = (x - rect.left) * sx
        fun v(y: Float) = (y - rect.top) * sy
        fun x(u: Double) = rect.left + u / sx
        fun y(v: Double) = rect.top + v / sy
        fun alpha(i: Int, j: Int) = raster.rgba[(j * raster.width + i) * 4 + 3].toInt() and 0xFF
        fun rgb(i: Int, j: Int): IntArray { val o = (j * raster.width + i) * 4; return IntArray(3) { raster.rgba[o + it].toInt() and 0xFF } }
        fun inside(i: Int, j: Int) = i in 0 until raster.width && j in 0 until raster.height
    }

    private fun reference(state: WorkspaceLayoutState): Reference {
        val set = state.layout.reference ?: throw IllegalArgumentException("No reference image is set; layout_set_reference sets one")
        val item = state.layout.materials.item(set.materialId) ?: throw IllegalArgumentException("Reference material not found: ${set.materialId}")
        return Reference(item.raster, set.rect)
    }

    private fun point(value: JsonElement): Pair<Float, Float> {
        val values = value.jsonArray.map { it.jsonPrimitive.float }
        require(values.size == 2 && values.all(Float::isFinite)) { "A point is [x, y] in canvas units" }
        return values[0] to values[1]
    }

    private fun hex(rgb: IntArray) = "#%02x%02x%02x".format(rgb[0].coerceIn(0, 255), rgb[1].coerceIn(0, 255), rgb[2].coerceIn(0, 255))
    private fun round(value: Double, places: Int = 2): Double { val f = 10.0.pow(places); return (value * f).roundToInt() / f }

    // sRGB <-> CIE Lab (D65), for colour distances that follow what the eye sees.
    private fun linear(c: Int): Double { val v = c / 255.0; return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }
    private fun gamma(v: Double): Int = ((if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055) * 255.0).roundToInt().coerceIn(0, 255)
    fun lab(rgb: IntArray): DoubleArray {
        val r = linear(rgb[0]); val g = linear(rgb[1]); val b = linear(rgb[2])
        fun f(t: Double) = if (t > 216.0 / 24389.0) cbrt(t) else (24389.0 / 27.0 * t + 16.0) / 116.0
        val fx = f((0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047); val fy = f(0.2126 * r + 0.7152 * g + 0.0722 * b)
        val fz = f((0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883)
        return doubleArrayOf(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }
    fun rgb(lab: DoubleArray): IntArray {
        val fy = (lab[0] + 16.0) / 116.0; val fx = fy + lab[1] / 500.0; val fz = fy - lab[2] / 200.0
        fun inv(t: Double) = if (t * t * t > 216.0 / 24389.0) t * t * t else (116.0 * t - 16.0) * 27.0 / 24389.0
        val x = inv(fx) * 0.95047; val y = inv(fy); val z = inv(fz) * 1.08883
        return intArrayOf(gamma(3.2406 * x - 1.5372 * y - 0.4986 * z), gamma(-0.9689 * x + 1.8758 * y + 0.0415 * z), gamma(0.0557 * x - 0.2040 * y + 1.0570 * z))
    }
    private fun distance(a: DoubleArray, b: DoubleArray) = sqrt((a[0] - b[0]).pow(2) + (a[1] - b[1]).pow(2) + (a[2] - b[2]).pow(2))

    /**
     * Colours of the reference: at `points` (the mean within `radius` canvas units, else the pixel), and the `colors`
     * main colours of the opaque pixels in `rect` with their shares, darkest first.
     */
    fun sample(state: WorkspaceLayoutState, request: JsonObject): JsonObject {
        val reference = reference(state)
        val points = request["points"]?.jsonArray?.map(::point).orEmpty()
        val rect = request["rect"]?.jsonObject?.let { r -> LayerCanvasRect(r.getValue("left").jsonPrimitive.float, r.getValue("top").jsonPrimitive.float,
            r.getValue("width").jsonPrimitive.float, r.getValue("height").jsonPrimitive.float) }
        require(points.isNotEmpty() || rect != null) { "Give points, rect or both" }
        val radius = request["radius"]?.jsonPrimitive?.float ?: 0f
        return buildJsonObject {
            if (points.isNotEmpty()) putJsonArray("points") {
                for ((x, y) in points) addJsonObject {
                    putJsonArray("point") { add(x); add(y) }
                    val ru = radius * reference.sx; val rv = radius * reference.sy
                    val cu = reference.u(x); val cv = reference.v(y)
                    var sum = DoubleArray(3); var alpha = 0.0; var count = 0
                    for (j in floor(cv - rv).toInt()..floor(cv + rv).toInt()) for (i in floor(cu - ru).toInt()..floor(cu + ru).toInt()) {
                        if (!reference.inside(i, j)) continue
                        if (radius > 0f && ((i + 0.5 - cu) / ru.coerceAtLeast(1e-6f)).pow(2) + ((j + 0.5 - cv) / rv.coerceAtLeast(1e-6f)).pow(2) > 1.0) continue
                        val a = reference.alpha(i, j); val c = reference.rgb(i, j)
                        sum = DoubleArray(3) { sum[it] + c[it] * a }; alpha += a; count++
                    }
                    if (count == 0) { put("inside", false); return@addJsonObject }
                    put("color", if (alpha > 0) hex(IntArray(3) { (sum[it] / alpha).roundToInt() }) else "#000000")
                    put("alpha", round(alpha / count / 255.0, 3))
                }
            }
            rect?.let { put("palette", palette(reference, it, request["colors"]?.jsonPrimitive?.int ?: 6)) }
        }
    }

    /** The main colours of [rect]: k-means in Lab over a 5-bit colour histogram of its opaque pixels. */
    private fun palette(reference: Reference, rect: LayerCanvasRect, k: Int): JsonArray {
        require(k in 1..16) { "colors is 1..16" }
        val bins = HashMap<Int, IntArray>()
        val i0 = floor(reference.u(rect.left)).toInt().coerceIn(0, reference.raster.width); val i1 = floor(reference.u(rect.right)).toInt().coerceIn(0, reference.raster.width)
        val j0 = floor(reference.v(rect.top)).toInt().coerceIn(0, reference.raster.height); val j1 = floor(reference.v(rect.bottom)).toInt().coerceIn(0, reference.raster.height)
        for (j in j0 until j1) for (i in i0 until i1) {
            if (reference.alpha(i, j) < 128) continue
            val c = reference.rgb(i, j)
            val key = ((c[0] shr 3) shl 10) or ((c[1] shr 3) shl 5) or (c[2] shr 3)
            val bin = bins.getOrPut(key) { IntArray(4) }
            bin[0] += c[0]; bin[1] += c[1]; bin[2] += c[2]; bin[3]++
        }
        if (bins.isEmpty()) return JsonArray(emptyList())
        val colors = bins.values.map { b -> lab(intArrayOf(b[0] / b[3], b[1] / b[3], b[2] / b[3])) to b[3].toDouble() }
        val total = colors.sumOf { it.second }
        // Seeds: the most common colour, then each colour that is common and far from the seeds so far.
        val centers = ArrayList<DoubleArray>()
        centers += colors.maxBy { it.second }.first
        while (centers.size < minOf(k, colors.size)) {
            val next = colors.maxBy { (c, w) -> w * centers.minOf { distance(it, c) }.pow(2) }
            if (centers.any { distance(it, next.first) < 1e-6 }) break
            centers += next.first
        }
        val assignment = IntArray(colors.size)
        repeat(24) {
            colors.forEachIndexed { index, (c, _) -> assignment[index] = centers.indices.minBy { distance(centers[it], c) } }
            for (center in centers.indices) {
                var weight = 0.0; val sum = DoubleArray(3)
                colors.forEachIndexed { index, (c, w) -> if (assignment[index] == center) { weight += w; for (d in 0..2) sum[d] += c[d] * w } }
                if (weight > 0) centers[center] = DoubleArray(3) { sum[it] / weight }
            }
        }
        val shares = DoubleArray(centers.size)
        colors.forEachIndexed { index, (_, w) -> shares[assignment[index]] += w }
        return buildJsonArray {
            centers.indices.filter { shares[it] > 0 }.sortedBy { centers[it][0] }.forEach { index ->
                addJsonObject { put("color", hex(rgb(centers[index]))); put("share", round(shares[index] / total, 3)); put("lightness", round(centers[index][0], 1)) }
            }
        }
    }

    /**
     * The area of the reference around `seed` whose colours lie within `tolerance` (Lab distance) of the seed's - or,
     * with compare neighbor, of the neighbour it grew from, so it follows soft shading up to the lines - as its outer
     * outline: the polygon simplified to `simplify` canvas units and a smooth closed SVG path through it. `holes` asks
     * for the outlines of that many of the largest holes too - traced from the background, the figure's silhouette.
     */
    fun trace(state: WorkspaceLayoutState, request: JsonObject): JsonObject {
        val reference = reference(state)
        val (x, y) = point(request.getValue("seed"))
        val tolerance = request["tolerance"]?.jsonPrimitive?.double ?: 12.0
        require(tolerance in 0.0..100.0) { "tolerance is a Lab distance 0..100" }
        val neighbor = request["compare"]?.jsonPrimitive?.content == "neighbor"
        val simplify = request["simplify"]?.jsonPrimitive?.double ?: 0.6
        val wantHoles = request["holes"]?.jsonPrimitive?.int ?: 0
        require(wantHoles in 0..16) { "holes is 0..16" }
        require(simplify in 0.0..64.0) { "simplify is 0..64 canvas units" }
        val w = reference.raster.width; val h = reference.raster.height
        val su = floor(reference.u(x)).toInt(); val sv = floor(reference.v(y)).toInt()
        require(reference.inside(su, sv) && reference.alpha(su, sv) >= 128) { "The seed is not on an opaque pixel of the reference" }
        val labs = arrayOfNulls<DoubleArray>(w * h)
        fun labAt(i: Int, j: Int) = labs[j * w + i] ?: lab(reference.rgb(i, j)).also { labs[j * w + i] = it }
        val seedLab = labAt(su, sv)
        val filled = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0; var tail = 0
        filled[sv * w + su] = true; queue[tail++] = sv * w + su
        while (head < tail) {
            val index = queue[head++]
            val i = index % w; val j = index / w
            val from = if (neighbor) labAt(i, j) else seedLab
            for ((di, dj) in NEIGHBOURS) {
                val ni = i + di; val nj = j + dj
                if (!reference.inside(ni, nj)) continue
                val n = nj * w + ni
                if (filled[n] || reference.alpha(ni, nj) < 128 || distance(labAt(ni, nj), from) > tolerance) continue
                filled[n] = true; queue[tail++] = n
            }
        }
        val loops = boundaries(filled, w, h)
        val outer = loops.maxBy { area(it) }
        val polygon = simplified(outer.map { (u, v) -> doubleArrayOf(reference.x(u.toDouble()), reference.y(v.toDouble())) }, simplify)
        var sum = DoubleArray(3)
        for (index in 0 until tail) { val c = reference.rgb(queue[index] % w, queue[index] / w); sum = DoubleArray(3) { sum[it] + c[it] } }
        val us = outer.map { it.first }; val vs = outer.map { it.second }
        return buildJsonObject {
            put("pixels", tail)
            put("area", round(area(outer) / (reference.sx * reference.sy)))
            putJsonArray("bounds") {
                add(round(reference.x(us.min().toDouble()))); add(round(reference.y(vs.min().toDouble())))
                add(round((us.max() - us.min()) / reference.sx.toDouble())); add(round((vs.max() - vs.min()) / reference.sy.toDouble()))
            }
            put("mean_color", hex(IntArray(3) { (sum[it] / tail).roundToInt() }))
            put("holes", loops.count { area(it) < 0 })
            put("touches_edge", outer.any { (u, v) -> u == 0 || v == 0 || u == w || v == h })
            putJsonArray("polygon") { polygon.forEach { p -> addJsonArray { add(round(p[0])); add(round(p[1])) } } }
            put("path", smoothPath(polygon))
            if (wantHoles > 0) putJsonArray("hole_outlines") {
                loops.filter { area(it) < 0 }.sortedBy { area(it) }.take(wantHoles).forEach { loop ->
                    val hole = simplified(loop.map { (u, v) -> doubleArrayOf(reference.x(u.toDouble()), reference.y(v.toDouble())) }, simplify)
                    addJsonObject {
                        put("area", round(-area(loop) / (reference.sx * reference.sy)))
                        putJsonArray("polygon") { hole.forEach { p -> addJsonArray { add(round(p[0])); add(round(p[1])) } } }
                        put("path", smoothPath(hole))
                    }
                }
            }
        }
    }

    private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    /**
     * The boundary loops of [mask] along pixel edges, in pixel corner coordinates: clockwise (positive area, y down)
     * around filled areas, counter-clockwise around holes.
     */
    private fun boundaries(mask: BooleanArray, w: Int, h: Int): List<List<Pair<Int, Int>>> {
        fun on(i: Int, j: Int) = i in 0 until w && j in 0 until h && mask[j * w + i]
        val edges = HashMap<Long, ArrayDeque<Long>>()
        fun key(i: Int, j: Int) = j.toLong() * (w + 1) + i
        fun edge(a: Long, b: Long) { edges.getOrPut(a) { ArrayDeque() }.addLast(b) }
        for (j in 0 until h) for (i in 0 until w) {
            if (!mask[j * w + i]) continue
            if (!on(i, j - 1)) edge(key(i, j), key(i + 1, j))
            if (!on(i + 1, j)) edge(key(i + 1, j), key(i + 1, j + 1))
            if (!on(i, j + 1)) edge(key(i + 1, j + 1), key(i, j + 1))
            if (!on(i - 1, j)) edge(key(i, j + 1), key(i, j))
        }
        val loops = ArrayList<List<Pair<Int, Int>>>()
        while (edges.isNotEmpty()) {
            val start = edges.keys.first()
            val loop = ArrayList<Pair<Int, Int>>()
            var at = start
            while (true) {
                loop += (at % (w + 1)).toInt() to (at / (w + 1)).toInt()
                val out = edges[at] ?: break
                val next = out.removeFirst()
                if (out.isEmpty()) edges.remove(at)
                at = next
                if (at == start) break
            }
            loops += loop
        }
        return loops
    }

    private fun area(loop: List<Pair<Int, Int>>): Double {
        var sum = 0.0
        for (index in loop.indices) {
            val (x0, y0) = loop[index]; val (x1, y1) = loop[(index + 1) % loop.size]
            sum += x0.toDouble() * y1 - x1.toDouble() * y0
        }
        return sum / 2.0
    }

    /** [polygon] (closed) simplified by Douglas-Peucker to [epsilon], split at its two farthest points. */
    private fun simplified(polygon: List<DoubleArray>, epsilon: Double): List<DoubleArray> {
        if (polygon.size <= 4 || epsilon <= 0.0) return polygon
        val first = 0
        val second = polygon.indices.maxBy { hypot(polygon[it][0] - polygon[first][0], polygon[it][1] - polygon[first][1]) }
        fun run(points: List<DoubleArray>): List<DoubleArray> {
            if (points.size <= 2) return points
            val a = points.first(); val b = points.last()
            val length = hypot(b[0] - a[0], b[1] - a[1])
            var farthest = 0; var distance = -1.0
            for (index in 1 until points.size - 1) {
                val p = points[index]
                val d = if (length < 1e-9) hypot(p[0] - a[0], p[1] - a[1]) else abs((b[0] - a[0]) * (a[1] - p[1]) - (a[0] - p[0]) * (b[1] - a[1])) / length
                if (d > distance) { distance = d; farthest = index }
            }
            if (distance <= epsilon) return listOf(a, b)
            return run(points.subList(0, farthest + 1)).dropLast(1) + run(points.subList(farthest, points.size))
        }
        val firstHalf = run(polygon.subList(first, second + 1))
        val secondHalf = run(polygon.subList(second, polygon.size) + listOf(polygon[first]))
        return firstHalf.dropLast(1) + secondHalf.dropLast(1)
    }

    /** A closed Catmull-Rom spline through [points] as SVG path data of cubic segments. */
    fun smoothPath(points: List<DoubleArray>): String {
        if (points.size < 3) return ""
        fun f(v: Double) = round(v).let { if (it == floor(it)) it.toLong().toString() else it.toString() }
        val n = points.size
        return buildString {
            append("M").append(f(points[0][0])).append(' ').append(f(points[0][1]))
            for (index in 0 until n) {
                val p0 = points[(index - 1 + n) % n]; val p1 = points[index]; val p2 = points[(index + 1) % n]; val p3 = points[(index + 2) % n]
                append("C").append(f(p1[0] + (p2[0] - p0[0]) / 6)).append(' ').append(f(p1[1] + (p2[1] - p0[1]) / 6)).append(' ')
                    .append(f(p2[0] - (p3[0] - p1[0]) / 6)).append(' ').append(f(p2[1] - (p3[1] - p1[1]) / 6)).append(' ')
                    .append(f(p2[0])).append(' ').append(f(p2[1]))
            }
            append('Z')
        }
    }

    /**
     * Each of `points` moved onto the centre of the nearest drawn line within `radius` canvas units: of the pixels
     * darker than their surroundings by at least half the darkest one's margin, the nearest, then the darkest pixel
     * right around it - the middle of that line. A point with no line near stays and says so.
     */
    fun snap(state: WorkspaceLayoutState, request: JsonObject): JsonObject {
        val reference = reference(state)
        val points = request.getValue("points").jsonArray.map(::point)
        val radius = request["radius"]?.jsonPrimitive?.float ?: 4f
        require(radius > 0f && radius <= 64f) { "radius is 0..64 canvas units" }
        val ru = radius * reference.sx; val rv = radius * reference.sy
        val pad = 4
        fun luminance(i: Int, j: Int): Double {
            val c = reference.rgb(i, j); val a = reference.alpha(i, j) / 255.0
            return (0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]) * a + 255.0 * (1 - a)
        }
        return buildJsonObject {
            putJsonArray("points") {
                for ((x, y) in points) addJsonObject {
                    val cu = reference.u(x); val cv = reference.v(y)
                    class Candidate(val i: Int, val j: Int, val distance: Double, val strength: Double)
                    val candidates = ArrayList<Candidate>()
                    for (j in floor(cv - rv).toInt()..floor(cv + rv).toInt()) for (i in floor(cu - ru).toInt()..floor(cu + ru).toInt()) {
                        if (!reference.inside(i, j)) continue
                        val d = hypot((i + 0.5 - cu) / reference.sx, (j + 0.5 - cv) / reference.sy)
                        if (d > radius) continue
                        var surround = 0.0; var count = 0
                        for (b in j - pad..j + pad) for (a in i - pad..i + pad) if (reference.inside(a, b)) { surround += luminance(a, b); count++ }
                        candidates += Candidate(i, j, d, (surround / count - luminance(i, j)) / 255.0)
                    }
                    val darkest = candidates.maxOfOrNull { it.strength } ?: 0.0
                    val nearest = candidates.filter { it.strength >= maxOf(0.04, darkest / 2) }.minByOrNull { it.distance }
                    val centre = nearest?.let { n -> candidates.filter { abs(it.i - n.i) <= 2 && abs(it.j - n.j) <= 2 }.maxBy { it.strength } }
                    val bestStrength = centre?.strength ?: darkest
                    val found = centre?.let { it.i to it.j }
                    val sx = found?.let { reference.x(it.first + 0.5) } ?: x.toDouble()
                    val sy = found?.let { reference.y(it.second + 0.5) } ?: y.toDouble()
                    putJsonArray("point") { add(round(sx)); add(round(sy)) }
                    put("snapped", found != null)
                    put("moved", round(hypot(sx - x, sy - y)))
                    put("strength", round(bestStrength, 3))
                }
            }
        }
    }
}
