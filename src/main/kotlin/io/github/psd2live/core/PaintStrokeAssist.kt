package io.github.psd2live.core

/**
 * The stroke stabilizer (SAI's 手ブレ補正, Photoshop's smoothing): the tip follows the average of the last
 * `level + 1` pointer positions instead of the pointer itself, so a shaky hand draws a calm line. Level 0 passes
 * the pointer through.
 *
 * When the stroke ends, [finish] walks the tip the rest of the way to where the pointer was released - Photoshop's
 * "catch up on stroke end" - so a stroke ends where it was drawn to rather than short of it.
 */
internal class PaintStabilizer(level: Int) {
    val level: Int = level.coerceIn(0, MAX_LEVEL)
    private val xs = ArrayDeque<Float>()
    private val ys = ArrayDeque<Float>()

    /** Takes the pointer at ([x], [y]) and answers where the tip goes. */
    fun add(x: Float, y: Float): Pair<Float, Float> {
        if (level == 0) return x to y
        xs.addLast(x); ys.addLast(y)
        while (xs.size > level + 1) { xs.removeFirst(); ys.removeFirst() }
        return xs.average().toFloat() to ys.average().toFloat()
    }

    /**
     * The positions that bring the tip from its last one to the last pointer position: the window is filled with
     * that position one step at a time, which is the path the tip would have taken had the pointer rested there.
     */
    fun finish(): List<Pair<Float, Float>> {
        if (level == 0 || xs.isEmpty()) return emptyList()
        val x = xs.last(); val y = ys.last()
        val out = ArrayList<Pair<Float, Float>>(level + 1)
        // Until the window holds nothing but the last position, whether or not a short stroke ever filled it.
        while (xs.size <= level || xs.any { it != x } || ys.any { it != y }) out += add(x, y)
        return out
    }

    companion object {
        /** The strongest level the options offer. */
        const val MAX_LEVEL = 30
    }
}

/**
 * [points] thinned to at most [max]: points nearer than [minDistance] to the last one kept are dropped, then, if that
 * is not enough, every n-th is kept. The first and last points stay, so a lasso closes where it was drawn.
 */
internal fun thinOutline(points: List<Pair<Float, Float>>, max: Int, minDistance: Float): List<Pair<Float, Float>> {
    if (points.size <= max) return points
    val spaced = ArrayList<Pair<Float, Float>>()
    for (p in points) {
        val last = spaced.lastOrNull()
        if (last == null || kotlin.math.hypot(p.first - last.first, p.second - last.second) >= minDistance) spaced += p
    }
    if (spaced.last() != points.last()) spaced += points.last()
    if (spaced.size <= max) return spaced
    val step = (spaced.size + max - 2) / (max - 1)
    return spaced.filterIndexed { i, _ -> i % step == 0 }.let { if (it.last() == spaced.last()) it else it + spaced.last() }
}

/** Which mirror axes a stroke is painted across, on the canvas as shown. */
internal enum class PaintSymmetry(val vertical: Boolean, val horizontal: Boolean) {
    OFF(false, false),
    /** Left and right of a vertical axis: a face's two halves. */
    VERTICAL(true, false),
    HORIZONTAL(false, true),
    BOTH(true, true);

    /**
     * [x], [y] and its mirror images across the vertical axis at [axisX] and the horizontal axis at [axisY], the
     * point itself first. A point on an axis is still listed once per image: the stroke lands once anyway.
     */
    fun images(x: Float, y: Float, axisX: Float, axisY: Float): List<Pair<Float, Float>> = buildList {
        add(x to y)
        if (vertical) add(2 * axisX - x to y)
        if (horizontal) add(x to 2 * axisY - y)
        if (vertical && horizontal) add(2 * axisX - x to 2 * axisY - y)
    }
}
