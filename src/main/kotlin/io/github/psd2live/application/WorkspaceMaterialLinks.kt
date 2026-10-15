package io.github.psd2live.application

import io.github.psd2live.project.MaterialItem
import io.github.psd2live.project.WorkspaceDocument
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.LayerRaster

/**
 * A layer linked to a material ([io.github.psd2live.project.WorkspaceLayout.layerMaterials]) shows the material's own
 * pixels: the material is their one source. Painting such a layer paints the material where the layer's meshes show
 * it, and every layer showing it follows ([WorkspacePaintOverflow]); any other edit that leaves the layer with pixels of its own - a split, an image
 * from elsewhere - unlinks it ([reconcile]), and `material_unlink` does so on request. So the document never holds a
 * linked layer whose pixels and material differ.
 */
internal object WorkspaceMaterialLinks {
    /** Whether [layer] and [material] are the same pixels. */
    fun shows(layer: LayerRaster, material: LayerRaster): Boolean = layer === material ||
        layer.width == material.width && layer.height == material.height && layer.rgba.contentEquals(material.rgba)

    /** The material layer [layerId] shows, with the layer, when it is linked, present and shows it; else null. */
    fun linked(document: WorkspaceDocument, layerId: String): Pair<MaterialItem, SourceLayer>? {
        val materialId = document.layout.layerMaterials[layerId] ?: return null
        if (layerId in document.deletedLayerIds) return null
        val item = document.layout.materials.item(materialId) ?: return null
        val layer = document.source.layers.singleOrNull { it.id.raw == layerId } ?: return null
        return (item to layer).takeIf { shows(layer.raster, item.raster) }
    }

    /**
     * [after] with the links of layers no longer showing their material removed: an edit from [before] gave them
     * pixels of their own. Links whose layer and material are both untouched are not compared again.
     */
    fun reconcile(before: WorkspaceDocument, after: WorkspaceDocument): WorkspaceDocument {
        val links = after.layout.layerMaterials
        if (links.isEmpty()) return after
        val previous = before.source.layers.associateBy { it.id.raw }
        val layers = after.source.layers.associateBy { it.id.raw }
        val broken = links.filter { (layerId, materialId) ->
            val layer = layers[layerId] ?: return@filter false
            val item = after.layout.materials.item(materialId) ?: return@filter true
            val unchanged = previous[layerId]?.raster === layer.raster && before.layout.materials.item(materialId)?.raster === item.raster
            !unchanged && !shows(layer.raster, item.raster)
        }.keys
        return if (broken.isEmpty()) after else after.copy(layout = after.layout.copy(layerMaterials = links - broken))
    }

    /** [document] with [layerIds] unlinked from their materials; their pixels stay as they are. */
    fun unlink(document: WorkspaceDocument, layerIds: Collection<String>): WorkspaceDocument {
        val links = document.layout.layerMaterials
        val ids = layerIds.toSet()
        ids.forEach { require(document.source.layers.any { layer -> layer.id.raw == it }) { "Layer not found: $it" } }
        if (ids.none { it in links }) return document
        return document.copy(layout = document.layout.copy(layerMaterials = links - ids))
    }
}
