package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.umamo.format.art.SourceLayer
import org.umamo.runtime.model.*

/** Source additions keep the preceding geometry input, rig frames and object identities. */
internal object WorkspaceLayerInsertionEdits {
    fun freeze(document: WorkspaceDocument, model: RigPreviewModel) = document.copy(
        rigEdits = RigLayerDeletion.preserve(model, document.config()),
        generationSource = document.generationSource ?: document.source)

    /**
     * The mesh ID each of [ids] gets: the one its mesh already has, else a fresh `ArtMeshImage…` no rig, journal record or
     * other layer holds. A one-layer build alone would name every image the same.
     */
    private fun meshIds(document: WorkspaceDocument, current: RigPreviewModel, ids: Set<String>): Map<String, String> {
        // A pending layer's quad or plain mesh is no mesh of the rig: its ID is not kept.
        val existing = current.rig.layerIdByDrawableId.entries.filter { it.value in ids && !WorkspaceLayoutPreview.isPendingMesh(it.key) }
            .groupBy({ it.value }, { it.key })
        val taken = HashSet<String>()
        current.rig.puppet.drawables.mapTo(taken) { it.id.raw }
        current.authored.rig.puppet.drawables.mapTo(taken) { it.id.raw }
        document.rigEdits.authoringJournal.filter { it["op"]?.jsonPrimitive?.content == RasterMeshCreation.OP }
            .mapTo(taken) { it.getValue("id").jsonPrimitive.content }
        taken += document.rigEdits.splitDrawableIds.values
        return ids.associateWith { layer ->
            document.rigEdits.splitDrawableIds[layer] ?: existing[layer]?.singleOrNull()
                ?: StableIds.fresh("ArtMeshImage") { it in taken }.also { taken += it }
        }
    }

    /** Generated root geometry is converted through the actual parent's neutral transform. */
    fun materialize(document: WorkspaceDocument, current: RigPreviewModel, ids: Set<String>, parent: String?,
                            checkCancelled: () -> Unit): WorkspaceDocument {
        checkCancelled()
        val meshIds = meshIds(document, current, ids)
        val layers = document.source.layers.filter { it.id.raw in ids }
        val orderStep = RigBuilder.generatedOrderStep(document.config().drawOrderStep, layers.size)
        val orders = layers.sortedBy { it.order }.mapIndexed { index, layer -> layer.id.raw to ((layers.size - index) * orderStep).toFloat() }.toMap()
        val references = layers.filter { it.id.raw in document.layout.referenceLayerIds }
        val artwork = layers.filterNot { it.id.raw in document.layout.referenceLayerIds }
        val source = WorkspaceSourceArt(document.source.widthPx, document.source.heightPx, artwork, document.source.groups)
        val config = document.config().copy(meshOnly = true, generateDeformers = false, generatePhysics = false,
            parentOverrides = emptyMap(), generationSource = null, meshSource = null, deletedLayerIds = emptySet(),
            rigEdits = RigEditOverlay.Empty.copy(splitDrawableIds = meshIds))
        val parentId = parent?.let(::DeformerId)
        val transforms = layers.associate { it.id.raw to it.transform }
        val meshed = if (artwork.isEmpty()) emptyList() else {
            val generated = PSD2LivePipeline().buildPreview(source, config, ProgressListener { _, _ -> checkCancelled() })
            val rig = RasterMeshPlacement.underParents(generated.rig, current.rig.puppet, { parentId },
                { mesh -> generated.rig.layerIdByDrawableId[mesh.raw]?.let(transforms::get) ?: LayerTransform.IDENTITY }, checkCancelled)
            rig.puppet.drawables.map { drawable -> checkCancelled(); RasterMeshCreation.encode(rig, drawable.id) }
        }
        val records = (meshed + references.map { layer ->
            checkCancelled()
            rectangle(current, layer, DrawableId(meshIds.getValue(layer.id.raw)), parentId)
        }).map { command ->
            val id = command.getValue("id").jsonPrimitive.content
            val layer = command.getValue("layer_id").jsonPrimitive.content
            val ordered = JsonObject(command + ("order" to JsonPrimitive(
                config.drawOrderOverrides[layer] ?: config.drawOrderOverrides[id] ?: orders.getValue(layer))))
            if (document.rigEdits.importedCmo3 == null) ordered else JsonObject(ordered +
                ("source_id" to JsonPrimitive(Cmo3ModelImport.PAINT_SOURCE_ID)))
        }
        val byId = records.associateBy { it.getValue("id").jsonPrimitive.content }
        val replaced = mutableSetOf<String>()
        // Replay starts at the checkpoint, so only a creation record after it is rewritten. A mesh the authored rig already
        // holds - its creation before the checkpoint, or generated from the layer and checkpointed since - gets a record
        // that replaces it: a rewrite before the checkpoint would never replay, and a second creation would collide.
        val checkpoint = document.rigEdits.checkpointIndex
        val journal = document.rigEdits.authoringJournal.mapIndexed { index, entry ->
            val id = entry["id"]?.jsonPrimitive?.content
            if (index > checkpoint && entry["op"]?.jsonPrimitive?.content == RasterMeshCreation.OP && id in byId) {
                require(replaced.add(requireNotNull(id))) { "Duplicate mesh creation record" }
                byId.getValue(id).let { if (RasterMeshCreation.replaces(entry)) RasterMeshCreation.replacing(it) else it }
            } else entry
        } + records.filterNot { it.getValue("id").jsonPrimitive.content in replaced }.map { record ->
            val id = record.getValue("id").jsonPrimitive.content
            if (current.authored.rig.puppet.drawables.any { it.id.raw == id }) RasterMeshCreation.replacing(record) else record
        }
        // The generators never mesh a layer a creation record owns ([RigGenerationSource.createdCoverage]); no placeholder is stored.
        return document.copy(rigEdits = document.rigEdits.copy(authoringJournal = journal))
    }

    /** Reference layers sample the whole raster on four corners, without tracing or triangulating its pixels. */
    private fun rectangle(current: RigPreviewModel, layer: SourceLayer, id: DrawableId, parent: DeformerId?): JsonObject {
        val space = LayerSpace.of(layer)
        val canvas = floatArrayOf(space.left, space.top, space.right, space.top, space.right, space.bottom, space.left, space.bottom)
        val shown = RasterMeshPlacement.shown(canvas, layer.transform)
        val tile = PuppetSourceAtlas.tileIdFor(layer.id.raw)
        val shell = Drawable(id, layer.name, parent, BlendMode.Normal, emptyList(), null, null,
            atlasTileId = tile, opacity = layer.opacity, isVisible = layer.visible, drawOrder = 10f)
        val positions = if (parent == null) shown else
            requireNotNull(RasterMeshPlacement.underParent(current.rig.puppet, shell, shown)) { "Parent transform cannot place the reference" }
        val drawable = shell.copy(mesh = DrawableMesh(positions, floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), intArrayOf(0, 1, 2, 0, 2, 3)))
        val bounds = layer.bounds
        val puppet = PuppetModel(parameters = emptyList(), parts = emptyList(), deformers = emptyList(), rootPartId = null,
            drawables = listOf(drawable), rootChildren = listOf(OrgChild.Drawable(id)),
            atlas = PuppetAtlas(tiles = listOf(AtlasTile(tile, layer.name, layer.raster.width, layer.raster.height,
                source = SourceLayerRef(ArtSourceId(PuppetSourceAtlas.SOURCE_ID_RAW), layer.id.raw, layer.idIsStable)))),
            sources = listOf(ArtSource(ArtSourceId(PuppetSourceAtlas.SOURCE_ID_RAW), "artwork.psd", null, "psd", layers = listOf(
                ArtSourceLayer(layer.id.raw, layer.name, layer.groupPath, bounds.left, bounds.top, bounds.width, bounds.height, layer.visible)))))
        val rig = current.rig.copy(puppet = puppet, layerIdByDrawableId = mapOf(id.raw to layer.id.raw),
            sourceBoundsByDrawableId = mapOf(id.raw to Bounds(shown.indices.step(2).minOf { shown[it] }, shown.indices.step(2).minOf { shown[it + 1] },
                shown.indices.step(2).maxOf { shown[it] }, shown.indices.step(2).maxOf { shown[it + 1] })))
        return RasterMeshCreation.encode(rig, id)
    }
}
