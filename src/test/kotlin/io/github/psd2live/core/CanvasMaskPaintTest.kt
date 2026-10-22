package io.github.psd2live.core

import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertTrue

class CanvasMaskPaintTest {
	private fun quad(x0: Float, x1: Float) = DrawableMesh(floatArrayOf(x0, 0f, x1, 0f, x0, 16f, x1, 16f),
		floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), intArrayOf(0, 1, 2, 1, 3, 2))

	/** Alpha of the clipped part at a pixel inside its mask and at one outside it. */
	private fun paint(invert: Boolean): Pair<Int, Int> {
		val mask = Drawable(DrawableId("mask"), "Mask", null, BlendMode.Normal, emptyList(), quad(0f, 8f), null, texturePage = 0)
		val part = Drawable(DrawableId("part"), "Part", null, BlendMode.Normal, listOf(mask.id), quad(0f, 16f), null,
			texturePage = 0, invertMask = invert)
		val model = PuppetModel(emptyList(), emptyList(), emptyList(), listOf(mask, part),
			listOf(OrgChild.Drawable(mask.id), OrgChild.Drawable(part.id)), null, canvasWidth = 16f, canvasHeight = 16f)
		val page = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB).apply {
			for (y in 0 until 4) for (x in 0 until 4) setRGB(x, y, 0xffff0000.toInt())
		}
		val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
		val g = image.createGraphics()
		try {
			RigCanvasSupport.paintPuppet(g, model, listOf(page), CpuDeformationEvaluator().evaluate(model, emptyMap()),
				CanvasViewport(1.0, 0.0, 0.0, 16f, 16f), only = setOf("part"))
		} finally { g.dispose() }
		return (image.getRGB(4, 8) ushr 24) to (image.getRGB(12, 8) ushr 24)
	}

	@Test fun clippedPartShowsOnlyInsideItsMask() {
		val (inside, outside) = paint(invert = false)
		assertTrue(inside > 200 && outside == 0, "inside $inside, outside $outside")
	}

	@Test fun invertedMaskShowsThePartOnlyOutsideIt() {
		val (inside, outside) = paint(invert = true)
		assertTrue(inside == 0 && outside > 200, "inside $inside, outside $outside")
	}
}
