package io.github.psd2live.project

import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterId

/** Saved poses belong to the portable project, not to the exported rig or rig history. */
data class ParameterSnapshot(val id: String, val number: Int, val name: String, val values: Map<ParameterId, Float>)
data class HistoryAnnotation(val title: String = "", val note: String = "", val hidden: Boolean = false)

/**
 * A ruler guide of the edit canvases: a [vertical] line at x = [position] canvas pixels, or a horizontal one at
 * y = [position]. Drawing help only; the rig and its exports never see it.
 */
data class CanvasGuide(val id: String, val vertical: Boolean, val position: Float)

data class WorkspaceAuxiliaryData(
    val parameterSnapshots: List<ParameterSnapshot> = emptyList(),
    val historyAnnotations: Map<String, HistoryAnnotation> = emptyMap(),
    val canvasGuides: List<CanvasGuide> = emptyList(),
    val timelineAids: TimelineAids = TimelineAids(),
)

/** These field names are the existing v1 presentation format; loading preserves IDs and numbers. */
object WorkspaceAuxiliaryCodec {
    fun encode(value: WorkspaceAuxiliaryData): JsonObject = buildJsonObject {
        putJsonArray("parameterSnapshots") { value.parameterSnapshots.forEach { snapshot -> add(buildJsonObject {
            put("id", snapshot.id); put("number", snapshot.number); put("name", snapshot.name)
            putJsonObject("values") { snapshot.values.forEach { (id, value) -> put(id.raw, value) } }
        }) } }
        putJsonObject("historyAnnotations") { value.historyAnnotations.forEach { (id, annotation) ->
            putJsonObject(id) { put("title", annotation.title); put("note", annotation.note); put("hidden", annotation.hidden) }
        } }
        putJsonArray("canvasGuides") { value.canvasGuides.forEach { guide -> add(buildJsonObject {
            put("id", guide.id); put("axis", if (guide.vertical) "x" else "y"); put("position", guide.position)
        }) } }
        put(TimelineAidsCodec.KEY, TimelineAidsCodec.encode(value.timelineAids))
    }

    fun decode(value: JsonObject, fallback: WorkspaceAuxiliaryData = WorkspaceAuxiliaryData()): WorkspaceAuxiliaryData = WorkspaceAuxiliaryData(
        parameterSnapshots = value["parameterSnapshots"]?.jsonArray?.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            ParameterSnapshot(id, obj["number"]?.jsonPrimitive?.intOrNull ?: index + 1,
                obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                obj["values"]?.jsonObject?.mapNotNull { (parameter, element) ->
                    element.jsonPrimitive.floatOrNull?.takeIf(Float::isFinite)?.let { ParameterId(parameter) to it }
                }?.toMap().orEmpty())
        } ?: fallback.parameterSnapshots,
        historyAnnotations = value["historyAnnotations"]?.jsonObject?.mapValues { (_, element) ->
            val annotation = element.jsonObject
            HistoryAnnotation(annotation.getValue("title").jsonPrimitive.content,
                annotation.getValue("note").jsonPrimitive.content, annotation.getValue("hidden").jsonPrimitive.boolean)
        } ?: fallback.historyAnnotations,
        canvasGuides = value["canvasGuides"]?.jsonArray?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val axis = obj["axis"]?.jsonPrimitive?.contentOrNull?.takeIf { it == "x" || it == "y" } ?: return@mapNotNull null
            val position = obj["position"]?.jsonPrimitive?.floatOrNull?.takeIf(Float::isFinite) ?: return@mapNotNull null
            CanvasGuide(id, axis == "x", position)
        }?.distinctBy { it.id } ?: fallback.canvasGuides,
        timelineAids = value[TimelineAidsCodec.KEY]?.let(TimelineAidsCodec::decode) ?: fallback.timelineAids,
    )
}
