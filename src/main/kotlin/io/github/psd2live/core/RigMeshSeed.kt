package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.model.*

/** A generated layer's existing rest mesh. Semantic generation fits bindings to it instead of tracing pixels again. */
internal object RigMeshSeed {
    const val OP = "rig_mesh_seed"

    /** Ownership survives an explicit geometry refresh; an old creation record must never take the layer back. */
    fun adopted(overlay: RigEditOverlay): Map<String, String> = overlay.authoringJournal.mapNotNull { record ->
        if (record["op"]?.jsonPrimitive?.contentOrNull != OP) null else (record["mesh"] as? JsonObject)?.let {
            it.getValue("layer_id").jsonPrimitive.content to it.getValue("id").jsonPrimitive.content
        }
    }.toMap()

    /** A newly adopted authored mesh has a plain-mesh baseline, so semantic generation can add bindings to it. */
    fun previous(model: PuppetModel, overlay: RigEditOverlay): PuppetModel {
        val seeds = layers(overlay).values.filter { seed -> model.drawables.none { it.id == seed.drawableId } }
        if (seeds.isEmpty()) return model
        val added = seeds.mapNotNull { seed ->
            val tile = model.atlas.tiles.firstOrNull { it.source?.layerKey == seed.layerId } ?: return@mapNotNull null
            val shell = Drawable(seed.drawableId, seed.name, null, BlendMode.Normal, emptyList(), null, null,
                atlasTileId = tile.id, texturePage = tile.placement?.pageIndex ?: -1)
            shell.copy(mesh = DrawableMesh(seed.positions, RasterMeshJournal.TextureCoordinates(model, shell).toUvs(seed.canvasUvs), seed.triangles))
        }
        return model.copy(drawables = model.drawables + added,
            rootChildren = model.rootChildren + added.map { OrgChild.Drawable(it.id) }).withDerivedRenderRoot()
    }

    fun layers(overlay: RigEditOverlay): Map<String, ResolvedPart> = buildMap {
        for (record in overlay.authoringJournal) {
            if (record["op"]?.jsonPrimitive?.contentOrNull != OP) continue
            val mesh = record.getValue("mesh")
            if (mesh == JsonNull) remove(record.getValue("layer_id").jsonPrimitive.content)
            else ArtPrimitiveV2.decodePrimitive(mesh.jsonObject, 0).let { put(it.layerId, it) }
        }
    }

    fun refresh(layers: Set<String>): List<JsonObject> = layers.sorted().map { layer ->
        buildJsonObject { put("op", OP); put("layer_id", layer); put("mesh", JsonNull) }
    }

    fun capture(model: RigPreviewModel, layers: Set<String>): List<JsonObject> {
        val base = model.baseRig
        val missing = layers - base.layerIdByDrawableId.values.toSet()
        val rigs = listOf(base) + if (missing.isEmpty()) emptyList() else listOf(model.authored.rig)
        val captured = HashSet<String>()
        return rigs.flatMap { rig -> rig.puppet.drawables.mapNotNull { drawable ->
            val layer = rig.layerIdByDrawableId[drawable.id.raw]?.takeIf { it in layers } ?: return@mapNotNull null
            if (!captured.add(layer)) return@mapNotNull null
            val mesh = drawable.mesh ?: return@mapNotNull null
            val texture = RasterMeshJournal.TextureCoordinates(rig.puppet, drawable)
            val world = requireNotNull(drawableSpaceMapping(rig.puppet, emptyMap(), drawable.id)).localToWorld(mesh.positions)
            val canvas = FloatArray(world.size) { if (it % 2 == 0) world[it] else -world[it] }
            val semantic = model.analysis.layers.single { it.source.id.raw == layer }.semantic
            fun floats(values: FloatArray) = JsonArray(values.map(::JsonPrimitive))
            val bounds = texture.layer
            val primitive = buildJsonObject {
                put("id", drawable.id.raw); put("layer_id", layer); put("source_id", texture.sourceId.raw); put("name", drawable.name)
                put("parent", JsonNull)
                put("source_bounds", floats(floatArrayOf(bounds.left.toFloat(), bounds.top.toFloat(),
                    (bounds.left + bounds.width).toFloat(), (bounds.top + bounds.height).toFloat())))
                put("neutral_bounds", floats(floatArrayOf(canvas.indices.step(2).minOf { canvas[it] }, canvas.indices.step(2).minOf { canvas[it + 1] },
                    canvas.indices.step(2).maxOf { canvas[it] }, canvas.indices.step(2).maxOf { canvas[it + 1] })))
                put("classification", ArtPrimitiveV2.encodeClassification(LayerClassificationOverride(semantic.type, semantic.tag, semantic.side,
                    semantic.parameter, semantic.switchId)))
                putJsonObject("mesh") {
                    put("canvas_positions", floats(canvas)); put("canvas_uvs", floats(texture.toCanvas(mesh.uvs)))
                    put("triangles", JsonArray(mesh.indices.map(::JsonPrimitive)))
                }
                put("fixed_topology", true); put("frozen_axes", JsonArray(emptyList())); put("authored", JsonObject(emptyMap()))
            }
            buildJsonObject { put("op", OP); put("mesh", primitive) }
        } }
    }
}
