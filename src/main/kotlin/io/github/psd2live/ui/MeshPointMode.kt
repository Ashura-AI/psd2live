package io.github.psd2live.ui

import io.github.psd2live.ui.state.ShortcutAction

/**
 * The two faces of the point tool.
 *
 * Both lay vertices on the mesh by the same snap; what differs is what holds them together afterwards. A point is
 * committed at once and the triangles around it are tidied, so nothing promises an edge between two points laid
 * side by side. A line is a polyline of anchors committed with Enter, every two neighbours joined by an edge, so the
 * mesh can fold along it - an eyelid, a mouth line, a crease. They were two tools (the point tool and the knife)
 * that read as one; the journal still records them as `add_points` and `knife`.
 *
 * Each face keeps its own chord, so the keys that used to arm two tools now arm two faces of one.
 */
enum class MeshPointMode(val action: ShortcutAction, val labelKey: String, val hintKey: String) {
    POINT(ShortcutAction.TOOL_MESH_POINT, "editor.meshPoint.point", "editor.meshPointHint"),
    LINE(ShortcutAction.TOOL_KNIFE, "editor.meshPoint.line", "editor.knifeGestureHint"),
}
