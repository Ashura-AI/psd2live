package io.github.psd2live.application

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import io.github.psd2live.project.WorkspaceWorkflowResult

internal object WorkspacePaintSessionSchemas {
    private val s = WorkspaceResultSchema
    private val color = s.array(s.integer(0, 255), 4, 4)
    private val common = mapOf("color" to color, "opacity" to s.number(0, 1), "lock_alpha" to s.boolean())
    private fun branch(mode: String, fields: Map<String, JsonObject>, required: Set<String>) =
        s.obj(common + fields + ("mode" to s.constant(mode)), required + "mode")
    val gesture = s.union(listOf(
        branch("brush", mapOf("points" to s.array(s.vector(2), 1, 512), "radius" to s.number(0.5, 512),
            "hardness" to s.number(0, 1)), setOf("points")),
        branch("pencil", mapOf("points" to s.array(s.vector(2), 1, 512), "radius" to s.number(0.5, 512)), setOf("points")),
        branch("eraser", mapOf("points" to s.array(s.vector(2), 1, 512), "radius" to s.number(0.5, 512),
            "hardness" to s.number(0, 1)), setOf("points")),
        branch("bucket", mapOf("point" to s.vector(2), "tolerance" to s.integer(0, 255), "contiguous" to s.boolean()), setOf("point")),
        branch("shape", mapOf("from" to s.vector(2), "to" to s.vector(2), "shape" to s.choices("line", "rectangle", "ellipse"),
            "stroke_width" to s.number(1, 512), "filled" to s.boolean()), setOf("from", "to", "shape")),
        s.obj(mapOf("mode" to s.constant("clear"))),
        // A blur stroke takes opacity as its strength and has no colour of its own.
        branch("blur", mapOf("points" to s.array(s.vector(2), 1, 512), "radius" to s.number(0.5, 512),
            "hardness" to s.number(0, 1)), setOf("points")),
        branch("fill", emptyMap(), emptySet()),
        // Free transform of the selection's pixels - the layer's content without one - by an affine map in canvas units.
        s.obj(mapOf("mode" to s.constant("transform"), "matrix" to s.array(s.number(), 6, 6), "smooth" to s.boolean()),
            setOf("mode", "matrix")),
        branch("gradient", mapOf("from" to s.vector(2), "to" to s.vector(2), "end_color" to color,
            "kind" to s.choices("linear", "radial")), setOf("from", "to")),
    ))
    /** A change of the session's selection; shape points are canvas units like a gesture's. */
    val selection = run {
        val op = mapOf("op" to s.choices("replace", "add", "subtract", "intersect"), "antialias" to s.boolean())
        fun shape(mode: String, fields: Map<String, JsonObject>) = s.obj(op + fields + ("mode" to s.constant(mode)), fields.keys + "mode")
        s.union(listOf(
            shape("rectangle", mapOf("from" to s.vector(2), "to" to s.vector(2))),
            shape("ellipse", mapOf("from" to s.vector(2), "to" to s.vector(2))),
            shape("lasso", mapOf("points" to s.array(s.vector(2), 3, 4096))),
            s.obj(op + mapOf("mode" to s.constant("wand"), "point" to s.vector(2), "tolerance" to s.integer(0, 255),
                "contiguous" to s.boolean()), setOf("mode", "point")),
            // A quick selection brush stroke: radius in canvas units, like a brush gesture's.
            s.obj(op + mapOf("mode" to s.constant("quick"), "points" to s.array(s.vector(2), 1, 4096),
                "radius" to s.number(0.5, 512), "tolerance" to s.integer(0, 255)), setOf("mode", "points", "radius")),
            s.obj(op + mapOf("mode" to s.constant("opaque")), setOf("mode")),
            s.obj(mapOf("mode" to s.constant("all"))), s.obj(mapOf("mode" to s.constant("none"))),
            s.obj(mapOf("mode" to s.constant("invert"))),
            s.obj(mapOf("mode" to s.constant("feather"), "radius" to s.number(0.5, 256)), setOf("mode", "radius")),
            s.obj(mapOf("mode" to s.constant("expand"), "radius" to s.number(0.5, 256)), setOf("mode", "radius")),
            s.obj(mapOf("mode" to s.constant("contract"), "radius" to s.number(0.5, 256)), setOf("mode", "radius")),
        ))
    }
    private fun control(action: String, fields: Map<String, JsonObject> = emptyMap(), required: Set<String> = fields.keys) =
        s.obj(fields + ("action" to s.constant(action)), required + "action")
    val edit = s.union(listOf(control("gesture", mapOf("gesture" to gesture, "name" to s.handle()), setOf("gesture")),
        control("select", mapOf("selection" to selection)), control("undo"), control("redo"), control("jump", mapOf("index" to s.integer(0))), control("abandon"),
        control("cancel"), control("inspect"), control("sample", mapOf("x" to s.integer(0), "y" to s.integer(0))),
        control("render", mapOf("max_edge" to s.integer(128, 2048)), emptySet())))
    val snapshot = s.obj(mapOf("session_id" to s.handle(), "session_state" to s.handle(), "project_id" to s.handle(),
        "workspace_state" to s.handle(), "layer_id" to s.handle(), "layer_name" to s.string(), "material_id" to s.handle(),
        "width" to s.integer(1), "height" to s.integer(1), "canvas_rect" to s.vector(4), "selection" to s.vector(4),
        "index" to s.integer(0), "dirty" to s.boolean(),
        "active_stroke" to s.boolean(), "closed" to s.boolean(),
        "strokes" to s.array(s.obj(mapOf("id" to s.handle(), "name" to s.string())), 1, Int.MAX_VALUE)),
        setOf("session_id", "session_state", "project_id", "workspace_state", "layer_id", "layer_name", "width", "height", "canvas_rect",
            "index", "dirty", "active_stroke", "closed", "strokes"))
    val result = s.union(listOf(s.obj(mapOf("kind" to s.constant("state"), "session" to snapshot)),
        s.obj(mapOf("kind" to s.constant("sample"), "session" to snapshot, "rgba" to color)),
        s.obj(mapOf("kind" to s.constant("render"), "session" to snapshot, "width" to s.integer(1),
            "height" to s.integer(1)))))
    val commit = s.obj(s.identity + mapOf("applied" to s.constant(false), "changed" to s.array(s.handle(), 1, Int.MAX_VALUE)), s.identity.keys)
}

/** Render/sample observe the isolated candidate, including its in-progress live stroke. */
internal fun WorkspacePaintSession.control(request: JsonObject, checkpoint: () -> Unit = {
    if (Thread.currentThread().isInterrupted) throw kotlinx.coroutines.CancellationException("Paint session gesture cancelled")
}): WorkspaceWorkflowResult {
    val edit = request.getValue("edit").jsonObject
    validateOperationSchema(edit, WorkspacePaintSessionSchemas.edit)
    val expected = request.getValue("session_state").jsonPrimitive.content
    val action = edit.getValue("action").jsonPrimitive.content
    return atomic(expected) {
        when (action) {
            "gesture" -> gesture(edit.getValue("gesture").jsonObject, edit["name"]?.jsonPrimitive?.content
                ?: edit.getValue("gesture").jsonObject.getValue("mode").jsonPrimitive.content, expected, checkpoint)
            "select" -> select(edit.getValue("selection").jsonObject, expected)
            "undo" -> undo(expected)
            "redo" -> redo(expected)
            "jump" -> jump(edit.getValue("index").jsonPrimitive.int, expected)
            "abandon" -> abandonStroke(expected)
            "cancel" -> cancel(expected)
            "inspect", "sample", "render" -> require(sessionState == expected) { "Paint session state changed" }
        }
        var images = emptyList<ByteArray>()
        val metadata = buildJsonObject {
            put("kind", if (action in setOf("sample", "render")) action else "state"); put("session", snapshot())
            if (action == "sample") {
                val pixel = sample(edit.getValue("x").jsonPrimitive.int, edit.getValue("y").jsonPrimitive.int)
                put("rgba", JsonArray(listOf(pixel ushr 16 and 255, pixel ushr 8 and 255, pixel and 255, pixel ushr 24 and 255).map(::JsonPrimitive)))
            }
            if (action == "render") {
                val source = image(); val edge = edit["max_edge"]?.jsonPrimitive?.int ?: 512
                val scale = minOf(1.0, edge.toDouble() / maxOf(source.width, source.height))
                val output = BufferedImage(maxOf(1, (source.width * scale).toInt()), maxOf(1, (source.height * scale).toInt()), BufferedImage.TYPE_INT_ARGB)
                output.createGraphics().also { graphics ->
                    try {
                        graphics.composite = java.awt.AlphaComposite.Src
                        graphics.drawImage(source, 0, 0, output.width, output.height, null)
                    } finally { graphics.dispose() }
                }
                val bytes = ByteArrayOutputStream().also { ImageIO.write(output, "png", it) }.toByteArray()
                put("width", output.width); put("height", output.height); images = listOf(bytes)
            }
        }
        WorkspaceWorkflowResult(metadata, images)
    }
}

internal fun registerPaintSessionOperations(registry: WorkspaceOperationRegistry, port: WorkspacePaintPort,
    statePort: WorkspaceStatePort, jobs: WorkspaceJobs) {
    val s = WorkspaceResultSchema
    val ids = mapOf("session_id" to s.handle(), "session_state" to s.handle())
    registry.register(WorkspaceOperationDefinition("paint_session_list",
        "List the current private paint sessions, including the GUI candidate and its stroke revision. Use a listed session_id to inspect or continue exactly that candidate.",
        s.obj(emptyMap()), WorkspaceOperationKind.QUERY, resultSchema = s.obj(mapOf("sessions" to s.array(WorkspacePaintSessionSchemas.snapshot))))) { _, _ ->
        WorkspaceOperationOutput(buildJsonObject { put("sessions", JsonArray(port.listPaintSessions())) })
    }
    registry.register(WorkspaceOperationDefinition("paint_session_begin",
        "Begin an isolated multi-stroke paint candidate from a committed layer (layer_id), capturing its workspace state, or from a material item of the library (material_id). Canvas X points right and Y down; gesture points, radii, widths and sample positions are canvas units. The candidate raster is the layer's own pixel grid (width x height pixels covering canvas_rect [left, top, width, height]), so strokes on a dense layer paint at its raster density, anywhere on the canvas; a material's is its own pixels, one per unit. A layer showing a material paints like any layer and reports its material_id; its commit writes the material. " +
            "A material session stays open while other edits commit (only a change to the material's pixels ends it) and its commit writes the pixels with material_set_pixels, every layer showing the material following; rebuild_mesh does not apply. Edits, sampling and undo never alter project history until commit.",
        s.obj(mapOf("state" to s.handle(), "layer_id" to s.handle(), "material_id" to s.handle()), setOf("state")), WorkspaceOperationKind.SESSION,
        resultSchema = WorkspacePaintSessionSchemas.result)) { request, _ ->
        val layer = request["layer_id"]?.jsonPrimitive?.content; val material = request["material_id"]?.jsonPrimitive?.content
        require((layer == null) != (material == null)) { "Give layer_id or material_id" }
        val session = if (material != null) port.beginMaterialPaintSession(material)
            else port.beginPaintSession(request.getValue("state").jsonPrimitive.content, requireNotNull(layer))
        WorkspaceOperationOutput(buildJsonObject { put("kind", "state"); put("session", session.snapshot()) })
    }
    registry.register(WorkspaceOperationDefinition("paint_session_control",
        "Edit, undo, redo or jump within the private paint candidate; cancel discards the entire session, abandon discards only the active live stroke. Sample and render read candidate pixels. " +
            "select changes the session's selection (rectangle, ellipse, lasso, wand, quick - a brush stroke grown across like colours up to edges - or opaque - the layer's alpha - met with it by op replace/add/subtract/intersect, or all, none, invert, feather, expand, contract); every later gesture lands only inside it, and lock_alpha keeps a gesture to already painted pixels. The selection is not in the stroke history. Pass the latest session_state to prevent concurrent stroke loss. Mutations reject a changed committed workspace; inspection and cancellation remain available.",
        s.obj(ids + ("edit" to WorkspacePaintSessionSchemas.edit)), WorkspaceOperationKind.SESSION,
        resultSchema = WorkspacePaintSessionSchemas.result)) { request, _ ->
        val result = port.controlPaintSession(request)
        WorkspaceOperationOutput(result.metadata, result.images)
    }
    registry.register(WorkspaceOperationDefinition("paint_session_commit",
        "Commit all candidate strokes once as one raster/mesh history node. Defaults to retaining mesh and authored bindings and cutting the paint along the layer's meshes: what lands on them stays the layer's (a layer showing a material writes the material, every layer showing it following), what lands off them becomes a new layer just above with its own mesh under the same parent, and a material of its own beside the layer's in the library; off the meshes erasing changes nothing. rebuild_mesh instead grows the layer to all its pixels and migrates existing rig bindings onto the new mesh. Both the captured workspace and session revision must match. A successful session cannot publish twice.",
        s.obj(mapOf("state" to s.handle()) + ids + mapOf("rebuild_mesh" to s.boolean(), "preserve_source_raster" to s.boolean()),
            setOf("state", "session_id", "session_state")), WorkspaceOperationKind.DOCUMENT,
        jobBacked = true, resultSchema = WorkspaceJobResultSchemas.snapshot("paint_session_commit"), jobResultSchema = WorkspacePaintSessionSchemas.commit)) { request, context ->
        startWorkspaceOperationJob(statePort, jobs, "paint_session_commit") {
            val completion = requireNotNull(currentCoroutineContext()[WorkspaceJobCompletion])
            withContext(WorkspacePaintJobExecution(completion)) {
                WorkspaceOperationOutput(port.commitPaintSession(request.getValue("state").jsonPrimitive.content,
                    request.getValue("session_id").jsonPrimitive.content, request.getValue("session_state").jsonPrimitive.content,
                    request["rebuild_mesh"]?.jsonPrimitive?.boolean ?: false, request["preserve_source_raster"]?.jsonPrimitive?.boolean ?: false,
                    context.author).compact())
            }
        }
    }
}
