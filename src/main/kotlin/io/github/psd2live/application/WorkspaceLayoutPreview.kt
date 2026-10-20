package io.github.psd2live.application

import io.github.psd2live.project.WorkspaceDocument

/** Layout generation status. Waiting for semantic generation never removes a mesh from the editing model. */
internal object WorkspaceLayoutPreview {
    fun pendingIds(document: WorkspaceDocument): Set<String> {
        val generation = document.layout.generation
        val ids = document.source.layers.mapTo(LinkedHashSet()) { it.id.raw } - document.deletedLayerIds
        if (!generation.generated) return ids
        return (generation.pendingLayerIds + (document.layout.referenceLayerIds - WorkspaceReferenceMeshes.meshed(document)))
            .filterTo(LinkedHashSet()) { it in ids }
    }

    /** IDs used by old, display-only Layout meshes; new workspace models never allocate them. */
    const val MESH_PREFIX = "LayoutMesh"
    fun isPendingMesh(id: String): Boolean = id.startsWith(MESH_PREFIX)
}
