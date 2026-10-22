package io.github.psd2live.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Long hair bends with the head's tilt: above the chin it turns with the head, its held part turns back. */
class HairRollTest {
	private val roll = RigBuilder.HairRoll(pivotX = 500f, pivotY = 400f, chinY = 450f, hold = 1f)

	private fun rotated(x: Float, y: Float, degrees: Float): Pair<Float, Float> {
		val r = Math.toRadians(degrees.toDouble())
		val rx = x - 500f; val ry = y - 400f
		return (500f + rx * cos(r).toFloat() - ry * sin(r).toFloat()) to (400f + rx * sin(r).toFloat() + ry * cos(r).toFloat())
	}

	@Test fun aboveTheChinNothingTurnsBack() {
		assertEquals(0f to 0f, roll.turnBack(520f, 430f, 1500f, 20f))
	}

	@Test fun theHeldPartCancelsTheTilt() {
		// Far below the chin (past six times the chin's depth) the whole tilt is turned back.
		val (x, y) = 560f to 1200f
		val (dx, dy) = roll.turnBack(x, y, 1500f, 20f)
		val (tx, ty) = rotated(x + dx, y + dy, 20f)
		assertTrue(abs(tx - x) < 0.01f && abs(ty - y) < 0.01f, "turned back to ($tx, $ty), not ($x, $y)")
	}

}
