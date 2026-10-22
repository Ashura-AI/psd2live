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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
         */
        fun wand(image: BufferedImage, x: Int, y: Int, tolerance: Int, contiguous: Boolean = true): PaintSelection {
            val width = image.width; val height = image.height
            val out = ByteArray(width * height)
            if (x !in 0 until width || y !in 0 until height) return PaintSelection(width, height, out)
            val pixels = image.getRGB(0, 0, width, height, null, 0, width)
            val target = pixels[y * width + x]
            fun matches(argb: Int) = RasterPaintEngine.colorWithin(argb, target, tolerance)
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
