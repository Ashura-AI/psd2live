package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.umamo.format.art.SourceArt

/** Explicit semantic generation over the complete editable workspace, preserving existing mesh topology. */
internal object WorkspaceGenerationEdits {
    const val GENERATE = "rig_generate"

    fun generate(document: WorkspaceDocument, model: RigPreviewModel, refresh: Set<String>?): WorkspaceDocument {
        val layout = document.layout
        val generation = layout.generation
        if (!generation.generated) {
            // The first generation reads every layer as it is now.
            return seeded(document, model).copy(layout = layout.copy(generation = WorkspaceGeneration.Generated),
                layerOverrides = document.layerOverrides + generation.pendingClassifications, generationSource = null, meshSource = null)
        }
        // The reference layers stay out of every generation.
        val pending = WorkspaceLayoutPreview.pendingIds(document) - layout.referenceLayerIds
        val refreshed = (refresh ?: generation.refreshLayerIds).toSet()
        refreshed.forEach { id ->
            require(document.source.layers.any { it.id.raw == id } && id !in document.deletedLayerIds) { "Layer not found: $id" }
            require(id !in pending && id !in layout.referenceLayerIds) { "Layer $id has not been generated yet" }
        }
        if (pending.isEmpty() && generation.pendingClassifications.isEmpty() && refreshed.isEmpty()) return document
        // Absent from the frozen generation input, a layer generates from its current pixels.
        val drop = pending + refreshed
        val seeded = seeded(document, model)
        return seeded.copy(
            rigEdits = seeded.rigEdits.let { it.copy(authoringJournal = it.authoringJournal + RigMeshSeed.refresh(refreshed)) },
            layerOverrides = document.layerOverrides + generation.pendingClassifications,
            generationSource = document.generationSource?.without(drop),
            meshSource = document.meshSource?.without(drop),
            layout = layout.copy(generation = generation.copy(pendingLayerIds = emptySet(), pendingClassifications = emptyMap(),
                refreshLayerIds = generation.refreshLayerIds - refreshed)),
        )
    }

    private fun seeded(document: WorkspaceDocument, model: RigPreviewModel): WorkspaceDocument {
        val pending = (WorkspaceLayoutPreview.pendingIds(document) + document.layout.generation.pendingClassifications.keys) - document.layout.referenceLayerIds
        val records = RigMeshSeed.capture(model, pending)
        return if (records.isEmpty()) document else document.copy(rigEdits = document.rigEdits.copy(
            authoringJournal = document.rigEdits.authoringJournal + records))
    }

    private fun SourceArt.without(ids: Set<String>): SourceArt =
        if (layers.none { it.id.raw in ids }) this else WorkspaceSourceArt(widthPx, heightPx, layers.filter { it.id.raw !in ids }, groups)

    /**
     * [document] after the image of generated layer [id] was replaced: the rig keeps its geometry, and the next
     * generation may read the new pixels instead ([WorkspaceGeneration.refreshLayerIds]).
     */
    fun replacedGenerated(document: WorkspaceDocument, id: String): WorkspaceDocument {
        val generation = document.layout.generation
        if (!generation.generated || id in generation.refreshLayerIds || id in WorkspaceLayoutPreview.pendingIds(document)) return document
        return document.copy(layout = document.layout.copy(generation = generation.copy(refreshLayerIds = generation.refreshLayerIds + id)))
    }

    val schema: JsonObject by lazy {
        buildJsonObject {
            put("type", "object"); put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("state") { put("type", "string"); put("minLength", 1) }
                putJsonObject("refresh_geometry") {
                    put("type", "array"); put("maxItems", 1024); putJsonObject("items") { put("type", "string"); put("minLength", 1) }
                    put("description", "Generated layers to mesh again from their current pixels (their image was replaced since). Omitted: every layer whose image was replaced; [] keeps all geometry.")
                }
            }
            put("required", JsonArray(listOf(JsonPrimitive("state"))))
        }
    }

    const val DESCRIPTION = "Generate the rig, or generate it again. A project created empty for Layout is not generated until this runs; " +
        "afterwards, layers placed since the last generation, classification and tag changes, and replaced images whose geometry should refresh " +
        "wait for it (workspace_inspect scope=generation lists them). The generators then make the rig from the layers as they are now and " +
        "merge it onto the user's edits: what the user left follows the new output, the user's keyforms, warps, physics, swings, skeleton and " +
        "glue stay, existing meshes keep their topology, and what does not carry over cleanly is reported in quality.regeneration. " +
        "Editing and export are available before generation too. One history edit."
}
