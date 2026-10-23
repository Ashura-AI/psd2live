package io.github.psd2live.core

import org.umamo.render.puppet.compositeReference
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import java.awt.Composite
import java.awt.CompositeContext
import java.awt.RenderingHints
import java.awt.image.ColorModel
import java.awt.image.Raster
import java.awt.image.WritableRaster

/**
 * Java2D compositing by a drawable's colour and alpha blend, pixel for pixel the preview's composite
 * ([compositeReference]): the source is a layer the part was drawn into alone, with its colours, opacity and
 * masks already applied.
 */
internal class CubismComposite(private val blend: BlendMode, private val alphaBlend: AlphaBlendMode) : Composite {
	override fun createContext(srcColorModel: ColorModel, dstColorModel: ColorModel, hints: RenderingHints?): CompositeContext =
		object : CompositeContext {
			override fun compose(src: Raster, dstIn: Raster, dstOut: WritableRaster) {
				val width = minOf(src.width, dstIn.width, dstOut.width)
				val height = minOf(src.height, dstIn.height, dstOut.height)
				var srcPixel: Any? = null
				var dstPixel: Any? = null
				val source = FloatArray(4)
				val destination = FloatArray(4)
				for (y in 0 until height) for (x in 0 until width) {
					srcPixel = src.getDataElements(src.minX + x, src.minY + y, srcPixel)
					dstPixel = dstIn.getDataElements(dstIn.minX + x, dstIn.minY + y, dstPixel)
					val s = srcColorModel.getRGB(srcPixel)
					if (s ushr 24 != 0) {
						premultiplied(s, source)
						premultiplied(dstColorModel.getRGB(dstPixel), destination)
						dstPixel = dstColorModel.getDataElements(straight(compositeReference(source, destination, blend, alphaBlend)), dstPixel)
					}
					dstOut.setDataElements(dstOut.minX + x, dstOut.minY + y, dstPixel)
				}
			}

			override fun dispose() {}
		}

	private companion object {
		fun premultiplied(argb: Int, out: FloatArray) {
			val a = (argb ushr 24) / 255f
			out[0] = (argb shr 16 and 0xff) / 255f * a
			out[1] = (argb shr 8 and 0xff) / 255f * a
			out[2] = (argb and 0xff) / 255f * a
			out[3] = a
		}

		fun straight(premultiplied: FloatArray): Int {
			val a = premultiplied[3]
			if (a <= 0f) return 0
			fun channel(v: Float) = ((v / a).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
			return ((a * 255f + 0.5f).toInt() shl 24) or (channel(premultiplied[0]) shl 16) or
				(channel(premultiplied[1]) shl 8) or channel(premultiplied[2])
		}
	}
}
