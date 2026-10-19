package io.github.psd2live.core.mesh.structure

import io.github.psd2live.core.mesh.Point
import io.github.psd2live.core.mesh.distanceSquaredToSegment
import kotlin.math.*
import kotlin.test.*

/** Synthetic drawings only: strokes, tones and prints painted by formula. */
class TextureStructureTest {
	private class Canvas(val w: Int, val h: Int, base: Int = 0xD8C8B8) {
		val rgba = ByteArray(w * h * 4).also { bytes ->
			for (i in 0 until w * h) { bytes[i * 4] = (base shr 16).toByte(); bytes[i * 4 + 1] = (base shr 8).toByte(); bytes[i * 4 + 2] = base.toByte(); bytes[i * 4 + 3] = -1 }
		}
		fun paint(x: Int, y: Int, rgb: Int, t: Double = 1.0) {
			if (x !in 0 until w || y !in 0 until h || t <= 0) return
			val i = (y * w + x) * 4
			for (c in 0..2) {
				val old = rgba[i + c].toInt() and 255; val new = (rgb shr (16 - 8 * c)) and 255
				rgba[i + c] = (old + (new - old) * t.coerceAtMost(1.0)).roundToInt().toByte()
			}
		}
		fun analyze(settings: StructureSettings = StructureSettings()) = TextureStructure.analyze(w, h, rgba, settings)
	}

	private fun distanceTo(p: Point, path: List<Point>) = path.zipWithNext().minOf { (a, b) -> sqrt(distanceSquaredToSegment(p, a, b)) }

	private fun arc(): List<Point> = (0..40).map { i -> val t = i / 40.0; Point(40 + 90 * t, 50 + 60 * t - 40 * sin(PI * t)) }

	/** A drawn crease: dark, thickest in the middle, fading to nothing at both ends. */
	private fun creased(): Canvas {
		val canvas = Canvas(176, 160)
		val path = arc()
		for (y in 0 until canvas.h) for (x in 0 until canvas.w) {
			val p = Point(x + .5, y + .5)
			var best = Double.POSITIVE_INFINITY; var at = 0.0
			for (k in 0 until path.size - 1) {
				val d = sqrt(distanceSquaredToSegment(p, path[k], path[k + 1]))
				if (d < best) { best = d; at = (k + .5) / (path.size - 1) }
			}
			val half = .4 + 1.6 * sin(PI * at)
			canvas.paint(x, y, 0x4A3A30, (half + .5 - best) * sin(PI * at).pow(.5))
		}
		return canvas
	}

	@Test
	fun aTaperedStrokeIsOneCreaseOnTheStroke() {
		val result = creased().analyze()
		val folds = result.lines.filter { it.kind == StructureKind.FOLD }
		assertEquals(1, folds.size, "lines: ${result.lines.map { it.kind to it.length.roundToInt() }}")
		val fold = folds.single()
		assertTrue(fold.length > 80, "length ${fold.length}")
		assertTrue(fold.freeEnds >= 1)
		assertTrue(fold.banded)
		val path = arc()
		fold.points.forEach { assertTrue(distanceTo(it, path) < 1.5, "off the stroke at $it") }
	}

	@Test
	fun aToneStepAcrossTheMaterialIsAShadingBoundary() {
		val canvas = Canvas(160, 160)
		for (y in 0 until canvas.h) for (x in 0 until canvas.w) if (x + y * .3 > 90) canvas.paint(x, y, 0xA89888)
		val lines = canvas.analyze().lines
		assertTrue(lines.isNotEmpty())
		assertTrue(lines.all { it.kind == StructureKind.SHADE }, "${lines.map { it.kind }}")
		lines.forEach { line -> line.points.forEach { assertEquals(90.0, it.x + it.y * .3, 2.0) } }
	}

	@Test
	fun colourStripesAndEvenlyPrintedStrokesArePatterns() {
		val stripes = Canvas(160, 160)
		for (y in 0 until stripes.h) for (x in 0 until stripes.w) if ((x / 20) % 2 == 1) stripes.paint(x, y, 0x88B0D8)
		val colour = stripes.analyze().lines
		assertTrue(colour.isNotEmpty())
		assertTrue(colour.all { it.kind == StructureKind.PATTERN }, "${colour.map { it.kind }}")

		val printed = Canvas(160, 160)
		for (y in 0 until printed.h) for (x in 0 until printed.w) if (x in 20..140 && (y - 20) % 24 in 0..1 && y in 20..140) printed.paint(x, y, 0x50403A)
		val strokes = printed.analyze().lines
		assertTrue(strokes.size >= 3)
		assertTrue(strokes.none { it.kind == StructureKind.FOLD }, "${strokes.map { it.kind to it.freeEnds }}")
	}

	@Test
	fun grainAndDotsAreNotStructure() {
		val canvas = Canvas(160, 160)
		var seed = 12345L
		for (y in 0 until canvas.h) for (x in 0 until canvas.w) {
			seed = seed * 6364136223846793005L + 1442695040888963407L
			val n = ((seed ushr 40) % 41).toInt() - 20
			canvas.paint(x, y, if (n > 0) 0xE8D8C8 else 0xC8B8A8, abs(n) / 20.0 * .6)
			if (x % 9 == 4 && y % 9 == 4) canvas.paint(x, y, 0xB0A090, .5)
		}
		assertEquals(emptyList(), canvas.analyze().lines.map { it.kind })
	}

	@Test
	fun aShadowWedgeYieldsOneCreaseAlongItsAxis() {
		val canvas = Canvas(176, 176)
		// A shadow opening from a tip at (40, 88) to the right, as a fold's shadow does: read either as a wedge between
		// two shading boundaries or, at the widest scale, as one soft stroke - a crease along y = 88 either way.
		for (y in 0 until canvas.h) for (x in 0 until canvas.w) {
			val dx = x + .5 - 40; val dy = abs(y + .5 - 88)
			if (dx > 0 && dx < 110 && dy < dx * .22) canvas.paint(x, y, 0xA08878)
		}
		val lines = canvas.analyze().lines
		val axis = lines.filter { it.kind == StructureKind.FOLD }
		assertTrue(axis.isNotEmpty(), "${lines.map { it.kind to it.length.roundToInt() }}")
		axis.forEach { line -> line.points.forEach { assertEquals(88.0, it.y, 2.5) } }
		assertTrue(axis.single().length > 50, "axis ${axis.single().length}")
	}

	@Test
	fun theSameDrawingGivesTheSameLines() {
		assertEquals(creased().analyze().lines, creased().analyze().lines)
	}

	@Test
	fun theOutlineIsNotALine() {
		val canvas = Canvas(160, 160)
		for (y in 0 until canvas.h) for (x in 0 until canvas.w) if (hypot(x - 80.0, y - 80.0) > 60) {
			val i = (y * canvas.w + x) * 4; canvas.rgba[i + 3] = 0
		}
		assertEquals(emptyList(), canvas.analyze().lines.map { it.kind })
	}
}
