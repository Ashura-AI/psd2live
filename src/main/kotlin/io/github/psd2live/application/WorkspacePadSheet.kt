package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The model over a grid of keyforms in one labelled sheet ([WorkspacePadViewRequest]): columns along the x
 * parameter's values left to right, rows along the y parameter's values top to bottom - a 2D pad's keyforms with the
 * highest y on top by default - each cell the same canvas rectangle at its keyform. A cell shows the model, the
 * reference layers shown there ([ReferencePins]), both, their difference or the two side by side, so references
 * pinned per angle are checked against the model at every angle at once. `reference_place_grid` reads sheets in the
 * same order.
 */
internal object WorkspacePadSheet {
    const val MAX_CELLS = 64
    private const val HEADER = 22
    private const val GUTTER = 2

    fun render(model: RigPreviewModel, document: WorkspaceDocument, revision: String, request: WorkspacePadViewRequest,
               visibleLayerIds: Set<String>?, checkCancelled: () -> Unit): WorkspaceWorkflowResult {
        val puppet = model.rig.puppet
        val x = WorkspaceReferenceGrid.parameter(puppet, request.xParameter)
        // A parameter of a 2D pad brings its partner along unless the request names the y axis.
        val y = (request.yParameter ?: puppet.parameterLinks.firstOrNull { it.horizontal == x.id }?.vertical?.raw)
            ?.let { WorkspaceReferenceGrid.parameter(puppet, it) }
        require(y?.id != x.id) { "x_parameter and y_parameter are two parameters" }
        require(y != null || request.yValues == null) { "y_values needs y_parameter" }
        val allX = WorkspaceReferenceGrid.axisValues(puppet, x, request.xValues, null, descending = false)
        val allY = y?.let { WorkspaceReferenceGrid.axisValues(puppet, it, request.yValues, null, descending = true) } ?: listOf(Float.NaN)
        val columns = request.columns ?: allX.indices
        val rows = request.rows ?: allY.indices
        require(!columns.isEmpty() && columns.first >= 0 && columns.last < allX.size) { "columns is [from, to] within 0..${allX.size - 1}" }
        require(!rows.isEmpty() && rows.first >= 0 && rows.last < allY.size) { "rows is [from, to] within 0..${allY.size - 1}" }
        require(columns.count() * rows.count() <= MAX_CELLS) { "A sheet holds at most $MAX_CELLS cells; cut it with columns and rows" }
        require(request.referenceOpacity in 0f..1f) { "reference_opacity is 0..1" }
        require(request.output.targetLongEdge in 256..4096 && request.output.maxBytes in 65536..16777216) { "Invalid sheet output budget" }
        for ((id, value) in request.parameters) {
            val parameter = WorkspaceReferenceGrid.parameter(puppet, id)
            require(value.isFinite() && value in parameter.min..parameter.max) { "$id is ${parameter.min}..${parameter.max}, not $value" }
        }

        val included = request.includeLayerIds ?: visibleLayerIds ?: document.renderVisibleLayers(model)
        // Reference layers, as quads or - generated - as meshes of the model.
        val references = included.filterTo(LinkedHashSet()) { it in document.layout.referenceLayerIds || it in model.referenceLayerIds }
        val modelLayers = included - references
        val show = request.show
        val side = show == WorkspacePadShow.SIDE_BY_SIDE
        val rect = request.frame.rect
        val aspect = rect.width.toDouble() / rect.height
        require(aspect.isFinite() && aspect > 0) { "Invalid pad camera" }

        // Tiles share the sheet's long edge after the headers.
        val across = columns.count() * (if (side) 2 else 1)
        val down = rows.count()
        // The row heads are as wide as the y parameter's name, which heads them in the corner.
        val left = if (y == null) 0 else BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().let { probe ->
            try { probe.font = Font(Font.SANS_SERIF, Font.BOLD, 12); (probe.fontMetrics.stringWidth(y.id.raw) + 12).coerceIn(56, 180) } finally { probe.dispose() }
        }
        val budget = request.output.targetLongEdge
        val cellWidth = floor(minOf((budget - left - GUTTER * across).toDouble() / across, (budget - HEADER - (HEADER + GUTTER) * down).toDouble() / down * aspect)).toInt()
        val cellHeight = floor(cellWidth / aspect).toInt()
        require(cellWidth >= 48 && cellHeight >= 32) { "Cells would be under 48 px; cut the sheet with columns/rows or raise target_long_edge" }
        val tileOutput = WorkspaceViewOutputSpec(maxOf(128, maxOf(cellWidth, cellHeight)), 16 * 1024 * 1024)

        val defaults = puppet.parameters.associate { it.id.raw to it.default }
        val pins = document.layout.referencePins
        fun view(applied: Map<String, Float>, layers: Set<String>, hidden: Set<String>): BufferedImage {
            checkCancelled()
            val rendered = WorkspaceViewRenderer.modelComposite(model, revision, applied, layers, emptySet(), request.frame,
                WorkspaceViewBackground.TRANSPARENT, tileOutput, hiddenReferenceLayerIds = hidden)
            return requireNotNull(ImageIO.read(rendered.png.inputStream())) { "Invalid cell PNG" }
        }

        class Cell(val column: Int, val row: Int, val values: Map<String, Float>, val images: List<BufferedImage>, val shown: List<String>, val difference: Float?)
        val cells = ArrayList<Cell>()
        for (row in rows) for (column in columns) {
            val values = LinkedHashMap<String, Float>().apply {
                put(x.id.raw, allX[column]); if (y != null) put(y.id.raw, allY[row])
            }
            val applied = defaults + request.parameters + values
            val hidden = ReferencePins.hidden(pins, puppet.parameters, applied::get)
            val shown = references.filter { it !in hidden }
            val model = if (show != WorkspacePadShow.REFERENCE) view(applied, modelLayers, hidden) else null
            val reference = if (show != WorkspacePadShow.MODEL) view(applied, references, hidden) else null
            var difference: Float? = null
            val images = when (show) {
                WorkspacePadShow.MODEL -> listOf(model!!)
                WorkspacePadShow.REFERENCE -> listOf(reference!!)
                WorkspacePadShow.SIDE_BY_SIDE -> listOf(model!!, reference!!)
                WorkspacePadShow.DIFFERENCE -> listOf(BufferedImage(model!!.width, model.height, BufferedImage.TYPE_INT_ARGB).also { out ->
                    difference = WorkspaceViewRenderer.paintDifference(out, model, reference!!)
                })
                WorkspacePadShow.UNDER, WorkspacePadShow.OVER -> listOf(BufferedImage(model!!.width, model.height, BufferedImage.TYPE_INT_ARGB).also { out ->
                    val g = out.createGraphics()
                    try {
                        if (show == WorkspacePadShow.UNDER) { g.drawImage(reference, 0, 0, null); g.drawImage(model, 0, 0, null) }
                        else {
                            g.drawImage(model, 0, 0, null)
                            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, request.referenceOpacity)
                            g.drawImage(reference, 0, 0, null)
                        }
                    } finally { g.dispose() }
                })
            }
            cells += Cell(column, row, values, images, shown, difference)
        }

        val panels = if (side) 2 else 1
        val tileWidth = cellWidth * panels + (panels - 1) * GUTTER
        val width = left + columns.count() * (tileWidth + GUTTER)
        val height = HEADER + rows.count() * (cellHeight + HEADER + GUTTER)
        val sheet = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val rects = HashMap<Pair<Int, Int>, List<IntArray>>()
        val g = sheet.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = Color(32, 35, 42); g.fillRect(0, 0, width, height)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
            g.color = Color.WHITE
            // The corner names the axes; each column head is its x value, each row head its y value.
            if (y != null) {
                g.drawString(fit(g, y.id.raw, left - 6), 4, 15)
                g.color = Color(200, 204, 214); g.drawString(fit(g, x.id.raw + " →", left - 6), 4, HEADER + 15); g.color = Color.WHITE
            }
            for ((i, column) in columns.withIndex()) {
                val cx = left + i * (tileWidth + GUTTER)
                val text = (if (y == null) "${x.id.raw} " else "") + format(allX[column]) + if (side) "  model | reference" else ""
                g.drawString(fit(g, text, tileWidth - 8), cx + 6, 15)
            }
            for (cell in cells) {
                val cx = left + columns.indexOf(cell.column) * (tileWidth + GUTTER)
                val cy = HEADER + rows.indexOf(cell.row) * (cellHeight + HEADER + GUTTER)
                if (y != null && cx == left) { g.color = Color.WHITE; g.drawString(fit(g, format(allY[cell.row]), left - 8), 6, cy + HEADER + cellHeight / 2 + 5) }
                // Under each tile: the references it shows, or the difference.
                g.color = Color(200, 204, 214)
                g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
                val note = cell.difference?.let { "diff ${"%.3f".format(java.util.Locale.ROOT, it)}" }
                    ?: if (show == WorkspacePadShow.MODEL) "" else if (cell.shown.isEmpty()) "no reference" else cell.shown.joinToString(", ")
                g.drawString(fit(g, note, tileWidth - 8), cx + 6, cy + 15)
                g.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
                val tiles = cell.images.mapIndexed { panel, image ->
                    val px = cx + panel * (cellWidth + GUTTER); val py = cy + HEADER
                    if (request.background == WorkspaceViewBackground.CHECKERBOARD) g.drawImage(WorkspaceViewRenderer.checkerboard(cellWidth, cellHeight), px, py, null)
                    else { g.color = Color(0, 0, 0, 0); g.composite = AlphaComposite.Src; g.fillRect(px, py, cellWidth, cellHeight); g.composite = AlphaComposite.SrcOver }
                    g.drawImage(image, px, py, cellWidth, cellHeight, null)
                    intArrayOf(px, py, cellWidth, cellHeight)
                }
                rects[cell.column to cell.row] = tiles
            }
        } finally { g.dispose() }
        val (encoded, png) = WorkspaceViewRenderer.encodeWithinBudget(sheet, request.output.maxBytes)
        val scale = encoded.width.toDouble() / sheet.width
        fun scaled(r: IntArray) = JsonArray(r.map { JsonPrimitive((it * scale).roundToInt()) })
        val metadata = buildJsonObject {
            put("revisionId", revision)
            put("comparison", "pad")
            put("show", show.name.lowercase())
            put("width", encoded.width); put("height", encoded.height)
            putJsonArray("canvasRect") { listOf(rect.left, rect.top, rect.right, rect.bottom).forEach { add(JsonPrimitive(it)) } }
            put("xParameter", x.id.raw)
            putJsonArray("xValues") { allX.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("columns") { add(JsonPrimitive(columns.first)); add(JsonPrimitive(columns.last)) }
            if (y != null) {
                put("yParameter", y.id.raw)
                putJsonArray("yValues") { allY.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("rows") { add(JsonPrimitive(rows.first)); add(JsonPrimitive(rows.last)) }
            }
            putJsonObject("commonParameters") { request.parameters.toSortedMap().forEach { (id, value) -> put(id, value) } }
            putJsonArray("cells") {
                for (cell in cells) addJsonObject {
                    put("column", cell.column); if (y != null) put("row", cell.row)
                    putJsonObject("parameters") { cell.values.forEach { (id, value) -> put(id, value) } }
                    val tiles = rects.getValue(cell.column to cell.row)
                    put("imageRect", scaled(tiles[0]))
                    if (tiles.size > 1) put("referenceImageRect", scaled(tiles[1]))
                    if (show != WorkspacePadShow.MODEL) putJsonArray("referenceLayerIds") { cell.shown.forEach { add(JsonPrimitive(it)) } }
                    cell.difference?.let { put("difference", it) }
                }
            }
        }
        return WorkspaceWorkflowResult(metadata, listOf(png))
    }

    private fun format(value: Float) = if (value == kotlin.math.round(value)) value.toInt().toString() else "%.2f".format(java.util.Locale.ROOT, value)

    /** [text] cut with an ellipsis to [width] pixels of [g]'s font. */
    private fun fit(g: java.awt.Graphics2D, text: String, width: Int): String {
        val metrics = g.fontMetrics
        if (metrics.stringWidth(text) <= width) return text
        var end = text.length
        while (end > 0 && metrics.stringWidth(text.take(end) + "…") > width) end--
        return text.take(end) + "…"
    }

    val resultSchema: JsonObject by lazy {
        val s = WorkspaceResultSchema
        val values = s.dictionary(s.number())
        val cellFields = linkedMapOf("column" to s.integer(0), "row" to s.integer(0), "parameters" to values, "imageRect" to s.vector(4),
            "referenceImageRect" to s.vector(4), "referenceLayerIds" to s.array(s.handle()), "difference" to s.number())
        val cell = s.obj(cellFields, setOf("column", "parameters", "imageRect"))
        val fields = linkedMapOf("revisionId" to s.handle(), "comparison" to s.constant("pad"),
            "show" to s.choices(*WorkspacePadShow.entries.map { it.name.lowercase() }.toTypedArray()),
            "width" to s.integer(1), "height" to s.integer(1), "canvasRect" to s.vector(4),
            "xParameter" to s.handle(), "xValues" to s.array(s.number()), "columns" to s.vector(2),
            "yParameter" to s.handle(), "yValues" to s.array(s.number()), "rows" to s.vector(2),
            "commonParameters" to values, "cells" to s.array(cell, 1, MAX_CELLS))
        s.obj(fields, fields.keys - setOf("yParameter", "yValues", "rows"))
    }
}
