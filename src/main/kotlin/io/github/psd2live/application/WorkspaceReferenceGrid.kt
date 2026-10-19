package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.StableIds
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.umamo.runtime.eval.allAxes
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.PuppetModel

/**
 * A sheet of references laid out as a grid of keyforms - the character drawn at each head angle of a 2D pad, say -
 * cut into its cells and placed as reference layers, each pinned to its keyform ([WorkspaceLayout.referencePins]).
 * Columns run along the x parameter's values left to right, rows along the y parameter's values top to bottom, the
 * order `view_render_pad` lays its sheets out in, so a sheet rendered there and redrawn elsewhere comes back cell
 * for cell.
 */
internal object WorkspaceReferenceGrid {
    const val PLACE = "reference_place_grid"
    const val MAX_SIDE = 16

    /**
     * The keyform values of [parameter] in [puppet], ascending: its own stops when it has them, else every key an
     * object's keyforms put on it; just its default when nothing keys it.
     */
    fun keyValues(puppet: PuppetModel, parameter: Parameter): List<Float> {
        parameter.keys?.takeIf { it.isNotEmpty() }?.let { return it.distinct().sorted() }
        val id = parameter.id
        val keys = sortedSetOf<Float>()
        fun axes(axes: List<org.umamo.runtime.model.KeyformAxis>?) = axes.orEmpty().forEach { axis -> if (axis.parameterId == id) axis.keys.forEach { keys += it } }
        for (drawable in puppet.drawables) {
            axes(drawable.geometryGrid?.axes); axes(drawable.channelGrids.allAxes())
            drawable.blendShapes.forEach { if (it.parameterId == id) it.keys.forEach { key -> keys += key } }
        }
        for (deformer in puppet.deformers) {
            val (geometry, blends) = when (deformer) {
                is Deformer.Warp -> deformer.geometryGrid to deformer.blendShapes
                is Deformer.Rotation -> deformer.geometryGrid to deformer.blendShapes
            }
            axes(geometry?.axes); axes(deformer.channelGrids.allAxes())
            blends.forEach { if (it.parameterId == id) it.keys.forEach { key -> keys += key } }
        }
        for (part in puppet.parts) axes(part.channelGrids.allAxes())
        return keys.toList().ifEmpty { listOf(parameter.default) }
    }

    /**
     * The values of one axis of a grid of [count] cells: [given] as they are, else [parameter]'s keyform values
     * ([descending] for a vertical axis, whose top row is the highest value, as on a 2D pad).
     */
    fun axisValues(puppet: PuppetModel, parameter: Parameter, given: List<Float>?, count: Int?, descending: Boolean): List<Float> {
        val values = given ?: keyValues(puppet, parameter).let { if (descending) it.reversed() else it }
        require(values.isNotEmpty() && values.size <= MAX_SIDE) { "Give 1..$MAX_SIDE values of ${parameter.id.raw}" }
        require(count == null || values.size == count) {
            "${parameter.id.raw} has ${values.size} value(s) (${values.joinToString()}) for $count cell(s); give its values" }
        for (value in values) require(value.isFinite() && value in parameter.min..parameter.max) { "${parameter.id.raw} is ${parameter.min}..${parameter.max}, not $value" }
        return values
    }

    fun parameter(puppet: PuppetModel, id: String): Parameter =
        puppet.parameters.firstOrNull { it.id.raw == id } ?: throw IllegalArgumentException("Unknown parameter: $id")

    fun apply(document: WorkspaceDocument, model: RigPreviewModel, request: JsonObject): WorkspaceDocument {
        fun int(key: String) = request[key]?.jsonPrimitive?.int
        fun floats(key: String) = request[key]?.jsonArray?.map { it.jsonPrimitive.float }
        fun rect(key: String) = request[key]?.jsonObject?.let { value ->
            LayerCanvasRect(value.getValue("left").jsonPrimitive.float, value.getValue("top").jsonPrimitive.float,
                value.getValue("width").jsonPrimitive.float, value.getValue("height").jsonPrimitive.float)
        }
        val materialId = request.getValue("material_id").jsonPrimitive.content
        val library = document.layout.materials
        val item = library.item(materialId) ?: throw IllegalArgumentException("Material item not found: $materialId")
        val puppet = model.rig.puppet
        val xParameter = parameter(puppet, request.getValue("x_parameter").jsonPrimitive.content)
        val yParameter = request["y_parameter"]?.jsonPrimitive?.content?.let { parameter(puppet, it) }
        require(yParameter?.id != xParameter.id) { "x_parameter and y_parameter are two parameters" }
        val columns = int("columns"); val rows = int("rows")
        val xs = axisValues(puppet, xParameter, floats("x_values"), columns, descending = false)
        val ys = yParameter?.let { axisValues(puppet, it, floats("y_values"), rows, descending = true) } ?: run {
            require(rows == null || rows == 1) { "Several rows need y_parameter" }
            require(request["y_values"] == null) { "y_values needs y_parameter" }
            listOf(Float.NaN)
        }
        val area = rect("pixel_rect") ?: LayerCanvasRect(0f, 0f, item.raster.width.toFloat(), item.raster.height.toFloat())
        require(area.width > 0f && area.height > 0f && area.left >= 0f && area.top >= 0f &&
            area.right <= item.raster.width + 1e-3f && area.bottom <= item.raster.height + 1e-3f) {
            "pixel_rect must lie within the item's ${item.raster.width}x${item.raster.height} pixels" }
        val cellW = area.width / xs.size; val cellH = area.height / ys.size
        require(cellW >= 1f && cellH >= 1f) { "A cell of ${xs.size}x${ys.size} is under a pixel" }
        val width = document.source.widthPx.toFloat(); val height = document.source.heightPx.toFloat()
        val place = rect("rect") ?: run {
            val scale = minOf(width / cellW, height / cellH)
            LayerCanvasRect((width - cellW * scale) / 2f, (height - cellH * scale) / 2f, cellW * scale, cellH * scale)
        }
        require(place.width > 0f && place.height > 0f && listOf(place.left, place.top, place.width, place.height).all(Float::isFinite)) {
            "rect needs a finite positive size" }
        val prefix = request["id_prefix"]?.jsonPrimitive?.content ?: StableIds.stem("refgrid_", materialId, xParameter.id.raw, yParameter?.id?.raw, xs, ys)
        require(prefix.matches(Regex("[A-Za-z0-9_.:-]{1,48}"))) { "id_prefix is 1-48 of A-Z a-z 0-9 _ . : -" }
        var next = document
        val pins = LinkedHashMap<String, Map<String, Float>>()
        for ((row, y) in ys.withIndex()) for ((column, x) in xs.withIndex()) {
            val id = "${prefix}_${row}_$column"
            val pixels = LayerCanvasRect(area.left + column * cellW, area.top + row * cellH, cellW, cellH)
            val label = buildString {
                append(item.name).append(" ").append(xParameter.name).append(" ").append(format(x))
                if (yParameter != null) append(", ").append(yParameter.name).append(" ").append(format(y))
            }
            next = WorkspaceMaterialEdits.crop(next, materialId, pixels, null, label.take(WorkspaceMaterialEdits.MAX_NAME), id, item.folderId, true)
            next = WorkspaceMaterialEdits.place(next, model, id, place, null, id, reference = true)
            pins[id] = if (yParameter == null) mapOf(xParameter.id.raw to x) else mapOf(xParameter.id.raw to x, yParameter.id.raw to y)
        }
        return next.copy(layout = next.layout.copy(referencePins = next.layout.referencePins + pins))
    }

    private fun format(value: Float) = if (value == kotlin.math.round(value)) value.toInt().toString() else "%.2f".format(java.util.Locale.ROOT, value)

    val schema: JsonObject by lazy {
        fun number() = buildJsonObject { put("type", "number") }
        fun numbers() = buildJsonObject { put("type", "array"); put("items", number()); put("minItems", 1); put("maxItems", MAX_SIDE) }
        fun string() = buildJsonObject { put("type", "string"); put("minLength", 1) }
        fun side() = buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", MAX_SIDE) }
        val rect = WorkspaceLayoutOperations.schema(listOf("left", "top", "width", "height").associateWith { number() }, listOf("left", "top", "width", "height"))
        WorkspaceLayoutOperations.schema(mapOf("state" to string(), "material_id" to string(), "x_parameter" to string(), "y_parameter" to string(),
            "x_values" to numbers(), "y_values" to numbers(), "columns" to side(), "rows" to side(), "pixel_rect" to rect, "rect" to rect,
            "id_prefix" to string()), listOf("state", "material_id", "x_parameter"))
    }

    const val DESCRIPTION = "Cut a sheet of references drawn at a grid of keyforms into its cells and place each as a reference layer pinned to its keyform " +
        "(layer_pin_reference), all at the same canvas rect: material_id is the sheet (material_import it first); columns run along x_parameter's x_values " +
        "left to right, rows along y_parameter's y_values top to bottom - view_render_pad's order. Values default to the parameter's keyform values " +
        "(y highest first, as on a 2D pad) and must then number columns/rows; without y_parameter the sheet is one row. pixel_rect is the grid's area in " +
        "the sheet's pixels (default all of it), split evenly. rect is where every cell sits on the canvas (default each cell fitted into the canvas and " +
        "centred); a cell's material item keeps the cut. Layer and material IDs are <id_prefix>_<row>_<column> (a stable prefix when omitted). " +
        "Each reference then shows on the edit canvas, view_render_model and view_render_pad only at its keyform. One history edit."
}
