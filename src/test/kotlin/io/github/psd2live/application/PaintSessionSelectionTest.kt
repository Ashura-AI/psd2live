package io.github.psd2live.application

import io.github.psd2live.core.RasterPaintEngine
import kotlinx.serialization.json.*
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaintSessionSelectionTest {
    private fun session(width: Int = 40, height: Int = 40) =
        WorkspacePaintSession("s", "p", "state", "layer", "Layer", BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), validateWorkspace = {})

    private fun point(x: Number, y: Number) = JsonArray(listOf(JsonPrimitive(x), JsonPrimitive(y)))
    private fun rgba(r: Int, g: Int, b: Int, a: Int = 255) = JsonArray(listOf(r, g, b, a).map(::JsonPrimitive))

    @Test
    fun aFillLandsOnlyInTheSelectionAndUndoKeepsTheSelection() {
        val session = session()
        session.select(buildJsonObject { put("mode", "rectangle"); put("from", point(0, 0)); put("to", point(20, 40)); put("antialias", false) })
        assertEquals(java.awt.Rectangle(0, 0, 20, 40), session.selectionBounds)
        session.gesture(buildJsonObject { put("mode", "fill"); put("color", rgba(255, 0, 0)) }, "fill")
        assertEquals(0xFFFF0000.toInt(), session.sample(5, 5))
        assertEquals(0, session.sample(30, 5))
        session.undo()
        assertEquals(0, session.sample(5, 5))
        assertEquals(java.awt.Rectangle(0, 0, 20, 40), session.selectionBounds, "the selection is not in the stroke history")
        session.select(buildJsonObject { put("mode", "none") })
        assertNull(session.selectionBounds)
    }

    @Test
    fun aSubtractedShapeLeavesAHoleThatALiveStrokeSkips() {
        val session = session()
        session.select(buildJsonObject { put("mode", "all") })
        session.select(buildJsonObject { put("mode", "ellipse"); put("op", "subtract"); put("from", point(10, 10)); put("to", point(30, 30)); put("antialias", false) })
        session.beginStroke()
        session.segment(0f, 20f, 40f, 20f, RasterPaintEngine.Tip(3f), 0xFF00FF00.toInt(), 1f, false)
        session.recordStroke("brush")
        assertEquals(0xFF00FF00.toInt(), session.sample(2, 20))
        assertEquals(0, session.sample(20, 20))
        assertTrue(session.snapshot().containsKey("selection"))
    }

    @Test
    fun theWandSelectsByColourAndClearRemovesOnlyThat() {
        val session = session(20, 1)
        session.gesture(buildJsonObject { put("mode", "fill"); put("color", rgba(0, 0, 255)) }, "fill")
        session.gesture(buildJsonObject { put("mode", "shape"); put("shape", "rectangle"); put("filled", true)
            put("from", point(10, 0)); put("to", point(20, 1)); put("color", rgba(255, 255, 255)) }, "box")
        session.select(buildJsonObject { put("mode", "wand"); put("point", point(15, 0)); put("tolerance", 0) })
        session.gesture(buildJsonObject { put("mode", "clear") }, "clear")
        assertEquals(0xFF0000FF.toInt(), session.sample(3, 0))
        assertEquals(0, session.sample(15, 0))
    }

    @Test
    fun lockAlphaKeepsAGradientOnThePaintedPixels() {
        val session = session(20, 1)
        session.gesture(buildJsonObject { put("mode", "shape"); put("shape", "rectangle"); put("filled", true)
            put("from", point(0, 0)); put("to", point(10, 1)); put("color", rgba(255, 0, 0)) }, "box")
        session.gesture(buildJsonObject { put("mode", "gradient"); put("from", point(0, 0)); put("to", point(20, 0))
            put("color", rgba(0, 0, 255)); put("end_color", rgba(0, 0, 255)); put("lock_alpha", true) }, "gradient")
        assertEquals(0xFF0000FF.toInt(), session.sample(5, 0))
        assertEquals(0, session.sample(15, 0))
    }

    @Test
    fun thePublicSchemasTakeTheNewGesturesAndSelections() {
        validateOperationSchema(buildJsonObject { put("mode", "blur"); put("points", JsonArray(listOf(point(1, 1)))); put("radius", 4) },
            WorkspacePaintSessionSchemas.gesture)
        validateOperationSchema(buildJsonObject { put("mode", "gradient"); put("from", point(0, 0)); put("to", point(1, 1)); put("kind", "radial") },
            WorkspacePaintSessionSchemas.gesture)
        validateOperationSchema(buildJsonObject { put("mode", "bucket"); put("point", point(0, 0)); put("contiguous", false) },
            WorkspacePaintSessionSchemas.gesture)
        validateOperationSchema(buildJsonObject { put("action", "select"); put("selection", buildJsonObject {
            put("mode", "lasso"); put("op", "add"); put("points", JsonArray(listOf(point(0, 0), point(4, 0), point(0, 4)))) }) },
            WorkspacePaintSessionSchemas.edit)
        assertFailsWith<Exception> {
            validateOperationSchema(buildJsonObject { put("mode", "lasso"); put("points", JsonArray(listOf(point(0, 0)))) },
                WorkspacePaintSessionSchemas.selection)
        }
    }

    @Test
    fun aFillWithoutASelectionCoversTheLayerNotTheCanvas() {
        val session = WorkspacePaintSession("s", "p", "state", "layer", "Layer", BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB),
            space = io.github.psd2live.core.PaintSpace.canvas(40, 40, java.awt.Rectangle(10, 10, 10, 10)), validateWorkspace = {})
        session.gesture(buildJsonObject { put("mode", "fill"); put("color", rgba(255, 0, 0)) }, "fill")
        assertEquals(0xFFFF0000.toInt(), session.sample(15, 15))
        assertEquals(0, session.sample(30, 30))
        assertEquals(0, session.sample(5, 15))
    }

    @Test
    fun lockAlphaIsRefusedWhereItWouldOnlyErase() {
        val session = session()
        assertFailsWith<IllegalArgumentException> {
            session.gesture(buildJsonObject { put("mode", "eraser"); put("points", JsonArray(listOf(point(1, 1), point(5, 5))))
                put("lock_alpha", true) }, "eraser")
        }
        assertFailsWith<Exception> { session.gesture(buildJsonObject { put("mode", "clear"); put("lock_alpha", true) }, "clear") }
    }

    @Test
    fun invertingNoSelectionLeavesNone() {
        val session = session()
        session.select(buildJsonObject { put("mode", "invert") })
        assertNull(session.selectionBounds)
    }
}
