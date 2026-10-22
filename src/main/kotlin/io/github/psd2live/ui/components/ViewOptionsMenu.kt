package io.github.psd2live.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ============================================================================
// View option icons
// ============================================================================

/** Texture: a picture, its hills and sun. */
@Composable
fun IconTextureView(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) = GridIcon(modifier, tint) {
	panel(2.2f, 2.2f, 13.6f, 13.6f, 1.6f)
	outline(path { m(3.6f, 12.6f); l(7.6f, 7.9f); l(10.4f, 10.8f); l(12.6f, 8.6f); l(14.4f, 12.2f) })
	dot(6.3f, 5.8f, 1.4f)
}

/** Mesh wireframe: the shared mesh glyph. */
@Composable
fun IconMeshWireframe(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) = GridIcon(modifier, tint) { meshPatch() }

/** Warp deformer: the shared lattice. */
@Composable
fun IconWarpDeformer(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) = GridIcon(modifier, tint) { warpLattice() }

/** Selection bounds: a dashed box with its corner handles. */
@Composable
fun IconSelectionBounds(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) = GridIcon(modifier, tint) {
	box(3.6f, 3.6f, 10.8f, 10.8f, 0f, ICON_FINE, dash = floatArrayOf(1.6f, 2.2f))
	for (x in floatArrayOf(3.6f, 14.4f)) for (y in floatArrayOf(3.6f, 14.4f)) fillBox(x - 1.6f, y - 1.6f, 3.2f, 3.2f, 0.5f)
}

/** Contextual warp: the deformer above the selection, linked to it. */
@Composable
fun IconContextualWarp(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) = GridIcon(modifier, tint) {
	box(2.5f, 2.5f, 6.5f, 5.8f, 1.2f, ICON_FINE, dash = floatArrayOf(1.6f, 1.8f))
	outline(path { m(5.75f, 8.3f); l(5.75f, 12.6f); l(9f, 12.6f) })
	panel(9f, 9.7f, 6.5f, 5.8f, 1.2f)
	dot(12.25f, 12.6f, 1.2f)
}

/** Selected only: framing brackets round the one element shown. */
@Composable
fun IconSelectedOnly(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) = GridIcon(modifier, tint) {
	brackets(2.5f, 2.5f, 15.5f, 15.5f, arm = 4f)
	fillBox(6.5f, 6.5f, 5f, 5f, 1.2f)
}

/** Dim unselected: the selected layer in front, the others faint behind it. */
@Composable
fun IconDimUnselected(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) = GridIcon(modifier, tint) {
	behind(rectPath(1.4f, 6.1f, 10.5f, 10.5f, 2.2f)) {
		box(7.2f, 2.5f, 8.3f, 8.3f, 1.5f, ICON_FINE, color.copy(alpha = color.alpha * 0.45f), floatArrayOf(1.6f, 2f))
	}
	panel(2.5f, 7.2f, 8.3f, 8.3f, 1.5f)
	dot(6.65f, 11.35f, 1.4f)
}

/** Names: a serif T. */
@Composable
fun IconWarpShowNames(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) = GridIcon(modifier, tint) {
	outline(path { m(3.6f, 5.8f); l(3.6f, 4f); l(14.4f, 4f); l(14.4f, 5.8f) })
	line(9f, 4f, 9f, 14f)
	line(6.3f, 14f, 11.7f, 14f)
}

/** Point indices: a number sign. */
@Composable
fun IconWarpShowIndices(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) = GridIcon(modifier, tint) {
	line(2.9f, 6.8f, 15.1f, 6.8f)
	line(2.9f, 11.5f, 15.1f, 11.5f)
	line(7.2f, 3.2f, 6.1f, 15.1f)
	line(11.9f, 3.2f, 10.8f, 15.1f)
}

/** The deform path icon's end anchors, as the canvas draws a path's points: each with its own ring. */
private val PATH_ICON_ENDS = listOf(3.6f to 13.7f, 14.4f to 4.3f)

/** The deform path glyph's curve and end anchors, drawn over each end's ring. */
private fun IconPen.pathIconBase() {
	outline(path { m(3.6f, 13.7f); c(3.6f, 7.3f, 14.4f, 10.7f, 14.4f, 4.3f) })
	PATH_ICON_ENDS.forEach { (x, y) -> fillBox(x - 1.4f, y - 1.4f, 2.8f, 2.8f, 0.5f) }
}

/** Path width: each point's reach, drawn as the dashed radius ring the canvas shows around it. */
@Composable
fun IconPathWidth(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) = GridIcon(modifier, tint) {
	PATH_ICON_ENDS.forEach { (x, y) -> ring(x, y, 3.2f, ICON_FINE, dash = floatArrayOf(1.2f, 1.3f)) }
	pathIconBase()
}

/** Path hardness: each point's solid core, drawn as the filled hardness disc the canvas shows around it. */
@Composable
fun IconPathHardness(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) = GridIcon(modifier, tint) {
	PATH_ICON_ENDS.forEach { (x, y) ->
		dot(x, y, 3f, soft)
		ring(x, y, 3f, ICON_FINE)
	}
	pathIconBase()
}
