package io.github.psd2live.project

import kotlinx.serialization.json.*
import org.umamo.runtime.model.Parameter

/** A named moment on a motion's timeline: a cue to time keys against. [color] indexes the editor's track colours. */
data class TimelineMarker(val id: String, val time: Float, val label: String = "", val color: Int = 0) {
    init {
        require(id.isNotBlank() && time.isFinite() && time >= 0f) { "A marker needs an ID and a time" }
    }
}

/**
 * How the editor shows one motion: tracks hidden as shy, tracks frozen at a value while it plays, and markers. Keyed by
 * the editor's clip ID ([TimelineAids.keyOf]), so a generated motion keeps them once edited.
 */
data class MotionTimelineAids(
    val shy: Set<String> = emptySet(),
    val frozen: Map<String, Float> = emptyMap(),
    val markers: List<TimelineMarker> = emptyList(),
) {
    val isEmpty: Boolean get() = shy.isEmpty() && frozen.isEmpty() && markers.isEmpty()
}

/**
 * The timeline aids saved with the project: bookmarked parameters and each motion's [MotionTimelineAids]. They are
 * editor data like saved poses - never part of the rig, its history or the export.
 */
data class TimelineAids(
    val bookmarks: List<String> = emptyList(),
    val clips: Map<String, MotionTimelineAids> = emptyMap(),
) {
    fun clip(id: String?): MotionTimelineAids = id?.let(clips::get) ?: MotionTimelineAids()

    companion object {
        /** The editor's ID for [clip]: a generated motion's override keeps its generated motion's aids. */
        fun keyOf(clipId: String, builtin: String?): String = builtin?.let { "preset:$it" } ?: clipId
    }
}

sealed interface TimelineAidsEdit {
    data class Bookmark(val parameterId: String, val bookmarked: Boolean) : TimelineAidsEdit
    data class Shy(val clipId: String, val parameterId: String, val shy: Boolean) : TimelineAidsEdit
    /** Freezes the track at [value], or thaws it when null. */
    data class Freeze(val clipId: String, val parameterId: String, val value: Float?) : TimelineAidsEdit
    /** Adds [marker], or replaces the one with its ID. */
    data class PutMarker(val clipId: String, val marker: TimelineMarker) : TimelineAidsEdit
    data class DeleteMarker(val clipId: String, val markerId: String) : TimelineAidsEdit
}

object TimelineAidsEdits {
    const val MAX_MARKERS = 256
    const val MAX_LABEL = 64
    const val COLORS = 8

    fun apply(current: TimelineAids, edit: TimelineAidsEdit, parameters: List<Parameter>): TimelineAids {
        val known = parameters.associateBy { it.id.raw }
        fun parameter(id: String) = requireNotNull(known[id]) { "Unknown parameter: $id" }
        fun clipId(id: String) = id.also { require(it.isNotBlank() && it.length <= 128 && it.none(Char::isISOControl)) { "Invalid motion ID" } }
        fun withClip(id: String, change: (MotionTimelineAids) -> MotionTimelineAids): TimelineAids {
            val next = change(current.clip(clipId(id)))
            return current.copy(clips = if (next.isEmpty) current.clips - id else current.clips + (id to next))
        }
        return when (edit) {
            // Turning an aid off never needs the parameter: it may be gone.
            is TimelineAidsEdit.Bookmark -> {
                if (edit.bookmarked) parameter(edit.parameterId)
                val without = current.bookmarks - edit.parameterId
                current.copy(bookmarks = if (edit.bookmarked) without + edit.parameterId else without)
            }
            is TimelineAidsEdit.Shy -> {
                if (edit.shy) parameter(edit.parameterId)
                withClip(edit.clipId) { it.copy(shy = if (edit.shy) it.shy + edit.parameterId else it.shy - edit.parameterId) }
            }
            is TimelineAidsEdit.Freeze -> {
                edit.value?.let { value ->
                    val definition = parameter(edit.parameterId)
                    require(value.isFinite() && (definition.repeat || value in definition.min..definition.max)) { "${edit.parameterId} outside parameter range" }
                }
                withClip(edit.clipId) { aids ->
                    aids.copy(frozen = edit.value?.let { aids.frozen + (edit.parameterId to it) } ?: (aids.frozen - edit.parameterId))
                }
            }
            is TimelineAidsEdit.PutMarker -> {
                val marker = edit.marker
                require(marker.label.length <= MAX_LABEL && marker.label.none(Char::isISOControl)) { "Marker labels are one line of at most $MAX_LABEL characters" }
                require(marker.color in 0 until COLORS) { "Marker colour must be 0..${COLORS - 1}" }
                withClip(edit.clipId) { aids ->
                    val others = aids.markers.filterNot { it.id == marker.id }
                    require(others.size < MAX_MARKERS) { "A motion holds at most $MAX_MARKERS markers" }
                    aids.copy(markers = (others + marker.copy(label = marker.label.trim())).sortedWith(compareBy({ it.time }, { it.id })))
                }
            }
            is TimelineAidsEdit.DeleteMarker -> withClip(edit.clipId) { aids ->
                require(aids.markers.any { it.id == edit.markerId }) { "Marker not found: ${edit.markerId}" }
                aids.copy(markers = aids.markers.filterNot { it.id == edit.markerId })
            }
        }
    }

    /** The next unused marker ID of [aids]: numbered, never random, so a replayed request names the same marker. */
    fun nextMarkerId(aids: MotionTimelineAids): String {
        val used = aids.markers.mapNotNullTo(HashSet()) { it.id.removePrefix("marker_").toIntOrNull() }
        var index = (used.maxOrNull() ?: 0) + 1
        while (index in used) index++
        return "marker_$index"
    }
}

object TimelineAidsCodec {
    const val KEY = "timelineAids"

    fun encode(value: TimelineAids): JsonObject = buildJsonObject {
        putJsonArray("bookmarks") { value.bookmarks.forEach { add(it) } }
        putJsonObject("clips") {
            value.clips.toSortedMap().forEach { (id, aids) ->
                putJsonObject(id) {
                    putJsonArray("shy") { aids.shy.sorted().forEach { add(it) } }
                    putJsonObject("frozen") { aids.frozen.toSortedMap().forEach { (parameter, frozen) -> put(parameter, frozen) } }
                    putJsonArray("markers") {
                        aids.markers.forEach { marker ->
                            add(buildJsonObject {
                                put("id", marker.id); put("time", marker.time); put("label", marker.label); put("color", marker.color)
                            })
                        }
                    }
                }
            }
        }
    }

    /** Lenient: an unreadable entry is dropped, never the whole record. */
    fun decode(value: JsonElement?): TimelineAids {
        val obj = value as? JsonObject ?: return TimelineAids()
        val bookmarks = obj["bookmarks"]?.let { it as? JsonArray }.orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content?.takeIf(String::isNotBlank) }.distinct()
        val clips = (obj["clips"] as? JsonObject).orEmpty().mapNotNull { (id, element) ->
            val clip = element as? JsonObject ?: return@mapNotNull null
            val aids = MotionTimelineAids(
                shy = (clip["shy"] as? JsonArray).orEmpty().mapNotNullTo(LinkedHashSet()) { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content },
                frozen = (clip["frozen"] as? JsonObject).orEmpty().mapNotNull { (parameter, frozen) ->
                    (frozen as? JsonPrimitive)?.floatOrNull?.takeIf(Float::isFinite)?.let { parameter to it }
                }.toMap(),
                markers = (clip["markers"] as? JsonArray).orEmpty().mapNotNull { entry ->
                    val marker = entry as? JsonObject ?: return@mapNotNull null
                    runCatching {
                        TimelineMarker(marker.getValue("id").jsonPrimitive.content, marker.getValue("time").jsonPrimitive.float,
                            marker["label"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            (marker["color"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, TimelineAidsEdits.COLORS - 1))
                    }.getOrNull()
                },
            )
            if (aids.isEmpty) null else id to aids
        }.toMap()
        return TimelineAids(bookmarks, clips)
    }
}
