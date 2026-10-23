package io.github.psd2live.application

import io.github.psd2live.core.RigIdConversion
import io.github.psd2live.project.WorkspaceAuxiliaryCodec
import kotlinx.serialization.json.JsonObject
import org.umamo.runtime.model.ParameterId

/**
 * Editor data beside the rig that names parameters - saved poses, the workspaces' authored poses and the timeline aids -
 * follows `convert_id`. The conversions one version's journal holds over another's say which IDs moved: forwards on a
 * commit, back on an undo, both across a checkout to another branch.
 */
internal object WorkspaceConvertedIds {
    /** Each parameter ID of the version recorded by [before] as the one recorded by [after] calls it. */
    fun parameters(before: List<JsonObject>, after: List<JsonObject>): Map<String, String> {
        if (before === after) return emptyMap()
        val limit = minOf(before.size, after.size)
        var shared = 0
        while (shared < limit && (before[shared] === after[shared] || before[shared] == after[shared])) shared++
        val undone = RigIdConversion.renames(before.subList(shared, before.size)).parameters
        val done = RigIdConversion.renames(after.subList(shared, after.size)).parameters
        if (undone.isEmpty() && done.isEmpty()) return emptyMap()
        val back = undone.entries.associate { (from, to) -> to to from }
        return RigIdConversion.Renames(mapOf("parameter" to back)).then(RigIdConversion.Renames(mapOf("parameter" to done))).parameters
    }

    /** [auxiliary] with its saved poses and timeline aids naming [renames]' new IDs; authored poses follow in [PreviewSessions.migrate]. */
    fun auxiliary(auxiliary: JsonObject, renames: Map<String, String>): JsonObject {
        if (renames.isEmpty()) return auxiliary
        fun id(raw: String) = renames[raw] ?: raw
        val data = WorkspaceAuxiliaryCodec.decode(auxiliary)
        val aids = data.timelineAids
        val next = data.copy(
            parameterSnapshots = data.parameterSnapshots.map { snapshot -> snapshot.copy(values = snapshot.values.mapKeys { ParameterId(id(it.key.raw)) }) },
            timelineAids = aids.copy(bookmarks = aids.bookmarks.map(::id), clips = aids.clips.mapValues { (_, clip) ->
                clip.copy(shy = clip.shy.mapTo(LinkedHashSet(), ::id), frozen = clip.frozen.mapKeys { id(it.key) })
            }),
        )
        return if (next == data) auxiliary else JsonObject(auxiliary + WorkspaceAuxiliaryCodec.encode(next))
    }
}
