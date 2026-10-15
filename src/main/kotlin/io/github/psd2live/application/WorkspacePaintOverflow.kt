package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayer
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A paint is free to reach anywhere on the canvas; its commit cuts it along the layer's meshes. What lands on them is
 * the layer's (for a layer showing a material, the material's, which every layer showing it follows); what lands
 * off them - which the meshes would never show - becomes a new layer of its own just above, with its own mesh under
 * the same parent and in the same part, showing a material of its own in the library. The layer's meshes, keyforms and bindings stay as they are.
 *
 * Off the meshes the layer keeps its own pixels: erasing there changes nothing, and only pixels the paint changed
 * move to the new layer, so a faint edge the meshes leave out never leaves the layer.
 */
internal object WorkspacePaintOverflow {
    /**
     * [request] committed to [document] cut along the layer's meshes, or null when it is not cut: a mesh rebuild was
     * asked for, the layer has no mesh yet (its mesh is made from all its pixels), or it is a depth-split front.
     */
    fun cut(document: WorkspaceDocument, model: RigPreviewModel, request: WorkspacePaintRaster, work: WorkspaceRasterWork,
            commit: (WorkspaceDocument, WorkspacePaintRaster) -> WorkspaceDocument): WorkspaceDocument? {
        if (request.rebuildMesh) return null
        val id = RasterPaintCommit.sourceLayerFor(model, model.analysis, request.layerId)?.id?.raw ?: return null
        val layer = document.source.layers.singleOrNull { it.id.raw == id && id !in document.deletedLayerIds } ?: return null
        if (DepthSplit.isFrontLayer(model, id)) return null
        val linked = WorkspaceMaterialLinks.linked(document, id)
        val canvasWidth = document.source.widthPx; val canvasHeight = document.source.heightPx
        val space = PaintSpace.of(layer, canvasWidth, canvasHeight)
        val material = space.raster.takeIf { linked != null }
        if (linked != null && material == null) return null
        val triangles = triangles(model, id)
        if (triangles.isEmpty() && linked == null) return null
        work.progress(0.2f, "Cutting the paint along the layer's meshes")
        val width = space.width; val height = space.height
        val original = space.image(layer, work::checkpoint).getRGB(0, 0, width, height, null, 0, width)
        val painted = painted(space, request.pixels, canvasWidth, canvasHeight, work)
        // A material's pixels are its rectangle; a layer without meshes keeps that whole rectangle.
        val on = if (triangles.isEmpty()) java.util.BitSet(width * height).apply {
            val area = requireNotNull(material).intersection(java.awt.Rectangle(0, 0, width, height))
            for (y in area.y until area.y + area.height) set(y * width + area.x, y * width + area.x + area.width)
        } else coverage(space, triangles, material, work)
        val kept = IntArray(width * height)
        val off = IntArray(width * height)
        var spilled = false
        for (i in kept.indices) {
            if (i % width == 0) work.checkpoint()
            if (on[i]) kept[i] = painted[i] else {
                kept[i] = original[i]
                if (painted[i] != original[i] && painted[i] ushr 24 != 0) { off[i] = painted[i]; spilled = true }
            }
        }
        if (linked == null && !spilled) return null
        var next = if (linked == null) commit(document, WorkspacePaintRaster.capture(request.layerId, image(kept, width, height), space, null,
            false, request.preserveSourceRaster, work::checkpoint)) else {
            val (item, _) = linked
            val raster = materialRaster(kept, width, height, requireNotNull(material), item.raster)
            if (WorkspaceMaterialLinks.shows(raster, item.raster)) document else WorkspaceMaterialPixels.replace(document, model, item, raster)
        }
        if (spilled) next = addLayer(next, model, layer, space.crop(image(off, width, height), null, work::checkpoint), work)
        return next
    }

    /** [layerId]'s meshes as canvas triangles, three points of six floats each, where they sample its pixels at rest. */
    private fun triangles(model: RigPreviewModel, layerId: String): List<FloatArray> {
        val puppet = model.rig.puppet
        return puppet.drawables.filter { model.rig.layerIdByDrawableId[it.id.raw] == layerId && it.mesh != null }.flatMap { drawable ->
            val mesh = requireNotNull(drawable.mesh)
            val canvas = runCatching { RasterMeshJournal.TextureCoordinates(puppet, drawable).toCanvas(mesh.uvs) }.getOrNull() ?: return@flatMap emptyList()
            (0 until mesh.indices.size / 3).map { t ->
                FloatArray(6) { k -> canvas[mesh.indices[t * 3 + k / 2] * 2 + k % 2] }
            }
        }
    }

    /** The image pixels of [space] whose centres lie on a triangle, and on [within] when given. */
    private fun coverage(space: PaintSpace, triangles: List<FloatArray>, within: java.awt.Rectangle?, work: WorkspaceRasterWork): java.util.BitSet {
        val width = space.width; val height = space.height
        val bits = java.util.BitSet(width * height)
        val clip = (within ?: java.awt.Rectangle(0, 0, width, height)).intersection(java.awt.Rectangle(0, 0, width, height))
        if (clip.isEmpty) return bits
        for (triangle in triangles) {
            work.checkpoint()
            val x0 = space.canvasToImageX(triangle[0]); val y0 = space.canvasToImageY(triangle[1])
            val x1 = space.canvasToImageX(triangle[2]); val y1 = space.canvasToImageY(triangle[3])
            val x2 = space.canvasToImageX(triangle[4]); val y2 = space.canvasToImageY(triangle[5])
            val area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
            if (area == 0f || !area.isFinite()) continue
            val left = max(clip.x, floor(min(x0, min(x1, x2)) - 0.5f).toInt()); val right = min(clip.x + clip.width - 1, ceil(max(x0, max(x1, x2)) - 0.5f).toInt())
            val top = max(clip.y, floor(min(y0, min(y1, y2)) - 0.5f).toInt()); val bottom = min(clip.y + clip.height - 1, ceil(max(y0, max(y1, y2)) - 0.5f).toInt())
            for (y in top..bottom) {
                val py = y + 0.5f
                for (x in left..right) {
                    val px = x + 0.5f
                    val a = ((x1 - px) * (y2 - py) - (x2 - px) * (y1 - py)) / area
                    val b = ((x2 - px) * (y0 - py) - (x0 - px) * (y2 - py)) / area
                    val c = 1f - a - b
                    if (a >= -EDGE && b >= -EDGE && c >= -EDGE) bits.set(y * width + x)
                }
            }
        }
        return bits
    }

    /** [pixels] on [space]'s image, each image pixel taking the painted pixel under its centre. */
    private fun painted(space: PaintSpace, pixels: PaintPixels, canvasWidth: Int, canvasHeight: Int, work: WorkspaceRasterWork): IntArray {
        val width = space.width; val height = space.height
        val out = IntArray(width * height)
        val from = pixels.space(canvasWidth, canvasHeight)
        val rgba = pixels.raster.rgba
        val left = max(0, floor(space.canvasToImageX(from.left)).toInt()); val right = min(width, ceil(space.canvasToImageX(from.right)).toInt())
        val top = max(0, floor(space.canvasToImageY(from.top)).toInt()); val bottom = min(height, ceil(space.canvasToImageY(from.bottom)).toInt())
        for (y in top until bottom) {
            work.checkpoint()
            val fy = floor(from.canvasToRasterY(space.imageToCanvasY(y + 0.5f))).toInt()
            if (fy !in 0 until from.rasterHeight) continue
            for (x in left until right) {
                val fx = floor(from.canvasToRasterX(space.imageToCanvasX(x + 0.5f))).toInt()
                if (fx !in 0 until from.rasterWidth) continue
                val o = (fy * from.rasterWidth + fx) * 4
                out[y * width + x] = ((rgba[o + 3].toInt() and 255) shl 24) or ((rgba[o].toInt() and 255) shl 16) or
                    ((rgba[o + 1].toInt() and 255) shl 8) or (rgba[o + 2].toInt() and 255)
            }
        }
        return out
    }

    /** The material's raster from [kept] where [at] lies on the image, and from [before] where it does not. */
    private fun materialRaster(kept: IntArray, width: Int, height: Int, at: java.awt.Rectangle, before: LayerRaster): LayerRaster {
        val rgba = before.rgba.copyOf()
        for (y in 0 until at.height) {
            val iy = at.y + y
            if (iy !in 0 until height) continue
            for (x in 0 until at.width) {
                val ix = at.x + x
                if (ix !in 0 until width) continue
                val p = kept[iy * width + ix]; val o = (y * at.width + x) * 4
                rgba[o] = (p ushr 16).toByte(); rgba[o + 1] = (p ushr 8).toByte(); rgba[o + 2] = p.toByte(); rgba[o + 3] = (p ushr 24).toByte()
            }
        }
        return LayerRaster(at.width, at.height, rgba)
    }

    private fun image(argb: IntArray, width: Int, height: Int) =
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { it.setRGB(0, 0, width, height, argb, 0, width) }

    /**
     * [document] with [painted] as a new layer just above [original], looking like it, its mesh under the parent and
     * in the part of [original]'s meshes and drawn right after them.
     */
    private fun addLayer(document: WorkspaceDocument, model: RigPreviewModel, original: SourceLayer, painted: PaintedLayer,
                         work: WorkspaceRasterWork): WorkspaceDocument {
        val taken = HashSet<String>().apply { document.source.layers.mapTo(this) { it.id.raw }; addAll(document.deletedLayerIds) }
        val id = StableIds.fresh(StableIds.stem("painted_", original.id.raw, painted.bounds.toString(), painted.rect?.toString())) { it in taken }
        val added = WorkspaceSourceLayer(id = org.umamo.format.art.LayerId(id), name = original.name + "+", groupPath = original.groupPath,
            kind = org.umamo.format.art.SourceLayerKind.Raster, visible = true, order = 0, bounds = painted.bounds, opacity = original.opacity,
            clipped = false, blend = original.blend, channelMask = original.channelMask, raster = painted.raster,
            sourceAssetId = null, sourceSpatialReferenceId = null, derived = false, rect = painted.rect,
            // Painted in the frame of the layer, wherever it was moved: the new layer moves with it.
            layerTransform = (original as? WorkspaceSourceMetadata)?.layerTransform)
        val layers = document.source.layers.toMutableList()
        layers.add(layers.indexOfFirst { it.id == original.id } + 1, added)
        val source = WorkspaceSourceArt(document.source.widthPx, document.source.heightPx,
            layers.mapIndexed { index, layer -> WorkspaceSourceLayer.copyOf(layer, layers.lastIndex - index) }, document.source.groups)
        // Like every layer read from a file, it shows a material of its own: beside the material of the layer it came
        // from, else at the top of the library.
        val library = document.layout.materials
        val beside = document.layout.layerMaterials[original.id.raw]?.let(library::item)
        val itemIds = HashSet<String>().apply { library.items.mapTo(this) { it.id }; library.folders.mapTo(this) { it.id } }
        val itemId = StableIds.fresh(StableIds.stem("mat_", beside?.folderId, added.name, WorkspaceRevisions.rasterDigest(painted.raster.rgba))) { it in itemIds }
        val item = MaterialItem(itemId, added.name, beside?.folderId, MaterialKind.BITMAP, painted.raster,
            placement = painted.rect ?: LayerCanvasRect.of(painted.bounds), origin = beside?.origin.orEmpty(), opacity = original.opacity)
        val items = library.items.toMutableList().apply { add(indexOfFirst { it.id == beside?.id } + 1, item) }
        val candidate = document.copy(source = source, layerVisibility = document.layerVisibility + (id to true),
            layout = document.layout.copy(materials = library.copy(items = items), layerMaterials = document.layout.layerMaterials + (id to itemId)),
            layerOverrides = document.layerOverrides + (id to (document.layerOverrides[original.id.raw]
                ?: LayerClassificationOverride(io.github.psd2live.core.LayerType.PRESET, SemanticTag.UNKNOWN, Side.NONE))))
        val puppet = model.rig.puppet
        val meshes = puppet.drawables.filter { model.rig.layerIdByDrawableId[it.id.raw] == original.id.raw }
        val parent = meshes.firstNotNullOfOrNull { it.parentDeformerId?.raw }
        work.progress(0.6f, "Meshing the paint off the layer's meshes")
        val created = WorkspaceLayerInsertionEdits.materialize(candidate, model, setOf(id), parent, work::checkpoint)
        val owner = meshes.firstNotNullOfOrNull { mesh -> puppet.parts.singleOrNull { OrgChild.Drawable(mesh.id) in it.children } }
        val chain = ArrayList<Part>()
        var part = owner
        while (part != null && chain.none { it.id == part!!.id }) {
            chain.add(0, part)
            val child = OrgChild.Part(part.id)
            part = puppet.parts.singleOrNull { child in it.children }
        }
        val order = meshes.maxOfOrNull { it.drawOrder }
        return created.copy(rigEdits = created.rigEdits.copy(authoringJournal = created.rigEdits.authoringJournal.map { entry ->
            if (entry["op"]?.jsonPrimitive?.contentOrNull != RasterMeshCreation.OP || entry["layer_id"]?.jsonPrimitive?.contentOrNull != id) entry
            else JsonObject(entry + buildMap {
                order?.let { put("order", JsonPrimitive(it)) }
                put("part", owner?.id?.raw?.let(::JsonPrimitive) ?: JsonNull)
                put("parts", JsonArray(chain.map { buildJsonObject { put("id", it.id.raw); put("name", it.name) } }))
            })
        }))
    }

    /** Slack on a triangle edge, in barycentric units: a pixel centre on a shared edge belongs to both. */
    private const val EDGE = 1e-5f
}
