package io.github.psd2live.application

import kotlinx.serialization.json.*
import org.umamo.edit.MeshTopology

/** The topology canvas_topology names by index: rest vertices, triangles and outline edges of one mesh. */
internal object WorkspaceMeshTopology {
    const val ID = "mesh_get_topology"
    const val MAX_LIMIT = 4096
    val SECTIONS = listOf("vertices", "triangles", "outline")
    private val s = WorkspaceResultSchema

    val request = s.obj(mapOf(
        "target" to JsonObject(s.handle() + ("description" to JsonPrimitive("mesh:ID"))),
        "section" to JsonObject(s.choices(*SECTIONS.toTypedArray()) + ("description" to JsonPrimitive(
            "What items holds: vertices as [index, x, y] (default), triangles as [index, a, b, c], or the outline edges as [a, b]."))),
        "offset" to s.integer(0),
        "limit" to JsonObject(s.integer(1, MAX_LIMIT) + ("description" to JsonPrimitive("Default 1024."))),
    ), setOf("target"))

    val result = s.obj(mapOf(
        "project_id" to s.handle(), "state" to s.handle(), "revision_id" to s.handle(), "target" to s.handle(),
        "coordinate_space" to s.constant("mesh_rest"), "vertex_count" to s.integer(0), "triangle_count" to s.integer(0),
        "outline_edge_count" to s.integer(0), "bounds" to s.vector(4), "section" to s.choices(*SECTIONS.toTypedArray()),
        "items" to s.array(s.array(s.number(), 2, 4), 0, MAX_LIMIT), "next_offset" to s.integer(1),
    ), setOf("project_id", "state", "revision_id", "target", "coordinate_space", "vertex_count", "triangle_count",
        "outline_edge_count", "bounds", "section", "items"))

    /**
     * The vertices [command] leaves selected in the editor - what add_points creates, where knife cuts, what merge
     * joins - by their indices in the mesh it makes. The same pure op the reducer runs, asked of the same mesh.
     */
    fun newVertices(puppet: org.umamo.runtime.model.PuppetModel, command: JsonObject): List<Int> {
        val drawable = puppet.drawables.firstOrNull { it.id.raw == command.getValue("id").jsonPrimitive.content } ?: return emptyList()
        val mesh = drawable.mesh ?: return emptyList()
        val edges = command["edges"]?.jsonArray?.mapTo(LinkedHashSet()) { pair ->
            org.umamo.edit.MeshElement.Edge.of(pair.jsonArray[0].jsonPrimitive.int, pair.jsonArray[1].jsonPrimitive.int)
        }.orEmpty()
        val result = io.github.psd2live.core.CanvasTopology.build(mesh, command.getValue("action").jsonPrimitive.content,
            command["vertices"]?.jsonArray?.mapTo(LinkedHashSet()) { it.jsonPrimitive.int }.orEmpty(),
            io.github.psd2live.core.CanvasTopology.parseAnchors(command["anchors"]?.jsonArray), edges) {
            io.github.psd2live.core.CanvasTopology.shapes(puppet, drawable.id)
        }
        return io.github.psd2live.core.CanvasTopology.selectedVertices(result).sorted()
    }

    fun read(queries: WorkspaceQueries, request: JsonObject): JsonObject {
        val target = request.getValue("target").jsonPrimitive.content
        require(target.startsWith("mesh:") && target.length > 5) { "Use a mesh:ID handle" }
        val snapshot = queries.snapshot()
        val puppet = requireNotNull(queries.currentPuppet()) { "No model is loaded" }
        val id = target.substringAfter(':')
        val drawable = requireNotNull(puppet.drawables.firstOrNull { it.id.raw == id }) { "Mesh not found: $id" }
        val mesh = requireNotNull(drawable.mesh) { "$id has no mesh" }
        val outline = MeshTopology.edgeUses(mesh.indices).filterValues { it.size == 1 }.keys
            .sortedWith(compareBy({ it.endpointLow }, { it.endpointHigh }))
        val section = request["section"]?.jsonPrimitive?.content ?: "vertices"
        val total = when (section) { "vertices" -> mesh.vertexCount; "triangles" -> mesh.triangleCount; else -> outline.size }
        val offset = request["offset"]?.jsonPrimitive?.int ?: 0
        val limit = request["limit"]?.jsonPrimitive?.int ?: 1024
        require(offset in 0..total) { "offset must be within 0..$total" }
        val end = minOf(offset + limit, total)
        val p = mesh.positions
        return buildJsonObject {
            put("project_id", requireNotNull(snapshot.projectId)); put("state", snapshot.state); put("revision_id", snapshot.revisionId)
            put("target", target); put("coordinate_space", "mesh_rest")
            put("vertex_count", mesh.vertexCount); put("triangle_count", mesh.triangleCount); put("outline_edge_count", outline.size)
            putJsonArray("bounds") {
                val xs = p.indices.step(2).map { p[it] }; val ys = p.indices.step(2).map { p[it + 1] }
                listOf(xs.min(), ys.min(), xs.max(), ys.max()).forEach { add(it) }
            }
            put("section", section)
            putJsonArray("items") { for (i in offset until end) add(when (section) {
                "vertices" -> buildJsonArray { add(i); add(p[i * 2]); add(p[i * 2 + 1]) }
                "triangles" -> buildJsonArray { add(i); for (k in 0 until 3) add(mesh.indices[i * 3 + k]) }
                else -> buildJsonArray { add(outline[i].endpointLow); add(outline[i].endpointHigh) }
            }) }
            if (end < total) put("next_offset", end)
        }
    }
}

internal fun registerMeshTopologyOperations(registry: WorkspaceOperationRegistry, read: WorkspaceReadPort) {
    registry.register(WorkspaceOperationDefinition(WorkspaceMeshTopology.ID,
        "Read one mesh's topology from one committed capture, as canvas_topology names it: vertex indices with their rest " +
            "positions (the mesh's own space, the one add_points anchors use), triangles by vertex index, and the outline edges " +
            "(used by one triangle). section picks the list in items, paged by offset and limit; counts and bounds always come back. " +
            "Pass the returned state to canvas_topology so the indices still hold.",
        WorkspaceMeshTopology.request, WorkspaceOperationKind.QUERY, resultSchema = WorkspaceMeshTopology.result)) { request, _ ->
        WorkspaceOperationOutput(WorkspaceMeshTopology.read(read.captureQueries(), request))
    }
}
