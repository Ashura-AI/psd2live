package io.github.psd2live.application

import io.github.psd2live.project.MaterialKind
import io.github.psd2live.project.MaterialItem
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.core.RigPreviewModel
import kotlinx.serialization.json.*
import org.umamo.format.art.LayerRaster
import java.awt.image.BufferedImage

/**
 * Pixel edits of a material item: a paint gesture on its own pixels and image adjustments (flip, hue, saturation,
 * lightness, contrast). The item keeps its size, so every layer showing it takes the new pixels over its unchanged
 * rectangle, as `material_set_resolution` does. A vector item edited this way becomes a bitmap of its current raster.
 */
internal object WorkspaceMaterialPixels {
    const val PAINT = "material_paint"
    const val ADJUST = "material_adjust"
    const val SET_PIXELS = "material_set_pixels"
    val supported = setOf(PAINT, ADJUST, SET_PIXELS)

    /** Pixels a material paint session commits, held in this process until the edit that writes them is prepared. */
    private val staged = object : LinkedHashMap<String, LayerRaster>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LayerRaster>?) = size > 8
    }

    /** Stages [raster] for `material_set_pixels`; the token names its pixels. */
    fun stage(raster: LayerRaster): String {
        val token = "pixels_" + io.github.psd2live.project.WorkspaceRevisions.rasterDigest(raster.rgba).take(24) + "_${raster.width}x${raster.height}"
        synchronized(staged) { staged[token] = raster }
        return token
    }

    private fun staged(token: String): LayerRaster = synchronized(staged) { staged[token] }
        ?: throw IllegalArgumentException("The staged pixels $token are no longer held; commit the paint session again")
    val flips = listOf("horizontal", "vertical")

    fun apply(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, model: RigPreviewModel,
              work: WorkspaceRasterWork = WorkspaceRasterWork.Direct): WorkspaceDocument {
        val request = operation.request
        val id = request.getValue("id").jsonPrimitive.content
        val item = document.layout.materials.item(id) ?: throw IllegalArgumentException("Material not found: $id")
        val raster = when (operation.operation) {
            SET_PIXELS -> staged(request.getValue("staging").jsonPrimitive.content).also {
                require(it.width == item.raster.width && it.height == item.raster.height) { "The pixels must keep the material's size" }
            }
            PAINT -> paint(item.raster, request, work)
            ADJUST -> adjust(item.raster, Adjustment.of(request), work::checkpoint)
            else -> throw IllegalArgumentException("Not a material pixel edit: ${operation.operation}")
        }
        if (raster.rgba.contentEquals(item.raster.rgba)) return document
        return replace(document, model, item, raster)
    }

    /** [item] showing [raster] (its own size) in the library and in every layer that shows it. */
    fun replace(document: WorkspaceDocument, model: RigPreviewModel, item: MaterialItem, raster: LayerRaster): WorkspaceDocument {
        val library = document.layout.materials
        val edited = item.copy(raster = raster, kind = MaterialKind.BITMAP, svg = null)
        var next = document.copy(layout = document.layout.copy(materials = library.copy(items = library.items.map { if (it.id == item.id) edited else it })))
        for (layer in WorkspaceMaterialEdits.references(document, setOf(item.id))[item.id].orEmpty())
            next = WorkspaceDocumentEdits.replaceImage(next, model, layer, raster)
        return next
    }

    /** Most gestures one `material_paint` carries: a painting session's strokes, applied as one edit. */
    const val MAX_GESTURES = 512

    /**
     * The paint gestures of a `material_paint` request in the item's pixel coordinates, in order: its `gestures`, or
     * the request itself as one gesture ([paintRasterGesture]'s arguments).
     */
    fun gestures(request: JsonObject): List<JsonObject> {
        val list = request["gestures"]?.jsonArray?.map { it.jsonObject }
        require((list == null) != ("mode" !in request)) { "Give either mode (one gesture) or gestures" }
        return (list ?: listOf(request)).also { require(it.size in 1..MAX_GESTURES) { "Give 1..$MAX_GESTURES gestures" } }
    }

    /** [request]'s gestures ([gestures]) painted on [raster] in turn. */
    fun paint(raster: LayerRaster, request: JsonObject, work: WorkspaceRasterWork = WorkspaceRasterWork.Direct): LayerRaster {
        val image = image(raster)
        for (gesture in gestures(request)) paintRasterGesture(image, gesture, work)
        return raster(image)
    }

    /** The adjustments of a `material_adjust` request: a flip, then hue, saturation, lightness and contrast. */
    data class Adjustment(val flip: String? = null, val hue: Float = 0f, val saturation: Float = 0f, val lightness: Float = 0f,
                          val contrast: Float = 0f) {
        val colour: Boolean get() = hue != 0f || saturation != 0f || lightness != 0f || contrast != 0f

        companion object {
            fun of(request: JsonObject): Adjustment {
                fun number(key: String, range: ClosedFloatingPointRange<Float>) = request[key]?.jsonPrimitive?.float?.also {
                    require(it.isFinite() && it in range) { "$key must be in ${range.start.toInt()}..${range.endInclusive.toInt()}" }
                } ?: 0f
                val flip = request["flip"]?.jsonPrimitive?.content?.also { require(it in flips) { "flip is horizontal or vertical" } }
                return Adjustment(flip, number("hue", -180f..180f), number("saturation", -100f..100f), number("lightness", -100f..100f),
                    number("contrast", -100f..100f)).also { require(it.flip != null || it.colour) { "Give flip or a colour adjustment" } }
            }
        }
    }

    fun adjust(raster: LayerRaster, adjustment: Adjustment, checkpoint: () -> Unit = {}): LayerRaster {
        val width = raster.width; val height = raster.height
        val source = raster.rgba
        val out = ByteArray(source.size)
        for (y in 0 until height) {
            if (y % 64 == 0) checkpoint()
            for (x in 0 until width) {
                val from = when (adjustment.flip) {
                    "horizontal" -> y * width + (width - 1 - x)
                    "vertical" -> (height - 1 - y) * width + x
                    else -> y * width + x
                } * 4
                val to = (y * width + x) * 4
                for (c in 0..3) out[to + c] = source[from + c]
            }
        }
        if (adjustment.colour) colour(out, adjustment, checkpoint)
        return LayerRaster(width, height, out)
    }

    private fun colour(rgba: ByteArray, a: Adjustment, checkpoint: () -> Unit) {
        val hue = a.hue / 360f
        val saturation = a.saturation / 100f
        val lightness = a.lightness / 100f
        // -100 flattens to grey, +100 steepens four times.
        val contrast = if (a.contrast >= 0f) 1f + 3f * a.contrast / 100f else 1f + a.contrast / 100f
        val hsl = FloatArray(3)
        for (i in 0 until rgba.size / 4) {
            if (i % 65536 == 0) checkpoint()
            val o = i * 4
            if (rgba[o + 3].toInt() == 0) continue
            var r = (rgba[o].toInt() and 255) / 255f; var g = (rgba[o + 1].toInt() and 255) / 255f; var b = (rgba[o + 2].toInt() and 255) / 255f
            if (hue != 0f || saturation != 0f || lightness != 0f) {
                toHsl(r, g, b, hsl)
                hsl[0] = ((hsl[0] + hue) % 1f + 1f) % 1f
                hsl[1] = (hsl[1] * (1f + saturation)).coerceIn(0f, 1f)
                hsl[2] = if (lightness >= 0f) hsl[2] + (1f - hsl[2]) * lightness else hsl[2] * (1f + lightness)
                val q = if (hsl[2] < 0.5f) hsl[2] * (1f + hsl[1]) else hsl[2] + hsl[1] - hsl[2] * hsl[1]
                val p = 2f * hsl[2] - q
                r = channel(p, q, hsl[0] + 1f / 3f); g = channel(p, q, hsl[0]); b = channel(p, q, hsl[0] - 1f / 3f)
            }
            if (a.contrast != 0f) { r = (r - 0.5f) * contrast + 0.5f; g = (g - 0.5f) * contrast + 0.5f; b = (b - 0.5f) * contrast + 0.5f }
            rgba[o] = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            rgba[o + 1] = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            rgba[o + 2] = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
        }
    }

    private fun toHsl(r: Float, g: Float, b: Float, out: FloatArray) {
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        val l = (max + min) / 2f
        if (max == min) { out[0] = 0f; out[1] = 0f; out[2] = l; return }
        val d = max - min
        out[1] = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        out[0] = when (max) {
            r -> ((g - b) / d + if (g < b) 6f else 0f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } / 6f
        out[2] = l
    }

    private fun channel(p: Float, q: Float, t0: Float): Float {
        val t = (t0 % 1f + 1f) % 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 0.5f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }

    fun image(raster: LayerRaster): BufferedImage {
        val image = BufferedImage(raster.width, raster.height, BufferedImage.TYPE_INT_ARGB)
        val pixels = IntArray(raster.width * raster.height) { i ->
            val o = i * 4
            ((raster.rgba[o + 3].toInt() and 255) shl 24) or ((raster.rgba[o].toInt() and 255) shl 16) or
                ((raster.rgba[o + 1].toInt() and 255) shl 8) or (raster.rgba[o + 2].toInt() and 255)
        }
        image.setRGB(0, 0, raster.width, raster.height, pixels, 0, raster.width)
        return image
    }

    fun raster(image: BufferedImage): LayerRaster {
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val rgba = ByteArray(pixels.size * 4)
        for (i in pixels.indices) {
            val p = pixels[i]; val o = i * 4
            val alpha = p ushr 24
            // A pixel painted fully transparent keeps no colour, as a layer's erased pixels do.
            if (alpha == 0) continue
            rgba[o] = (p shr 16).toByte(); rgba[o + 1] = (p shr 8).toByte(); rgba[o + 2] = p.toByte(); rgba[o + 3] = alpha.toByte()
        }
        return LayerRaster(image.width, image.height, rgba)
    }
}
