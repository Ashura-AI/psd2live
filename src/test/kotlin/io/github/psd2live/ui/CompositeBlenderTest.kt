package io.github.psd2live.ui

import org.jetbrains.skia.*
import org.umamo.render.glsl.skslCompositeBlender
import org.umamo.render.puppet.compositeReference
import org.umamo.render.puppet.packedAlphaModeOf
import org.umamo.render.puppet.packedColorModeOf
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** The software canvas's SkSL blender composites every mode pair as the preview's reference does. */
class CompositeBlenderTest {
	@Test fun blenderMatchesTheReferenceForEveryModePair() {
		val effect = RuntimeEffect.makeForBlender(skslCompositeBlender())
		// Premultiplied source and destination pairs: opaque, half covered, and a transparent destination.
		val pairs = listOf(
			floatArrayOf(0.8f, 0.2f, 0.4f, 1f) to floatArrayOf(0.3f, 0.6f, 0.9f, 1f),
			floatArrayOf(0.3f, 0.1f, 0.05f, 0.5f) to floatArrayOf(0.2f, 0.5f, 0.1f, 0.75f),
			floatArrayOf(0.5f, 0.5f, 0.25f, 0.5f) to floatArrayOf(0f, 0f, 0f, 0f),
		)
		val failures = ArrayList<String>()
		for (blend in BlendMode.entries) for (alpha in AlphaBlendMode.entries) for ((source, destination) in pairs) {
			val uniforms = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
				.putInt(packedColorModeOf(blend)).putInt(packedAlphaModeOf(alpha)).array()
			val drawn = Surface.makeRaster(ImageInfo(1, 1, ColorType.RGBA_8888, ColorAlphaType.PREMUL)).use { surface ->
				Paint().use { under ->
					under.blendMode = org.jetbrains.skia.BlendMode.SRC
					under.color4f = straight(destination)
					surface.canvas.drawRect(Rect.makeWH(1f, 1f), under)
				}
				Paint().use { paint ->
					paint.color4f = straight(source)
					Data.makeFromBytes(uniforms).use { data -> effect.makeBlender(data).use { paint.blender = it; surface.canvas.drawRect(Rect.makeWH(1f, 1f), paint) } }
				}
				// Read back premultiplied.
				Bitmap().use { bitmap ->
					bitmap.allocPixels(ImageInfo(1, 1, ColorType.RGBA_8888, ColorAlphaType.PREMUL))
					surface.makeImageSnapshot().use { it.readPixels(bitmap, 0, 0) }
					val bytes = bitmap.readPixels()!!
					FloatArray(4) { (bytes[it].toInt() and 0xff) / 255f }
				}
			}
			val expected = compositeReference(source, destination, blend, alpha)
			if ((0 until 4).any { abs(drawn[it] - expected[it]) > 3f / 255f }) {
				failures += "$blend/$alpha ${source.toList()} on ${destination.toList()}: ${drawn.toList()} vs ${expected.toList()}"
			}
		}
		assertTrue(failures.isEmpty(), failures.joinToString("\n"))
	}

	/** Skia takes a paint's colour straight; the pairs are given premultiplied. */
	private fun straight(c: FloatArray): Color4f =
		if (c[3] <= 0f) Color4f(0f, 0f, 0f, 0f) else Color4f(c[0] / c[3], c[1] / c[3], c[2] / c[3], c[3])
}
