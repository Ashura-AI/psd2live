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
    fun aFillWithoutASelectionCoversTheLayerNotTheCanvas() {
        val session = WorkspacePaintSession("s", "p", "state", "layer", "Layer", BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB),
            space = io.github.psd2live.core.PaintSpace.canvas(40, 40, java.awt.Rectangle(10, 10, 10, 10)), validateWorkspace = {})
        session.gesture(buildJsonObject { put("mode", "fill"); put("color", rgba(255, 0, 0)) }, "fill")
        assertEquals(0xFFFF0000.toInt(), session.sample(15, 15))
        assertEquals(0, session.sample(30, 30))
        assertEquals(0, session.sample(5, 15))
    }

}
