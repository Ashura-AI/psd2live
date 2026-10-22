package io.github.psd2live.application

import io.github.psd2live.project.*
import kotlinx.serialization.json.*

/**
 * The motion editor's timeline aids: parameter bookmarks, and per motion shy tracks, frozen tracks and markers. They
 * are saved project data on the auxiliary CAS - no rig history node, no export - shared by the editor and agents.
 */
internal fun registerTimelineAidsOperations(registry: WorkspaceOperationRegistry, port: WorkspaceAuxiliaryPort) {
    fun register(id: String, description: String, fields: Map<String, JsonObject>, required: List<String>,
                 kind: WorkspaceOperationKind = WorkspaceOperationKind.SESSION, action: (JsonObject) -> JsonObject) {
        val schema = buildJsonObject {
            put("type", "object"); put("additionalProperties", false); put("properties", JsonObject(fields))
            put("required", JsonArray(required.map(::JsonPrimitive)))
        }
        registry.register(WorkspaceOperationDefinition(id, description, schema, kind,
            resultSchema = WorkspaceTimelineAidsResultSchemas.forOperation(id))) { request, _ -> WorkspaceOperationOutput(action(request)) }
    }
    fun string() = buildJsonObject { put("type", "string"); put("minLength", 1) }
    fun boolean() = buildJsonObject { put("type", "boolean") }
    fun number(min: Number? = null) = buildJsonObject { put("type", "number"); min?.let { put("minimum", it) } }
    fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    fun WorkspaceAuxiliarySnapshot.result() = buildJsonObject {
        put("project_id", projectId); put("state", state); put("history_node_id", historyNodeId)
    }
    fun edit(request: JsonObject, edit: TimelineAidsEdit) =
        port.editSavedProjectData(request.text("state"), WorkspaceAuxiliaryEdit.EditTimeline(edit))

    register("timeline_aids_get", "Read the motion editor's timeline aids: bookmarked parameters, and for each motion (by editor clip ID - a generated motion is preset:<Name>) its shy tracks, frozen tracks with the value they hold, and markers. clip_id narrows clips to one motion.",
        mapOf("clip_id" to string()), emptyList(), WorkspaceOperationKind.QUERY) { request ->
        val captured = port.savedProjectData()
        val aids = captured.data.timelineAids.let { all ->
            request["clip_id"]?.jsonPrimitive?.content?.let { id -> all.copy(clips = all.clips.filterKeys { it == id }) } ?: all
        }
        JsonObject(captured.result() + TimelineAidsCodec.encode(aids))
    }
    register("timeline_bookmark_set", "Bookmark a parameter, or remove its bookmark. Bookmarked parameters lead the motion editor's track picker and can be the only tracks it shows. Saved with the project; no history node.",
        mapOf("state" to string(), "parameter" to string(), "bookmarked" to boolean()), listOf("state", "parameter", "bookmarked")) { request ->
        edit(request, TimelineAidsEdit.Bookmark(request.text("parameter"), request.getValue("bookmarked").jsonPrimitive.boolean)).result()
    }
    register("timeline_shy_set", "Mark a motion's track shy, or not: the editor hides shy tracks while Hide shy is on. The track still plays and exports. Saved with the project; no history node.",
        mapOf("state" to string(), "clip_id" to string(), "parameter" to string(), "shy" to boolean()), listOf("state", "clip_id", "parameter", "shy")) { request ->
        edit(request, TimelineAidsEdit.Shy(request.text("clip_id"), request.text("parameter"), request.getValue("shy").jsonPrimitive.boolean)).result()
    }
    register("timeline_freeze_set", "Freeze a motion's track at value, or thaw it (frozen=false). While frozen, playing or scrubbing that motion in the editor holds the parameter at the value and the editor locks the track's keys; the keys themselves and the export are unchanged. value is required to freeze and must lie in the parameter's range (any finite value for a repeating parameter). Saved with the project; no history node.",
        mapOf("state" to string(), "clip_id" to string(), "parameter" to string(), "frozen" to boolean(), "value" to number()),
        listOf("state", "clip_id", "parameter", "frozen")) { request ->
        val frozen = request.getValue("frozen").jsonPrimitive.boolean
        val value = request["value"]?.jsonPrimitive?.float
        require(!frozen || value != null) { "value is required to freeze a track" }
        edit(request, TimelineAidsEdit.Freeze(request.text("clip_id"), request.text("parameter"), if (frozen) value else null)).result()
    }
    register("timeline_marker_put", "Add a marker to a motion's timeline, or replace the one with id: a time in seconds, an optional one-line label (at most 64 characters) and colour 0..7. Without id the marker gets the next marker_<n>. Markers are cues for timing; they are not motion3 events and never export. Saved with the project; no history node.",
        mapOf("state" to string(), "clip_id" to string(), "id" to string(), "time" to number(0),
            "label" to buildJsonObject { put("type", "string"); put("maxLength", TimelineAidsEdits.MAX_LABEL) },
            "color" to buildJsonObject { put("type", "integer"); put("minimum", 0); put("maximum", TimelineAidsEdits.COLORS - 1) }),
        listOf("state", "clip_id", "time")) { request ->
        val clipId = request.text("clip_id")
        val id = request["id"]?.jsonPrimitive?.content ?: TimelineAidsEdits.nextMarkerId(port.savedProjectData().data.timelineAids.clip(clipId))
        val marker = TimelineMarker(id, request.getValue("time").jsonPrimitive.float, request["label"]?.jsonPrimitive?.content.orEmpty(),
            request["color"]?.jsonPrimitive?.int ?: 0)
        val next = edit(request, TimelineAidsEdit.PutMarker(clipId, marker))
        val stored = next.data.timelineAids.clip(clipId).markers.single { it.id == id }
        JsonObject(next.result() + ("marker" to buildJsonObject {
            put("id", stored.id); put("time", stored.time); put("label", stored.label); put("color", stored.color)
        }))
    }
    register("timeline_marker_delete", "Delete a motion's timeline marker. Saved with the project; no history node.",
        mapOf("state" to string(), "clip_id" to string(), "id" to string()), listOf("state", "clip_id", "id")) { request ->
        edit(request, TimelineAidsEdit.DeleteMarker(request.text("clip_id"), request.text("id"))).result()
    }
}

internal object WorkspaceTimelineAidsResultSchemas {
    private val s = WorkspaceResultSchema
    private val marker = s.obj(mapOf("id" to s.handle(), "time" to s.number(0), "label" to s.string(),
        "color" to s.integer(0, TimelineAidsEdits.COLORS - 1)))
    private val clip = s.obj(mapOf("shy" to s.array(s.handle()), "frozen" to s.dictionary(s.number()), "markers" to s.array(marker)))

    fun forOperation(id: String): JsonObject = when (id) {
        "timeline_aids_get" -> s.obj(s.identity + mapOf("bookmarks" to s.array(s.handle()), "clips" to s.dictionary(clip)))
        "timeline_marker_put" -> s.obj(s.identity + ("marker" to marker))
        "timeline_bookmark_set", "timeline_shy_set", "timeline_freeze_set", "timeline_marker_delete" -> s.obj(s.identity)
        else -> error("Timeline aid operation has no result schema: $id")
    }
}

/**
 * New motion IDs chosen before an edit is recorded, so replay reads them from the request: a deleted motion's ID
 * stays taken while its timeline aids remain, and a new motion never inherits its frozen, shy or marked tracks.
 */
internal object WorkspaceMotionIds {
    private val fields = mapOf("motion_create" to "id", "motion_duplicate" to "new_id")

    fun chosen(edit: WorkspaceDocumentOperation, clips: List<io.github.psd2live.core.MotionClip>, aids: TimelineAids) =
        chosen(listOf(edit), clips, aids).single()

    fun chosen(edits: List<WorkspaceDocumentOperation>, clips: List<io.github.psd2live.core.MotionClip>, aids: TimelineAids):
        List<WorkspaceDocumentOperation> {
        if (edits.none { it.operation in fields }) return edits
        val reserved = HashSet(aids.clips.keys)
        return edits.map { edit ->
            val field = fields[edit.operation] ?: return@map edit
            edit.request[field]?.let { reserved += it.jsonPrimitive.content; return@map edit }
            val id = io.github.psd2live.core.MotionClips.newId(clips, reserved).also { reserved += it }
            edit.copy(request = JsonObject(edit.request + (field to JsonPrimitive(id))))
        }
    }
}
