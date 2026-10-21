package io.github.psd2live.ui.state

import io.github.psd2live.application.WorkspaceDocumentOperation
import io.github.psd2live.core.ExpressionBlend
import io.github.psd2live.core.ModelExpressions
import io.github.psd2live.core.MotionClips
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterId

/*
 * The animation panel's expression, pose and event actions. Each is one candidate document operation
 * ([io.github.psd2live.application.WorkspaceSidecarEdits], the motion candidate for events), committed as one history
 * node the way the public operations commit them.
 */

private fun PSD2LiveViewModel.commitSidecar(operation: String, fields: JsonObject, summary: String, completed: () -> Unit = {}) {
	val state = currentWorkspaceState() ?: return
	saveDocumentEdits(state, summary, listOf(WorkspaceDocumentOperation(operation, fields))) { failure ->
		if (failure == null) completed() else setStatusText(failure)
	}
}

private fun Map<ParameterId, Float>.json() = JsonObject(entries.associate { it.key.raw to JsonPrimitive(it.value) })

/** The expressions the panel lists: the document's, or the standard ones until the first edit. */
internal fun PSD2LiveState.modelExpressions() =
	ModelExpressions.effective(rigEdits, previewModel?.rig?.puppet?.parameters?.mapTo(HashSet()) { it.id.raw }.orEmpty())

/** A new expression of the shown pose, of saved snapshot [snapshotId], or an empty one when [fromPose] is false. */
internal fun PSD2LiveViewModel.createExpression(fromPose: Boolean, snapshotId: String? = null) {
	val current = state.value
	val values = when {
		snapshotId != null -> current.parameterSnapshots.firstOrNull { it.id == snapshotId }?.values ?: return
		fromPose -> shownParameterValues() ?: return
		else -> null
	}
	val name = snapshotId?.let { id -> current.parameterSnapshots.firstOrNull { it.id == id }?.name?.takeIf(String::isNotBlank) }
		?: tr("expression.newName")
	commitSidecar("expression_create", buildJsonObject {
		put("id", ModelExpressions.newId(current.modelExpressions())); put("name", name)
		values?.let { put("values", it.json()) }
	}, "Create expression")
}

/** Replaces [id]'s entries with the shown pose. */
internal fun PSD2LiveViewModel.captureExpression(id: String) {
	val values = shownParameterValues() ?: return
	commitSidecar("expression_capture", buildJsonObject { put("id", id); put("values", values.json()) }, "Capture expression")
}

internal fun PSD2LiveViewModel.renameExpression(id: String, name: String) {
	val trimmed = name.trim()
	if (trimmed.isEmpty() || trimmed.any(Char::isISOControl)) return
	if (state.value.modelExpressions().firstOrNull { it.id == id }?.name == trimmed) return
	commitSidecar("expression_update", buildJsonObject { put("id", id); put("name", trimmed) }, "Rename expression")
}

internal fun PSD2LiveViewModel.setExpressionFades(id: String, fadeIn: Float? = null, fadeOut: Float? = null) {
	commitSidecar("expression_update", buildJsonObject {
		put("id", id); fadeIn?.let { put("fade_in", it) }; fadeOut?.let { put("fade_out", it) }
	}, "Edit expression")
}

/** Sets, reblends or removes the entry of [parameterId]; a blend change without a value keeps the face it makes. */
internal fun PSD2LiveViewModel.setExpressionEntry(id: String, parameterId: String, blend: ExpressionBlend? = null, value: Float? = null, remove: Boolean = false) {
	commitSidecar("expression_set_entries", buildJsonObject {
		put("id", id)
		putJsonArray("entries") { add(buildJsonObject {
			put("parameter", parameterId); blend?.let { put("blend", it.name) }; value?.let { put("value", it) }
			if (remove) put("remove", true)
		}) }
	}, "Edit expression")
}

internal fun PSD2LiveViewModel.duplicateExpression(id: String) =
	commitSidecar("expression_duplicate", buildJsonObject { put("id", id) }, "Duplicate expression")

internal fun PSD2LiveViewModel.deleteExpression(id: String) =
	commitSidecar("expression_delete", buildJsonObject { put("id", id) }, "Delete expression")

internal fun PSD2LiveViewModel.moveExpression(id: String, index: Int) =
	commitSidecar("expression_move", buildJsonObject { put("id", id); put("index", index) }, "Reorder expressions")

internal fun PSD2LiveViewModel.resetExpressions() = commitSidecar("expression_reset", JsonObject(emptyMap()), "Reset expressions")

/** Shows [id] on the parameters: applied at full weight over the pose shown now, as a host plays it over a motion. */
internal fun PSD2LiveViewModel.previewExpression(id: String) {
	val current = state.value
	val parameters = current.previewModel?.rig?.puppet?.parameters ?: return
	val expression = current.modelExpressions().firstOrNull { it.id == id } ?: return
	val shown = shownParameterValues() ?: return
	val applied = ModelExpressions.applied(expression, shown.mapKeys { it.key.raw }, parameters)
	if (applied.isNotEmpty()) setParameterValues(applied.mapKeys { ParameterId(it.key) })
}

/** A new pose group holding part [partId]. */
internal fun PSD2LiveViewModel.addPoseGroup(partId: String) =
	commitSidecar("pose_add_group", buildJsonObject { putJsonArray("parts") { add(JsonPrimitive(partId)) } }, "Add pose group")

/** Group [index] holding [parts] (part to its links), in order; none left deletes it. */
internal fun PSD2LiveViewModel.setPoseGroup(index: Int, parts: List<Pair<String, List<String>>>) {
	if (parts.isEmpty()) return commitSidecar("pose_delete_group", buildJsonObject { put("index", index) }, "Delete pose group")
	commitSidecar("pose_set_group", buildJsonObject {
		put("index", index)
		putJsonArray("parts") { parts.forEach { (part, links) -> add(buildJsonObject {
			put("part", part); if (links.isNotEmpty()) putJsonArray("links") { links.forEach { add(JsonPrimitive(it)) } }
		}) } }
	}, "Edit pose group")
}

internal fun PSD2LiveViewModel.deletePoseGroup(index: Int) =
	commitSidecar("pose_delete_group", buildJsonObject { put("index", index) }, "Delete pose group")

internal fun PSD2LiveViewModel.setPoseFade(fadeIn: Float) =
	commitSidecar("pose_update", buildJsonObject { put("fade_in", fadeIn) }, "Edit pose")

/** An event on clip [clipId] at [time] (the playhead by default), or the event at [fromTime] moved there. */
internal fun PSD2LiveViewModel.setMotionEvent(clipId: String, value: String, time: Float? = null, fromTime: Float? = null) {
	val clip = state.value.rigEdits.motionClips.firstOrNull { it.id == clipId } ?: return
	if (value.isEmpty()) return
	val at = (time ?: motionEditor.playhead).coerceIn(0f, clip.duration)
	commitSidecar("motion_set_event", buildJsonObject {
		put("id", clipId); put("time", at); put("value", value); fromTime?.let { put("from_time", it) }
	}, "Edit motion event")
}

internal fun PSD2LiveViewModel.deleteMotionEvent(clipId: String, time: Float) =
	commitSidecar("motion_delete_event", buildJsonObject { put("id", clipId); put("time", time) }, "Delete motion event")

/** A value for a new event: the clip's stem and a number past the events it has. */
internal fun newMotionEventValue(clipName: String, existing: Int): String = "${MotionClips.slug(clipName)}_${existing + 1}"
