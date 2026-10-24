package io.github.psd2live.ui.views

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.core.modifier.ModifierHandle
import io.github.psd2live.ui.ModifierCanvasTool
import io.github.psd2live.ui.theme.ToolColors

/**
 * The modifier tool's canvas layer: where the modifier rests dashed in the structure's tone, what it makes of it
 * solid in the accent (a bent axis, a twirled diameter, the wave, the control net), and its handles. Points that
 * stand for a place are round, the ones that set an amount or a direction square; the lit one is hovered or held.
 */
internal fun DrawScope.drawModifierHandles(tool: ModifierCanvasTool, viewport: CanvasViewport, colors: ToolColors) {
	val scene = tool.scene() ?: return
	val lit = tool.active ?: tool.hover
	val dash = PathEffect.dashPathEffect(floatArrayOf(5f, 4f), 0f)

	fun polyline(points: List<Offset>, tone: Color, width: Float, effect: PathEffect? = null) {
		if (points.size < 2) return
		val path = Path().apply { points.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
		drawPath(path, MeshLook.Halo, style = Stroke(width + MeshLook.HALO_EXTRA, cap = StrokeCap.Round, pathEffect = effect))
		drawPath(path, tone, style = Stroke(width, cap = StrokeCap.Round, pathEffect = effect))
	}

	for ((points, dashed) in tool.guides(scene, viewport)) {
		if (dashed) polyline(points, MeshLook.Structure.copy(alpha = MeshLook.WIRE_ALPHA), MeshLook.WIRE, dash)
		else polyline(points, colors.accent.copy(alpha = 0.85f), 1.4f)
	}
	// A path's points where they rest go under the moved ones, fainter.
	for ((handle, o) in tool.handles(scene, viewport).entries.sortedBy { it.key.role != ModifierHandle.Role.PATH }) {
		val tone = if (handle.role == ModifierHandle.Role.PATH) MeshLook.Structure.copy(alpha = MeshLook.WIRE_ALPHA) else MeshLook.Structure
		drawMeshHandle(o, tone, colors.accent, selected = lit == handle && tool.dragging, hovered = lit == handle,
			shape = if (handle.role.point) HandleShape.ROUND else HandleShape.SQUARE)
	}
}
