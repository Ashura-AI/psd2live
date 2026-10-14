package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*

/**
 * A layer's semantic tags and variant as pure candidate edits ([LayerTags]). The tags the generators read become the
 * layer's classification, exactly as `layer_classify` writes it; the user's own words go to the document's Layout state
 * and never reach the generators.
 */
internal object WorkspaceLayoutEdits {
    const val TAG = "layer_tag"
    const val VARIANT = "layer_set_variant"
    val supported = setOf(TAG, VARIANT, WorkspaceGenerationEdits.GENERATE)

    fun apply(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, model: RigPreviewModel): WorkspaceDocument {
        val request = operation.request
        return when (operation.operation) {
            TAG -> tag(document, model, request.strings("layer_ids").also { require(it.size in 1..128) { "Give 1..128 layer_ids" } },
                request["set"]?.let { strings(it) }, request["add"]?.let { strings(it) }.orEmpty(), request["remove"]?.let { strings(it) }.orEmpty())
            VARIANT -> variant(document, model, request.getValue("layer_id").jsonPrimitive.content, LayerVariant(
                when (val kind = request.getValue("variant").jsonPrimitive.content) {
                    "none" -> LayerType.PRESET; "toggle" -> LayerType.TOGGLE; "switch" -> LayerType.SWITCH
                    else -> throw IllegalArgumentException("Unknown variant: $kind")
                },
                request["parameter"]?.jsonPrimitive?.content?.trim().orEmpty(),
                request["switch_id"]?.jsonPrimitive?.int ?: 0))
            WorkspaceGenerationEdits.GENERATE -> WorkspaceGenerationEdits.generate(document, model,
                request["refresh_geometry"]?.jsonArray?.mapTo(LinkedHashSet()) { it.jsonPrimitive.content })
            else -> throw IllegalArgumentException("Not a layout edit: ${operation.operation}")
        }
    }

    /** What a layer's classification is now: the value waiting for the next generation, else the one the rig reads. */
    fun classification(document: WorkspaceDocument, model: RigPreviewModel, id: String): LayerSemantic {
        val generated = model.analysis.layers.firstOrNull { it.source.id.raw == id }?.semantic
            ?: document.source.layers.firstOrNull { it.id.raw == id }?.let { CharacterAnalyzer.classify(it, document.config()).semantic }
            ?: throw IllegalArgumentException("Layer not found: $id")
        return pending(document, generated, id)
    }

    /** [generated] - a layer's classification as the rig reads it - with the value waiting for the next generation. */
    fun pending(document: WorkspaceDocument, generated: LayerSemantic, id: String): LayerSemantic {
        val pending = document.layout.generation.pendingClassifications[id] ?: return generated
        return generated.copy(tag = pending.tag, side = pending.side, type = pending.type, parameter = pending.parameter,
            switchId = pending.switchId, confidence = 1f)
    }

    /** Whether [id] waits for the next generation: placed since, or reclassified or reparented since. */
    fun isPending(document: WorkspaceDocument, id: String): Boolean = document.layout.generation.let { generation ->
        !generation.generated || id in generation.pendingLayerIds || id in generation.pendingClassifications
    }

    /** A layer's whole tag set: its classification's words and the user's own. */
    fun tags(document: WorkspaceDocument, semantic: LayerSemantic, id: String): Set<String> =
        LayerTags.of(semantic, document.layout.layerTags[id].orEmpty())

    fun tag(document: WorkspaceDocument, model: RigPreviewModel, ids: List<String>, set: List<String>?,
            add: List<String>, remove: List<String>): WorkspaceDocument {
        require(set != null || add.isNotEmpty() || remove.isNotEmpty()) { "Give set, add or remove" }
        require(set == null || (add.isEmpty() && remove.isEmpty())) { "set replaces the tags; do not combine it with add or remove" }
        fun normalized(values: List<String>) = values.map { LayerTags.normalize(it).also(LayerTags::requireValid) }
        val replacing = set?.let(::normalized)?.toSet()
        val adding = normalized(add); val removing = normalized(remove).toSet()
        require(ids.distinct().size == ids.size) { "layer_ids repeats a layer" }
        var next = document
        for (id in ids) {
            require(next.source.layers.any { it.id.raw == id } && id !in next.deletedLayerIds) { "Layer not found: $id" }
            val semantic = classification(next, model, id)
            val tags = replacing ?: (tags(next, semantic, id) - removing + adding)
            require(!(LayerTags.LEFT in tags && LayerTags.RIGHT in tags)) { "A layer is left or right, not both: $id" }
            val resolution = LayerTags.resolve(tags)
            next = classify(next, model, id, LayerClassificationOverride.of(tags, semantic.layerVariant))
            next = withUserTags(next, id, resolution.user)
        }
        return next
    }

    fun variant(document: WorkspaceDocument, model: RigPreviewModel, id: String, variant: LayerVariant): WorkspaceDocument {
        require(document.source.layers.any { it.id.raw == id } && id !in document.deletedLayerIds) { "Layer not found: $id" }
        require(variant.type == LayerType.PRESET || variant.parameter.isNotEmpty()) { "A toggle or switch names its parameter" }
        require(variant.type != LayerType.PRESET || (variant.parameter.isEmpty() && variant.switchId == 0)) { "variant none takes no parameter or switch_id" }
        val semantic = classification(document, model, id)
        return classify(document, model, id, LayerClassificationOverride(variant.type, semantic.tag, semantic.side, variant.parameter, variant.switchId))
    }

    /** [document] with [id] classified as [value]: the same edit `layer_classify` makes. */
    fun classify(document: WorkspaceDocument, model: RigPreviewModel, id: String, value: LayerClassificationOverride): WorkspaceDocument =
        WorkspaceDocumentEdits.classify(document, model, id, value)

    private fun withUserTags(document: WorkspaceDocument, id: String, tags: Set<String>): WorkspaceDocument {
        val layout = document.layout
        val next = if (tags.isEmpty()) layout.layerTags - id else layout.layerTags + (id to tags)
        return if (next == layout.layerTags) document else document.copy(layout = layout.copy(layerTags = next))
    }

    private fun strings(value: JsonElement) = value.jsonArray.map { it.jsonPrimitive.content }
    private fun JsonObject.strings(key: String) = strings(getValue(key))

    private fun stringSchema() = buildJsonObject { put("type", "string"); put("minLength", 1); put("maxLength", LayerTags.MAX_LENGTH) }
    private fun array(items: JsonObject, min: Int, max: Int) = buildJsonObject { put("type", "array"); put("items", items); put("minItems", min); put("maxItems", max) }
    private fun schema(fields: Map<String, JsonObject>, required: List<String>) = buildJsonObject {
        put("type", "object"); put("additionalProperties", false); put("properties", JsonObject(fields))
        put("required", JsonArray(required.map(::JsonPrimitive)))
    }

    /** Request schemas, with the shared `state` field. */
    val schemas: Map<String, JsonObject> by lazy {
        val state = buildJsonObject { put("type", "string"); put("minLength", 1) }
        val tags = array(stringSchema(), 0, 64)
        mapOf(
            TAG to schema(mapOf("state" to state, "layer_ids" to array(buildJsonObject { put("type", "string"); put("minLength", 1) }, 1, 128),
                "set" to tags, "add" to tags, "remove" to tags), listOf("state", "layer_ids")),
            VARIANT to schema(mapOf("state" to state, "layer_id" to buildJsonObject { put("type", "string"); put("minLength", 1) },
                "variant" to buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("none", "toggle", "switch").map(::JsonPrimitive))) },
                "parameter" to buildJsonObject { put("type", "string") },
                "switch_id" to buildJsonObject { put("type", "integer"); put("minimum", 0) }), listOf("state", "layer_id", "variant")),
            WorkspaceGenerationEdits.GENERATE to WorkspaceGenerationEdits.schema,
        )
    }

    val descriptions = mapOf(
        TAG to "Set, add or remove semantic tags on 1..128 source layers. Tags the generators read - part words " +
            "(${LayerTags.vocabulary.filter { it != LayerTags.LEFT && it != LayerTags.RIGHT }.joinToString(", ")}) and the sides left/right - " +
            "become the layer's classification: {hair, front} is front hair, {eye, iris, left} the left iris, {mouth, open} the open mouth; a set matching " +
            "no part leaves the layer unrecognised. Any other word is the user's own tag, kept for filtering and never read by the generators. " +
            "The variant (toggle/switch) is separate: layer_set_variant. A generated layer keeps the classification the rig was generated with " +
            "until rig_generate runs; the new one waits (pending in workspace_inspect). One history edit for all layers.",
        VARIANT to "Set a source layer's variant: none, toggle (shown or hidden by parameter) or switch (choice switch_id of parameter). " +
            "Keeps the layer's semantic tags. One history edit.",
        WorkspaceGenerationEdits.GENERATE to WorkspaceGenerationEdits.DESCRIPTION,
    )
}
