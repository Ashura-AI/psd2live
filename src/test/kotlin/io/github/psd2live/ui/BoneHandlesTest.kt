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

	@Test
	fun limitsStayOnTheirSideOfRest() {
		val above = Offset(50f, -50f)
		assertEquals(0f, BoneHandles.limitFrom(head, tail, 1f, above, max = true), 0.01f)
		assertEquals(-45f, BoneHandles.limitFrom(head, tail, 1f, above, max = false), 0.01f)
	}

	@Test
	fun blendFollowsThePointerAlongTheAxis() {
		val axis = BoneHandles.axis(head, tail)!!
		assertEquals(20f, BoneHandles.blendFrom(head, axis, Offset(40f, 9f), scale = 2f, limit = 50f), 0.01f)
		assertEquals(50f, BoneHandles.blendFrom(head, axis, Offset(400f, 0f), scale = 2f, limit = 50f), 0.01f)
	}
}
