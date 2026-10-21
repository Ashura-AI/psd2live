package io.github.psd2live.core

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.ArrayDeque
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * High-performance raster operations for layer texture painting (L1 Paint Engine).
 * Edits target layer pixels on the atlas page with clipping to the layer's placement slice.
 */
internal object RasterPaintEngine {

    /**
     * A brush tip: the rule that turns "how far is this pixel from where the pointer went" into
     * coverage. Solid out to `hardness x radius` and fading to nothing at `radius` - the circle the
     * cursor draws - so the ring cannot promise a reach the paint does not have.
     *
     * One definition serves the stroke the engine rasterizes and the preview the overlay paints.
     */
    open class Tip(radius: Float, hardness: Float = 1f, val antialias: Boolean = true, minRadius: Float = 0.5f) {
        /**
         * The radius the tip reaches, in canvas pixels: what the cursor ring marks out. It is at least [minRadius]:
         * half a pixel of the grid it paints on, which on a layer denser than the canvas is less than half a canvas pixel.
         */
        val radius: Float = radius.coerceAtLeast(minRadius)

        /** Fraction of the radius that stays solid; 1 is a pen. */
        val hardness: Float = hardness.coerceIn(0f, 1f)

        /** Where the fade begins: inside this distance the tip is solid. */
        val core: Float = this.radius * this.hardness

        /** The coverage a pixel whose centre is [distance] away from the stroke gets from the tip. */
        fun alphaAt(distance: Float): Float {
            if (distance >= radius) return 0f
            // A tip asked to be hard has nothing left to fade over, so what is left is the edge itself:
            // one pixel of antialiasing, or - for the pencil - no blending at all.
            if (radius - core <= 0.5f) {
                return if (antialias) (radius + 0.5f - distance).coerceAtMost(1f) else 1f
            }
            val t = ((distance - core) / (radius - core)).coerceIn(0f, 1f)
            return 1f - t * t * (3f - 2f * t)
        }
    }

    /**
     * One stroke's coverage, one byte per pixel: the strongest the tip ever was over each pixel.
     *
     * A stroke is a *shape*, not a pile of stamps. Because a pixel keeps the strongest coverage it was
     * given rather than the sum of everything that passed over it, a stroke that crosses itself, stalls,
     * or is drawn in twenty small steps lands exactly once, at the opacity it was asked for - and,
     * because nothing here depends on how the drag was chopped into segments, the picture while the
     * pointer is still down is the same picture it will be when it comes up.
     */
    open class Stroke(private val width: Int, private val height: Int) {
        private val coverage = ByteArray(max(1, width) * max(1, height))

        /** Everywhere the stroke has reached, or null while it is still empty. Cleared by [reset]. */
        var bounds: Rectangle? = null
            private set

        /** Forgets the previous stroke's coverage, leaving the buffer ready for the next one. */
        fun reset() {
            val region = bounds ?: return
            val left = region.x.coerceIn(0, width)
            val right = (region.x + region.width).coerceIn(0, width)
            val top = region.y.coerceIn(0, height)
            val bottom = (region.y + region.height).coerceIn(0, height)
            for (y in top until bottom) {
                Arrays.fill(coverage, y * width + left, y * width + right, 0)
            }
            bounds = null
        }

        /**
         * Extends the stroke by one segment: a round-capped capsule of half-width [tip]`radius`, with the
         * tip's own falloff on the inside of that.
         *
         * @return The pixels that just gained coverage, which are the only ones whose value on the layer
         *         can have changed - or null when the segment covers ground the stroke already had, which
         *         is what a stroke doubling back over itself does.
         */
        fun addSegment(fromX: Float, fromY: Float, toX: Float, toY: Float, tip: Tip, checkpoint: () -> Unit = {}): Rectangle? {
            checkpoint()
            val radius = tip.radius
            val minX = floor(min(fromX, toX) - radius - 0.5f).toInt().coerceAtLeast(0)
            val maxX = ceil(max(fromX, toX) + radius + 0.5f).toInt().coerceAtMost(width - 1)
            val minY = floor(min(fromY, toY) - radius - 0.5f).toInt().coerceAtLeast(0)
            val maxY = ceil(max(fromY, toY) + radius + 0.5f).toInt().coerceAtMost(height - 1)
            if (minX > maxX || minY > maxY) return null

            val dx = toX - fromX
            val dy = toY - fromY
            val lengthSq = dx * dx + dy * dy
            val radiusSq = radius * radius
            val coreSq = tip.core * tip.core
            val fades = tip.radius - tip.core > 0.5f
            var left = Int.MAX_VALUE
            var top = Int.MAX_VALUE
            var right = -1
            var bottom = -1

            for (y in minY..maxY) {
                checkpoint()
                val py = y + 0.5f
                val row = y * width
                for (x in minX..maxX) {
                    val px = x + 0.5f
                    val along = if (lengthSq <= 1e-6f) {
                        0f
                    } else {
                        (((px - fromX) * dx + (py - fromY) * dy) / lengthSq).coerceIn(0f, 1f)
                    }
                    val offX = px - (fromX + along * dx)
                    val offY = py - (fromY + along * dy)
                    val distanceSq = offX * offX + offY * offY
                    if (distanceSq >= radiusSq) continue
                    // Only the rim needs the actual distance: the core is solid by definition.
                    val alpha = if (fades && distanceSq <= coreSq) 1f else tip.alphaAt(sqrt(distanceSq))
                    if (alpha <= 0f) continue
                    val value = (alpha * 255f + 0.5f).toInt()
                    val index = row + x
                    if (value > (coverage[index].toInt() and 0xFF)) {
                        coverage[index] = value.toByte()
                        if (x < left) left = x
                        if (x > right) right = x
                        if (y < top) top = y
                        if (y > bottom) bottom = y
                    }
                }
            }
            if (right < 0) return null

            val touched = Rectangle(left, top, right - left + 1, bottom - top + 1)
            bounds = bounds?.union(touched) ?: touched
            return touched
        }

        /**
         * Lands [region] of the stroke's coverage on [target] in one pass: [color] at [opacity] for a
         * brush, or the same coverage taken back out of the layer for an eraser.
         *
         * The coverage is the whole of what the stroke has to say about a pixel, so landing it region by
         * region - which is what a live stroke does, once per pointer move - adds up to exactly the same
         * layer as landing the finished stroke in one go.
         */
        fun land(
            target: BufferedImage,
            color: Int,
            opacity: Float,
            erase: Boolean,
            region: Rectangle,
            checkpoint: () -> Unit = {},
            /** Confines the stroke to the session's selection: a pixel takes coverage times its selection. */
            mask: PaintSelection? = null,
            /** Photoshop's lock transparency: colour lands on what is painted, alpha stays as it is. */
            lockAlpha: Boolean = false,
        ) {
            checkpoint()
            val area = Rectangle(region)
                .intersection(Rectangle(0, 0, target.width, target.height))
                .intersection(Rectangle(0, 0, width, height))
            if (area.isEmpty) return

            val argb = color
            val strength = (((argb ushr 24) and 0xFF) / 255f) * opacity.coerceIn(0f, 1f)
            if (strength <= 0f) return
            val red = (argb ushr 16) and 0xFF
            val green = (argb ushr 8) and 0xFF
            val blue = argb and 0xFF
            val pixels = target.getRGB(area.x, area.y, area.width, area.height, null, 0, area.width)

            for (row in 0 until area.height) {
                checkpoint()
                val maskRow = (area.y + row) * width + area.x
                val pixelRow = row * area.width
                for (column in 0 until area.width) {
                    var source = ((coverage[maskRow + column].toInt() and 0xFF) / 255f) * strength
                    if (mask != null) source *= mask.at(area.x + column, area.y + row) / 255f
                    if (source <= 0f) continue
                    val at = pixelRow + column
                    val destination = pixels[at]
                    if (lockAlpha && !erase) {
                        // Only what is already there takes the colour, and it keeps its alpha.
                        if (destination ushr 24 == 0) continue
                        val keep = 1f - source
                        val r = (red * source + ((destination ushr 16) and 0xFF) * keep + 0.5f).toInt().coerceIn(0, 255)
                        val g = (green * source + ((destination ushr 8) and 0xFF) * keep + 0.5f).toInt().coerceIn(0, 255)
                        val b = (blue * source + (destination and 0xFF) * keep + 0.5f).toInt().coerceIn(0, 255)
                        pixels[at] = (destination and -0x1000000) or (r shl 16) or (g shl 8) or b
                        continue
                    }
                    // An erase takes the coverage out of the layer's alpha and leaves its colour alone;
                    // a stroke blends its colour in over whatever the layer already held.
                    if (erase) {
                        val destinationAlpha = ((destination ushr 24) and 0xFF) / 255f
                        val alpha = (destinationAlpha * (1f - source) * 255f + 0.5f).toInt().coerceIn(0, 255)
                        pixels[at] = (alpha shl 24) or (destination and 0xFFFFFF)
                        continue
                    }
                    val destinationAlpha = ((destination ushr 24) and 0xFF) / 255f
                    val outAlpha = source + destinationAlpha * (1f - source)
                    val back = destinationAlpha * (1f - source)
                    val r = ((red * source + ((destination ushr 16) and 0xFF) * back) / outAlpha).toInt().coerceIn(0, 255)
                    val g = ((green * source + ((destination ushr 8) and 0xFF) * back) / outAlpha).toInt().coerceIn(0, 255)
                    val b = ((blue * source + (destination and 0xFF) * back) / outAlpha).toInt().coerceIn(0, 255)
                    pixels[at] = ((outAlpha * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            target.setRGB(area.x, area.y, area.width, area.height, pixels, 0, area.width)
        }

        /**
         * Lands [region] of the stroke as a blur: each pixel moves towards the box-blurred stroke-start pixels around
         * it by its coverage times [strength]. [original] holds the stroke-start pixels of [originalArea], which must
         * reach [kernel] pixels past [region] on every side the raster has; reading the stroke's start rather than
         * what earlier segments left keeps a blur stroke landing once, like a paint stroke.
         */
        fun landBlur(
            target: BufferedImage,
            original: IntArray,
            originalArea: Rectangle,
            region: Rectangle,
            strength: Float,
            kernel: Int,
            mask: PaintSelection? = null,
        ) {
            val area = Rectangle(region)
                .intersection(originalArea)
                .intersection(Rectangle(0, 0, width, height))
            if (area.isEmpty || strength <= 0f) return
            val blurred = blurPremultiplied(original, originalArea.width, originalArea.height, kernel.coerceAtLeast(1))
            val pixels = target.getRGB(area.x, area.y, area.width, area.height, null, 0, area.width)
            for (row in 0 until area.height) {
                val y = area.y + row
                for (column in 0 until area.width) {
                    val x = area.x + column
                    var weight = ((coverage[y * width + x].toInt() and 0xFF) / 255f) * strength.coerceIn(0f, 1f)
                    if (mask != null) weight *= mask.at(x, y) / 255f
                    if (weight <= 0f) continue
                    val at = (y - originalArea.y) * originalArea.width + (x - originalArea.x)
                    pixels[row * area.width + column] = mixArgb(original[at], blurred[at], (weight * 255f + 0.5f).toInt())
                }
            }
            target.setRGB(area.x, area.y, area.width, area.height, pixels, 0, area.width)
        }
    }

    /** Whether [argb] is within [tolerance] of [target] on every channel, alpha included: the fill's and wand's test. */
    fun colorWithin(argb: Int, target: Int, tolerance: Int): Boolean =
        abs(((argb ushr 16) and 0xFF) - ((target ushr 16) and 0xFF)) <= tolerance &&
            abs(((argb ushr 8) and 0xFF) - ((target ushr 8) and 0xFF)) <= tolerance &&
            abs((argb and 0xFF) - (target and 0xFF)) <= tolerance &&
            abs(((argb ushr 24) and 0xFF) - ((target ushr 24) and 0xFF)) <= tolerance

    /** A box blur of [pixels] ([width] x [height]) with radius [r], in premultiplied colour and clamped at the edges. */
    fun blurPremultiplied(pixels: IntArray, width: Int, height: Int, r: Int): IntArray {
        val channels = Array(4) { IntArray(pixels.size) }
        for (i in pixels.indices) {
            val p = pixels[i]; val a = (p ushr 24) and 0xFF
            channels[0][i] = a
            channels[1][i] = ((p ushr 16) and 0xFF) * a
            channels[2][i] = ((p ushr 8) and 0xFF) * a
            channels[3][i] = (p and 0xFF) * a
        }
        val span = 2 * r + 1
        for (c in channels) {
            val pass = IntArray(c.size)
            for (y in 0 until height) {
                val row = y * width
                var sum = 0L
                for (i in -r..r) sum += c[row + i.coerceIn(0, width - 1)]
                for (x in 0 until width) {
                    pass[row + x] = (sum / span).toInt()
                    sum += c[row + (x + r + 1).coerceAtMost(width - 1)] - c[row + (x - r).coerceAtLeast(0)]
                }
            }
            for (x in 0 until width) {
                var sum = 0L
                for (i in -r..r) sum += pass[i.coerceIn(0, height - 1) * width + x]
                for (y in 0 until height) {
                    c[y * width + x] = (sum / span).toInt()
                    sum += pass[(y + r + 1).coerceAtMost(height - 1) * width + x] - pass[(y - r).coerceAtLeast(0) * width + x]
                }
            }
        }
        return IntArray(pixels.size) { i ->
            val a = channels[0][i]
            if (a <= 0) 0 else (a.coerceAtMost(255) shl 24) or
                ((channels[1][i] / a).coerceIn(0, 255) shl 16) or ((channels[2][i] / a).coerceIn(0, 255) shl 8) or
                (channels[3][i] / a).coerceIn(0, 255)
        }
    }

    /**
     * Lays a gradient over [area] of [image]: [from] at ([x0], [y0]) to [to] at ([x1], [y1]), along the line, or
     * out from the first point when [radial], composited over the pixels at [opacity]. Each colour keeps its own
     * alpha, so a foreground-to-transparent gradient is [to] with alpha 0.
     */
    fun gradient(image: BufferedImage, x0: Float, y0: Float, x1: Float, y1: Float, from: Int, to: Int,
        radial: Boolean, opacity: Float, area: Rectangle, checkpoint: () -> Unit = {}) {
        val clip = area.intersection(Rectangle(0, 0, image.width, image.height))
        if (clip.isEmpty) return
        val dx = x1 - x0; val dy = y1 - y0
        val lengthSq = dx * dx + dy * dy
        val length = sqrt(lengthSq)
        val pixels = image.getRGB(clip.x, clip.y, clip.width, clip.height, null, 0, clip.width)
        val strength = opacity.coerceIn(0f, 1f)
        for (row in 0 until clip.height) {
            checkpoint()
            val py = clip.y + row + 0.5f
            for (column in 0 until clip.width) {
                val px = clip.x + column + 0.5f
                val t = when {
                    lengthSq <= 1e-6f -> 1f
                    radial -> (sqrt((px - x0) * (px - x0) + (py - y0) * (py - y0)) / length).coerceIn(0f, 1f)
                    else -> (((px - x0) * dx + (py - y0) * dy) / lengthSq).coerceIn(0f, 1f)
                }
                val color = mixArgb(from, to, (t * 255f + 0.5f).toInt())
                val sa = ((color ushr 24) and 0xFF) / 255f * strength
                if (sa <= 0f) continue
                val at = row * clip.width + column
                val d = pixels[at]
                val da = ((d ushr 24) and 0xFF) / 255f
                val outA = sa + da * (1f - sa)
                val back = da * (1f - sa)
                fun ch(shift: Int) = ((((color ushr shift) and 0xFF) * sa + ((d ushr shift) and 0xFF) * back) / outA + 0.5f).toInt().coerceIn(0, 255)
                pixels[at] = ((outA * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
        image.setRGB(clip.x, clip.y, clip.width, clip.height, pixels, 0, clip.width)
    }

    fun floodFill(
        image: BufferedImage,
        startX: Int,
        startY: Int,
        fillColor: Int,
        tolerance: Int,
        clipRect: Rectangle? = null,
        before: (Rectangle) -> Unit = {},
        checkpoint: () -> Unit = {},
        opacity: Float = 1f,
        /** Off fills every pixel within [tolerance] of the clicked colour, joined to it or not. */
        contiguous: Boolean = true,
    ): Rectangle? {
        checkpoint()
        if (startX !in 0 until image.width || startY !in 0 until image.height) return null
        if (clipRect != null && !clipRect.contains(startX, startY)) return null

        val targetRgb = image.getRGB(startX, startY)
        val cover = (((fillColor ushr 24) and 0xFF) * opacity.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        val fillArgb = (cover shl 24) or (fillColor and 0x00FFFFFF)
        if (targetRgb == fillArgb) return null

        fun matches(rgb: Int): Boolean = colorWithin(rgb, targetRgb, tolerance)

        val minX = clipRect?.x ?: 0
        val minY = clipRect?.y ?: 0
        val maxX = clipRect?.let { it.x + it.width - 1 } ?: (image.width - 1)
        val maxY = clipRect?.let { it.y + it.height - 1 } ?: (image.height - 1)

        if (!contiguous) {
            // Every matching pixel, wherever it is: mapped first and filled after, one action like the joined fill.
            var left = Int.MAX_VALUE; var top = Int.MAX_VALUE; var right = -1; var bottom = -1
            val w = maxX - minX + 1; val h = maxY - minY + 1
            val pixels = image.getRGB(minX, minY, w, h, null, 0, w)
            for (y in 0 until h) {
                if (y % 64 == 0) checkpoint()
                for (x in 0 until w) if (matches(pixels[y * w + x])) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
            if (right < 0) return null
            val area = Rectangle(minX + left, minY + top, right - left + 1, bottom - top + 1)
            before(area)
            for (i in pixels.indices) if (matches(pixels[i])) pixels[i] = fillArgb
            image.setRGB(minX, minY, w, h, pixels, 0, w)
            return area
        }

        val queue = ArrayDeque<Pair<Int, Int>>()
        val visited = java.util.BitSet((maxX - minX + 1) * (maxY - minY + 1))
        val stride = maxX - minX + 1

        var left = startX
        var top = startY
        var right = startX
        var bottom = startY

        queue.add(startX to startY)
        visited.set((startY - minY) * stride + (startX - minX))

        // The region is mapped first and filled after: the pixels the fill is about to replace have to
        // be read while they are still there, and a fill is one action to undo, not one per pixel.
        var processed = 0
        while (queue.isNotEmpty()) {
            if (processed++ % 1024 == 0) checkpoint()
            val (cx, cy) = queue.removeFirst()
            if (cx < left) left = cx
            if (cx > right) right = cx
            if (cy < top) top = cy
            if (cy > bottom) bottom = cy

            val neighbors = listOf(cx + 1 to cy, cx - 1 to cy, cx to cy + 1, cx to cy - 1)
            for ((nx, ny) in neighbors) {
                if (nx in minX..maxX && ny in minY..maxY) {
                    val idx = (ny - minY) * stride + (nx - minX)
                    if (!visited.get(idx)) {
                        visited.set(idx)
                        if (matches(image.getRGB(nx, ny))) {
                            queue.add(nx to ny)
                        }
                    }
                }
            }
        }

        val area = Rectangle(left, top, right - left + 1, bottom - top + 1)
        before(area)
        for (y in top..bottom) {
            checkpoint()
            val row = (y - minY) * stride
            for (x in left..right) {
                if (visited.get(row + (x - minX)) && matches(image.getRGB(x, y))) {
                    image.setRGB(x, y, fillArgb)
                }
            }
        }
        return area
    }

    /** The area a shape can write to, which is what has to be remembered before it is drawn. */
    fun shapeArea(
        x0: Int, y0: Int,
        x1: Int, y1: Int,
        strokeWidth: Float,
        clipRect: Rectangle? = null,
    ): Rectangle {
        val left = min(x0, x1)
        val top = min(y0, y1)
        val width = max(1, abs(x1 - x0))
        val height = max(1, abs(y1 - y0))
        val pad = (max(1f, strokeWidth) / 2f).toInt() + 2
        val area = Rectangle(left - pad, top - pad, width + pad * 2 + 1, height + pad * 2 + 1)
        return clipRect?.let { area.intersection(it) } ?: area
    }

    /**
     * Rasterizes one shape in [color] at [opacity]. A shape is not a stroke: it is previewed from the
     * two corners the gesture drags and lands as a whole when the pointer comes up.
     *
     * @return The area the shape can have written to, which is what has to be remembered to undo it.
     */
    fun drawShape(
        image: BufferedImage,
        x0: Int, y0: Int,
        x1: Int, y1: Int,
        shape: RasterPaintShape,
        color: Int,
        opacity: Float,
        strokeWidth: Float,
        filled: Boolean,
        clipRect: Rectangle? = null,
    ): Rectangle? {
        val left = min(x0, x1)
        val top = min(y0, y1)
        val width = max(1, abs(x1 - x0))
        val height = max(1, abs(y1 - y0))
        val g = image.createGraphics()
        try {
            if (clipRect != null) g.clip = clipRect
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val argb = color
            val alpha = (((argb ushr 24) and 0xFF) * opacity.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
            g.color = java.awt.Color((alpha shl 24) or (argb and 0xFFFFFF), true)
            g.stroke = BasicStroke(max(1f, strokeWidth), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

            when (shape) {
                RasterPaintShape.LINE -> g.drawLine(x0, y0, x1, y1)
                RasterPaintShape.RECTANGLE -> {
                    if (filled) g.fillRect(left, top, width, height)
                    else g.drawRect(left, top, width, height)
                }
                RasterPaintShape.ELLIPSE -> {
                    if (filled) g.fillOval(left, top, width, height)
                    else g.drawOval(left, top, width, height)
                }
            }
        } finally {
            g.dispose()
        }
        return shapeArea(x0, y0, x1, y1, strokeWidth, clipRect)
    }

    fun clear(image: BufferedImage) {
        val g = image.createGraphics()
        try {
            g.composite = AlphaComposite.Clear
            g.fillRect(0, 0, image.width, image.height)
        } finally {
            g.dispose()
        }
    }
}

internal enum class RasterPaintShape { LINE, RECTANGLE, ELLIPSE }
