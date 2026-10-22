package io.github.psd2live.core

import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaintPixelTransformTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    private fun square(): BufferedImage = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB).also {
        for (y in 2 until 6) for (x in 2 until 6) it.setRGB(x, y, red)
    }

    private fun transform(image: BufferedImage, region: Rectangle, m: FloatArray, selection: PaintSelection? = null, smooth: Boolean = false) {
        val floating = PaintPixelTransform.lifted(image, region, selection)
        PaintPixelTransform.clear(image, region, selection)
        PaintPixelTransform.place(image, floating, region, m, smooth)
    }

    @Test
    fun aMoveTakesThePixelsAwayAndPutsThemDownElsewhere() {
        val image = square()
        transform(image, Rectangle(2, 2, 4, 4), floatArrayOf(1f, 0f, 0f, 1f, 10f, 8f))
        assertEquals(0, image.getRGB(3, 3))
        assertEquals(red, image.getRGB(13, 11))
        assertEquals(Rectangle(12, 10, 4, 4), PaintPixelTransform.contentBounds(image, Rectangle(0, 0, 20, 20)))
    }

    @Test
    fun scalingTwiceCoversTwiceTheSideAndKeepsHardPixelsWithoutSmoothing() {
        val image = square()
        // Twice as large about the square's top-left corner.
        transform(image, Rectangle(2, 2, 4, 4), floatArrayOf(2f, 0f, 0f, 2f, -2f, -2f))
        assertEquals(Rectangle(2, 2, 8, 8), PaintPixelTransform.contentBounds(image, Rectangle(0, 0, 20, 20)))
        assertEquals(red, image.getRGB(9, 9))
    }

    @Test
    fun aQuarterTurnTurnsTheSquareAboutItsCentre() {
        val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        // A 4x2 bar, turned 90° about (4, 3) becomes a 2x4 bar.
        for (y in 2 until 4) for (x in 2 until 6) image.setRGB(x, y, red)
        transform(image, Rectangle(2, 2, 4, 2), floatArrayOf(0f, 1f, -1f, 0f, 4f + 3f, 3f - 4f))
        assertEquals(Rectangle(3, 1, 2, 4), PaintPixelTransform.contentBounds(image, Rectangle(0, 0, 20, 20)))
    }

    @Test
    fun aSelectionLiftsOnlyWhatItTakesAndMovesWithIt() {
        val image = square()
        for (y in 2 until 6) image.setRGB(5, y, blue)
        // The left three columns of the square are selected; the blue column stays.
        val selection = PaintSelection.rectangle(20, 20, 2f, 2f, 5f, 6f, antialias = false)
        transform(image, Rectangle(2, 2, 3, 4), floatArrayOf(1f, 0f, 0f, 1f, 10f, 0f), selection)
        assertEquals(blue, image.getRGB(5, 3))
        assertEquals(0, image.getRGB(2, 3))
        assertEquals(red, image.getRGB(12, 3))
        val moved = PaintPixelTransform.mapSelection(selection, floatArrayOf(1f, 0f, 0f, 1f, 10f, 0f))!!
        assertEquals(Rectangle(12, 2, 3, 4), moved.bounds)
    }

    @Test
    fun aSmoothScaleBlendsTheEdgeAndNoTransparencyDarkensIt() {
        val image = square()
        transform(image, Rectangle(2, 2, 4, 4), floatArrayOf(1.5f, 0f, 0f, 1.5f, 0f, 0f), smooth = true)
        val edge = image.getRGB(3, 6)
        assertEquals(0xFF0000, edge and 0xFFFFFF, "an edge pixel is red, however see-through")
        assertTrue((edge ushr 24) in 1..255)
    }

    @Test
    fun aFlatMapHasNoInverse() {
        assertNull(PaintPixelTransform.inverse(floatArrayOf(1f, 0f, 2f, 0f, 0f, 0f)))
    }
}
