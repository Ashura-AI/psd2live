package io.github.psd2live.core

import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertTrue

/** The Java2D painter draws masks, colours and blends as the preview does. */
class CanvasMaskPaintTest {
	private fun quad(x0: Float, x1: Float) = DrawableMesh(floatArrayOf(x0, 0f, x1, 0f, x0, 16f, x1, 16f),
		floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), intArrayOf(0, 1, 2, 1, 3, 2))

	/** A 4×4 page, [argb] where [opaque] says, transparent elsewhere. */
	private fun page(argb: Int, opaque: (Int) -> Boolean = { true }) = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB).apply {
		for (y in 0 until 4) for (x in 0 until 4) setRGB(x, y, if (opaque(x)) argb else 0)
	}

	/** Paints [drawables] (back to front) on pages by their texture page and returns the 16×16 image. */
	private fun paint(drawables: List<Drawable>, pages: List<BufferedImage>, only: Set<String>? = null): BufferedImage {
		val model = PuppetModel(emptyList(), emptyList(), emptyList(), drawables,
			drawables.map { OrgChild.Drawable(it.id) }, null, canvasWidth = 16f, canvasHeight = 16f)
		val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
		val g = image.createGraphics()
		try {
			RigCanvasSupport.paintPuppet(g, model, pages, CpuDeformationEvaluator().evaluate(model, emptyMap()),
				CanvasViewport(1.0, 0.0, 0.0, 16f, 16f), only = only)
		} finally { g.dispose() }
		return image
	}

	/** Alpha of the clipped part at a pixel inside its mask's opaque texels and at one outside them. */
	private fun masked(invert: Boolean): Pair<Int, Int> {
		// The mask's mesh spans the whole canvas, but only its left half is opaque.
		val mask = Drawable(DrawableId("mask"), "Mask", null, BlendMode.Normal, emptyList(), quad(0f, 16f), null, texturePage = 1)
		val part = Drawable(DrawableId("part"), "Part", null, BlendMode.Normal, listOf(mask.id), quad(0f, 16f), null,
			texturePage = 0, invertMask = invert)
		val image = paint(listOf(mask, part), listOf(page(0xffff0000.toInt()), page(0xff00ff00.toInt()) { it < 2 }), only = setOf("part"))
		return (image.getRGB(4, 8) ushr 24) to (image.getRGB(12, 8) ushr 24)
	}

	@Test fun clippedPartShowsOnlyOverItsMasksOpaqueTexels() {
		val (inside, outside) = masked(invert = false)
		assertTrue(inside > 200 && outside == 0, "inside $inside, outside $outside")
	}

	@Test fun invertedMaskShowsThePartOnlyOutsideIt() {
		val (inside, outside) = masked(invert = true)
		assertTrue(inside == 0 && outside > 200, "inside $inside, outside $outside")
	}

	@Test fun multiplyAndScreenColoursTintThePart() {
		val part = Drawable(DrawableId("part"), "Part", null, BlendMode.Normal, emptyList(), quad(0f, 16f), null, texturePage = 0,
			multiplyColor = ColorRgb(0.5f, 1f, 1f), screenColor = ColorRgb(0f, 0f, 1f))
		val rgb = paint(listOf(part), listOf(page(0xffff0000.toInt()))).getRGB(8, 8)
		// Red halved by the multiply colour, blue screened to full.
		assertTrue(rgb shr 16 and 0xff in 125..130 && rgb and 0xff == 255, Integer.toHexString(rgb))
	}

	@Test fun multiplyBlendDarkensWhatIsUnder() {
		val base = Drawable(DrawableId("base"), "Base", null, BlendMode.Normal, emptyList(), quad(0f, 16f), null, texturePage = 1, drawOrder = 400f)
		val part = Drawable(DrawableId("part"), "Part", null, BlendMode.Multiply, emptyList(), quad(0f, 16f), null, texturePage = 0, drawOrder = 600f)
		val rgb = paint(listOf(base, part), listOf(page(0xffff0000.toInt()), page(0xff808080.toInt()))).getRGB(8, 8)
		assertTrue(rgb shr 16 and 0xff in 126..130 && rgb shr 8 and 0xff == 0 && rgb and 0xff == 0, Integer.toHexString(rgb))
	}
}
