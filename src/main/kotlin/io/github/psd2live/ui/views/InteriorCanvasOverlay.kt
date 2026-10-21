package io.github.psd2live.ui.views

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.core.interior.*
import io.github.psd2live.core.mesh.structure.StructureKind
import io.github.psd2live.ui.InteriorCanvasTool
import io.github.psd2live.ui.InteriorCanvasTool.Part
import io.github.psd2live.ui.theme.ToolColors
import kotlin.math.hypot

/**
 * The interior tool's canvas layer: the virtual surface's frame and arch with the cross of its four amount handles, the
 * propagation arrow, and every guide in its kind's tone with the fold reach as a band. Points and wires are drawn in
 * [MeshLook]; the lit part is the one hovered or held.
 */
internal fun DrawScope.drawInteriorHandles(tool: InteriorCanvasTool, viewport: CanvasViewport, colors: ToolColors) {
	val scene = tool.scene() ?: return
	val lit = tool.active ?: tool.hover
	val draft = scene.draft
	val dash = PathEffect.dashPathEffect(floatArrayOf(5f, 4f), 0f)

	fun polyline(points: List<Offset>, tone: Color, width: Float, effect: PathEffect? = null) {
		if (points.size < 2) return
		val path = Path().apply { points.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
		drawPath(path, MeshLook.Halo, style = Stroke(width + MeshLook.HALO_EXTRA, cap = StrokeCap.Round, pathEffect = effect))
		drawPath(path, tone, style = Stroke(width, cap = StrokeCap.Round, pathEffect = effect))
	}

	// The surface: the visible frame it spans, its arch at rest and leaning as a full input leans it.
	scene.surface?.let { frame ->
		val corners = tool.screen(scene, listOf(frame.at(0f, 0f), frame.at(1f, 0f), frame.at(1f, 1f), frame.at(0f, 1f), frame.at(0f, 0f)), viewport)
		polyline(corners, MeshLook.Structure.copy(alpha = MeshLook.WIRE_ALPHA), MeshLook.WIRE, dash)
		for (vertical in listOf(false, true)) {
			polyline(tool.screen(scene, InteriorHandles.arch(frame, draft.shape, pressed = false, vertical = vertical), viewport),
				colors.accent.copy(alpha = if (vertical) 0.45f else 0.85f), 1.4f)
			if (draft.shape.press > 0f && draft.shape.bulge > 0f)
				polyline(tool.screen(scene, InteriorHandles.arch(frame, draft.shape, pressed = true, vertical = vertical), viewport),
					colors.accent.copy(alpha = if (vertical) 0.3f else 0.55f), 1.2f, dash)
		}
		val handles = tool.surfaceHandles(scene, viewport)
		val center = tool.screen(scene, listOf(InteriorPoint(frame.cx, frame.cy)), viewport).single()
		drawMeshWires(handles.values.flatMap { listOf(center, it) }, MeshLook.Structure)
		drawMeshHandle(center, MeshLook.Structure, colors.accent)
		for ((handle, o) in handles) {
			val shape = if (handle == InteriorHandle.HORIZONTAL || handle == InteriorHandle.VERTICAL) HandleShape.SQUARE else HandleShape.ROUND
			drawMeshHandle(o, MeshLook.Structure, colors.accent, selected = lit == Part.Handle(handle) && tool.dragging,
				hovered = lit == Part.Handle(handle), shape = shape)
		}
	}

	// The propagation: an arrow from where a move starts to where it arrives last.
	val propagation = tool.propagationHandles(scene, viewport)
	val from = propagation[InteriorHandle.FROM]
	val to = propagation[InteriorHandle.TO]
	if (from != null && to != null) {
		val tone = colors.accent
		polyline(listOf(from, to), tone, 1.6f)
		val length = hypot(to.x - from.x, to.y - from.y)
		if (length > 1f) {
			val ux = (to.x - from.x) / length; val uy = (to.y - from.y) / length
			val head = 9f
			val left = Offset(to.x - ux * head - uy * head * .55f, to.y - uy * head + ux * head * .55f)
			val right = Offset(to.x - ux * head + uy * head * .55f, to.y - uy * head - ux * head * .55f)
			polyline(listOf(left, to, right), tone, 1.6f)
		}
		for ((handle, o) in propagation) drawMeshHandle(o, MeshLook.Structure, tone,
			selected = lit == Part.Handle(handle) && tool.dragging, hovered = lit == Part.Handle(handle),
			shape = if (handle == InteriorHandle.DIRECTION) HandleShape.SQUARE else HandleShape.ROUND)
	}

	// The guides: folds with their reach, the lines a remesh found in their kind's tone; the selected one cased.
	val selected = tool.selectedGuide(scene)
	val lines = tool.guideLines(scene, viewport)
	for ((i, g) in draft.guides.withIndex()) {
		val points = lines[i]
		val fold = g.purpose == InteriorGuidePurpose.FOLD
		val tone = when {
			fold -> StructureLook.Fold
			g.detected == StructureKind.SHADE -> StructureLook.Shade
			else -> StructureLook.Pattern
		}
		if (fold && points.size >= 2) {
			// The reach as a band: the radius is in raster pixels, so measure it on screen along the line.
			val (mid, edge) = InteriorGuideEdits.radiusHandle(g)
			val (a, b) = tool.screen(scene, listOf(mid, edge), viewport)
			val band = 2f * hypot(b.x - a.x, b.y - a.y)
			drawPoints(points, PointMode.Polygon, tone.copy(alpha = 0.14f), band, StrokeCap.Round)
		}
		val hot = (lit as? Part.Segment)?.guide == i || (lit as? Part.Point)?.guide == i
		if (i == selected) drawSelectedWires(points.zipWithNext().flatMap { listOf(it.first, it.second) }, colors.accent)
		val alpha = if (fold || g.detected == null || hot) 1f else StructureLook.UNFOLLOWED_ALPHA
		polyline(points, tone.copy(alpha = alpha), if (hot) StructureLook.LINE * 1.5f else StructureLook.LINE)
		// Points show where they can be picked without crowding: on drawn guides, and on any guide in hand.
		if (g.detected == null || i == selected || hot) for ((j, o) in points.withIndex()) {
			val picked = i == selected && tool.selected?.second == j
			drawMeshHandle(o, tone, colors.accent, selected = picked, hovered = lit == Part.Point(i, j))
		}
	}
	tool.radiusHandle(scene, viewport)?.let { (mid, handle) ->
		polyline(listOf(mid, handle), StructureLook.Fold, MeshLook.WIRE, dash)
		drawMeshHandle(handle, StructureLook.Fold, colors.accent, selected = lit is Part.Radius && tool.dragging,
			hovered = lit is Part.Radius, shape = HandleShape.SQUARE)
	}

	// The line being drawn, and the segment that would follow the pointer.
	if (tool.drawing.isNotEmpty()) {
		val drawn = tool.screen(scene, tool.drawing, viewport)
		polyline(drawn, StructureLook.Fold, StructureLook.LINE)
		tool.cursor?.let { polyline(listOf(drawn.last(), it), StructureLook.Fold.copy(alpha = 0.6f), StructureLook.LINE, dash) }
		for ((j, o) in drawn.withIndex()) drawMeshHandle(o, StructureLook.Fold, colors.accent, selected = j == drawn.lastIndex)
	}
}
