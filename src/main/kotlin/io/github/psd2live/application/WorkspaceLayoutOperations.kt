package io.github.psd2live.application

import io.github.psd2live.project.MaterialKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * Layout operations: semantic tags, variants, generation, the material library and placement. Each document
 * operation runs as a one-member document batch ([startDocumentBatch]), so the GUI and MCP commit it exactly as
 * `workspace_apply_edits` does, and it is a batch member itself.
 */
internal object WorkspaceLayoutOperations {
    /** Operations whose job result is the batch result. */
    val batches: Set<String> get() = WorkspaceLayoutEdits.supported + WorkspaceMaterialEdits.supported + IMPORT
    const val IMPORT = "material_import"
    const val LIST = "material_list"
    const val GET = "material_get"
    const val PREVIEW = "material_preview"
    const val EXPORT_PSD = "material_export_psd"
    val queries = setOf(LIST, GET, PREVIEW)

    private fun string() = buildJsonObject { put("type", "string"); put("minLength", 1) }
    private fun nullableString() = buildJsonObject { put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null")))) }
    private fun integer(min: Int, max: Int) = buildJsonObject { put("type", "integer"); put("minimum", min); put("maximum", max) }
    private fun number() = buildJsonObject { put("type", "number") }
    private fun boolean() = buildJsonObject { put("type", "boolean") }
    private fun strings(min: Int, max: Int) = buildJsonObject { put("type", "array"); put("items", string()); put("minItems", min); put("maxItems", max) }
    fun schema(fields: Map<String, JsonObject>, required: List<String>) = buildJsonObject {
        put("type", "object"); put("additionalProperties", false); put("properties", JsonObject(fields))
        put("required", JsonArray(required.map(::JsonPrimitive)))
    }
    private val rect = schema(listOf("left", "top", "width", "height").associateWith { number() }, listOf("left", "top", "width", "height"))
    private val state = mapOf("state" to string())
    private fun choices(vararg values: String) = buildJsonObject { put("type", "string"); put("enum", JsonArray(values.map(::JsonPrimitive))) }
    private fun range(min: Int, max: Int) = buildJsonObject { put("type", "number"); put("minimum", min); put("maximum", max) }
    private val point = buildJsonObject { put("type", "array"); put("items", number()); put("minItems", 2); put("maxItems", 2) }
    /** The fields of one paint gesture, as `paint` takes them. */
    private val gesture = mapOf("mode" to choices("brush", "pencil", "eraser", "bucket", "shape", "clear"),
        "points" to buildJsonObject { put("type", "array"); put("items", point); put("minItems", 1); put("maxItems", 512) },
        "radius" to range(0, 512), "hardness" to range(0, 1), "point" to point, "tolerance" to integer(0, 255),
        "from" to point, "to" to point, "shape" to choices("line", "rectangle", "ellipse"), "stroke_width" to range(1, 512), "filled" to boolean(),
        "color" to buildJsonObject { put("type", "array"); put("items", integer(0, 255)); put("minItems", 4); put("maxItems", 4) },
        "opacity" to range(0, 1))

    /** Request schemas and descriptions of the material edits. */
    val materialSchemas: Map<String, JsonObject> = mapOf(
        WorkspaceMaterialEdits.ADD to schema(state + mapOf("staging" to string(), "folder_id" to nullableString()), listOf("state", "staging")),
        WorkspaceMaterialEdits.FOLDER to schema(state + mapOf("name" to string(), "parent_id" to nullableString(), "id" to string()), listOf("state", "name")),
        WorkspaceMaterialEdits.MOVE to schema(state + mapOf("ids" to strings(1, 1024), "folder_id" to nullableString()), listOf("state", "ids")),
        WorkspaceMaterialEdits.RENAME to schema(state + mapOf("id" to string(), "name" to string()), listOf("state", "id", "name")),
        WorkspaceMaterialEdits.DELETE to schema(state + mapOf("ids" to strings(1, 1024)), listOf("state", "ids")),
        WorkspaceMaterialEdits.TAG to schema(state + mapOf("ids" to strings(1, 1024), "set" to strings(0, 64), "add" to strings(0, 64),
            "remove" to strings(0, 64)), listOf("state", "ids")),
        WorkspaceMaterialEdits.RESOLUTION to schema(state + mapOf("id" to string(), "width" to integer(1, WorkspaceMaterialFiles.MAX_VECTOR_SIDE),
            "height" to integer(1, WorkspaceMaterialFiles.MAX_VECTOR_SIDE)), listOf("state", "id", "width", "height")),
        WorkspaceMaterialEdits.PLACE to schema(state + mapOf("material_id" to string(), "rect" to rect,
            "at" to buildJsonObject { put("type", "array"); put("items", number()); put("minItems", 2); put("maxItems", 2) },
            "layer_id" to string()), listOf("state", "material_id")),
        WorkspaceMaterialPixels.PAINT to schema(state + mapOf("id" to string()) + gesture + mapOf("gestures" to buildJsonObject {
            put("type", "array"); put("minItems", 1); put("maxItems", WorkspaceMaterialPixels.MAX_GESTURES)
            put("items", schema(gesture, listOf("mode")))
        }), listOf("state", "id")),
        WorkspaceMaterialPixels.SET_PIXELS to schema(state + mapOf("id" to string(), "staging" to string()), listOf("state", "id", "staging")),
        WorkspaceMaterialPixels.ADJUST to schema(state + mapOf("id" to string(), "flip" to choices(*WorkspaceMaterialPixels.flips.toTypedArray()),
            "hue" to range(-180, 180), "saturation" to range(-100, 100), "lightness" to range(-100, 100), "contrast" to range(-100, 100)),
            listOf("state", "id")),
    )

    val materialDescriptions: Map<String, String> = mapOf(
        WorkspaceMaterialEdits.ADD to "Add materials material_import read and staged (staging token) to the library under folder_id; material_import runs it. One history edit.",
        WorkspaceMaterialEdits.FOLDER to "Create a folder in the material library under parent_id (top level when omitted); give id to name it for later batch members. One history edit.",
        WorkspaceMaterialEdits.MOVE to "Move material items and folders into folder_id (null: the top level). A folder cannot move into itself. One history edit.",
        WorkspaceMaterialEdits.RENAME to "Rename a material item or folder. One history edit.",
        WorkspaceMaterialEdits.DELETE to "Delete material items and folders with everything in them. Refused while a layer shows one of the items; the error lists those layers. Undo brings them back. One history edit.",
        WorkspaceMaterialEdits.TAG to "Set, add or remove tags on material items and folders. When a material is placed, its tags and its folders' tags become the layer's tags (see layer_tag). One history edit.",
        WorkspaceMaterialEdits.RESOLUTION to "Render a vector (SVG) material at width x height pixels, its texture resolution. Every layer showing it takes the new pixels over its unchanged canvas rectangle, as layer_replace_image does. One history edit.",
        WorkspaceMaterialEdits.PLACE to "Place a material on the canvas as new source layers: an item as one layer, a folder as one layer per item in it with its subfolders as source groups, items keeping their places relative to each other. " +
            "Default size: the item's own place (a PSD layer's position), else one material pixel per canvas unit fitted into the canvas; rect sets an item's canvas rectangle (the pixels are not resampled, so a smaller rectangle is a denser texture); at [x, y] centres the placement there. " +
            "The material's tags and its folders' tags become the layer's tags. Placed layers paint over the others. In a generated project they wait for rig_generate and show as plain meshes until then; " +
            "move them with layer_set_canvas_rect or layer_transform (move, scale, mirror), replace their pixels with layer_replace_image. layer_id names a single item's new layer. The changed list names the new layers (layer:<id>). One history edit.",
        WorkspaceMaterialPixels.PAINT to "Paint on a material item's own pixels, in its pixel coordinates (origin at the top left), with the raster engine the paint operation uses: one gesture given by mode and its fields, or gestures, a list of up to ${WorkspaceMaterialPixels.MAX_GESTURES} applied in order (a painting session's strokes as one edit). " +
            "brush, pencil and eraser take points, radius and hardness; bucket a point and tolerance; shape from, to, shape, stroke_width and filled; clear erases everything. color is RGBA bytes (default opaque black), opacity 0..1. " +
            "Every layer showing the item takes the new pixels over its unchanged canvas rectangle, as layer_replace_image does; a vector item becomes a bitmap of its current raster. One history edit.",
        WorkspaceMaterialPixels.SET_PIXELS to "Write the pixels a material paint session committed (staging names them; paint_session_commit stages and runs this). The item keeps its size; every layer showing it takes the new pixels over its unchanged canvas rectangle; a vector item becomes a bitmap. One history edit.",
        WorkspaceMaterialPixels.ADJUST to "Adjust a material item's pixels: flip (horizontal or vertical), then hue (-180..180 degrees), saturation, lightness and contrast (-100..100 each, 0 unchanged). " +
            "The item keeps its size; every layer showing it takes the new pixels over its unchanged canvas rectangle; a vector item becomes a bitmap of its current raster. One history edit.",
    )

    fun summary(id: String, request: JsonObject): String = when (id) {
        WorkspaceLayoutEdits.TAG -> "Tagged ${request.getValue("layer_ids").jsonArray.size} layer(s)"
        WorkspaceLayoutEdits.VARIANT -> "Set variant of ${request.getValue("layer_id").jsonPrimitive.content}"
        WorkspaceGenerationEdits.GENERATE -> "Generated the rig"
        WorkspaceMaterialEdits.ADD -> "Imported materials"
        WorkspaceMaterialEdits.FOLDER -> "Created material folder ${request.getValue("name").jsonPrimitive.content}"
        WorkspaceMaterialEdits.MOVE -> "Moved materials"
        WorkspaceMaterialEdits.RENAME -> "Renamed material"
        WorkspaceMaterialEdits.DELETE -> "Deleted materials"
        WorkspaceMaterialEdits.TAG -> "Tagged materials"
        WorkspaceMaterialEdits.RESOLUTION -> "Set vector resolution"
        WorkspaceMaterialEdits.PLACE -> "Placed material ${request.getValue("material_id").jsonPrimitive.content}"
        WorkspaceMaterialPixels.PAINT -> "Painted material ${request.getValue("id").jsonPrimitive.content}"
        WorkspaceMaterialPixels.SET_PIXELS -> "Painted material ${request.getValue("id").jsonPrimitive.content}"
        WorkspaceMaterialPixels.ADJUST -> "Adjusted material ${request.getValue("id").jsonPrimitive.content}"
        else -> id
    }

    /** The result of a material query or preview. */
    val listSchema: JsonObject by lazy {
        val s = WorkspaceResultSchema
        val tags = s.array(s.string())
        val folder = s.obj(mapOf("id" to s.handle(), "name" to s.string(), "parent_id" to s.handle(), "tags" to tags, "origin" to s.string(),
            "folders" to s.integer(0), "items" to s.integer(0)), setOf("id", "name", "tags", "folders", "items"))
        val item = s.obj(mapOf("id" to s.handle(), "name" to s.string(), "folder_id" to s.handle(), "kind" to s.choices("bitmap", "vector"),
            "width" to s.integer(1), "height" to s.integer(1), "tags" to tags, "origin" to s.string(), "placement" to s.vector(4),
            "references" to s.integer(0)), setOf("id", "name", "kind", "width", "height", "tags", "references"))
        s.obj(mapOf("folder_id" to s.handle(), "path" to s.array(s.obj(mapOf("id" to s.handle(), "name" to s.string()))),
            "total" to s.integer(0), "folders" to s.array(folder), "items" to s.array(item), "next" to s.integer(1)),
            setOf("path", "total", "folders", "items"))
    }
    val getSchema: JsonObject by lazy {
        val s = WorkspaceResultSchema
        s.obj(mapOf("type" to s.choices("item", "folder"), "id" to s.handle(), "name" to s.string(), "folder_id" to s.handle(),
            "parent_id" to s.handle(), "kind" to s.choices("bitmap", "vector"), "width" to s.integer(1), "height" to s.integer(1),
            "tags" to s.array(s.string()), "origin" to s.string(), "placement" to s.vector(4), "references" to s.integer(0),
            "layers" to s.array(s.handle()), "folder_path" to s.array(s.string()), "vector_size" to s.array(s.integer(1), 2, 2),
            "folders" to s.integer(0), "items" to s.integer(0)), setOf("type", "id", "name", "tags", "folder_path"))
    }
    val previewSchema: JsonObject by lazy {
        val s = WorkspaceResultSchema
        s.obj(mapOf("id" to s.handle(), "width" to s.integer(1), "height" to s.integer(1)))
    }
    val exportSchema: JsonObject by lazy {
        val s = WorkspaceResultSchema
        s.obj(mapOf("state" to s.handle(), "path" to s.handle(), "bytes" to s.integer(0), "layers" to s.integer(0)))
    }
}

internal fun registerLayoutOperations(registry: WorkspaceOperationRegistry, port: WorkspaceDocumentPort,
                                      reads: WorkspaceReadPort, jobs: WorkspaceJobs) {
    val schemas = WorkspaceLayoutEdits.schemas + WorkspaceLayoutOperations.materialSchemas
    val descriptions = WorkspaceLayoutEdits.descriptions + WorkspaceLayoutOperations.materialDescriptions
    for (id in WorkspaceLayoutEdits.supported + WorkspaceMaterialEdits.supported) {
        registry.register(WorkspaceOperationDefinition(id,
            descriptions.getValue(id) + " Returns a process-owned job handle; use job_wait/job_get for the result.",
            schemas.getValue(id), WorkspaceOperationKind.DOCUMENT, jobBacked = true,
            resultSchema = requireNotNull(WorkspaceJobResultSchemas.operationOutput(id)),
            jobResultSchema = WorkspaceJobResultSchemas.result(id))) { request, context ->
            startDocumentBatch(port, reads, jobs, id, request.getValue("state").jsonPrimitive.content, WorkspaceLayoutOperations.summary(id, request),
                listOf(WorkspaceDocumentOperation(id, JsonObject(request - setOf("state", "request_id", "project_id")))), context.author)
        }
    }

    registry.register(WorkspaceOperationDefinition(WorkspaceLayoutOperations.IMPORT,
        "Import 1..${WorkspaceMaterialFiles.MAX_PATHS} absolute paths into the material library under folder_id: PNG, BMP, TIFF, WebP and JPEG images as items; " +
            "a PSD as a folder of its layers (its groups as subfolders, each layer keeping its place, opacity and visibility); a CMO3's layered images the same way " +
            "(its texture pages when it has none - the whole model imports with project_import_cmo3); an SVG as a vector item rendered at its own size " +
            "(material_set_resolution changes it); a folder as a folder of the files it holds. Each image is at most 16 megapixels. The library is part of the " +
            "document: one history edit, undoable. Returns a process-owned job handle; the result's changed list names the new materials (material:<id>).",
        WorkspaceLayoutOperations.schema(mapOf("state" to buildJsonObject { put("type", "string"); put("minLength", 1) },
            "paths" to buildJsonObject { put("type", "array"); put("minItems", 1); put("maxItems", WorkspaceMaterialFiles.MAX_PATHS)
                put("items", buildJsonObject { put("type", "string"); put("minLength", 1) }) },
            "folder_id" to buildJsonObject { put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null")))) }), listOf("state", "paths")),
        WorkspaceOperationKind.DOCUMENT, jobBacked = true,
        resultSchema = requireNotNull(WorkspaceJobResultSchemas.operationOutput(WorkspaceLayoutOperations.IMPORT)),
        jobResultSchema = WorkspaceJobResultSchemas.result(WorkspaceLayoutOperations.IMPORT))) { request, context ->
        val paths = request.getValue("paths").jsonArray.map { Path.of(it.jsonPrimitive.content) }
        val state = request.getValue("state").jsonPrimitive.content
        val folder = request["folder_id"]?.takeUnless { it is JsonNull }
        startWorkspaceOperationJob(reads, jobs, WorkspaceLayoutOperations.IMPORT) {
            val coroutine = currentCoroutineContext()
            coroutine[WorkspaceJobContext]?.progress(0.05f, "Reading files")
            val entries = runInterruptible(Dispatchers.IO) { WorkspaceMaterialFiles.read(paths) { coroutine.ensureActive() } }
            val edits = listOf(WorkspaceDocumentOperation(WorkspaceMaterialEdits.ADD, buildJsonObject {
                put("staging", WorkspaceMaterialStaging.stage(entries)); folder?.let { put("folder_id", it) }
            }))
            val execution = WorkspaceBatchJobExecution(edits, requireNotNull(coroutine[WorkspaceJobCompletion]), coroutine[WorkspaceJobContext])
            withContext(execution) {
                WorkspaceOperationOutput(port.applyDocumentEdits(state, "Imported ${entries.size} material(s)", edits, context.author).batchResult(1))
            }
        }
    }

    registry.register(WorkspaceOperationDefinition(WorkspaceLayoutOperations.LIST,
        "List the material library: the folders and items in folder_id (the top level when omitted), or everywhere below it with recursive. " +
            "query matches names and tags (an item's folders' tags included), kind filters bitmap or vector items, unused keeps items no layer shows. Paged by offset and limit (default 64).",
        WorkspaceLayoutOperations.schema(mapOf("folder_id" to buildJsonObject { put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null")))) },
            "recursive" to buildJsonObject { put("type", "boolean") }, "query" to buildJsonObject { put("type", "string") },
            "kind" to buildJsonObject { put("type", "string"); put("enum", JsonArray(MaterialKind.entries.map { JsonPrimitive(it.name.lowercase()) })) },
            "unused" to buildJsonObject { put("type", "boolean") },
            "offset" to buildJsonObject { put("type", "integer"); put("minimum", 0) },
            "limit" to buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 256) }), emptyList()),
        WorkspaceOperationKind.QUERY, resultSchema = WorkspaceLayoutOperations.listSchema)) { request, _ ->
        val state = reads.captureQueries().layoutState() ?: throw IllegalStateException("No workspace is loaded")
        WorkspaceOperationOutput(WorkspaceMaterialQueries.list(state, request))
    }
    registry.register(WorkspaceOperationDefinition(WorkspaceLayoutOperations.GET,
        "One material item or folder: its size, tags, origin, default placement, and the layers that show it.",
        WorkspaceLayoutOperations.schema(mapOf("id" to buildJsonObject { put("type", "string"); put("minLength", 1) }), listOf("id")),
        WorkspaceOperationKind.QUERY, resultSchema = WorkspaceLayoutOperations.getSchema)) { request, _ ->
        val state = reads.captureQueries().layoutState() ?: throw IllegalStateException("No workspace is loaded")
        WorkspaceOperationOutput(WorkspaceMaterialQueries.get(state, request.getValue("id").jsonPrimitive.content))
    }
    registry.register(WorkspaceOperationDefinition(WorkspaceLayoutOperations.PREVIEW,
        "A PNG of a material fitted into size pixels (default 512): an item's pixels, or a folder's items composited where they sit.",
        WorkspaceLayoutOperations.schema(mapOf("id" to buildJsonObject { put("type", "string"); put("minLength", 1) },
            "size" to buildJsonObject { put("type", "integer"); put("minimum", 16); put("maximum", 4096) }), listOf("id")),
        WorkspaceOperationKind.QUERY, resultSchema = WorkspaceLayoutOperations.previewSchema)) { request, _ ->
        val state = reads.captureQueries().layoutState() ?: throw IllegalStateException("No workspace is loaded")
        val (json, png) = WorkspaceMaterialQueries.preview(state, request.getValue("id").jsonPrimitive.content, request["size"]?.jsonPrimitive?.int ?: 512)
        WorkspaceOperationOutput(json, listOf(png))
    }
    registry.register(WorkspaceOperationDefinition(WorkspaceLayoutOperations.EXPORT_PSD,
        "Export a material folder to an absolute PSD path: its items as layers where they sit (in a row from the corner when they have no place), its subfolders as groups, " +
            "on the project canvas grown to hold them; vector items at their texture resolution. Returns a job handle.",
        WorkspaceLayoutOperations.schema(mapOf("state" to buildJsonObject { put("type", "string"); put("minLength", 1) },
            "folder_id" to buildJsonObject { put("type", "string"); put("minLength", 1) },
            "path" to buildJsonObject { put("type", "string"); put("minLength", 1) }), listOf("state", "folder_id", "path")),
        WorkspaceOperationKind.OUTPUT, jobBacked = true,
        resultSchema = requireNotNull(WorkspaceJobResultSchemas.operationOutput(WorkspaceLayoutOperations.EXPORT_PSD)),
        jobResultSchema = WorkspaceJobResultSchemas.result(WorkspaceLayoutOperations.EXPORT_PSD))) { request, _ ->
        val path = Path.of(request.getValue("path").jsonPrimitive.content)
        require(path.isAbsolute && path.fileName.toString().endsWith(".psd", true)) { "Provide an absolute PSD output path" }
        val queries = reads.captureQueries()
        val captured = queries.snapshot().state
        if (captured != request.getValue("state").jsonPrimitive.content) throw WorkspaceConflict(request.getValue("state").jsonPrimitive.content, captured)
        val state = queries.layoutState() ?: throw IllegalStateException("No workspace is loaded")
        startWorkspaceOperationJob(reads, jobs, WorkspaceLayoutOperations.EXPORT_PSD) {
            val coroutine = currentCoroutineContext()
            val (bytes, layers) = runInterruptible(Dispatchers.Default) { WorkspaceMaterialQueries.psd(state, request.getValue("folder_id").jsonPrimitive.content) }
            coroutine.ensureActive()
            val output = buildJsonObject { put("state", captured); put("path", path.toString()); put("bytes", bytes.size); put("layers", layers) }
            withContext(NonCancellable + Dispatchers.IO) {
                Files.createDirectories(path.parent)
                val temporary = Files.createTempFile(path.parent, ".material", ".psd")
                try { Files.write(temporary, bytes); Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                finally { Files.deleteIfExists(temporary) }
                coroutine[WorkspaceJobCompletion]?.committed(WorkspaceOperationOutput(output))
            }
            WorkspaceOperationOutput(output)
        }
    }
}
