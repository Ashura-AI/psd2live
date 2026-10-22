package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals

class BoneHandlesTest {
	private val head = Offset(0f, 0f)
	private val tail = Offset(100f, 0f)

	@Test
	fun limitHandleRoundTripsThroughThePointer() {
		for (direction in listOf(1f, -1f)) {
			for (param in listOf(-90f, -30f, 45f, 150f)) {
				val point = BoneHandles.limitPoint(head, tail, direction, param, 60f)
				val back = BoneHandles.limitFrom(head, tail, direction, point, max = param > 0)
				assertEquals(param, back, 0.01f)
			}
		}
	}

}
