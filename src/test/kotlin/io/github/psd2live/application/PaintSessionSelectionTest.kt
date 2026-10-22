package io.github.psd2live.application

import kotlinx.serialization.json.*
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
    fun quickSelectionOpaquePixelsAndExpandContractChangeTheSelection() {
        val session = session()
        // A red square on the left half, painted through the session so the selection sees it.
        session.select(buildJsonObject { put("mode", "rectangle"); put("from", point(0, 0)); put("to", point(20, 40)); put("antialias", false) })
        session.gesture(buildJsonObject { put("mode", "fill"); put("color", rgba(255, 0, 0)) }, "fill")
        session.select(buildJsonObject { put("mode", "none") })

        session.select(buildJsonObject { put("mode", "quick"); put("points", JsonArray(listOf(point(5, 5), point(10, 10))))
            put("radius", 2); put("tolerance", 16) })
        assertEquals(java.awt.Rectangle(0, 0, 20, 40), session.selectionBounds, "the red half, up to its edge")
        // A stroke over the empty half, added: the whole raster.
        session.select(buildJsonObject { put("mode", "quick"); put("op", "add"); put("points", JsonArray(listOf(point(30, 5))))
            put("radius", 2) })
        assertEquals(java.awt.Rectangle(0, 0, 40, 40), session.selectionBounds)

        session.select(buildJsonObject { put("mode", "opaque") })
        assertEquals(java.awt.Rectangle(0, 0, 20, 40), session.selectionBounds)
        session.select(buildJsonObject { put("mode", "contract"); put("radius", 4) })
        assertEquals(java.awt.Rectangle(0, 0, 16, 40), session.selectionBounds, "the raster's border is no edge")
        session.select(buildJsonObject { put("mode", "expand"); put("radius", 10) })
        assertEquals(java.awt.Rectangle(0, 0, 26, 40), session.selectionBounds)
        session.select(buildJsonObject { put("mode", "opaque"); put("op", "subtract") })
        assertEquals(java.awt.Rectangle(20, 0, 6, 40), session.selectionBounds)

        validateOperationSchema(buildJsonObject { put("action", "select"); put("selection", buildJsonObject {
            put("mode", "quick"); put("op", "subtract"); put("points", JsonArray(listOf(point(0, 0)))); put("radius", 3) }) },
            WorkspacePaintSessionSchemas.edit)
        assertFailsWith<Exception> {
            validateOperationSchema(buildJsonObject { put("mode", "expand") }, WorkspacePaintSessionSchemas.selection)
        }
    }

    @Test
    fun aFreeTransformLiftsMovesAndUndoesAsOneStroke() {
        val session = session()
        session.select(buildJsonObject { put("mode", "rectangle"); put("from", point(0, 0)); put("to", point(10, 10)); put("antialias", false) })
        session.gesture(buildJsonObject { put("mode", "fill"); put("color", rgba(255, 0, 0)) }, "fill")
        val strokes = session.strokes.size

        // Lifted: the pixels are off the raster while the box is in hand, and every other edit waits.
        val lift = requireNotNull(session.lift())
        assertEquals(java.awt.Rectangle(0, 0, 10, 10), lift.region)
        assertEquals(0, session.sample(5, 5))
        assertFailsWith<WorkspaceBusy> { session.gesture(buildJsonObject { put("mode", "clear") }, "clear") }
        // Cancelled: back as they were, nothing recorded.
        session.abandonStroke()
        assertEquals(0xFFFF0000.toInt(), session.sample(5, 5))
        assertEquals(strokes, session.strokes.size)

        session.lift()
        session.gesture(buildJsonObject { put("mode", "transform"); put("smooth", false)
            put("matrix", JsonArray(listOf(1, 0, 0, 1, 20, 5).map(::JsonPrimitive))) }, "transform")
        assertEquals(0, session.sample(5, 5))
        assertEquals(0xFFFF0000.toInt(), session.sample(25, 10))
        assertEquals(strokes + 1, session.strokes.size)
        assertEquals(java.awt.Rectangle(20, 5, 10, 10), session.selectionBounds, "the selection goes with its pixels")
        session.undo()
        assertEquals(0xFFFF0000.toInt(), session.sample(5, 5))
        assertEquals(0, session.sample(25, 10))
    }

    @Test
    fun aTransformWithoutASelectionTakesTheLayersContent() {
        val session = session()
        session.select(buildJsonObject { put("mode", "rectangle"); put("from", point(4, 4)); put("to", point(8, 8)); put("antialias", false) })
        session.gesture(buildJsonObject { put("mode", "fill"); put("color", rgba(0, 0, 255)) }, "fill")
        session.select(buildJsonObject { put("mode", "none") })
        assertEquals(java.awt.Rectangle(4, 4, 4, 4), session.lift()?.region)
        session.gesture(buildJsonObject { put("mode", "transform"); put("smooth", false)
            put("matrix", JsonArray(listOf(2, 0, 0, 2, 0, 0).map(::JsonPrimitive))) }, "transform")
        assertEquals(0xFF0000FF.toInt(), session.sample(15, 15))
        assertEquals(0, session.sample(5, 5))
        assertNull(session.selectionBounds)
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

}
