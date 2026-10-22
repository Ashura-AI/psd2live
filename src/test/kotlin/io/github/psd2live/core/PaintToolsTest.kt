package io.github.psd2live.core

import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaintToolsTest {
    private fun image(width: Int, height: Int, argb: Int = 0) = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also {
        if (argb != 0) for (y in 0 until height) for (x in 0 until width) it.setRGB(x, y, argb)
    }
    private fun alpha(argb: Int) = argb ushr 24

    @Test
    fun aRectangleSelectionCoversItsBoxAndCombinesLikePhotoshop() {
        val a = PaintSelection.rectangle(20, 20, 2f, 2f, 10f, 10f, antialias = false)
        assertEquals(Rectangle(2, 2, 8, 8), a.bounds)
        assertEquals(255, a.at(5, 5)); assertEquals(0, a.at(12, 5))
        val b = PaintSelection.rectangle(20, 20, 6f, 6f, 14f, 14f, antialias = false)
        assertEquals(Rectangle(2, 2, 12, 12), a.combine(b, PaintSelectionOp.ADD).bounds)
        assertEquals(Rectangle(6, 6, 4, 4), a.combine(b, PaintSelectionOp.INTERSECT).bounds)
        val cut = a.combine(b, PaintSelectionOp.SUBTRACT)
        assertEquals(255, cut.at(3, 3)); assertEquals(0, cut.at(7, 7))
        assertEquals(b, a.combine(b, PaintSelectionOp.REPLACE))
        val inverted = a.inverted()
        assertEquals(0, inverted.at(5, 5)); assertEquals(255, inverted.at(0, 0))
        assertNull(PaintSelection(4, 4, ByteArray(16)).bounds)
    }

    @Test
    fun anEllipseAndALassoSelectTheirInsides() {
        val ellipse = PaintSelection.ellipse(40, 40, 0f, 0f, 40f, 40f, antialias = false)
        assertEquals(255, ellipse.at(20, 20)); assertEquals(0, ellipse.at(1, 1))
        val triangle = PaintSelection.polygon(40, 40, floatArrayOf(0f, 0f, 40f, 0f, 0f, 40f), antialias = false)
        assertEquals(255, triangle.at(5, 5)); assertEquals(0, triangle.at(35, 35))
    }

    @Test
    fun featheringSoftensTheEdgeAndKeepsTheMiddle() {
        val hard = PaintSelection.rectangle(40, 40, 10f, 10f, 30f, 30f, antialias = false)
        val soft = hard.feathered(4f)
        assertEquals(255, soft.at(20, 20))
        val edge = soft.at(10, 20)
        assertTrue(edge in 40..215, "edge $edge")
        assertTrue(soft.at(8, 20) > 0, "the feather reaches past the old edge")
    }

    @Test
    fun theOutlineOfABoxIsItsFourSides() {
        val box = PaintSelection.rectangle(10, 10, 2f, 3f, 6f, 8f, antialias = false)
        val segments = box.outline().toList().chunked(4).map { it.map(Float::toInt) }.toSet()
        assertEquals(setOf(listOf(2, 3, 6, 3), listOf(2, 8, 6, 8), listOf(2, 3, 2, 8), listOf(6, 3, 6, 8)), segments)
    }

    @Test
    fun theWandTakesTheJoinedColourOrEveryPixelOfIt() {
        val picture = image(10, 1, 0xFFFF0000.toInt())
        picture.setRGB(5, 0, 0xFF0000FF.toInt())
        val joined = PaintSelection.wand(picture, 0, 0, 0, contiguous = true)
        assertEquals(Rectangle(0, 0, 5, 1), joined.bounds)
        val everywhere = PaintSelection.wand(picture, 0, 0, 0, contiguous = false)
        assertEquals(Rectangle(0, 0, 10, 1), everywhere.bounds)
        assertEquals(0, everywhere.at(5, 0))
    }

    @Test
    fun aStrokeLandsOnlyInsideTheSelection() {
        val target = image(20, 20)
        val stroke = RasterPaintEngine.Stroke(20, 20)
        val region = requireNotNull(stroke.addSegment(2f, 10f, 18f, 10f, RasterPaintEngine.Tip(3f)))
        val mask = PaintSelection.rectangle(20, 20, 0f, 0f, 10f, 20f, antialias = false)
        stroke.land(target, 0xFF00FF00.toInt(), 1f, false, region, mask = mask)
        assertEquals(255, alpha(target.getRGB(5, 10)))
        assertEquals(0, alpha(target.getRGB(15, 10)))
    }

    @Test
    fun lockTransparencyColoursOnlyWhatIsPaintedAndKeepsItsAlpha() {
        val target = image(20, 20)
        for (x in 0 until 10) target.setRGB(x, 10, 0x80FF0000.toInt())
        val stroke = RasterPaintEngine.Stroke(20, 20)
        val region = requireNotNull(stroke.addSegment(2f, 10.5f, 18f, 10.5f, RasterPaintEngine.Tip(2f)))
        stroke.land(target, 0xFF0000FF.toInt(), 1f, false, region, lockAlpha = true)
        assertEquals(0x800000FF.toInt(), target.getRGB(5, 10))
        assertEquals(0, target.getRGB(15, 10))
    }

    @Test
    fun aBucketThatIsNotContiguousFillsEveryMatchingPixel() {
        val picture = image(9, 1)
        picture.setRGB(4, 0, 0xFFFFFFFF.toInt())
        val area = RasterPaintEngine.floodFill(picture, 0, 0, 0xFF00FF00.toInt(), 0, contiguous = false)
        assertEquals(Rectangle(0, 0, 9, 1), area)
        assertEquals(0xFF00FF00.toInt(), picture.getRGB(8, 0))
        assertEquals(0xFFFFFFFF.toInt(), picture.getRGB(4, 0))
        val joined = image(9, 1)
        joined.setRGB(4, 0, 0xFFFFFFFF.toInt())
        RasterPaintEngine.floodFill(joined, 0, 0, 0xFF00FF00.toInt(), 0)
        assertEquals(0, joined.getRGB(8, 0))
    }

    @Test
    fun aLinearGradientRunsFromItsFirstColourToItsSecond() {
        val picture = image(101, 1)
        RasterPaintEngine.gradient(picture, 0f, 0f, 101f, 0f, 0xFFFF0000.toInt(), 0xFF0000FF.toInt(), false, 1f, Rectangle(0, 0, 101, 1))
        assertTrue(((picture.getRGB(0, 0) ushr 16) and 0xFF) >= 250)
        assertTrue((picture.getRGB(100, 0) and 0xFF) >= 250)
        val middle = picture.getRGB(50, 0)
        assertTrue(((middle ushr 16) and 0xFF) in 100..155 && (middle and 0xFF) in 100..155, Integer.toHexString(middle))
        // To transparent: the far end leaves the pixels alone.
        val fade = image(10, 1)
        RasterPaintEngine.gradient(fade, 0f, 0f, 10f, 0f, 0xFF000000.toInt(), 0x00000000, true, 1f, Rectangle(0, 0, 10, 1))
        assertTrue(alpha(fade.getRGB(0, 0)) > 200)
        assertTrue(alpha(fade.getRGB(9, 0)) < 40)
    }

    @Test
    fun aBlurStrokeSoftensAnEdgeOnceHoweverItIsCutUp() {
        fun edge() = image(40, 20).also { for (y in 0 until 20) for (x in 0 until 20) it.setRGB(x, y, 0xFF000000.toInt()) }
        fun blur(target: BufferedImage, segments: List<Pair<Float, Float>>) {
            val stroke = RasterPaintEngine.Stroke(40, 20)
            val original = target.getRGB(0, 0, 40, 20, null, 0, 40)
            segments.zipWithNext().forEach { (a, b) -> stroke.addSegment(a.first, 10f, b.first, 10f, RasterPaintEngine.Tip(6f)) }
            stroke.landBlur(target, original, Rectangle(0, 0, 40, 20), requireNotNull(stroke.bounds), 1f, 2)
        }
        val once = edge().also { blur(it, listOf(14f to 0f, 26f to 0f)) }
        val chopped = edge().also { blur(it, listOf(14f to 0f, 18f to 0f, 16f to 0f, 22f to 0f, 26f to 0f)) }
        assertTrue(alpha(once.getRGB(20, 10)) in 30..225, "the edge is softened")
        assertEquals(0xFF000000.toInt(), once.getRGB(5, 10), "outside the tip nothing moves")
        // The same stroke drawn in pieces lands the same, give or take the rounding of the tip's edge.
        val a = once.getRGB(0, 0, 40, 20, null, 0, 40); val b = chopped.getRGB(0, 0, 40, 20, null, 0, 40)
        assertTrue(a.indices.all { kotlin.math.abs(alpha(a[it]) - alpha(b[it])) <= 2 })
    }

    @Test
    fun confiningAPatchTakesTheEditBackOutsideTheSelection() {
        val picture = image(64, 64)
        val patch = PaintPixelPatch()
        patch.capture(picture, Rectangle(0, 0, 64, 64))
        RasterPaintEngine.clear(picture)
        for (y in 0 until 64) for (x in 0 until 64) picture.setRGB(x, y, 0xFF00FF00.toInt())
        patch.confine(picture, PaintSelection.rectangle(64, 64, 0f, 0f, 32f, 64f, antialias = false), lockAlpha = false)
        assertEquals(0xFF00FF00.toInt(), picture.getRGB(10, 10))
        assertEquals(0, picture.getRGB(40, 10))
    }

    @Test
    fun mixingIsPremultipliedSoTransparencyDoesNotBleedColour() {
        assertEquals(0xFF0000FF.toInt(), mixArgb(0x00FF0000, 0xFF0000FF.toInt(), 128) or -0x1000000)
        assertEquals(0, mixArgb(0, 0, 100))
        assertEquals(0x12345678, mixArgb(0x12345678, 0x7FFFFFFF, 0))
    }

    @Test
    fun theStabilizerAveragesThePointerAndCatchesUpAtTheEnd() {
        val off = PaintStabilizer(0)
        assertEquals(3f to 4f, off.add(3f, 4f))
        assertTrue(off.finish().isEmpty())
        val steady = PaintStabilizer(3)
        steady.add(0f, 0f)
        assertEquals(2f to 0f, steady.add(4f, 0f))
        steady.add(8f, 0f)
        val tip = steady.add(12f, 0f)
        assertEquals(6f to 0f, tip)
        val tail = steady.finish()
        assertEquals(12f to 0f, tail.last())
    }

    @Test
    fun symmetryMirrorsAcrossTheCanvasMiddle() {
        assertEquals(listOf(10f to 20f), PaintSymmetry.OFF.images(10f, 20f, 50f, 50f))
        assertEquals(listOf(10f to 20f, 90f to 20f), PaintSymmetry.VERTICAL.images(10f, 20f, 50f, 50f))
        assertEquals(listOf(10f to 20f, 90f to 20f, 10f to 80f, 90f to 80f), PaintSymmetry.BOTH.images(10f, 20f, 50f, 50f))
    }

    @Test
    fun aShortStabilizedStrokeStillEndsWhereThePointerLeft() {
        val stabilizer = PaintStabilizer(5)
        stabilizer.add(0f, 0f)
        stabilizer.add(10f, 0f)
        assertEquals(10f to 0f, stabilizer.finish().last())
    }

    @Test
    fun aLongLassoThinsToTheLimitAndKeepsItsEnds() {
        val points = List(20_000) { i -> i * 0.01f to (i % 7).toFloat() }
        val thin = thinOutline(points, 4096, 0.5f)
        assertTrue(thin.size <= 4096)
        assertEquals(points.first(), thin.first())
        assertEquals(points.last(), thin.last())
        assertEquals(points.take(10), thinOutline(points.take(10), 4096, 0.5f))
        val dense = List(10_000) { i -> i.toFloat() to 0f }
        assertTrue(thinOutline(dense, 4096, 0.5f).size <= 4096)
    }
}
