package io.github.psd2live.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import io.github.psd2live.project.LayerTransform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The paint mode's Free Transform box, Photoshop's: the pixels being moved sit in a box whose handles scale them, the
 * inside moves them and the outside turns them. All of it in canvas units as the canvas shows them; the box starts as
 * the parallelogram [origin] + u [u] + v [v] for u, v in 0..1 - the lifted area, slanted with a turned layer - and
 * [transform] is what the gestures have done to it so far.
 */
internal class PaintTransformBox(val origin: Offset, val u: Offset, val v: Offset) {
    /** The handles, at their place in the box's own 0..1 square; [MOVE] and [ROTATE] are the inside and the outside. */
    enum class Handle(val at: Offset?) {
        TOP_LEFT(Offset(0f, 0f)), TOP(Offset(0.5f, 0f)), TOP_RIGHT(Offset(1f, 0f)), RIGHT(Offset(1f, 0.5f)),
        BOTTOM_RIGHT(Offset(1f, 1f)), BOTTOM(Offset(0.5f, 1f)), BOTTOM_LEFT(Offset(0f, 1f)), LEFT(Offset(0f, 0.5f)),
        MOVE(null), ROTATE(null);

        val corner: Boolean get() = at != null && at.x != 0.5f && at.y != 0.5f
    }

    var transform by mutableStateOf(LayerTransform.IDENTITY)

    /** The box's own square onto the canvas, before any gesture. */
    private val square = LayerTransform(u.x, u.y, v.x, v.y, origin.x, origin.y)

    /** Where the point (s, t) of the box's own square is shown now. */
    fun point(s: Float, t: Float): Offset {
        val m = transform.after(square)
        return Offset(m.x(s, t), m.y(s, t))
    }

    /** The four corners as shown now: top-left, top-right, bottom-right, bottom-left. */
    val corners: List<Offset> get() = listOf(point(0f, 0f), point(1f, 0f), point(1f, 1f), point(0f, 1f))
    val center: Offset get() = point(0.5f, 0.5f)

    /**
     * What a press at [p] takes: a handle within [reach] canvas units of it, else moving when inside the box, else
     * turning it.
     */
    fun hit(p: Offset, reach: Float): Handle {
        Handle.entries.filter { it.at != null }
            .minByOrNull { (point(it.at!!.x, it.at.y) - p).getDistance() }
            ?.takeIf { (point(it.at!!.x, it.at.y) - p).getDistance() <= reach }?.let { return it }
        return if (inside(p)) Handle.MOVE else Handle.ROTATE
    }

    private fun inside(p: Offset): Boolean {
        val local = transform.after(square).takeIf { abs(it.a * it.d - it.b * it.c) > 1e-9f }?.inverse() ?: return false
        val s = local.x(p.x, p.y); val t = local.y(p.x, p.y)
        return s in 0f..1f && t in 0f..1f
    }

    /**
     * The transform after dragging [handle] from [from] to [to], starting from [start] (the transform at the press).
     * A corner keeps the proportions unless [free]; [fromCenter] scales about the middle instead of the opposite
     * side; [snap] turns in 15° steps.
     */
    fun dragged(handle: Handle, start: LayerTransform, from: Offset, to: Offset,
                free: Boolean = false, fromCenter: Boolean = false, snap: Boolean = false): LayerTransform = when (handle) {
        Handle.MOVE -> LayerTransform(1f, 0f, 0f, 1f, to.x - from.x, to.y - from.y).after(start)
        Handle.ROTATE -> {
            val box = start.after(square)
            val c = Offset(box.x(0.5f, 0.5f), box.y(0.5f, 0.5f))
            var angle = atan2(to.y - c.y, to.x - c.x) - atan2(from.y - c.y, from.x - c.x)
            if (snap) angle = ((angle / SNAP).roundToInt() * SNAP).toFloat()
            val cs = cos(angle); val sn = sin(angle)
            LayerTransform(cs, sn, -sn, cs, c.x - cs * c.x + sn * c.y, c.y - sn * c.x - cs * c.y).after(start)
        }
        else -> scaled(handle, start, to, free, fromCenter)
    }

    private fun scaled(handle: Handle, start: LayerTransform, to: Offset, free: Boolean, fromCenter: Boolean): LayerTransform {
        val box = start.after(square)
        if (abs(box.a * box.d - box.b * box.c) < 1e-9f) return start
        val at = handle.at!!
        val q = box.inverse().let { Offset(it.x(to.x, to.y), it.y(to.x, to.y)) }
        // The point that stays put, in the box's own square.
        val anchor = if (fromCenter) Offset(0.5f, 0.5f) else Offset(1f - at.x, 1f - at.y)
        fun factor(h: Float, a: Float, p: Float) = if (h == a) 1f else ((p - a) / (h - a)).let { if (abs(it) < MIN_SCALE) MIN_SCALE * (if (it < 0f) -1f else 1f) else it }
        var sx = if (at.x == 0.5f) 1f else factor(at.x, anchor.x, q.x)
        var sy = if (at.y == 0.5f) 1f else factor(at.y, anchor.y, q.y)
        if (handle.corner && !free) { val s = if (abs(sx) >= abs(sy)) sx else sy; sx = s; sy = s }
        val inSquare = LayerTransform(sx, 0f, 0f, sy, anchor.x - sx * anchor.x, anchor.y - sy * anchor.y)
        return start.after(square).after(inSquare).after(square.inverse())
    }

    companion object {
        /** Where the scale handles sit in the box's own square. */
        val handlePoints: List<Offset> = Handle.entries.mapNotNull { it.at }
        private const val SNAP = PI / 12
        /** The smallest share a side scales to, so the box never goes flat and its pixels stay recoverable. */
        private const val MIN_SCALE = 0.01f
    }
}
