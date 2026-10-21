package io.github.psd2live.application

import io.github.psd2live.core.*
import kotlinx.serialization.json.*

/** Request and result contracts of the expression and pose operations. */
internal object WorkspaceSidecarSchemas {
    private val s = WorkspaceResultSchema
    private fun text() = buildJsonObject { put("type", "string"); put("minLength", 1) }
    private fun id() = buildJsonObject { put("type", "string"); put("pattern", "^[A-Za-z0-9_-]{1,64}$") }
    private fun name() = buildJsonObject { put("type", "string"); put("minLength", 1); put("maxLength", ModelExpression.MAX_NAME) }
    private fun fade() = buildJsonObject { put("type", "number"); put("minimum", 0); put("maximum", ModelExpression.MAX_FADE) }
    private fun number() = buildJsonObject { put("type", "number") }
    private fun boolean() = buildJsonObject { put("type", "boolean") }
    private fun index() = buildJsonObject { put("type", "integer"); put("minimum", 0) }
    private fun blend() = buildJsonObject { put("type", "string"); put("enum", JsonArray(ExpressionBlend.entries.map { JsonPrimitive(it.name) })) }
    private fun array(item: JsonObject, min: Int, max: Int) = buildJsonObject {
        put("type", "array"); put("items", item); put("minItems", min); put("maxItems", max)
    }
    private fun obj(fields: Map<String, JsonObject>, required: List<String>) = buildJsonObject {
        put("type", "object"); put("additionalProperties", false); put("properties", JsonObject(fields))
        put("required", JsonArray(required.map(::JsonPrimitive)))
    }
    private val pose = buildJsonObject { put("type", "object"); put("additionalProperties", number()) }
    private val entry = obj(mapOf("parameter" to text(), "blend" to blend(), "value" to number()), listOf("parameter", "blend", "value"))
    private val expression = obj(mapOf("id" to id(), "name" to name(), "fade_in" to fade(), "fade_out" to fade(),
        "entries" to array(entry, 0, ModelExpression.MAX_ENTRIES)), listOf("id", "name", "entries"))
    private val entryChange = obj(mapOf("parameter" to text(), "blend" to blend(), "value" to number(), "remove" to boolean()), listOf("parameter"))
    private val posePart = obj(mapOf("part" to text(), "links" to array(text(), 0, ModelPose.MAX_GROUPS)), listOf("part"))
    private val group = array(posePart, 1, ModelPose.MAX_GROUPS)
    private val poseValue = obj(mapOf("fade_in" to fade(), "groups" to array(group, 0, ModelPose.MAX_GROUPS)), listOf("groups"))
    private val groupParts = array(buildJsonObject { putJsonArray("oneOf") { add(text()); add(posePart) } }, 1, ModelPose.MAX_GROUPS)

    /** The business fields of each edit, without the request context. */
    val requests: Map<String, JsonObject> = mapOf(
        "expression_create" to obj(mapOf("id" to id(), "name" to name(), "values" to pose, "entries" to array(entry, 0, ModelExpression.MAX_ENTRIES),
            "fade_in" to fade(), "fade_out" to fade(), "index" to index()), emptyList()),
        "expression_put" to obj(mapOf("expression" to expression), listOf("expression")),
        "expression_update" to obj(mapOf("id" to text(), "name" to name(), "fade_in" to fade(), "fade_out" to fade()), listOf("id")),
        "expression_set_entries" to obj(mapOf("id" to text(), "entries" to array(entryChange, 1, ModelExpression.MAX_ENTRIES)), listOf("id", "entries")),
        "expression_capture" to obj(mapOf("id" to text(), "values" to pose), listOf("id", "values")),
        "expression_delete" to obj(mapOf("id" to text()), listOf("id")),
        "expression_duplicate" to obj(mapOf("id" to text(), "new_id" to id()), listOf("id")),
        "expression_move" to obj(mapOf("id" to text(), "index" to index()), listOf("id", "index")),
        "expression_reset" to obj(emptyMap(), emptyList()),
        "pose_put" to obj(mapOf("pose" to poseValue), listOf("pose")),
        "pose_add_group" to obj(mapOf("parts" to groupParts, "index" to index()), listOf("parts")),
        "pose_set_group" to obj(mapOf("index" to index(), "parts" to groupParts), listOf("index", "parts")),
        "pose_delete_group" to obj(mapOf("index" to index()), listOf("index")),
        "pose_update" to obj(mapOf("fade_in" to fade()), listOf("fade_in")),
        "pose_clear" to obj(emptyMap(), emptyList()),
    )

    val descriptions: Map<String, String> = mapOf(
        "expression_create" to "Create an expression (exp3). values is a parameter pose, such as preview_get or a snapshot returns: every parameter away from its default becomes an Add entry of its offset, as Cubism's animation view makes one. entries gives exact entries instead; with neither it is empty, the neutral expression a host resets to. from_snapshot reads a saved snapshot's values, from_current the current authored pose (single operation only). Fades default to 0.5 s. Until the first edit the model has the standard expressions made from its face parameters; any edit takes them into the document.",
        "expression_put" to "Replace the expression with this ID, or append it. Entries name existing parameters; Add adds value, Multiply multiplies by it, Overwrite sets it.",
        "expression_update" to "Rename an expression or change its fade-in and fade-out seconds. A taken name gets the first free number.",
        "expression_set_entries" to "Add, change or remove entries in order. A changed blend without a value converts the value so the parameter at its default ends where it did; remove drops the entry. A new entry without a value starts at no change (Add 0, Multiply 1, Overwrite the default).",
        "expression_capture" to "Replace an expression's entries with the pose values, as expression_create values does.",
        "expression_delete" to "Delete an expression.",
        "expression_duplicate" to "Copy an expression under new_id (or the next free exp_N) and a unique name, right after it.",
        "expression_move" to "Move an expression to index in the list; the model's settings list expressions in this order.",
        "expression_reset" to "Drop the document's expressions: the model has the standard expressions made from its face parameters again.",
        "pose_put" to "Replace the pose (pose3): groups of parts of which one shows at a time, each part with links that show and hide with it, and fade_in seconds (default 0.5). A part is in one group once. An empty list removes the pose.",
        "pose_add_group" to "Add a group of parts (IDs, or {part, links}) at index (default last); at load its first part shows.",
        "pose_set_group" to "Replace the parts of pose group index.",
        "pose_delete_group" to "Delete pose group index; the pose goes once it has no group.",
        "pose_update" to "Change the seconds a switch inside a pose group fades over.",
        "pose_clear" to "Remove the pose.",
    )

    private val entryResult = s.obj(mapOf("parameter" to s.handle(), "blend" to s.choices(*ExpressionBlend.entries.map { it.name }.toTypedArray()), "value" to s.number()))
    private val expressionResult = s.obj(mapOf("id" to s.handle(), "name" to s.handle(), "fade_in" to s.number(0), "fade_out" to s.number(0),
        "entries" to s.array(entryResult)))
    private val poseResult = s.obj(mapOf("fade_in" to s.number(0), "groups" to s.array(s.array(
        s.obj(mapOf("part" to s.handle(), "links" to s.array(s.handle())), setOf("part"))))))
    val edit = s.obj(s.identity + mapOf("applied" to s.constant(false), "changed" to s.array(s.handle(), 1, Int.MAX_VALUE)), s.identity.keys)
    val list = s.obj(mapOf("project_id" to s.handle(), "state" to s.handle(), "standard" to s.boolean(), "expressions" to s.array(expressionResult)))
    val get = s.obj(mapOf("project_id" to s.handle(), "state" to s.handle(), "standard" to s.boolean(), "expression" to expressionResult))
    val poseGet = s.obj(mapOf("project_id" to s.handle(), "state" to s.handle(), "pose" to poseResult,
        "missing_parts" to s.array(s.handle())), setOf("project_id", "state", "missing_parts"))
    val events = s.obj(mapOf("project_id" to s.handle(), "state" to s.handle(), "events" to s.array(s.obj(mapOf(
        "clip_id" to s.handle(), "clip" to s.handle(), "time" to s.number(0), "value" to s.string()))),
        "user_data" to s.array(s.obj(mapOf("target" to s.constant("ArtMesh"), "id" to s.handle(), "value" to s.string())))))
}

/**
 * The expression and pose operations: each edit is the same candidate the panel and an atomic batch use
 * ([WorkspaceSidecarEdits]), committed as one history node; the reads return the captured model's sidecar data.
 */
internal fun registerSidecarOperations(registry: WorkspaceOperationRegistry, workspace: WorkspaceBackend, auxiliary: WorkspaceAuxiliaryPort) {
    val state = buildJsonObject { put("type", "string"); put("minLength", 1) }
    for ((id, business) in WorkspaceSidecarSchemas.requests) {
        // Only the single operation reads a snapshot or the current pose; a batch member carries its values.
        val extra = if (id == "expression_create") mapOf("from_snapshot" to state, "from_current" to buildJsonObject { put("type", "boolean") }) else emptyMap()
        val properties = business.getValue("properties").jsonObject + extra + ("state" to state)
        val schema = JsonObject(business + mapOf("properties" to JsonObject(properties),
            "required" to JsonArray(listOf(JsonPrimitive("state")) + business.getValue("required").jsonArray)))
        registry.register(WorkspaceOperationDefinition(id, WorkspaceSidecarSchemas.descriptions.getValue(id), schema, WorkspaceOperationKind.DOCUMENT,
            batchable = true, destructive = id in setOf("expression_delete", "expression_reset", "pose_delete_group", "pose_clear"),
            resultSchema = WorkspaceSidecarSchemas.edit)) { request, context ->
            var fields = JsonObject(request - "state" - extra.keys)
            if (id == "expression_create") {
                val sources = listOf("values", "entries", "from_snapshot", "from_current").filter { it in request && request[it] != JsonPrimitive(false) }
                require(sources.size <= 1) { "Give one of values, entries, from_snapshot or from_current" }
                val values = when {
                    "from_snapshot" in sources -> {
                        val snapshotId = request.getValue("from_snapshot").jsonPrimitive.content
                        auxiliary.savedProjectData().data.parameterSnapshots.firstOrNull { it.id == snapshotId }?.values
                            ?: throw IllegalArgumentException("Snapshot not found: $snapshotId")
                    }
                    "from_current" in sources -> workspace.snapshot().parameters.associate { org.umamo.runtime.model.ParameterId(it.id) to it.current }
                    else -> null
                }
                if (values != null) fields = JsonObject(fields + ("values" to JsonObject(values.entries.associate { it.key.raw to JsonPrimitive(it.value) })))
            }
            validateOperationSchema(fields, business)
            val before = workspace.modelSidecars()
            val result = workspace.applyDocumentEdits(request.getValue("state").jsonPrimitive.content,
                WorkspaceSidecarSchemas.descriptions.getValue(id).substringBefore('.').take(80), listOf(WorkspaceDocumentOperation(id, fields)), context.author)
            val changed = if (!result.applied) emptyList() else if (id.startsWith("pose_")) listOf("pose") else {
                val after = workspace.modelSidecars().expressions
                (before.expressions.filter { it !in after }.map { it.id } + after.filter { it !in before.expressions }.map { it.id }).distinct()
            }
            WorkspaceOperationOutput(result.copy(affectedObjectIds = changed).compact())
        }
    }
    fun query(id: String, description: String, fields: Map<String, JsonObject>, required: List<String>, result: JsonObject,
              action: (WorkspaceQueries, JsonObject) -> JsonObject) {
        val schema = buildJsonObject {
            put("type", "object"); put("additionalProperties", false); put("properties", JsonObject(fields))
            put("required", JsonArray(required.map(::JsonPrimitive)))
        }
        registry.register(WorkspaceOperationDefinition(id, description, schema, WorkspaceOperationKind.QUERY, resultSchema = result)) { request, _ ->
            val queries = workspace.captureQueries()
            val snapshot = queries.snapshot()
            WorkspaceOperationOutput(JsonObject(buildJsonObject { put("project_id", requireNotNull(snapshot.projectId)); put("state", snapshot.state) } +
                action(queries, request)))
        }
    }
    query("expression_list", "List the model's expressions in export order with their entries; standard is true while they are the standard ones made from the face parameters, not yet edited.",
        emptyMap(), emptyList(), WorkspaceSidecarSchemas.list) { queries, _ ->
        val sidecars = queries.modelSidecars()
        buildJsonObject { put("standard", sidecars.standard); put("expressions", JsonArray(sidecars.expressions.map(ModelExpressions::toJson))) }
    }
    query("expression_get", "Read one expression.", mapOf("id" to state), listOf("id"), WorkspaceSidecarSchemas.get) { queries, request ->
        val sidecars = queries.modelSidecars()
        val id = request.getValue("id").jsonPrimitive.content
        val expression = sidecars.expressions.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Expression not found: $id")
        buildJsonObject { put("standard", sidecars.standard); put("expression", ModelExpressions.toJson(expression)) }
    }
    query("pose_get", "Read the pose; missing_parts lists the parts it names that the rig no longer has, which export leaves out.",
        emptyMap(), emptyList(), WorkspaceSidecarSchemas.poseGet) { queries, _ ->
        val sidecars = queries.modelSidecars()
        buildJsonObject {
            sidecars.pose?.let { put("pose", ModelPoses.toJson(it)) }
            putJsonArray("missing_parts") {
                sidecars.pose?.groups.orEmpty().flatMap { group -> group.parts.flatMap { it.ids } }.filter { it !in sidecars.partIds }.forEach { add(JsonPrimitive(it)) }
            }
        }
    }
    query("motion_events", "List the events of every authored motion in clip and time order, and the user data of every ArtMesh: what a host receives from motion3 UserData and userdata3.",
        emptyMap(), emptyList(), WorkspaceSidecarSchemas.events) { queries, _ ->
        buildJsonObject {
            putJsonArray("events") {
                for (clip in queries.motionClips()) for (event in clip.events) add(buildJsonObject {
                    put("clip_id", clip.id); put("clip", clip.name); put("time", event.time); put("value", event.value)
                })
            }
            putJsonArray("user_data") {
                for (drawable in queries.currentPuppet()?.drawables.orEmpty()) if (drawable.userData.isNotEmpty()) add(buildJsonObject {
                    put("target", "ArtMesh"); put("id", drawable.id.raw); put("value", drawable.userData)
                })
            }
        }
    }
}
