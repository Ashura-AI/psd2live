package io.github.psd2live.application

import io.github.psd2live.core.StableIds
import io.github.psd2live.project.LayerCanvasRect
import io.github.psd2live.project.MaterialItem
import io.github.psd2live.project.MaterialKind
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.storedCanvasRect
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.LayerRaster

/**
 * A layer linked to a material ([io.github.psd2live.project.WorkspaceLayout.layerMaterials]) shows the material's own
 * pixels: the material is their one source, and the library holds every layer's image. A link never breaks on its
 * own. Painting a layer that shares its material with others paints the material where the layer's meshes show it,
 * and every layer showing it follows ([WorkspacePaintOverflow]); any other edit that gives a layer pixels of its own
 * takes its material along when no other layer shows it, else gives the layer a copy of its own ([reconcile]), and
 * `material_fork` does so on request. Layers an edit adds get materials of their own. So the document never holds a
 * linked layer whose pixels and material differ.
 */
internal object WorkspaceMaterialLinks {
    /** The folder the materials of layers made by edits go in. */
    const val LAYERS_FOLDER = "Layers"

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

    /** [linked], when another present layer shows the same material too: painting the layer paints them all. */
    fun shared(document: WorkspaceDocument, layerId: String): Pair<MaterialItem, SourceLayer>? =
        linked(document, layerId)?.takeIf { (item, _) -> users(document, item.id).size > 1 }

    /** The present layers linked to material [itemId]. */
    fun users(document: WorkspaceDocument, itemId: String): List<String> {
        val present = present(document)
        return document.layout.layerMaterials.filter { (layer, item) -> item == itemId && layer in present }.keys.sorted()
    }

    /** The present layers that show no material of the library. */
    fun unlinked(document: WorkspaceDocument): List<String> {
        val links = document.layout.layerMaterials
        return document.source.layers.filter { layer ->
            val id = layer.id.raw
            id !in document.deletedLayerIds && layer.raster.width > 0 && layer.raster.height > 0 &&
                links[id]?.let(document.layout.materials::item) == null
        }.map { it.id.raw }
    }

    private fun present(document: WorkspaceDocument) = document.source.layers.mapTo(HashSet()) { it.id.raw } - document.deletedLayerIds

    /**
     * [after] with every link true again after an edit from [before]. A layer the edit gave pixels of its own takes its
     * material along when it alone shows it and the edit left the material as it was; otherwise the layer gets a copy
     * of its own beside it. Layers the edit added get materials of their own ([adopt]). Links whose layer and material
     * are both untouched are not compared again.
     */
    fun reconcile(before: WorkspaceDocument, after: WorkspaceDocument): WorkspaceDocument {
        val links = after.layout.layerMaterials
        var next = after
        if (links.isNotEmpty()) {
            val previous = before.source.layers.associateBy { it.id.raw }
            val layers = after.source.layers.associateBy { it.id.raw }
            val present = present(after)
            val broken = links.filter { (layerId, materialId) ->
                val layer = layers[layerId] ?: return@filter false
                if (layerId !in present) return@filter false
                val item = after.layout.materials.item(materialId) ?: return@filter true
                val unchanged = previous[layerId]?.raster === layer.raster && before.layout.materials.item(materialId)?.raster === item.raster
                !unchanged && !shows(layer.raster, item.raster)
            }
            for ((layerId, materialId) in broken) {
                val layer = layers.getValue(layerId)
                val item = next.layout.materials.item(materialId)
                if (item == null) {
                    next = next.copy(layout = next.layout.copy(layerMaterials = next.layout.layerMaterials - layerId))
                    continue
                }
                val alone = links.none { (other, id) -> id == materialId && other != layerId && other in present }
                val untouched = before.layout.materials.item(materialId)?.raster === item.raster
                next = if (alone && untouched) follow(next, item, layer) else fork(next, item, layer)
            }
        }
        val added = after.source.layers.map { it.id.raw }.toSet() - before.source.layers.mapTo(HashSet()) { it.id.raw }
        return adopt(next, added)
    }

    /** [document] with material [item] showing [layer]'s pixels, at the layer's place. */
    private fun follow(document: WorkspaceDocument, item: MaterialItem, layer: SourceLayer): WorkspaceDocument {
        val library = document.layout.materials
        val followed = item.copy(raster = layer.raster, kind = MaterialKind.BITMAP, svg = null, placement = placement(layer))
        return document.copy(layout = document.layout.copy(materials = library.copy(items = library.items.map { if (it.id == item.id) followed else it })))
    }

    /** [document] with [layer] showing a copy of [item] holding its own pixels, just after [item] in the library. */
    private fun fork(document: WorkspaceDocument, item: MaterialItem, layer: SourceLayer): WorkspaceDocument {
        val library = document.layout.materials
        val taken = library.folders.mapTo(HashSet()) { it.id } + library.items.map { it.id }
        val id = StableIds.fresh(StableIds.stem("mat_", item.id, layer.id.raw)) { it in taken }
        val copy = item.copy(id = id, raster = layer.raster, kind = MaterialKind.BITMAP, svg = null, placement = placement(layer))
        val at = library.items.indexOfFirst { it.id == item.id } + 1
        val items = library.items.toMutableList().apply { add(at, copy) }
        return document.copy(layout = document.layout.copy(materials = library.copy(items = items),
            layerMaterials = document.layout.layerMaterials + (layer.id.raw to id)))
    }

    private fun placement(layer: SourceLayer): LayerCanvasRect = layer.storedCanvasRect ?: LayerCanvasRect.of(layer.bounds)

    /** [document] with [layerIds] each given a copy of the material it shows, the pixels unchanged (`material_fork`). */
    fun forkLayers(document: WorkspaceDocument, layerIds: Collection<String>): WorkspaceDocument {
        val ids = layerIds.toSet()
        var next = document
        for (id in ids) {
            val layer = document.source.layers.singleOrNull { it.id.raw == id } ?: throw IllegalArgumentException("Layer not found: $id")
            val item = document.layout.layerMaterials[id]?.let(next.layout.materials::item)
            next = if (item == null) adopt(next, setOf(id)) else if (users(next, item.id).size > 1) fork(next, item, layer) else next
        }
        return next
    }

    /** [document] with those of [layerIds] that show no material given one each, in [LAYERS_FOLDER]. */
    fun adopt(document: WorkspaceDocument, layerIds: Set<String>, folder: String = LAYERS_FOLDER): WorkspaceDocument {
        if (layerIds.isEmpty()) return document
        val missing = unlinked(document).filterTo(HashSet()) { it in layerIds }
        if (missing.isEmpty()) return document
        return WorkspaceMaterialEdits.addLinked(document, folder, folder, only = missing)
    }
}
