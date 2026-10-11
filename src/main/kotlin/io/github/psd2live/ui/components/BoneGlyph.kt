package io.github.psd2live.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import kotlin.math.hypot
import kotlin.math.roundToInt

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
	if (kind == BoneKind.FLEX) {
		drawBeads(head, tail, 3, gap = 0.07f, width = 0.34f, tint, tint, tint.alpha * 0.45f, tint.alpha * 0.2f, stroke)
		drawCircle(tint, headRadius, head)
		return
	}
	val shape = when (kind) {
		BoneKind.LIMB -> BoneShape.of(head, tail, shoulder = 0.24f, width = 0.16f)
		BoneKind.FLEX -> null
		BoneKind.BODY -> BoneShape.of(head, tail, shoulder = 0.34f, width = 0.24f)
		BoneKind.ANCHOR -> BoneShape.of(head, tail, shoulder = 0.5f, width = 0.34f)
	} ?: return
	drawPath(shape.leftFacet(), tint.copy(alpha = tint.alpha * 0.45f))
	drawPath(shape.outline(), tint, style = Stroke(stroke, join = StrokeJoin.Round))
	drawCircle(tint, headRadius, head)
}

/**
 * A flexible bone drawn as a chain of [count] beads - small diamonds with a [gap] between them, each [width] wide
 * as a fraction of its length - so it reads as a row of joints that bend, not as one rigid bone. Fractions are of
 * the whole bone's length; [facet] and [back] are the alphas of the two shaded faces.
 */
private fun DrawScope.drawBeads(
	head: Offset, tail: Offset, count: Int, gap: Float, width: Float,
	fill: Color, edge: Color, facet: Float, back: Float, stroke: Float,
) {
	val dx = tail.x - head.x
	val dy = tail.y - head.y
	val slot = 1f / count
	for (i in 0 until count) {
		val from = i * slot + gap * slot / 2f
		val to = (i + 1) * slot - gap * slot / 2f
		val h = Offset(head.x + dx * from, head.y + dy * from)
		val t = Offset(head.x + dx * to, head.y + dy * to)
		val shape = BoneShape.of(h, t, 0.5f, width) ?: continue
		drawPath(shape.leftFacet(), fill.copy(alpha = facet))
		drawPath(shape.rightFacet(), fill.copy(alpha = back))
		drawPath(shape.outline(), edge, style = Stroke(stroke, join = StrokeJoin.Round))
	}
}

/**
 * Shows what IK will do for the selected bone: the chain it may turn is laid over in [color], the root it pivots
 * from is pinned with a square, and a bend direction other than automatic is an arrow arcing at the elbow, the
 * joint that folds. [joints] run root head to effector tail.
 */
internal fun DrawScope.drawIkGuide(joints: List<Offset>, color: Color, bendDirection: Int) {
	if (joints.size < 2) return
	val path = Path().apply {
		moveTo(joints[0].x, joints[0].y)
		for (i in 1 until joints.size) lineTo(joints[i].x, joints[i].y)
	}
	drawPath(path, color.copy(alpha = 0.22f), style = Stroke(9f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = StrokeJoin.Round))
	drawPath(path, color.copy(alpha = 0.6f), style = Stroke(1.4f, join = StrokeJoin.Round,
		pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5f, 4f))))
	val root = joints.first()
	drawRect(color, androidx.compose.ui.geometry.Offset(root.x - 4f, root.y - 4f), androidx.compose.ui.geometry.Size(8f, 8f), style = Stroke(1.6f))
	if (bendDirection == 0 || joints.size < 3) return
	// The arrow sweeps round the elbow in the direction the joint folds: clockwise on screen is positive.
	val elbow = joints[joints.size - 2]
	val radius = 14f
	val sign = if (bendDirection > 0) 1f else -1f
	val start = -60f
	val sweep = 240f * sign
	drawArc(color, start, sweep, useCenter = false, topLeft = Offset(elbow.x - radius, elbow.y - radius),
		size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2), style = Stroke(1.8f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
	val end = Math.toRadians((start + sweep).toDouble())
	val tip = Offset(elbow.x + radius * kotlin.math.cos(end).toFloat(), elbow.y + radius * kotlin.math.sin(end).toFloat())
	// Tangent along the sweep, for the arrow head.
	val tangent = Offset(-kotlin.math.sin(end).toFloat() * sign, kotlin.math.cos(end).toFloat() * sign)
	val normal = Offset(-tangent.y, tangent.x)
	val head = Path().apply {
		moveTo(tip.x + tangent.x * 5f, tip.y + tangent.y * 5f)
		lineTo(tip.x - tangent.x * 2f + normal.x * 4f, tip.y - tangent.y * 2f + normal.y * 4f)
		lineTo(tip.x - tangent.x * 2f - normal.x * 4f, tip.y - tangent.y * 2f - normal.y * 4f)
		close()
	}
	drawPath(head, color)
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
		if (kind == BoneKind.FLEX && length >= 24f) {
			// A chain of beads about 16px long each, as wide as a plain bone of the same length is.
			val count = (length / 16f).roundToInt().coerceIn(2, 10)
			val beadWidth = (if (lit) 6.5f else 5.5f) / (length / count)
			drawBeads(head, tail, count, gap = 4f / (length / count), width = beadWidth, tint, edge,
				(if (lit) 0.65f else 0.42f) * strength, (if (lit) 0.38f else 0.22f) * strength, if (lit) 1.8f else 1.2f)
		} else {
			val shoulder = (length * kind.shoulder).coerceIn(5f, 28f * kind.shoulder / 0.18f) / length
			val width = (length * kind.width).coerceIn(3f, 12f * kind.width / 0.09f) / length
			val shape = BoneShape.of(head, tail, shoulder, width)!!
			drawPath(shape.leftFacet(), tint.copy(alpha = (if (lit) 0.65f else 0.42f) * strength))
			drawPath(shape.rightFacet(), tint.copy(alpha = (if (lit) 0.38f else 0.22f) * strength))
			drawPath(shape.outline(), edge, style = Stroke(if (lit) 1.8f else 1.2f, join = StrokeJoin.Round))
		}
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
