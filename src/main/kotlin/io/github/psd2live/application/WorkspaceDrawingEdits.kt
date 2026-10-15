package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Drawing parts as vectors on the canvas ([WorkspaceVectorDrawing]): a drawing is a vector material whose view box is
 * its place, rendered at a texture density, and the drawing kit its parts share. Redrawing a part carries the layers
 * showing it along, so a part can be drawn, placed, looked at and drawn again.
 */
internal object WorkspaceDrawingEdits {
    const val DRAW = "material_draw"
    const val KIT = "layout_set_drawing_kit"
    val supported = setOf(DRAW, KIT)
    const val MIN_DENSITY = 0.05f
    const val MAX_DENSITY = 16f
    private val ID = Regex("[A-Za-z0-9_.:-]{1,64}")

    fun apply(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, model: RigPreviewModel, work: WorkspaceRasterWork): WorkspaceDocument =
        when (operation.operation) {
            DRAW -> draw(document, model, operation.request)
            KIT -> kit(document, model, operation.request, work)
            else -> throw IllegalArgumentException("Not a drawing edit: ${operation.operation}")
        }

    /** A material's texture pixels per canvas unit, from its raster over its place. */
    fun density(item: MaterialItem): Float? = item.placement?.let { place -> (item.raster.width / place.width + item.raster.height / place.height) / 2f }

    /**
     * Vector item `id` drawn from `svg` (a new item when the id is free), rendered at `density` texture pixels per canvas
     * unit - the item's current density, else 1, when omitted. The view box is its place on the canvas.
     */
    fun draw(document: WorkspaceDocument, model: RigPreviewModel, request: JsonObject): WorkspaceDocument {
        val id = request.getValue("id").jsonPrimitive.content
        val library = document.layout.materials
        val existing = library.item(id)
        if (existing == null) {
            require(id.matches(ID)) { "A material id is 1-64 letters, digits, _ . : -: $id" }
            require(library.folder(id) == null) { "$id names a material folder" }
        } else require(existing.kind == MaterialKind.VECTOR) { "$id is a bitmap material; draw under another id" }
        val edits = request["edits"]?.jsonArray?.map { edit ->
            val value = edit.jsonObject
            value.getValue("id").jsonPrimitive.content to value.getValue("set").jsonObject.mapValues { (_, v) -> if (v is JsonNull) null else v.jsonPrimitive.content }
        }.orEmpty()
        val drawn = request["svg"]?.jsonPrimitive?.content ?: existing?.svg ?: throw IllegalArgumentException("Give svg to draw a new material")
        val svg = WorkspaceVectorDrawing.normalized(if (edits.isEmpty()) drawn else WorkspaceVectorDrawing.patch(drawn, edits))
        val box = WorkspaceVectorDrawing.viewBox(svg)
        val density = request["density"]?.jsonPrimitive?.float ?: existing?.let(::density) ?: 1f
        require(density.isFinite() && density in MIN_DENSITY..MAX_DENSITY) { "density is $MIN_DENSITY..$MAX_DENSITY texture pixels per canvas unit" }
        val width = (box.width * density).roundToInt().coerceAtLeast(1)
        val height = (box.height * density).roundToInt().coerceAtLeast(1)
        val max = WorkspaceMaterialFiles.MAX_VECTOR_SIDE
        require(width <= max && height <= max) { "At density $density the drawing is $width x $height pixels, over $max a side; lower the density or draw the part in pieces" }
        val folderId = if ("folder_id" in request) request["folder_id"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content else existing?.folderId
        folderId?.let { requireNotNull(library.folder(it)) { "Material folder not found: $it" } }
        val name = (request["name"]?.jsonPrimitive?.content ?: existing?.name ?: id).trim()
        require(name.isNotEmpty() && name.length <= 128 && name.none(Char::isISOControl)) { "A name must be 1-128 printable characters" }
        val raster = existing?.takeIf { it.svg == svg && it.raster.width == width && it.raster.height == height }?.raster
            ?: WorkspaceVectorDrawing.render(svg, document.layout.drawingKit, width, height)
        val item = MaterialItem(id, name, folderId, MaterialKind.VECTOR, raster, box, svg, existing?.tags.orEmpty(),
            existing?.origin ?: "drawing", existing?.opacity ?: 1f, existing?.visible ?: true, document.source.widthPx, document.source.heightPx)
        if (existing == item) return document
        val items = if (existing == null) library.items + item else library.items.map { if (it.id == id) item else it }
        val next = document.copy(layout = document.layout.copy(materials = library.copy(items = items)))
        return if (existing == null) next else follow(next, model, existing, item)
    }

    /**
     * Layers showing [before] take [after]'s pixels; those over [before]'s place move to [after]'s, so a part redrawn
     * larger (more hidden area, say) stays where it is drawn.
     */
    fun follow(document: WorkspaceDocument, model: RigPreviewModel, before: MaterialItem, after: MaterialItem): WorkspaceDocument {
        var next = document
        for (layerId in WorkspaceMaterialEdits.references(document, setOf(before.id))[before.id].orEmpty()) {
            val shown = next.source.layers.firstOrNull { it.id.raw == layerId }?.displayedRect()
            if (after.raster !== before.raster) next = WorkspaceDocumentEdits.replaceImage(next, model, layerId, after.raster)
            val from = before.placement; val to = after.placement
            if (shown != null && from != null && to != null && from != to && same(shown, from))
                next = WorkspaceTextureEdits.apply(next, model, WorkspaceTextureEdit.SetCanvasRect(layerId, to))
        }
        return next
    }

    private fun same(a: LayerCanvasRect, b: LayerCanvasRect): Boolean {
        val tolerance = 1e-3f * maxOf(1f, b.width, b.height)
        return abs(a.left - b.left) <= tolerance && abs(a.top - b.top) <= tolerance && abs(a.width - b.width) <= tolerance && abs(a.height - b.height) <= tolerance
    }

    /**
     * The drawing kit changed: `colors` merges (null removes a colour), `styles` and `defs` replace. Every vector
     * material is drawn again with it, at its resolution, and those that change carry their layers along. Refused when
     * a drawing would be left with a colour the kit no longer has.
     */
    fun kit(document: WorkspaceDocument, model: RigPreviewModel, request: JsonObject, work: WorkspaceRasterWork): WorkspaceDocument {
        val current = document.layout.drawingKit ?: DrawingKit()
        val colors = LinkedHashMap(current.colors)
        request["colors"]?.jsonObject?.forEach { (name, value) ->
            if (value is JsonNull) colors.remove(name) else colors[name] = value.jsonPrimitive.content
        }
        val kit = DrawingKit(colors, request["styles"]?.jsonPrimitive?.content ?: current.styles, request["defs"]?.jsonPrimitive?.content ?: current.defs)
            .takeUnless { it.isEmpty }
        if (kit == document.layout.drawingKit) return document
        var next = document.copy(layout = document.layout.copy(drawingKit = kit))
        val drawings = document.layout.materials.items.filter { it.kind == MaterialKind.VECTOR }
        val failures = ArrayList<String>()
        drawings.forEachIndexed { index, item ->
            work.checkpoint()
            work.progress(index.toFloat() / drawings.size.coerceAtLeast(1), "Drawing ${item.name} again")
            val raster = try {
                WorkspaceVectorDrawing.render(requireNotNull(item.svg), kit, item.raster.width, item.raster.height)
            } catch (e: IllegalArgumentException) { failures += "${item.id}: ${e.message}"; return@forEachIndexed }
            if (raster.rgba.contentEquals(item.raster.rgba)) return@forEachIndexed
            val redrawn = item.copy(raster = raster)
            val library = next.layout.materials
            next = next.copy(layout = next.layout.copy(materials = library.copy(items = library.items.map { if (it.id == item.id) redrawn else it })))
            next = follow(next, model, item, redrawn)
        }
        require(failures.isEmpty()) { "The kit would leave drawings unresolved - ${failures.joinToString("; ")}" }
        return next
    }
}
