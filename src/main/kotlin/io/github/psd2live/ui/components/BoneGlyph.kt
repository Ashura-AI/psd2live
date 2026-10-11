package io.github.psd2live.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import kotlin.math.hypot

/**
 * The one bone look, shared by the canvas armature and every bone icon: a Blender-style octahedron
 * seen from the side - head, two shoulders a short way down, tail - its two facets shaded apart.
 */
internal class BoneShape(val head: Offset, val tail: Offset, val left: Offset, val right: Offset) {
	fun outline() = Path().apply {
		moveTo(head.x, head.y); lineTo(left.x, left.y); lineTo(tail.x, tail.y); lineTo(right.x, right.y); close()
	}

	fun leftFacet() = Path().apply { moveTo(head.x, head.y); lineTo(left.x, left.y); lineTo(tail.x, tail.y); close() }

	fun rightFacet() = Path().apply { moveTo(head.x, head.y); lineTo(right.x, right.y); lineTo(tail.x, tail.y); close() }

	companion object {
		/** Shoulders at [shoulder] of the length, [width] of the length to each side; null for a degenerate bone. */
		fun of(head: Offset, tail: Offset, shoulder: Float = 0.2f, width: Float = 0.1f): BoneShape? {
			val dx = tail.x - head.x
			val dy = tail.y - head.y
			val length = hypot(dx, dy)
			if (length < 1e-3f) return null
			val c = Offset(head.x + dx * shoulder, head.y + dy * shoulder)
			val n = Offset(-dy * width, dx * width)
			return BoneShape(head, tail, c + n, c - n)
		}
	}
}

/**
 * What a bone is, as far as its look goes: each kind has its own silhouette so the kind reads without color.
 * [LIMB] is the plain octahedron; [FLEX] is segmented (it bends as a curve); [BODY] is a stout half of the
 * torso; [ANCHOR] is the diamond the body hangs from and carries no tail joint.
 */
enum class BoneKind(val shoulder: Float, val width: Float) {
	LIMB(0.18f, 0.09f),
	FLEX(0.18f, 0.09f),
	BODY(0.30f, 0.15f),
	ANCHOR(0.50f, 0.28f),
}

/** Shared single-bone glyph for tree rows, overlay toggles and the mode menu. */
internal fun DrawScope.drawSingleBoneIcon(tint: Color, kind: BoneKind = BoneKind.LIMB) {
	val w = size.width
	val h = size.height
	drawBoneIcon(Offset(w * 0.16f, h * 0.84f), Offset(w * 0.88f, h * 0.12f), tint, stroke = IconPen(this, tint).px(1.2f), headRadius = w * 0.13f, kind = kind)
}

/** A bone icon: shaded octahedron with a solid head, in [tint] at icon scale. */
internal fun DrawScope.drawBoneIcon(head: Offset, tail: Offset, tint: Color, stroke: Float, headRadius: Float, kind: BoneKind = BoneKind.LIMB) {
	val shape = when (kind) {
		BoneKind.LIMB -> BoneShape.of(head, tail, shoulder = 0.24f, width = 0.16f)
		BoneKind.FLEX -> BoneShape.of(head, tail, shoulder = 0.24f, width = 0.16f)
		BoneKind.BODY -> BoneShape.of(head, tail, shoulder = 0.34f, width = 0.24f)
		BoneKind.ANCHOR -> BoneShape.of(head, tail, shoulder = 0.5f, width = 0.34f)
	} ?: return
	drawPath(shape.leftFacet(), tint.copy(alpha = tint.alpha * 0.45f))
	drawPath(shape.outline(), tint, style = Stroke(stroke, join = StrokeJoin.Round))
	if (kind == BoneKind.FLEX) drawSegmentRungs(shape, 0.24f, tint, stroke)
	drawCircle(tint, headRadius, head)
}

/** Two rungs across the body at a third and two thirds of its length: the joints a flexible bone bends between. */
private fun DrawScope.drawSegmentRungs(shape: BoneShape, shoulder: Float, color: Color, stroke: Float) {
	for (t in listOf(1f / 3f, 2f / 3f)) {
		// The octahedron's half width grows to the shoulders and falls to the tail.
		val k = if (t < shoulder) t / shoulder else (1f - t) / (1f - shoulder)
		val mid = Offset(shape.head.x + (shape.tail.x - shape.head.x) * t, shape.head.y + (shape.tail.y - shape.head.y) * t)
		val side = Offset(shape.left.x - (shape.head.x + (shape.tail.x - shape.head.x) * shoulder), shape.left.y - (shape.head.y + (shape.tail.y - shape.head.y) * shoulder))
		drawLine(color, mid + Offset(side.x * k, side.y * k), mid - Offset(side.x * k, side.y * k), strokeWidth = stroke)
	}
}

/**
 * A bone on the canvas, in screen space. Both the armature editor and the pose layer draw through
 * here, so the bones look the same in every mode: shaded facets, a solid head disc for the pivot and
 * a ring on the tail for the joint that moves. [lit] is the selected/hovered bone, [tipLit] its
 * grabbed tail; [halo] rings the joints so they read over the art.
 */
internal fun DrawScope.drawCanvasBone(
	head: Offset,
	tail: Offset,
	color: Color,
	halo: Color,
	lit: Boolean = false,
	tipLit: Boolean = false,
	strength: Float = 1f,
	kind: BoneKind = BoneKind.LIMB,
	pinned: Boolean = false,
) {
	val length = hypot(tail.x - head.x, tail.y - head.y)
	val tint = color.copy(alpha = color.alpha * strength)
	val edge = if (lit) Color.White.copy(alpha = strength) else tint
	if (length >= 6f) {
		val shoulder = (length * 0.18f).coerceIn(5f, 28f) / length
		val width = (length * 0.09f).coerceIn(3f, 12f) / length
		val shape = BoneShape.of(head, tail, shoulder, width)!!
		drawPath(shape.leftFacet(), tint.copy(alpha = (if (lit) 0.65f else 0.42f) * strength))
		drawPath(shape.rightFacet(), tint.copy(alpha = (if (lit) 0.38f else 0.22f) * strength))
		drawPath(shape.outline(), edge, style = Stroke(if (lit) 1.8f else 1.2f, join = StrokeJoin.Round))
		if (kind == BoneKind.FLEX) drawSegmentRungs(shape, shoulder, edge, if (lit) 1.8f else 1.2f)
	} else {
		drawLine(edge, head, tail, strokeWidth = if (lit) 2.6f else 1.8f)
	}
	val haloTint = halo.copy(alpha = halo.alpha * strength)
	val headRadius = if (lit) 5f else 4f
	drawCircle(haloTint, headRadius + 1.5f, head)
	drawCircle(edge, headRadius, head)
	drawCircle(tint, headRadius - 1.6f, head)
	// An anchor is only the pivot its body hangs from: no tail joint to grab.
	if (kind == BoneKind.ANCHOR) return
	val tailRadius = if (tipLit) 5.5f else if (lit) 4.4f else 3.8f
	drawCircle(haloTint, tailRadius + 1.2f, tail, style = Stroke(2.4f))
	val ring = if (tipLit) Color.White.copy(alpha = strength) else edge
	drawCircle(ring, tailRadius, tail, style = Stroke(1.6f))
	// A pinned tail has an IK target to chase: a dot in the ring, like the target's own bullseye.
	if (pinned) drawCircle(ring, 1.6f, tail)
}
