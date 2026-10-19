package io.github.psd2live.ui.views

import androidx.compose.ui.geometry.Offset
import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.project.LayerCanvasRect
import io.github.psd2live.project.LayoutReference
import io.github.psd2live.ui.BoundingHandle
import io.github.psd2live.ui.TransformDrag
import io.github.psd2live.ui.outOfTransformFrame
import io.github.psd2live.ui.rotateVector
import io.github.psd2live.ui.transformHandleAt
import kotlin.test.Test
import kotlin.test.assertEquals

class ReferenceTransformTest {
	private val reference = LayoutReference("material", LayerCanvasRect(20f, 30f, 100f, 80f), rotation = 30f)
	private fun viewport(scale: Double) = CanvasViewport(scale, 123.0, -57.0, 800f, 600f)
	private fun point(expected: Offset, actual: Offset) {
		assertEquals(expected.x, actual.x, 1e-3f)
		assertEquals(expected.y, actual.y, 1e-3f)
	}

	@Test fun movesFollowThePointerAtEveryZoomWithoutChangingSizeOrTurn() {
		for (scale in listOf(0.5, 2.0)) {
			val frame = reference.transformFrame(viewport(scale))
			val press = frame.pivot + Offset(10f, 10f)
			val drag = TransformDrag(BoundingHandle.BODY, frame.bounds, frame.pivot, frame.angleDeg, press)
			val moved = reference.transformed(frame, drag.apply(press + Offset(60f, -30f), null, false, false), scale.toFloat())
			assertEquals(reference.rect.copy(left = 20f + 60f / scale.toFloat(), top = 30f - 30f / scale.toFloat()), moved.rect)
			assertEquals(reference.rotation, moved.rotation)
		}
	}

	@Test fun aRotatedEdgeScalesOneAxisAndKeepsTheOppositeEdgeInPlace() {
		val view = viewport(2.0)
		val frame = reference.transformFrame(view)
		fun at(u: Float) = frame.bounds.at(Offset(u, 0.5f)).outOfTransformFrame(frame.pivot, frame.angleDeg)
		val press = at(1f)
		assertEquals(BoundingHandle.RIGHT, transformHandleAt(press, frame))
		val drag = TransformDrag(BoundingHandle.RIGHT, frame.bounds, frame.pivot, frame.angleDeg, press)
		val scaled = reference.transformed(frame, drag.apply(press + Offset(40f, 0f).rotateVector(frame.angleDeg), null, false, false), 2f)
		assertEquals(120f, scaled.rect.width, 1e-3f)
		assertEquals(reference.rect.height, scaled.rect.height, 1e-3f)
		val after = scaled.transformFrame(view)
		point(at(0f), after.bounds.at(Offset(0f, 0.5f)).outOfTransformFrame(after.pivot, after.angleDeg))
	}

	@Test fun shiftAndAltScaleProportionallyAroundTheAnchor() {
		val view = viewport(2.0)
		val frame = reference.transformFrame(view)
		val press = frame.bounds.at(Offset(1f, 1f)).outOfTransformFrame(frame.pivot, frame.angleDeg)
		val drag = TransformDrag(BoundingHandle.BOTTOM_RIGHT, frame.bounds, frame.pivot, frame.angleDeg, press, frame.anchor)
		val scaled = reference.transformed(frame, drag.apply(press + Offset(40f, 10f).rotateVector(frame.angleDeg), null, true, true), 2f)
		assertEquals(reference.rect.width / reference.rect.height, scaled.rect.width / scaled.rect.height, 1e-4f)
		point(frame.pivot, scaled.transformFrame(view).pivot)
	}

	@Test fun turningAroundAMovedAnchorAlsoMovesTheReferenceCentre() {
		val source = reference.copy(rotation = 0f)
		val view = viewport(2.0)
		val frame = source.transformFrame(view).withAnchor(Offset.Zero)
		val press = frame.anchor + Offset(50f, 0f)
		val drag = TransformDrag(BoundingHandle.ROTATE, frame.bounds, frame.pivot, frame.angleDeg, press, frame.anchor)
		val turned = source.transformed(frame, drag.apply(frame.anchor + Offset(0f, 50f), null, true, false), 2f)
		assertEquals(90f, turned.rotation, 1e-3f)
		assertEquals(source.rect.width, turned.rect.width)
		assertEquals(source.rect.height, turned.rect.height)
		point(frame.anchor, turned.transformFrame(view).withAnchor(Offset.Zero).anchor)
	}
}
