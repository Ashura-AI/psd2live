package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** The handles drawn on the canvas for the selected bone, which stand in for numeric fields. */
internal enum class BoneHandle { MIN_ANGLE, MAX_ANGLE, BLEND, AXIS }

/**
 * The geometry of the canvas handles, in screen space: a fan around the bone's head whose two edges are
 * its angle limits, and a bracket across the head whose half width is its blend band. Dragging a handle
 * sets the value it shows, so a limit is placed where the limb should stop rather than typed in.
 */
internal object BoneHandles {
	/** Screen pixels within which the pointer grabs a handle. */
	const val GRAB_RADIUS = 9f

	/** Radius of the limit fan for a bone [lengthPx] long on screen. */
	fun fanRadius(lengthPx: Float): Float = (lengthPx * 0.7f).coerceIn(34f, 96f)

	private fun degrees(v: Offset): Float = Math.toDegrees(atan2(v.y.toDouble(), v.x.toDouble())).toFloat()

	private fun wrap(deg: Float): Float {
		var a = deg % 360f
		if (a > 180f) a -= 360f
		if (a <= -180f) a += 360f
		return a
	}

	/** Where the handle for a limit of [param] degrees sits: [direction] signs the parameter against the world turn. */
	fun limitPoint(head: Offset, tail: Offset, direction: Float, param: Float, radius: Float): Offset {
		val angle = Math.toRadians((degrees(tail - head) + param * direction).toDouble())
		return Offset(head.x + radius * cos(angle).toFloat(), head.y + radius * sin(angle).toFloat())
	}

	/** The limit a pointer at [pointer] asks for: at most 0 for the minimum, at least 0 for the maximum, within a half turn. */
	fun limitFrom(head: Offset, tail: Offset, direction: Float, pointer: Offset, max: Boolean): Float {
		val turn = wrap(degrees(pointer - head) - degrees(tail - head))
		val param = turn / direction
		return if (max) param.coerceIn(0f, 180f) else param.coerceIn(-180f, 0f)
	}

	/** Unit vector along which the blend band extends, or null for a zero-length axis. */
	fun axis(from: Offset, to: Offset): Offset? {
		val length = hypot(to.x - from.x, to.y - from.y)
		return if (length < 1e-3f) null else Offset((to.x - from.x) / length, (to.y - from.y) / length)
	}

	/** The blend half width, in canvas pixels, that a pointer at [pointer] asks for. */
	fun blendFrom(head: Offset, axis: Offset, pointer: Offset, scale: Float, limit: Float): Float =
		(kotlin.math.abs((pointer.x - head.x) * axis.x + (pointer.y - head.y) * axis.y) / scale).coerceIn(0f, limit)
}
