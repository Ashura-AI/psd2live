package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaintTransformBoxTest {
    /** A 100 x 50 box at (10, 20). */
    private fun box() = PaintTransformBox(Offset(10f, 20f), Offset(100f, 0f), Offset(0f, 50f))

    private fun assertNear(expected: Offset, actual: Offset) =
        assertTrue((expected - actual).getDistance() < 1e-3f, "expected $expected, was $actual")

    @Test
    fun pressesTakeTheHandleTheInsideOrTheOutside() {
        val box = box()
        assertEquals(PaintTransformBox.Handle.TOP_LEFT, box.hit(Offset(12f, 22f), 4f))
        assertEquals(PaintTransformBox.Handle.RIGHT, box.hit(Offset(110f, 45f), 4f))
        assertEquals(PaintTransformBox.Handle.MOVE, box.hit(Offset(50f, 40f), 4f))
        assertEquals(PaintTransformBox.Handle.ROTATE, box.hit(Offset(200f, 200f), 4f))
    }

    @Test
    fun aCornerKeepsTheProportionsUnlessShiftFreesThem() {
        val box = box()
        val start = box.transform
        // The bottom-right corner dragged 100 right and 10 down: twice as wide, so twice as tall too.
        box.transform = box.dragged(PaintTransformBox.Handle.BOTTOM_RIGHT, start, Offset(110f, 70f), Offset(210f, 80f))
        assertNear(Offset(10f, 20f), box.corners[0])
        assertNear(Offset(210f, 120f), box.corners[2])
        box.transform = box.dragged(PaintTransformBox.Handle.BOTTOM_RIGHT, start, Offset(110f, 70f), Offset(210f, 80f), free = true)
        assertNear(Offset(210f, 80f), box.corners[2])
    }

    @Test
    fun anEdgeScalesOneWayAndAltScalesAboutTheMiddle() {
        val box = box()
        box.transform = box.dragged(PaintTransformBox.Handle.RIGHT, box.transform, Offset(110f, 45f), Offset(130f, 60f), fromCenter = true)
        assertNear(Offset(-10f, 20f), box.corners[0])
        assertNear(Offset(130f, 70f), box.corners[2])
    }

    @Test
    fun theInsideMovesAndTheOutsideTurnsInFifteenDegreeStepsWithShift() {
        val box = box()
        box.transform = box.dragged(PaintTransformBox.Handle.MOVE, box.transform, Offset(50f, 40f), Offset(60f, 45f))
        assertNear(Offset(20f, 25f), box.corners[0])
        val center = box.center
        // A drag of a little more than a quarter turn about the centre, snapped to exactly a quarter.
        val turned = box.dragged(PaintTransformBox.Handle.ROTATE, box.transform, center + Offset(100f, 0f),
            center + Offset(-5f, 100f), snap = true)
        box.transform = turned
        assertNear(center, box.center)
        // The top-left corner, 50 left and 25 up of the centre, turns a quarter to 25 right and 50 up of it.
        assertNear(center + Offset(25f, -50f), box.corners[0])
    }
}
