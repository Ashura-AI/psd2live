package io.github.psd2live.application

import io.github.psd2live.core.LayerSpace
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.StableIds
import io.github.psd2live.project.WorkspaceDocument
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * One reference, three places to show it: behind the model or in front of it as a reference image
 * ([io.github.psd2live.project.LayoutReference]), or in the layer stack as a reference layer between the ArtMeshes
 * ([io.github.psd2live.project.WorkspaceLayout.referenceLayerIds]). Moving a reference between them keeps its
 * material, rectangle, visibility and keyform pin; a reference image's turn and lock stay with reference images, and a
 * reference layer's place in the stack with reference layers.
 */
internal object WorkspaceReferenceModes {
    const val OP = "reference_set_mode"

    enum class Mode(val key: String) {
        BACKGROUND("background"), LAYER("layer"), FOREGROUND("foreground");

        companion object {
            fun of(key: String) = entries.firstOrNull { it.key == key } ?: throw IllegalArgumentException("mode is background, layer or foreground")
        }
    }

    fun apply(document: WorkspaceDocument, model: RigPreviewModel, request: JsonObject): WorkspaceDocument {
        val mode = Mode.of(request.getValue("mode").jsonPrimitive.content)
        val referenceId = request["reference_id"]?.jsonPrimitive?.content
        val layerId = request["layer_id"]?.jsonPrimitive?.content
        require((referenceId == null) != (layerId == null)) { "Give reference_id (a reference image) or layer_id (a reference layer)" }
        if (referenceId != null) {
            val reference = document.layout.reference(referenceId) ?: throw IllegalArgumentException("Reference image not found: $referenceId")
            if (mode != Mode.LAYER) return WorkspaceMaterialEdits.reference(document, buildJsonObject {
                put("id", referenceId); put("under", mode == Mode.BACKGROUND)
            })
            val taken = document.source.layers.mapTo(HashSet()) { it.id.raw }
            val id = StableIds.fresh(StableIds.stem("ref_", reference.id, reference.materialId)) { it in taken }
            var next = WorkspaceMaterialEdits.place(document, model, reference.materialId, reference.rect, null, id, reference = true)
            next = next.copy(layout = next.layout.copy(references = next.layout.references.filter { it.id != referenceId },
                referencePins = if (reference.pins.isEmpty()) next.layout.referencePins else next.layout.referencePins + (id to reference.pins)))
            return if (reference.visible) next else next.copy(layerVisibility = next.layerVisibility + (id to false))
        }
        val id = requireNotNull(layerId)
        require(id in document.layout.referenceLayerIds) { "Not a reference layer: $id" }
        if (mode == Mode.LAYER) return document
        val layer = document.source.layers.singleOrNull { it.id.raw == id } ?: throw IllegalArgumentException("Layer not found: $id")
        // The layer's material is the reference image's pixels; a layer without one gets one first.
        val adopted = WorkspaceMaterialLinks.adopt(document, setOf(id))
        val material = requireNotNull(adopted.layout.layerMaterials[id]) { "Reference layer $id shows no material" }
        val space = LayerSpace.of(layer)
        val pin = document.layout.referencePins[id].orEmpty()
        val added = WorkspaceMaterialEdits.addReference(adopted, buildJsonObject {
            put("material_id", material)
            putJsonObject("rect") { put("left", space.left); put("top", space.top); put("width", space.width); put("height", space.height) }
            put("under", mode == Mode.BACKGROUND)
            put("visible", document.layerVisibility[id] ?: layer.visible)
            if (pin.isNotEmpty()) putJsonObject("pin") { pin.forEach { (parameter, value) -> put(parameter, value) } }
        })
        val removed = WorkspaceGenerationEdits.delete(added, id)
        return removed.copy(layout = removed.layout.copy(referencePins = removed.layout.referencePins - id))
    }
}
