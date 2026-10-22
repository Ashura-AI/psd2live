package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import kotlinx.serialization.json.*

/** One opacity edit for a reference image, a pending reference quad or an authored reference mesh. */
internal object WorkspaceReferenceOpacity {
    const val OP = "reference_set_opacity"

    fun apply(document: WorkspaceDocument, model: RigPreviewModel, request: JsonObject): WorkspaceDocument {
        val referenceId = request["reference_id"]?.jsonPrimitive?.content
        val layerId = request["layer_id"]?.jsonPrimitive?.content
        require((referenceId == null) != (layerId == null)) { "Give reference_id or layer_id" }
        val opacity = request.getValue("opacity").jsonPrimitive.float
        require(opacity in 0f..1f) { "opacity is 0..1" }
        if (referenceId != null) {
            require(document.layout.reference(referenceId) != null) { "Reference image not found: $referenceId" }
            return WorkspaceMaterialEdits.reference(document, buildJsonObject { put("id", referenceId); put("opacity", opacity) })
        }
        val id = requireNotNull(layerId)
        require(id in document.layout.referenceLayerIds && id !in document.deletedLayerIds) { "Not a reference layer: $id" }
        val layer = document.source.layers.single { it.id.raw == id }
        val meshes = model.rig.puppet.drawables.filter {
            model.rig.ownLayerIdByDrawableId[it.id.raw] == id && !WorkspaceLayoutPreview.isPendingMesh(it.id.raw)
        }
        var next = if (layer.opacity == opacity) document else document.copy(source = WorkspaceSourceArt(
            document.source.widthPx, document.source.heightPx,
            document.source.layers.map {
                if (it.id.raw == id) (WorkspaceSourceLayer.copyOf(it, it.order) as WorkspaceSourceLayer).copy(opacity = opacity) else it
            }, document.source.groups))
        val edits = meshes.filter { it.opacity != opacity }.map { mesh -> buildJsonObject {
            put("action", "static"); put("kind", "mesh"); put("id", mesh.id.raw); put("opacity", opacity)
        } }
        if (edits.isNotEmpty()) next = WorkspaceDocumentEdits.journal(next, model, buildJsonArray {
            add(buildJsonObject { put("op", "structure"); put("edits", JsonArray(edits)) })
        })
        return next
    }
}
