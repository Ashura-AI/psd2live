package io.github.psd2live.application

import io.github.psd2live.core.RigCanvasSupport
import io.github.psd2live.core.RigHitArea
import io.github.psd2live.core.RigHitAreas
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.WorkspaceDocument
import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterId

/**
 * The document's hit areas ([io.github.psd2live.core.RigEditOverlay.hitAreas]): the inspector, the hierarchy menu and the
 * public operations edit them through the same candidates.
 */
internal object WorkspaceHitAreaEdits {
    const val PUT = "hit_area_put"
    const val DELETE = "hit_area_delete"
    const val PRESET = "hit_area_preset"
    val supported = setOf(PUT, DELETE, PRESET)

    fun put(id: String, name: String? = null, meshes: List<String>? = null, newId: String? = null) = buildJsonObject {
        put("id", id); name?.let { put("name", it) }; newId?.let { put("new_id", it) }
        meshes?.let { list -> putJsonArray("meshes") { list.forEach { add(JsonPrimitive("mesh:$it")) } } }
    }

    fun delete(id: String) = buildJsonObject { put("id", id) }

    fun apply(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, model: RigPreviewModel): WorkspaceDocument {
        val request = operation.request
        validateOperationSchema(request, WorkspaceHitAreaSchemas.request(operation.operation))
        val areas = document.rigEdits.hitAreas
        val next = when (operation.operation) {
            PUT -> put(areas, model, request)
            DELETE -> {
                val id = request.getValue("id").jsonPrimitive.content
                require(areas.any { it.id == id }) { "Hit area not found: $id" }
                areas.filterNot { it.id == id }
            }
            PRESET -> {
                val presets = RigHitAreas.presets(model.rig.puppet, model.analysis, model.rig.layerIdByDrawableId)
                require(presets.isNotEmpty()) { "The rig has no face, top, neck or bottom mesh to set up Head and Body on" }
                areas + presets.filter { preset -> areas.none { it.id == preset.id } }
            }
            else -> throw IllegalArgumentException("Unknown hit area operation: ${operation.operation}")
        }
        RigHitAreas.problem(next)?.let { throw IllegalArgumentException(it) }
        return if (next == areas) document else document.copy(rigEdits = document.rigEdits.copy(hitAreas = next))
    }

    private fun put(areas: List<RigHitArea>, model: RigPreviewModel, request: JsonObject): List<RigHitArea> {
        val id = request.getValue("id").jsonPrimitive.content
        val current = areas.firstOrNull { it.id == id }
        // A mesh the area names already may be gone (export leaves it out and reports it); only a mesh it gains must exist.
        val meshes = request["meshes"]?.jsonArray?.map { element ->
            val raw = element.jsonPrimitive.content.removePrefix("mesh:")
            require(current?.meshes?.contains(raw) == true || model.rig.puppet.drawables.any { it.id.raw == raw && it.mesh != null }) {
                "Mesh not found: $raw"
            }
            raw
        }?.distinct()
        require(current != null || meshes != null) { "A new hit area needs meshes" }
        val newId = request["new_id"]?.jsonPrimitive?.content ?: id
        require(newId == id || areas.none { it.id == newId }) { "Hit area ID already exists: $newId" }
        val area = RigHitArea(newId, request["name"]?.jsonPrimitive?.content?.trim() ?: current?.name ?: defaultName(newId),
            meshes ?: current!!.meshes)
        val rest = areas.filterNot { it.id == id }
        val before = when {
            "before_id" in request -> request["before_id"]?.jsonPrimitive?.contentOrNull
            current != null -> areas.getOrNull(areas.indexOf(current) + 1)?.id
            else -> null
        }
        val at = before?.let { target -> rest.indexOfFirst { it.id == target }.also { require(it >= 0) { "Hit area not found: $target" } } } ?: rest.size
        return rest.subList(0, at) + area + rest.subList(at, rest.size)
    }

    /** `HitAreaHead` is named `Head`, as Cubism's samples name their areas. */
    fun defaultName(id: String): String = id.removePrefix("HitArea").ifEmpty { id }

    /** What [areas] report in a listing: each area with the meshes [model] has and those it names that are gone. */
    fun listing(areas: List<RigHitArea>, model: RigPreviewModel?) = JsonArray(areas.map { area ->
        val present = model?.let { RigHitAreas.present(area, it.rig.puppet) } ?: area.meshes
        buildJsonObject {
            put("id", area.id); put("name", area.name)
            putJsonArray("meshes") { present.forEach { add(JsonPrimitive("mesh:$it")) } }
            putJsonArray("missing") { (area.meshes - present.toSet()).forEach { add(JsonPrimitive("mesh:$it")) } }
        }
    })

    /** The area at canvas point ([x], [y]) of [model] posed at [pose], as the runtime tests it; null on a miss. */
    fun test(areas: List<RigHitArea>, model: RigPreviewModel, pose: Map<ParameterId, Float>, x: Float, y: Float): RigHitArea? =
        RigHitAreas.areaAt(areas, model.rig.puppet, RigCanvasSupport.evaluateExact(model, pose), x, -y)
}

internal object WorkspaceHitAreaSchemas {
    private val s = WorkspaceResultSchema
    private val id = buildJsonObject { put("type", "string"); put("pattern", RigHitAreas.ID.pattern) }

    fun request(operation: String): JsonObject = when (operation) {
        WorkspaceHitAreaEdits.PUT -> s.obj(mapOf("id" to id, "new_id" to id,
            "name" to buildJsonObject { put("type", "string"); put("minLength", 1); put("maxLength", RigHitAreas.MAX_NAME) },
            "meshes" to s.array(s.handle(), 1, 256), "before_id" to s.nullable(id)), setOf("id"))
        WorkspaceHitAreaEdits.DELETE -> s.obj(mapOf("id" to id))
        WorkspaceHitAreaEdits.PRESET -> s.obj(emptyMap())
        else -> error("Unknown hit area operation")
    }

    val result = s.obj(s.identity + mapOf("applied" to s.constant(false), "changed" to s.array(s.handle())), s.identity.keys)

    val area = s.obj(mapOf("id" to s.handle(), "name" to s.handle(), "meshes" to s.array(s.handle()), "missing" to s.array(s.handle())))
    val listResult = s.obj(mapOf("state" to s.handle(), "hit_areas" to s.array(area), "automatic" to s.boolean()))
    val testRequest = s.obj(mapOf("x" to s.number(), "y" to s.number(), "pose" to s.dictionary(s.number())), setOf("x", "y"))
    val testResult = s.obj(mapOf("state" to s.handle(), "hit" to s.boolean(), "id" to s.handle(), "name" to s.handle(),
        "meshes" to s.array(s.handle())), setOf("state", "hit"))
}

internal fun registerHitAreaOperations(registry: WorkspaceOperationRegistry, port: WorkspaceDocumentPort, read: WorkspaceReadPort) {
    fun withState(business: JsonObject) = JsonObject(business + mapOf(
        "properties" to JsonObject(business.getValue("properties").jsonObject + ("state" to WorkspaceResultSchema.handle())),
        "required" to JsonArray(business.getValue("required").jsonArray + JsonPrimitive("state"))))
    val order = " Areas are tested in list order and the first hit wins; with none set up, the .p2lrt runtime falls back to " +
        "automatic Head and Body areas and model3.json has no HitAreas."
    for ((operation, description) in listOf(
        WorkspaceHitAreaEdits.PUT to "Create or change a hit area: id (Cubism ID alphabet, e.g. HitAreaHead), name (what a host asks for, e.g. Head; " +
            "defaults to the id without HitArea), meshes (mesh:<id> handles; required for a new area, replaces the list), new_id renames the area, " +
            "before_id places it before another area (null: last). Export writes model3.json HitAreas (one ArtMesh per entry, so several meshes become " +
            "several entries under one name) and the .p2lrt HITA chunk.",
        WorkspaceHitAreaEdits.DELETE to "Delete a hit area by id.",
        WorkspaceHitAreaEdits.PRESET to "Add Head (the face) and Body (the top, else the neck or the bottom) as the generated rig suggests, " +
            "keeping areas that already have those ids.",
    )) {
        registry.register(WorkspaceOperationDefinition(operation, description + order, withState(WorkspaceHitAreaSchemas.request(operation)),
            WorkspaceOperationKind.DOCUMENT, batchable = true, destructive = operation == WorkspaceHitAreaEdits.DELETE,
            resultSchema = WorkspaceHitAreaSchemas.result)) { request, context ->
            val edit = WorkspaceDocumentOperation(operation, JsonObject(request - "state"))
            WorkspaceOperationOutput(port.applyDocumentEdits(request.getValue("state").jsonPrimitive.content,
                "Updated hit areas", listOf(edit), context.author).compact())
        }
    }
    registry.register(WorkspaceOperationDefinition("hit_area_list",
        "List the document's hit areas in test order from one committed capture: each with its present meshes and the meshes it names " +
            "that the rig no longer has (export leaves those out). automatic=true when none is set up and the .p2lrt runtime uses its own Head and Body.",
        WorkspaceResultSchema.obj(emptyMap()), WorkspaceOperationKind.QUERY, resultSchema = WorkspaceHitAreaSchemas.listResult)) { _, _ ->
        val queries = read.captureQueries()
        val areas = queries.hitAreas()
        WorkspaceOperationOutput(buildJsonObject {
            put("state", queries.snapshot().state)
            put("hit_areas", WorkspaceHitAreaEdits.listing(areas, queries.hitAreaModel()))
            put("automatic", areas.isEmpty())
        })
    }
    registry.register(WorkspaceOperationDefinition("hit_area_test",
        "Hit-test the committed model at canvas point x, y (canvas units, y down) posed at pose (parameter values; others at default), as the " +
            "runtime does: the first area with a visible, not fully transparent mesh whose triangles contain the point. hit=false on a miss.",
        WorkspaceHitAreaSchemas.testRequest, WorkspaceOperationKind.QUERY, resultSchema = WorkspaceHitAreaSchemas.testResult)) { request, _ ->
        val queries = read.captureQueries()
        val model = requireNotNull(queries.hitAreaModel()) { "No workspace is loaded" }
        val pose = request["pose"]?.jsonObject.orEmpty().map { (key, value) -> ParameterId(key) to value.jsonPrimitive.float }.toMap()
        val hit = WorkspaceHitAreaEdits.test(queries.hitAreas(), model, pose, request.getValue("x").jsonPrimitive.float, request.getValue("y").jsonPrimitive.float)
        WorkspaceOperationOutput(buildJsonObject {
            put("state", queries.snapshot().state); put("hit", hit != null)
            hit?.let { area ->
                put("id", area.id); put("name", area.name)
                putJsonArray("meshes") { RigHitAreas.present(area, model.rig.puppet).forEach { add(JsonPrimitive("mesh:$it")) } }
            }
        })
    }
}
