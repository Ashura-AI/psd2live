package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SoftSelectionTest {
    @Test fun pointsFollowLessTheFurtherTheyAreFromThePickedOnes() {
        val points = (0..5).map { Offset(it * 10f, 0f) }
        val weights = softSelectionWeights(points, setOf(0), radius = 40f, falloff = BrushFalloff.LINEAR)
        assertEquals(1f, weights[0])
        assertTrue((1 until 4).all { weights[it] > weights[it + 1] }, "weights must fall with distance: ${weights.toList()}")
        assertEquals(0f, weights[4], "a point at the radius stays")
        assertEquals(0f, weights[5])
    }

    @Test fun theNearestPickedPointDecides() {
        val points = listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(20f, 0f))
        val weights = softSelectionWeights(points, setOf(0, 2), radius = 40f, falloff = BrushFalloff.CONSTANT)
        assertEquals(listOf(1f, 1f, 1f), weights.toList())
        assertTrue(softSelectionWeights(points, emptySet(), 40f, BrushFalloff.SMOOTH).all { it == 0f })
    }
}
