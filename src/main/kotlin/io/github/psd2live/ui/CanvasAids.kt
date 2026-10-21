package io.github.psd2live.ui

import io.github.psd2live.project.CanvasGuide
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.random.Random

/**
 * The colour a canvas aid is drawn in, by its meaning in the theme rather than as a value: an aid keeps reading
 * against the canvas whichever theme is on.
 */
enum class CanvasAidColor { TEXT, ACCENT, HIGHLIGHT, SUCCESS, WARNING, ERROR }

/**
 * A canvas's drawing aids: the grid, the guides and the onion skin. They are part of the canvas's view options,
 * saved with the workspace's presentation and never read by the document or an export. The guides themselves are
 * project data ([CanvasGuide]); these say whether and how they show.
 */
data class CanvasAidOptions(
	val showGrid: Boolean = false,
	/** Canvas pixels between the grid's major lines. */
	val gridSpacing: Float = 100f,
	/** Minor cells per major cell; 1 draws major lines only. */
	val gridSubdivisions: Int = 4,
	val gridColor: CanvasAidColor = CanvasAidColor.TEXT,
	val gridOpacity: Float = 0.35f,
	/** The guides and the rulers they are dragged from. */
	val showGuides: Boolean = false,
	/** Locked guides stay put: they are neither dragged nor added. */
	val guidesLocked: Boolean = false,
	val guideColor: CanvasAidColor = CanvasAidColor.HIGHLIGHT,
	/** The pointer is drawn to the grid and the guides shown, within a few screen pixels. */
	val snap: Boolean = false,
	val onionSkin: Boolean = false,
	val onionBefore: Int = 1,
	val onionAfter: Int = 1,
	val onionOpacity: Float = 0.35f,
	val onionBeforeColor: CanvasAidColor = CanvasAidColor.ERROR,
	val onionAfterColor: CanvasAidColor = CanvasAidColor.SUCCESS,
	/** Frames between two animation ghosts. */
	val onionFrameStep: Int = 2,
) {
	/** Settings kept to what the canvas can draw, whatever a saved workspace holds. */
	fun normalized(): CanvasAidOptions = copy(
		gridSpacing = if (gridSpacing.isFinite()) gridSpacing.coerceIn(MIN_GRID_SPACING, MAX_GRID_SPACING) else 100f,
		gridSubdivisions = gridSubdivisions.coerceIn(1, MAX_GRID_SUBDIVISIONS),
		gridOpacity = if (gridOpacity.isFinite()) gridOpacity.coerceIn(0.05f, 1f) else 0.35f,
		onionBefore = onionBefore.coerceIn(0, MAX_ONION_GHOSTS),
		onionAfter = onionAfter.coerceIn(0, MAX_ONION_GHOSTS),
		onionOpacity = if (onionOpacity.isFinite()) onionOpacity.coerceIn(0.05f, 1f) else 0.35f,
		onionFrameStep = onionFrameStep.coerceIn(1, MAX_ONION_FRAME_STEP),
	)

	/** Whether the pointer snaps to anything: snapping is on and the grid or guides it snaps to are shown. */
	val snaps: Boolean get() = snap && (showGrid || showGuides)

	companion object {
		const val MIN_GRID_SPACING = 2f
		const val MAX_GRID_SPACING = 4096f
		const val MAX_GRID_SUBDIVISIONS = 16
		const val MAX_ONION_GHOSTS = 5
		const val MAX_ONION_FRAME_STEP = 30
	}
}

/** One grid line at [position] canvas pixels along its axis; [major] lines are drawn stronger. */
data class GridLine(val position: Float, val major: Boolean)

/** The step the grid is drawn at for one zoom, and every how many steps a major line falls. */
data class GridStep(val step: Float, val majorEvery: Int)

object CanvasGrid {
	/** Lines closer than this on screen are thinned out, so a zoomed-out grid does not turn into a wash. */
	const val MIN_SCREEN_GAP = 6f
	private const val MAX_LINES = 4000

	/**
	 * The step the grid shows at [scale] screen pixels per canvas pixel: its minor cells when they are far enough
	 * apart, else its major lines alone, doubled until they are.
	 */
	fun step(spacing: Float, subdivisions: Int, scale: Double): GridStep {
		val major = spacing.coerceAtLeast(CanvasAidOptions.MIN_GRID_SPACING)
		val minor = major / subdivisions.coerceAtLeast(1)
		if (subdivisions > 1 && minor * scale >= MIN_SCREEN_GAP) return GridStep(minor, subdivisions)
		var step = major
		while (step * scale < MIN_SCREEN_GAP && step < Float.MAX_VALUE / 4) step *= 2f
		return GridStep(step, 1)
	}

	/** The lines of one axis between [from] and [to] canvas pixels, the canvas origin on a major line. */
	fun lines(spacing: Float, subdivisions: Int, from: Float, to: Float, scale: Double): List<GridLine> {
		if (!from.isFinite() || !to.isFinite() || to < from || scale <= 0.0) return emptyList()
		val (step, majorEvery) = step(spacing, subdivisions, scale)
		val first = ceil(from / step).toLong()
		val last = floor(to / step).toLong()
		if (last - first > MAX_LINES) return emptyList()
		return (first..last).map { k -> GridLine(k * step, Math.floorMod(k, majorEvery.toLong()) == 0L) }
	}

	/** The grid line nearest [value] at [scale], as the grid draws it there. */
	fun nearest(value: Float, spacing: Float, subdivisions: Int, scale: Double): Float {
		val step = step(spacing, subdivisions, scale).step
		return Math.round(value / step) * step
	}
}

/** One ruler tick at [position] canvas pixels; [label]led ticks are long and numbered. */
data class RulerTick(val position: Float, val label: Boolean)

object CanvasRuler {
	/** Screen pixels the numbered ticks keep at least between them. */
	const val LABEL_GAP = 64f

	/** The numbered step at [scale]: 1, 2 or 5 times a power of ten canvas pixels, the smallest far enough apart. */
	fun labelStep(scale: Double): Float {
		if (scale <= 0.0 || !scale.isFinite()) return 100f
		val raw = LABEL_GAP / scale
		val magnitude = 10.0.pow(floor(log10(raw)))
		val step = listOf(1.0, 2.0, 5.0, 10.0).first { it * magnitude >= raw - 1e-9 } * magnitude
		return step.toFloat()
	}

	/** The ticks between [from] and [to] canvas pixels: numbered ones at [labelStep], five small ones between. */
	fun ticks(from: Float, to: Float, scale: Double): List<RulerTick> {
		if (!from.isFinite() || !to.isFinite() || to < from) return emptyList()
		val step = labelStep(scale)
		val minor = step / 5f
		val first = ceil(from / minor).toLong()
		val last = floor(to / minor).toLong()
		if (last - first > 4000) return emptyList()
		return (first..last).map { k -> RulerTick(k * minor, Math.floorMod(k, 5L) == 0L) }
	}
}

object CanvasSnap {
	/** Screen pixels within which the pointer is drawn to a line. */
	const val SCREEN_TOLERANCE = 6f

	/** The candidate nearest [value] within [tolerance], or null when none is that close. */
	fun nearest(value: Float, candidates: Iterable<Float>, tolerance: Float): Float? {
		var best: Float? = null
		var distance = tolerance
		for (candidate in candidates) {
			val d = abs(candidate - value)
			if (d <= distance) { best = candidate; distance = d }
		}
		return best
	}

	/**
	 * A canvas point drawn, axis by axis, to the nearest guide or grid line within [tolerance] canvas pixels.
	 * [grid] is the line nearest on each axis (see [CanvasGrid.nearest]), null when the grid does not snap.
	 */
	fun snap(x: Float, y: Float, guides: List<CanvasGuide>, grid: ((Float) -> Float)?, tolerance: Float): Pair<Float, Float> {
		fun axis(value: Float, vertical: Boolean): Float {
			val lines = guides.filter { it.vertical == vertical }.map { it.position } + listOfNotNull(grid?.invoke(value))
			return nearest(value, lines, tolerance) ?: value
		}
		return axis(x, vertical = true) to axis(y, vertical = false)
	}
}

object CanvasGuides {
	const val MAX_GUIDES = io.github.psd2live.application.WorkspaceAuxiliaryEdits.MAX_CANVAS_GUIDES

	/** A new guide's ID: one more than the highest numbered one. IDs never repeat while their guides stay. */
	fun nextId(guides: List<CanvasGuide>): String =
		"guide-${(guides.mapNotNull { it.id.removePrefix("guide-").toIntOrNull() }.maxOrNull() ?: 0) + 1}"

	/** [guides] with [guide] put in place of the one with its ID, or added after them. */
	fun put(guides: List<CanvasGuide>, guide: CanvasGuide): List<CanvasGuide> =
		if (guides.any { it.id == guide.id }) guides.map { if (it.id == guide.id) guide else it } else guides + guide

	/** The guide within [tolerance] canvas pixels of the point, the nearest, or null. */
	fun hit(guides: List<CanvasGuide>, x: Float, y: Float, tolerance: Float): CanvasGuide? = guides
		.map { it to abs((if (it.vertical) x else y) - it.position) }
		.filter { it.second <= tolerance }
		.minByOrNull { it.second }?.first
}

enum class RandomPoseScope { ALL, EDITING }

object RandomPose {
	/**
	 * A random value for each parameter of [targets] (every one when null) that is not [locked]: [amount] of the
	 * way from its default towards either limit at most, so 1 spans the whole range and a smaller amount keeps the
	 * pose near rest. Parameters with no range are left out.
	 */
	fun sample(parameters: List<Parameter>, targets: Set<ParameterId>?, locked: Set<ParameterId>, amount: Float,
	           random: Random): Map<ParameterId, Float> {
		val reach = if (amount.isFinite()) amount.coerceIn(0f, 1f) else 1f
		return parameters.asSequence()
			.filter { (targets == null || it.id in targets) && it.id !in locked && it.max > it.min }
			.associate { parameter ->
				val rest = parameter.default.coerceIn(parameter.min, parameter.max)
				val t = (random.nextDouble(-1.0, 1.0) * reach).toFloat()
				val value = if (t >= 0f) rest + t * (parameter.max - rest) else rest + t * (rest - parameter.min)
				parameter.id to value.coerceIn(parameter.min, parameter.max)
			}
	}
}

/** One onion-skin ghost: [offset] steps before (negative) or after (positive) the shown pose, at [value]. */
data class OnionGhost(val offset: Int, val value: Float)

object OnionSkin {
	private const val EPS = 1e-4f

	/** The [before] keys under [current] and the [after] keys over it, nearest first on each side. */
	fun keyGhosts(keys: List<Float>, current: Float, before: Int, after: Int): List<OnionGhost> {
		val sorted = keys.filter { it.isFinite() }.distinct().sorted()
		val under = sorted.filter { it < current - EPS }.asReversed().take(before.coerceAtLeast(0))
			.mapIndexed { i, key -> OnionGhost(-(i + 1), key) }
		val over = sorted.filter { it > current + EPS }.take(after.coerceAtLeast(0))
			.mapIndexed { i, key -> OnionGhost(i + 1, key) }
		return under + over
	}

	/**
	 * The animation ghosts around [playhead]: every [step] frames at [fps], [before] earlier and [after] later.
	 * A looping clip wraps around its [duration]; another drops the times outside it.
	 */
	fun frameGhosts(playhead: Float, fps: Float, step: Int, before: Int, after: Int, duration: Float, loop: Boolean): List<OnionGhost> {
		if (!(fps > 0f) || !(duration > 0f) || !playhead.isFinite()) return emptyList()
		val spacing = step.coerceAtLeast(1).toFloat() / fps
		fun at(offset: Int): OnionGhost? {
			val time = playhead + offset * spacing
			return when {
				loop -> OnionGhost(offset, ((time % duration) + duration) % duration)
				time < -EPS || time > duration + EPS -> null
				else -> OnionGhost(offset, time.coerceIn(0f, duration))
			}
		}
		return (1..before.coerceAtLeast(0)).mapNotNull { at(-it) } + (1..after.coerceAtLeast(0)).mapNotNull { at(it) }
	}

	/** A ghost's opacity: [opacity] next to the pose, fading towards the farthest of [count] on its side. */
	fun alpha(offset: Int, count: Int, opacity: Float): Float {
		val n = count.coerceAtLeast(1)
		val distance = abs(offset).coerceIn(1, n)
		return opacity * (n - distance + 1) / n
	}
}
