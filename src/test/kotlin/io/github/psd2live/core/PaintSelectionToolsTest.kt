package io.github.psd2live.core

import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaintSelectionToolsTest {
    private fun image(width: Int, height: Int, argb: (Int, Int) -> Int) = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also {
        for (y in 0 until height) for (x in 0 until width) it.setRGB(x, y, argb(x, y))
    }

    @Test
    fun quickSelectionSpreadsThroughShadingAndStopsAtALine() {
        // Red shaded 2 levels a column, a black line at column 20, and the same red again past it.
        val picture = image(40, 20) { x, _ -> if (x == 20) 0xFF000000.toInt() else 0xFF000000.toInt() or ((150 + (x % 20) * 2) shl 16) }
        val stroke = PaintSelection.quick(picture, floatArrayOf(4f, 10f, 8f, 10f), radius = 2f, tolerance = 40)
        assertEquals(Rectangle(0, 0, 20, 20), stroke.bounds, "the left region, shading and all, and nothing past the line")
    }

    @Test
    fun quickSelectionStopsWhereTheColourJumpsAndKeepsWhatTheBrushCovered() {
        val picture = image(30, 10) { x, _ -> if (x < 15) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        val left = PaintSelection.quick(picture, floatArrayOf(5f, 5f), radius = 1.5f, tolerance = 16)
        assertEquals(Rectangle(0, 0, 15, 10), left.bounds)
        // A brush over the edge takes both sides: what it passed over is what it was asked for.
        val both = PaintSelection.quick(picture, floatArrayOf(15f, 5f), radius = 3f, tolerance = 16)
        assertEquals(Rectangle(0, 0, 30, 10), both.bounds)
    }

    @Test
    fun transparentPixelsAreAlikeWhateverColourTheyHide() {
        // A transparent area whose hidden colour changes halfway, beside an opaque square.
        val picture = image(20, 10) { x, _ -> when { x >= 15 -> 0xFF00FF00.toInt(); x < 7 -> 0x00FF0000; else -> 0x000000FF } }
        assertEquals(Rectangle(0, 0, 15, 10), PaintSelection.wand(picture, 0, 0, 0).bounds)
        assertEquals(Rectangle(0, 0, 15, 10), PaintSelection.quick(picture, floatArrayOf(2f, 2f), 1f, 0).bounds)
    }

    @Test
    fun expandAndContractMoveTheEdgeByTheDistance() {
        val box = PaintSelection.rectangle(40, 40, 10f, 10f, 30f, 30f, antialias = false)
        val grown = box.grown(3f)
        assertEquals(Rectangle(7, 7, 26, 26), grown.bounds)
        assertEquals(255, grown.at(8, 20)); assertEquals(0, grown.at(5, 20))
        assertEquals(0, grown.at(7, 7), "the corner is rounded")
        assertTrue(grown.at(7, 8) in 1..254, "and antialiased: ${grown.at(7, 8)}")
        val shrunk = box.grown(-3f)
        assertEquals(Rectangle(13, 13, 14, 14), shrunk.bounds)
        assertEquals(0, shrunk.at(12, 20)); assertEquals(255, shrunk.at(14, 20))
        // The raster's border is no edge: everything selected stays everything.
        assertEquals(Rectangle(0, 0, 40, 40), PaintSelection.all(40, 40).grown(-5f).bounds)
        assertEquals(255, PaintSelection.all(40, 40).grown(-5f).at(0, 0))
        // Nothing selected grows into nothing.
        assertEquals(null, PaintSelection(8, 8, ByteArray(64)).grown(4f).bounds)
    }

    @Test
    fun opaquePixelsSelectByTheirAlpha() {
        val picture = image(4, 1) { x, _ -> (x * 85 shl 24) or 0x123456 }
        val selection = PaintSelection.opaque(picture)
        assertEquals(listOf(0, 85, 170, 255), (0 until 4).map { selection.at(it, 0) })
    }
}
