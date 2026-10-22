package io.github.psd2live.core

import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How a new selection meets the one already there: Photoshop's new, add, subtract and intersect. */
internal enum class PaintSelectionOp { REPLACE, ADD, SUBTRACT, INTERSECT }

/**
 * A paint session's selection: one byte per raster pixel saying how much of an edit reaches it, 255 fully,
 * 0 not at all - the soft edge an antialiased or feathered selection has in between.
 *
 * It is not pixel data and is not in the stroke history; every edit of the session lands through it
 * ([PaintPixelPatch.clipTo], [RasterPaintEngine.Stroke.land]), which is what confines a stroke, a fill, a
 * gradient or a clear to the selected area.
 */
internal class PaintSelection(val width: Int, val height: Int, val mask: ByteArray) {
    init { require(mask.size == width * height) { "Selection mask must cover the raster" } }

    /** The coverage at ([x], [y]), 0..255; 0 off the raster. */
    fun at(x: Int, y: Int): Int = if (x in 0 until width && y in 0 until height) mask[y * width + x].toInt() and 0xFF else 0

    /** The smallest rectangle holding every selected pixel, or null when nothing is selected. */
    val bounds: Rectangle? by lazy {
        var left = width; var top = height; var right = -1; var bottom = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) if (mask[row + x].toInt() != 0) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < 0) null else Rectangle(left, top, right - left + 1, bottom - top + 1)
    }

    val isEmpty: Boolean get() = bounds == null

    /** This selection met by [other] through [op]. */
    fun combine(other: PaintSelection, op: PaintSelectionOp): PaintSelection {
        require(other.width == width && other.height == height) { "Selections must share a raster" }
        if (op == PaintSelectionOp.REPLACE) return other
        val out = ByteArray(mask.size)
        for (i in mask.indices) {
            val a = mask[i].toInt() and 0xFF
            val b = other.mask[i].toInt() and 0xFF
            out[i] = when (op) {
                PaintSelectionOp.ADD -> max(a, b)
                PaintSelectionOp.SUBTRACT -> (a * (255 - b) + 127) / 255
                PaintSelectionOp.INTERSECT -> min(a, b)
                PaintSelectionOp.REPLACE -> b
            }.toByte()
        }
        return PaintSelection(width, height, out)
    }

    fun inverted(): PaintSelection = PaintSelection(width, height, ByteArray(mask.size) { (255 - (mask[it].toInt() and 0xFF)).toByte() })

    /**
     * The selection with its edge softened over [radius] pixels: three box blurs, which is a close and cheap
     * stand-in for the gaussian Photoshop's feather uses.
     */
    fun feathered(radius: Float): PaintSelection {
        val r = (radius / 1.7f).roundToInt()
        if (r <= 0) return this
        var values = IntArray(mask.size) { mask[it].toInt() and 0xFF }
        repeat(3) { values = boxBlur(values, width, height, r) }
        return PaintSelection(width, height, ByteArray(values.size) { values[it].coerceIn(0, 255).toByte() })
    }

    /**
     * The selection grown outwards by [radius] pixels ([radius] > 0) or drawn in by it ([radius] < 0): Photoshop's
     * Expand and Contract. Measured as the true distance to the edge (coverage at or above one half), with a pixel's
     * worth of antialiasing on the new edge; what was selected before keeps its own softness inside it. The raster's
     * border is not an edge, so a selection reaching it is not drawn in from there.
     */
    fun grown(radius: Float): PaintSelection {
        if (radius == 0f || !radius.isFinite()) return this
        val expand = radius > 0f
        val r = abs(radius)
        // Distance to the nearest pixel on the other side: selected ones when growing, unselected ones when shrinking.
        val distance = distanceSquared(width, height) { ((mask[it].toInt() and 0xFF) >= 128) == expand }
        val out = ByteArray(mask.size)
        for (i in mask.indices) {
            val own = mask[i].toInt() and 0xFF
            val d = sqrt(distance[i])
            out[i] = if (expand) max(own, ((r + 1f - d).coerceIn(0f, 1f) * 255f).roundToInt()).toByte()
            else min(own, ((d - r).coerceIn(0f, 1f) * 255f).roundToInt()).toByte()
        }
        return PaintSelection(width, height, out)
    }

    /**
     * The edge between selected and unselected pixels (coverage at or above one half), as segments `x0 y0 x1 y1` in
     * raster pixels along pixel borders: what the marching ants trace. Runs along a row or column are merged, so a
     * rectangle is four segments rather than its perimeter in pixels.
     */
    fun outline(): FloatArray {
        var out = FloatArray(256)
        var size = 0
        fun segment(x0: Int, y0: Int, x1: Int, y1: Int) {
            if (size + 4 > out.size) out = out.copyOf(out.size * 2)
            out[size++] = x0.toFloat(); out[size++] = y0.toFloat(); out[size++] = x1.toFloat(); out[size++] = y1.toFloat()
        }
        fun inside(x: Int, y: Int) = at(x, y) >= 128
        // Horizontal borders: between row y-1 and row y.
        for (y in 0..height) {
            var start = -1; var startTop = false
            for (x in 0..width) {
                val edge = x < width && inside(x, y - 1) != inside(x, y)
                val top = x < width && inside(x, y - 1)
                if (edge && start >= 0 && top == startTop) continue
                if (start >= 0) { segment(start, y, x, y); start = -1 }
                if (edge) { start = x; startTop = top }
            }
        }
        // Vertical borders: between column x-1 and column x.
        for (x in 0..width) {
            var start = -1; var startLeft = false
            for (y in 0..height) {
                val edge = y < height && inside(x - 1, y) != inside(x, y)
                val left = y < height && inside(x - 1, y)
                if (edge && start >= 0 && left == startLeft) continue
                if (start >= 0) { segment(x, start, x, y); start = -1 }
                if (edge) { start = y; startLeft = left }
            }
        }
        return out.copyOf(size)
    }

    companion object {
        fun all(width: Int, height: Int) = PaintSelection(width, height, ByteArray(width * height) { -1 })

        /** The box from ([x0], [y0]) to ([x1], [y1]) in raster pixels, corners in either order. */
        fun rectangle(width: Int, height: Int, x0: Float, y0: Float, x1: Float, y1: Float, antialias: Boolean = true) =
            shape(width, height, Rectangle2D.Float(min(x0, x1), min(y0, y1), abs(x1 - x0), abs(y1 - y0)), antialias)

        /** The ellipse inscribed in the box from ([x0], [y0]) to ([x1], [y1]). */
        fun ellipse(width: Int, height: Int, x0: Float, y0: Float, x1: Float, y1: Float, antialias: Boolean = true) =
            shape(width, height, Ellipse2D.Float(min(x0, x1), min(y0, y1), abs(x1 - x0), abs(y1 - y0)), antialias)

        /** The polygon through [points] (`x y` pairs, raster pixels), closed back to its first point; a lasso. */
        fun polygon(width: Int, height: Int, points: FloatArray, antialias: Boolean = true): PaintSelection {
            require(points.size >= 6 && points.size % 2 == 0) { "A lasso needs at least three points" }
            val path = Path2D.Float(Path2D.WIND_NON_ZERO)
            path.moveTo(points[0], points[1])
            for (i in 2 until points.size step 2) path.lineTo(points[i], points[i + 1])
            path.closePath()
            return shape(width, height, path, antialias)
        }

        /**
         * The magic wand: every pixel whose colour is within [tolerance] of the one at ([x], [y]) on each channel,
         * alpha included - joined to it when [contiguous], anywhere on the raster otherwise. Empty off the raster.
         * Compared premultiplied, so a click on a transparent area takes all of it, whatever colour it hides.
         */
        fun wand(image: BufferedImage, x: Int, y: Int, tolerance: Int, contiguous: Boolean = true): PaintSelection {
            val width = image.width; val height = image.height
            val out = ByteArray(width * height)
            if (x !in 0 until width || y !in 0 until height) return PaintSelection(width, height, out)
            val pixels = premultiplied(image.getRGB(0, 0, width, height, null, 0, width))
            val target = pixels[y * width + x]
            fun matches(argb: Int) = premultipliedDistance(argb, target) <= tolerance
            if (!contiguous) {
                for (i in pixels.indices) if (matches(pixels[i])) out[i] = -1
                return PaintSelection(width, height, out)
            }
            val stack = IntArray(width * height)
            var size = 0
            stack[size++] = y * width + x
            out[y * width + x] = -1
            while (size > 0) {
                val at = stack[--size]
                val px = at % width; val py = at / width
                fun visit(n: Int) { if (out[n].toInt() == 0 && matches(pixels[n])) { out[n] = -1; stack[size++] = n } }
                if (px > 0) visit(at - 1)
                if (px < width - 1) visit(at + 1)
                if (py > 0) visit(at - width)
                if (py < height - 1) visit(at + width)
            }
            return PaintSelection(width, height, out)
        }

        /** The pixels by how opaque they are: Photoshop's Ctrl + click on a layer's thumbnail, alpha read as coverage. */
        fun opaque(image: BufferedImage): PaintSelection {
            val width = image.width; val height = image.height
            val pixels = image.getRGB(0, 0, width, height, null, 0, width)
            return PaintSelection(width, height, ByteArray(pixels.size) { (pixels[it] ushr 24).toByte() })
        }

        /**
         * Quick selection: what a brush of [radius] raster pixels dragged along [points] (`x y` pairs) passes over,
         * grown across joined pixels of the colours it passed over. A pixel joins when it is within [tolerance] of one
         * of those colours and of the neighbour it is reached from, so the growth spreads through a region of like
         * colour - shading included - and stops at an edge, where the colour jumps. Colours are compared
         * premultiplied, so every transparent pixel is alike whatever colour it hides.
         */
        fun quick(image: BufferedImage, points: FloatArray, radius: Float, tolerance: Int): PaintSelection {
            require(points.size >= 2 && points.size % 2 == 0) { "A quick selection needs at least one point" }
            val width = image.width; val height = image.height
            val out = ByteArray(width * height)
            val pixels = premultiplied(image.getRGB(0, 0, width, height, null, 0, width))
            val r = radius.coerceAtLeast(0.5f)
            val stack = IntArray(width * height)
            var size = 0
            // The colours the brush passed over, one per bin of 16 levels a channel.
            val palette = LinkedHashMap<Int, Int>()
            fun stamp(cx: Float, cy: Float) {
                val y0 = floor(cy - r).toInt().coerceAtLeast(0); val y1 = floor(cy + r).toInt().coerceAtMost(height - 1)
                val x0 = floor(cx - r).toInt().coerceAtLeast(0); val x1 = floor(cx + r).toInt().coerceAtMost(width - 1)
                for (y in y0..y1) for (x in x0..x1) {
                    val dx = x + 0.5f - cx; val dy = y + 0.5f - cy
                    if (dx * dx + dy * dy > r * r) continue
                    val at = y * width + x
                    if (out[at].toInt() != 0) continue
                    out[at] = -1; stack[size++] = at
                    val c = pixels[at]
                    palette.putIfAbsent(c and 0xF0F0F0F0.toInt(), c)
                }
            }
            stamp(points[0], points[1])
            for (i in 2 until points.size step 2) {
                val ax = points[i - 2]; val ay = points[i - 1]; val bx = points[i]; val by = points[i + 1]
                val steps = (hypot(bx - ax, by - ay) / (r / 2f)).toInt().coerceIn(1, 4096)
                for (k in 1..steps) stamp(ax + (bx - ax) * k / steps, ay + (by - ay) * k / steps)
            }
            if (size == 0) return PaintSelection(width, height, out)
            val colours = palette.values.toIntArray().let { all ->
                if (all.size <= MAX_QUICK_COLOURS) all else IntArray(MAX_QUICK_COLOURS) { all[it * all.size / MAX_QUICK_COLOURS] }
            }
            val known = HashMap<Int, Boolean>()
            fun like(c: Int) = known.getOrPut(c) { colours.any { premultipliedDistance(it, c) <= tolerance } }
            while (size > 0) {
                val at = stack[--size]
                val px = at % width; val py = at / width
                val from = pixels[at]
                fun visit(n: Int) {
                    if (out[n].toInt() != 0) return
                    val c = pixels[n]
                    if (premultipliedDistance(from, c) <= tolerance && like(c)) { out[n] = -1; stack[size++] = n }
                }
                if (px > 0) visit(at - 1)
                if (px < width - 1) visit(at + 1)
                if (py > 0) visit(at - width)
                if (py < height - 1) visit(at + width)
            }
            return PaintSelection(width, height, out)
        }

        /** The most colours a quick selection stroke matches against; a stroke over more keeps an even spread of them. */
        private const val MAX_QUICK_COLOURS = 64

        /** [argb] premultiplied by its alpha, alpha kept: a transparent pixel is 0 whatever colour it held. */
        private fun premultiplied(argb: IntArray) = IntArray(argb.size) {
            val c = argb[it]; val a = c ushr 24
            if (a == 255) c else (a shl 24) or (((((c ushr 16) and 0xFF) * a + 127) / 255) shl 16) or
                (((((c ushr 8) and 0xFF) * a + 127) / 255) shl 8) or (((c and 0xFF) * a + 127) / 255)
        }

        /** The largest difference on any channel of two premultiplied colours. */
        private fun premultipliedDistance(a: Int, b: Int): Int = max(
            max(abs(((a ushr 24) and 0xFF) - ((b ushr 24) and 0xFF)), abs(((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF))),
            max(abs(((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF)), abs((a and 0xFF) - (b and 0xFF))))

        /**
         * The squared Euclidean distance from every pixel to the nearest one [feature] holds, between pixel centres:
         * Felzenszwalb and Huttenlocher's two passes of lower parabola envelopes. Without any feature pixel every
         * distance is [Float.MAX_VALUE].
         */
        private fun distanceSquared(width: Int, height: Int, feature: (Int) -> Boolean): FloatArray {
            val far = 1e20f
            val grid = FloatArray(width * height) { if (feature(it)) 0f else far }
            val n = max(width, height)
            val f = FloatArray(n); val d = FloatArray(n); val v = IntArray(n); val z = FloatArray(n + 1)
            fun pass(count: Int) {
                var k = 0
                v[0] = 0; z[0] = -Float.MAX_VALUE; z[1] = Float.MAX_VALUE
                fun meet(q: Int, p: Int) = ((f[q] + q.toFloat() * q) - (f[p] + p.toFloat() * p)) / (2f * (q - p))
                for (q in 1 until count) {
                    var s = meet(q, v[k])
                    while (s <= z[k]) { k--; s = meet(q, v[k]) }
                    k++; v[k] = q; z[k] = s; z[k + 1] = Float.MAX_VALUE
                }
                k = 0
                for (q in 0 until count) {
                    while (z[k + 1] < q) k++
                    val p = v[k]
                    d[q] = (q - p).toFloat() * (q - p) + f[p]
                }
            }
            for (x in 0 until width) {
                for (y in 0 until height) f[y] = grid[y * width + x]
                pass(height)
                for (y in 0 until height) grid[y * width + x] = d[y]
            }
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) f[x] = grid[row + x]
                pass(width)
                for (x in 0 until width) grid[row + x] = d[x]
            }
            for (i in grid.indices) if (grid[i] >= far / 2f) grid[i] = Float.MAX_VALUE
            return grid
        }

        private fun shape(width: Int, height: Int, shape: Shape, antialias: Boolean): PaintSelection {
            val image = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
            val g = image.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    if (antialias) RenderingHints.VALUE_ANTIALIAS_ON else RenderingHints.VALUE_ANTIALIAS_OFF)
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                g.color = java.awt.Color.WHITE
                g.fill(shape)
            } finally { g.dispose() }
            val bytes = (image.raster.dataBuffer as DataBufferByte).data
            // The gray raster's rows can be padded; copy row by row onto the packed mask.
            val stride = bytes.size / height
            val mask = ByteArray(width * height)
            for (row in 0 until height) System.arraycopy(bytes, row * stride, mask, row * width, width)
            return PaintSelection(width, height, mask)
        }

        /** One box blur of radius [r], horizontal then vertical, clamped at the raster's edges. */
        private fun boxBlur(values: IntArray, width: Int, height: Int, r: Int): IntArray {
            val pass = IntArray(values.size)
            val span = 2 * r + 1
            for (y in 0 until height) {
                val row = y * width
                var sum = 0
                for (i in -r..r) sum += values[row + i.coerceIn(0, width - 1)]
                for (x in 0 until width) {
                    pass[row + x] = (sum + span / 2) / span
                    sum += values[row + (x + r + 1).coerceAtMost(width - 1)] - values[row + (x - r).coerceAtLeast(0)]
                }
            }
            val out = IntArray(values.size)
            for (x in 0 until width) {
                var sum = 0
                for (i in -r..r) sum += pass[i.coerceIn(0, height - 1) * width + x]
                for (y in 0 until height) {
                    out[y * width + x] = (sum + span / 2) / span
                    sum += pass[(y + r + 1).coerceAtMost(height - 1) * width + x] - pass[(y - r).coerceAtLeast(0) * width + x]
                }
            }
            return out
        }
    }
}

/**
 * [current] taken back towards [original] where [weight] (0..255) is short of full: the step every masked edit
 * ends with. Mixed premultiplied, so a colour does not bleed out of a transparent pixel.
 */
internal fun mixArgb(original: Int, current: Int, weight: Int): Int {
    if (weight >= 255) return current
    if (weight <= 0) return original
    val t = weight / 255f
    val oa = (original ushr 24) and 0xFF; val ca = (current ushr 24) and 0xFF
    val a = oa + (ca - oa) * t
    if (a <= 0.5f) return 0
    fun channel(shift: Int): Int {
        val o = ((original ushr shift) and 0xFF) * oa
        val c = ((current ushr shift) and 0xFF) * ca
        return ((o + (c - o) * t) / a).roundToInt().coerceIn(0, 255)
    }
    return (a.roundToInt().coerceIn(0, 255) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}
