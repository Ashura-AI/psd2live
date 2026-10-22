package io.github.psd2live.project

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.WorkspaceStateCodec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import kotlin.test.*

/**
 * The motion editor's timeline aids are saved project data: edits go through the auxiliary boundary the editor and
 * the timeline_* operations share, survive the archive's presentation, and change what the editor plays without
 * touching the motion.
 */
class TimelineAidsTest {
    private val parameters = listOf(
        Parameter(ParameterId("ParamAngleX"), "Angle X", -30f, 30f, 0f),
        Parameter(ParameterId("ParamWheel"), "Wheel", 0f, 360f, 0f, repeat = true),
    )

    private fun apply(aids: TimelineAids, vararg edits: TimelineAidsEdit) =
        edits.fold(aids) { next, edit -> TimelineAidsEdits.apply(next, edit, parameters) }

    @Test fun editsBookmarkShyFreezeAndMarkAndTurnOffWhatIsGone() {
        val aids = apply(TimelineAids(),
            TimelineAidsEdit.Bookmark("ParamAngleX", true), TimelineAidsEdit.Bookmark("ParamWheel", true),
            TimelineAidsEdit.Shy("preset:Idle", "ParamAngleX", true),
            TimelineAidsEdit.Freeze("motion_1", "ParamWheel", 720f),
            TimelineAidsEdit.PutMarker("motion_1", TimelineMarker("marker_1", 0.5f, " hit ", 2)),
            TimelineAidsEdit.PutMarker("motion_1", TimelineMarker("marker_2", 0.25f)))
        assertEquals(listOf("ParamAngleX", "ParamWheel"), aids.bookmarks)
        assertEquals(setOf("ParamAngleX"), aids.clip("preset:Idle").shy)
        assertEquals(mapOf("ParamWheel" to 720f), aids.clip("motion_1").frozen, "a repeating parameter freezes past its range")
        assertEquals(listOf("marker_2", "marker_1"), aids.clip("motion_1").markers.map { it.id }, "markers keep time order")
        assertEquals("hit", aids.clip("motion_1").markers.last().label)
        assertEquals("marker_3", TimelineAidsEdits.nextMarkerId(aids.clip("motion_1")))

        assertFailsWith<IllegalArgumentException> { apply(aids, TimelineAidsEdit.Freeze("motion_1", "ParamAngleX", 40f)) }
        assertFailsWith<IllegalArgumentException> { apply(aids, TimelineAidsEdit.Shy("motion_1", "ParamGone", true)) }
        assertFailsWith<IllegalArgumentException> { apply(aids, TimelineAidsEdit.PutMarker("motion_1", TimelineMarker("m", 1f, color = 9))) }
        assertFailsWith<IllegalArgumentException> { apply(aids, TimelineAidsEdit.DeleteMarker("motion_1", "marker_9")) }
        // An aid of a deleted parameter can still be turned off, and an emptied motion leaves the record.
        val cleared = apply(aids.copy(bookmarks = aids.bookmarks + "ParamGone"), TimelineAidsEdit.Bookmark("ParamGone", false),
            TimelineAidsEdit.Shy("preset:Idle", "ParamAngleX", false))
        assertEquals(listOf("ParamAngleX", "ParamWheel"), cleared.bookmarks)
        assertFalse("preset:Idle" in cleared.clips)
    }

    @Test fun aidsSurviveTheProjectPresentationAndOlderRecordsKeepTheirFallback() {
        val aids = apply(TimelineAids(), TimelineAidsEdit.Bookmark("ParamWheel", true),
            TimelineAidsEdit.Freeze("motion_1", "ParamAngleX", -12.5f), TimelineAidsEdit.Shy("motion_1", "ParamWheel", true),
            TimelineAidsEdit.PutMarker("motion_1", TimelineMarker("marker_1", 1.25f, "step", 3)))
        assertEquals(aids, TimelineAidsCodec.decode(TimelineAidsCodec.encode(aids)))
        assertEquals(TimelineAidsCodec.encode(aids), TimelineAidsCodec.encode(TimelineAidsCodec.decode(TimelineAidsCodec.encode(aids))),
            "the encoding is stable, with no time or random value in it")
        val data = WorkspaceAuxiliaryData(timelineAids = aids)
        assertEquals(aids, WorkspaceAuxiliaryCodec.decode(WorkspaceAuxiliaryCodec.encode(data)).timelineAids)
        assertEquals(aids, WorkspaceAuxiliaryCodec.decode(JsonObject(emptyMap()), data).timelineAids, "a record without aids keeps the fallback")
        // The archive's workspace.json is the editor's presentation; reopening decodes it.
        val saved = WorkspaceStateCodec.encode(PSD2LiveState(timelineAids = aids))
        assertEquals(aids, WorkspaceStateCodec.decode(saved).timelineAids)
        // Unreadable entries drop alone.
        val damaged = buildJsonObject {
            putJsonArray("bookmarks") { add("ParamWheel"); add(7) }
            putJsonObject("clips") { putJsonObject("motion_1") { putJsonArray("markers") { add(buildJsonObject { put("time", -1) }) }
                putJsonObject("frozen") { put("ParamAngleX", 3) } } }
        }
        assertEquals(TimelineAids(listOf("ParamWheel"), mapOf("motion_1" to MotionTimelineAids(frozen = mapOf("ParamAngleX" to 3f)))),
            TimelineAidsCodec.decode(damaged))
    }

    @Test fun operationsEditOnTheAuxiliaryBoundaryAndAnswerTheirStrictSchemas() = runBlocking {
        val port = FakeAuxiliary(parameters)
        val registry = WorkspaceOperationRegistry()
        registerTimelineAidsOperations(registry, port)
        val context = WorkspaceOperationContext(MutationAuthor.AGENT)
        suspend fun call(id: String, vararg fields: Pair<String, Any>) = registry.invoke(id, JsonObject(fields.associate { (k, v) ->
            k to when (v) { is String -> JsonPrimitive(v); is Number -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); else -> error("$v") }
        }), context).data
        call("timeline_bookmark_set", "state" to port.state, "parameter" to "ParamWheel", "bookmarked" to true)
        call("timeline_shy_set", "state" to port.state, "clip_id" to "spin", "parameter" to "ParamAngleX", "shy" to true)
        call("timeline_freeze_set", "state" to port.state, "clip_id" to "spin", "parameter" to "ParamAngleX", "frozen" to true, "value" to 5)
        val marker = call("timeline_marker_put", "state" to port.state, "clip_id" to "spin", "time" to 0.5, "label" to "beat")
        assertEquals("marker_1", marker.getValue("marker").jsonObject.getValue("id").jsonPrimitive.content)
        assertFailsWith<WorkspaceConflict> { call("timeline_marker_delete", "state" to "stale", "clip_id" to "spin", "id" to "marker_1") }
        assertFailsWith<IllegalArgumentException> { call("timeline_freeze_set", "state" to port.state, "clip_id" to "spin", "parameter" to "ParamAngleX", "frozen" to true) }
        val read = call("timeline_aids_get", "clip_id" to "spin")
        assertEquals(TimelineAids(listOf("ParamWheel"), mapOf("spin" to MotionTimelineAids(setOf("ParamAngleX"), mapOf("ParamAngleX" to 5f),
            listOf(TimelineMarker("marker_1", 0.5f, "beat"))))), TimelineAidsCodec.decode(read))
        assertEquals(4, port.edits, "every edit is one auxiliary write")
    }

    @Test fun aFrozenTrackHoldsItsValueWhereTheMotionPlays() {
        val clip = MotionClip("spin", "Spin", curves = listOf(MotionCurve("ParamAngleX", listOf(MotionKey(0f, -30f), MotionKey(2f, 30f)))))
        val sampled = MotionClips.sampleAll(clip, 1.5)
        val held = sampled + mapOf(ParameterId("ParamAngleX") to 12f)
        assertNotEquals(12f, sampled.getValue(ParameterId("ParamAngleX")))
        assertEquals(12f, boundedPreviewPose(held, parameters).getValue(ParameterId("ParamAngleX")))
        assertEquals(30f, boundedPreviewPose(mapOf(ParameterId("ParamWheel") to 390f), parameters).getValue(ParameterId("ParamWheel")),
            "a repeating parameter wraps where an ordinary one clamps")
        assertEquals(330f, boundedPreviewPose(mapOf(ParameterId("ParamWheel") to -30f), parameters).getValue(ParameterId("ParamWheel")))
        assertEquals(360f, boundedPreviewPose(mapOf(ParameterId("ParamWheel") to 360f), parameters).getValue(ParameterId("ParamWheel")))
    }

    /** The auxiliary boundary over one in-memory record, as [WorkspaceHost] keeps it. */
    private class FakeAuxiliary(private val parameters: List<Parameter>) : WorkspaceAuxiliaryPort {
        var data = WorkspaceAuxiliaryData()
        var state = "state:0"
        var edits = 0
        override fun savedProjectData() = WorkspaceAuxiliarySnapshot("project", state, "node", data, setOf("node"))
        override fun editSavedProjectData(state: String, edit: WorkspaceAuxiliaryEdit): WorkspaceAuxiliarySnapshot {
            if (state != this.state) throw WorkspaceConflict(state, this.state)
            data = WorkspaceAuxiliaryEdits.apply(data, edit, parameters, setOf("node"))
            this.state = "state:${++edits}"
            return savedProjectData()
        }
        override fun applySavedSnapshotPose(state: String, id: String) = error("unused")
    }
}
