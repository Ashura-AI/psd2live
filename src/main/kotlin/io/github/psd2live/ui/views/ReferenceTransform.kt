package io.github.psd2live.ui.views

import androidx.compose.ui.geometry.Offset
import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.project.LayerCanvasRect
import io.github.psd2live.project.LayoutReference
import io.github.psd2live.project.normalizedRotation
import io.github.psd2live.ui.BoundingBox
import io.github.psd2live.ui.TransformDragResult
import io.github.psd2live.ui.TransformFrame
import io.github.psd2live.ui.ScaleDrag

/** Reference placement expressed in the same screen-space frame as every other transform control. */
internal fun LayoutReference.transformFrame(viewport: CanvasViewport): TransformFrame {
	val left = viewport.x(rect.left).toFloat()
	val top = (viewport.offsetY + rect.top * viewport.scale).toFloat()
	val bounds = BoundingBox(left, top, left + rect.width * viewport.scale.toFloat(), top + rect.height * viewport.scale.toFloat())
	return TransformFrame(bounds, Offset(bounds.centerX, bounds.centerY), rotation)
}

/** Convert the shared drag's centre and extents back to the document, including turns around a moved anchor. */
internal fun LayoutReference.transformed(frame: TransformFrame, result: TransformDragResult, scale: Float): LayoutReference {
	val centre = result.destination(frame.pivot)
	val width = if (result is ScaleDrag) rect.width * (result.bounds.width / frame.bounds.width) else rect.width
	val height = if (result is ScaleDrag) rect.height * (result.bounds.height / frame.bounds.height) else rect.height
	return copy(rect = LayerCanvasRect(
		rect.left + rect.width / 2f + (centre.x - frame.pivot.x) / scale - width / 2f,
		rect.top + rect.height / 2f + (centre.y - frame.pivot.y) / scale - height / 2f,
		width, height), rotation = normalizedRotation(result.frameAngle))
}
