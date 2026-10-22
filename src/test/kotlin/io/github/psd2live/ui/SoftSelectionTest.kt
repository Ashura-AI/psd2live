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

}
