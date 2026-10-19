package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.WorkspaceDocument
import kotlinx.serialization.json.*

/** The ruler, inspector and public requests resolve the same explicit layer-order override. */
internal object WorkspaceDrawOrderEdits {
    const val OP = "layer_draw_order"

    fun request(target: String, order: Float?) = buildJsonObject {
        put("target", target); put("order", order?.let(::JsonPrimitive) ?: JsonNull)
    }

    fun apply(document: WorkspaceDocument, model: RigPreviewModel, request: JsonObject): WorkspaceDocument {
        validateOperationSchema(request, WorkspaceDrawOrderSchemas.request)
        val raw = request.getValue("target").jsonPrimitive.content.removePrefix("mesh:").removePrefix("layer:")
        val drawable = model.rig.puppet.drawables.singleOrNull { it.id.raw == raw }
            ?: model.rig.puppet.drawables.singleOrNull { model.rig.layerIdByDrawableId[it.id.raw] == raw }
            ?: throw IllegalArgumentException("Draw-order target not found: $raw")
        val layer = model.rig.layerIdByDrawableId[drawable.id.raw] ?: drawable.id.raw
        val before = document.settings["drawOrderOverrides"]?.jsonObject?.mapValues { it.value.jsonPrimitive.float }
            ?: model.config.drawOrderOverrides
        val value = request.getValue("order")
        val after = if (value == JsonNull) before - layer - drawable.id.raw else {
            val order = value.jsonPrimitive.float
            require(order.isFinite() && order in 0f..1000f) { "Draw order must be within 0..1000" }
            require(order % 1f == 0f) { WorkspaceDrawOrderSchemas.WHOLE }
            before - drawable.id.raw + (layer to order)
        }
        if (before == after) return document
        return document.withOverrides(after)
    }

    private fun WorkspaceDocument.withOverrides(overrides: Map<String, Float>) = copy(settings = JsonObject(settings +
        ("drawOrderOverrides" to JsonObject(overrides.toSortedMap().mapValues { JsonPrimitive(it.value) }))))

    /**
     * [document] with [layerIds] drawn just in front of layer or mesh [anchor] ([inFront]) or just behind it, as [model] draws:
     * whole orders spread through the gap to the next mesh, or the anchor's own order when there is none.
     */
    fun beside(document: WorkspaceDocument, model: RigPreviewModel, anchor: String, layerIds: List<String>, inFront: Boolean): WorkspaceDocument {
        if (layerIds.isEmpty()) return document
        val drawables = model.rig.puppet.drawables
        val target = drawables.firstOrNull { model.rig.layerIdByDrawableId[it.id.raw] == anchor } ?: drawables.firstOrNull { it.id.raw == anchor }
            ?: return document
        val at = target.drawOrder
        val others = drawables.filter { it !== target && model.rig.layerIdByDrawableId[it.id.raw] !in layerIds }.map { it.drawOrder }
        val bound = if (inFront) others.filter { it > at }.minOrNull() ?: 1001f else others.filter { it < at }.maxOrNull() ?: -1f
        val step = (bound - at) / (layerIds.size + 1)
        // The first image of the batch draws in front of the rest.
        val placed = layerIds.withIndex().associate { (index, id) ->
            val slot = if (inFront) layerIds.size - index else index + 1
            id to kotlin.math.round(at + step * slot).coerceIn(0f, 1000f)
        }
        val before = document.settings["drawOrderOverrides"]?.jsonObject?.mapValues { it.value.jsonPrimitive.float }
            ?: model.config.drawOrderOverrides
        return document.withOverrides(before + placed)
    }
}

internal object WorkspaceDrawOrderSchemas {
    /** Why a fractional order is refused, and what to do instead; the tools' descriptions say the same. */
    const val WHOLE = "Draw order must be a whole number: Cubism stores integers. Pick a free number between the two " +
        "neighbours (generated layers are 10 apart), or move a neighbour to open a gap."

    /** The draw-order paragraph every tool that writes one carries. */
    const val GUIDE = "Draw orders are whole numbers 0..1000 (Cubism stores integers; fractions are refused); a higher " +
        "number draws in front. Generated layers are 10 apart (closer only when there are more than 100), so a mesh " +
        "goes between two neighbours at a free number in the gap, e.g. 385 between 380 and 390. When the neighbours " +
        "are consecutive, move one of them to open a gap rather than renumbering the rest; read the current orders " +
        "with workspace_inspect first."

    val request = WorkspaceResultSchema.obj(mapOf(
        "target" to WorkspaceResultSchema.handle(),
        "order" to buildJsonObject { put("type", JsonArray(listOf(JsonPrimitive("integer"), JsonPrimitive("null")))); put("minimum", 0); put("maximum", 1000) },
    ))
    val result = WorkspaceResultSchema.obj(WorkspaceResultSchema.identity + mapOf(
        "applied" to WorkspaceResultSchema.constant(false), "changed" to WorkspaceResultSchema.array(WorkspaceResultSchema.handle(), 1, Int.MAX_VALUE)),
        WorkspaceResultSchema.identity.keys)
}

internal fun registerDrawOrderOperations(registry: WorkspaceOperationRegistry, port: WorkspaceDocumentPort) {
    val business = WorkspaceDrawOrderSchemas.request
    val schema = JsonObject(business + mapOf(
        "properties" to JsonObject(business.getValue("properties").jsonObject + ("state" to WorkspaceResultSchema.handle())),
        "required" to JsonArray(business.getValue("required").jsonArray + JsonPrimitive("state"))))
    registry.register(WorkspaceOperationDefinition(WorkspaceDrawOrderEdits.OP,
        "Set the ruler's explicit drawing order for a current layer or mesh target. This order wins over animated order until reset. Use order=null to remove the layer and mesh overrides and restore the underlying generated or authored order, including its animation. Ordinary, imported and partitioned artwork use the same rule; the underlying ordered edits remain intact. " + WorkspaceDrawOrderSchemas.GUIDE,
        schema, WorkspaceOperationKind.DOCUMENT, batchable = true, resultSchema = WorkspaceDrawOrderSchemas.result)) { request, context ->
        val edit = WorkspaceDocumentOperation(WorkspaceDrawOrderEdits.OP, JsonObject(request - "state"))
        WorkspaceOperationOutput(port.applyDocumentEdits(request.getValue("state").jsonPrimitive.content,
            "Updated drawing order", listOf(edit), context.author).compact())
    }
}
