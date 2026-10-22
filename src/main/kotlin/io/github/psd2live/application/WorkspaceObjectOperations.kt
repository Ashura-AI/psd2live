package io.github.psd2live.application

import io.github.psd2live.core.RigMeshDuplication
import io.github.psd2live.core.RigObjectPaste
import kotlinx.serialization.json.*

/** Request and result contracts of the object edits ([WorkspaceObjectEdits]). */
internal object WorkspaceObjectSchemas {
	private val s = WorkspaceResultSchema
	private fun describe(value: JsonObject, text: String) = JsonObject(value + ("description" to JsonPrimitive(text)))
	private fun request(fields: Map<String, JsonObject>, required: List<String>) =
		s.obj(mapOf("state" to s.handle()) + fields, (listOf("state") + required).toSet())

	private val kinds = s.array(s.choices(*WorkspaceObjectEdits.FIND_KINDS.toTypedArray()), 1, WorkspaceObjectEdits.FIND_KINDS.size)

	/** The fields a find reads; a rename reads the same ones to replace what it finds. */
	val findFields: Map<String, JsonObject> = mapOf(
		"field" to describe(s.choices("name", "id"), "What is searched and replaced: the display name (default) or the ID."),
		"kinds" to describe(kinds, "Object kinds searched; default all."),
		"targets" to describe(s.array(s.handle(), 1, 4096), "Search only these kind:id objects (the selection)."),
		"find" to describe(s.string(), "Text to find; empty matches every object."),
		"regex" to describe(s.boolean(), "find is a regular expression and replace may use \$1-style groups. Default false."),
		"match_case" to describe(s.boolean(), "Default true."),
		"replace" to describe(s.string(), "What each occurrence of find becomes; an empty string removes it."),
		"prefix" to describe(s.string(), "Added before the result."),
		"suffix" to describe(s.string(), "Added after the result."),
		"swap_sides" to describe(s.boolean(), "Exchange left and right where they are words of their own: L/R (ArmL, Hair_L_01, EyeLOpen), Left/Right, 左/右."),
		"standard_ids" to describe(s.boolean(), "With field id: Cubism 2 style IDs take Cubism 3's standard style (PARAM_EYE_L_OPEN to ParamEyeLOpen, PARTS_01_FACE to PartFace); other IDs are left out."),
	)

	val paste = request(mapOf(
		"sources" to describe(s.array(s.handle(), 1, 128), "Copied objects as kind:id (mesh, warp, rotation, part, parameter). A deformer brings its descendant deformers and meshes, a part its child parts, meshes and the deformers organized in it."),
		"parent" to describe(s.handle(), "warp:ID, rotation:ID or root: the deformer the pasted meshes and deformers hang under. Meshes keep where they show (space canvas); deformers keep their local geometry. Default: the originals' parent."),
		"part" to describe(s.handle(), "part:ID or root: where the pasted objects sit in the parts tree. Default: right after each original."),
		"parameter_group" to describe(s.handle(), "param_group:ID or root for pasted parameters. Default: right after each original."),
		"ids" to describe(s.dictionary(s.handle()), "New IDs by source kind:id, fixed in advance. Others are the source ID with _copy, then _copy_2, ... - the first one free."),
	), listOf("sources"))
	val meshDuplicate = request(mapOf(
		"source" to describe(s.handle(), "mesh:ID whose vertices and triangles are copied."),
		"targets" to describe(s.array(s.handle(), 1, 128), "mesh:ID of each mesh that takes them."),
	), listOf("source", "targets"))
	val rename = request(findFields, emptyList())
	val convert = request(mapOf(
		"renames" to s.array(s.obj(mapOf("target" to describe(s.handle(), "kind:id: parameter, param_group, mesh, warp, rotation or part."),
			"id" to describe(s.handle(), "The new ID: 1-64 ASCII letters, digits or underscores, starting with a letter."))), 1, 4096),
	), listOf("renames"))
	val find = s.obj(findFields + mapOf("offset" to s.integer(0), "limit" to s.integer(1, 256)), emptySet())

	private val changed = s.array(s.handle(), 1, Int.MAX_VALUE)
	private fun result(fields: Map<String, JsonObject>) =
		s.obj(s.identity + mapOf("applied" to s.constant(false), "changed" to changed) + fields, s.identity.keys + fields.keys)
	val pasteResult = result(mapOf("ids" to s.dictionary(s.handle())))
	val meshDuplicateResult = result(mapOf("targets" to s.array(s.obj(mapOf("target" to s.handle(), "vertices" to s.integer(3),
		"outside" to s.integer(0))))))
	val renameResult = result(mapOf("field" to s.choices("name", "id"), "renamed" to s.array(s.obj(mapOf("target" to s.handle(),
		"before" to s.string(), "after" to s.string())))))
	val convertResult = result(mapOf("ids" to s.dictionary(s.handle())))
	val findResult = s.obj(mapOf("project_id" to s.handle(), "state" to s.handle(), "revision" to s.handle(), "total" to s.integer(0),
		"matches" to s.array(s.obj(mapOf("target" to s.handle(), "kind" to s.choices(*WorkspaceObjectEdits.FIND_KINDS.toTypedArray()),
			"id" to s.handle(), "name" to s.string(), "field" to s.choices("name", "id"), "before" to s.string(), "after" to s.string())))))
}

internal fun registerObjectOperations(registry: WorkspaceOperationRegistry, workspace: WorkspaceBackend) {
	val schemas = WorkspaceObjectSchemas
	fun register(id: String, description: String, schema: JsonObject, result: JsonObject, summary: (JsonObject) -> String) {
		registry.register(WorkspaceOperationDefinition(id, description, schema, WorkspaceOperationKind.DOCUMENT, batchable = true,
			resultSchema = result)) { request, context ->
			// Planned on the captured version the state names: the pinned request records exactly what the fields name,
			// or the commit fails on that state.
			val (pinned, planned) = workspace.captureQueries().planObjectEdit(id, JsonObject(request - "state"))
			val mutation = workspace.applyDocumentEdits(request.getValue("state").jsonPrimitive.content, summary(request),
				listOf(WorkspaceDocumentOperation(id, pinned)), context.author).compact()
			WorkspaceOperationOutput(JsonObject(mutation + planned))
		}
	}

	register(WorkspaceObjectEdits.PASTE, "Paste copies of objects, as Cubism's copy and paste: meshes, warp and rotation deformers with their descendants, parts with their contents, and parameters. Copies keep keyforms, blend shapes, masks, paths, vertex groups and the glue between copied meshes, share the original's texture and layer, and reference other copies in place of their originals; a copied parameter is a new axis with the original's range and no keyforms. Swings, simulations and interior effects are not copied. New IDs are fixed when the paste is recorded and returned in ids. One undoable history edit.",
		schemas.paste, schemas.pasteResult, { "Pasted ${it.getValue("sources").jsonArray.size} objects" })
	register(WorkspaceObjectEdits.MESH_DUPLICATE, "Duplicate one mesh's topology onto other meshes, as Cubism's mesh duplication: each target takes the source's vertices and triangles where the source has them on the canvas and samples its own texture there; its keyforms, blend shapes, paths, vertex groups and glue migrate as for layer_mesh_rebuild. outside counts copied vertices that fall outside a target's artwork. One undoable history edit.",
		schemas.meshDuplicate, schemas.meshDuplicateResult, { "Duplicated mesh ${it.getValue("source").jsonPrimitive.content}" })
	register(WorkspaceObjectEdits.RENAME, "Find and replace object names or IDs, as Cubism's search and replace: the objects object_find matches with the same fields take what the replace makes of them. Names change as rig_edit_structure renames; IDs convert as object_convert_id, with every reference. Fails when nothing matches. One undoable history edit.",
		schemas.rename, schemas.renameResult, { "Replaced ${it["field"]?.jsonPrimitive?.content ?: "name"}s" })
	register(WorkspaceObjectEdits.CONVERT_ID, "Convert object IDs, as Cubism's ID conversion: each target takes its new ID and every reference follows - keyforms and blend shapes, parents, parts, masks, glue, paths, vertex groups, parameter links and folders, motions, user physics groups and swing targets; generated physics, motions and expressions find renamed standard parameters under their new IDs, and later edits use the new ones. A new ID must be free in its kind (warps and rotations share one). Objects a swing, simulation or interior effect owns or reads are refused. One undoable history edit.",
		schemas.convert, schemas.convertResult, { "Converted ${it.getValue("renames").jsonArray.size} IDs" })
	registry.register(WorkspaceOperationDefinition(WorkspaceObjectEdits.FIND, "Find objects by name or ID from one captured version, and show what a replace with the same fields would make of each (object_rename applies it). Lists every match, paged by offset and limit (default 64).",
		schemas.find, WorkspaceOperationKind.QUERY, resultSchema = schemas.findResult)) { request, _ ->
		WorkspaceOperationOutput(workspace.captureQueries().findObjects(request))
	}
}
