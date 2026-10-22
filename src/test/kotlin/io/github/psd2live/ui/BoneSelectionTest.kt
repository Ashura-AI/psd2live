package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoneSelectionTest {
	@Test
	fun toggleKeepsPrimaryInTheSet() {
		val selection = BoneSelection()
		selection.set("a")
		selection.toggle("b")
		assertEquals("b", selection.primary)
		selection.toggle("b")
		assertEquals("a", selection.primary)
		selection.toggle("a")
		assertNull(selection.primary)
		assertEquals(emptySet(), selection.ids)
	}

}

class BoneHitTestTest {
	private val bones = listOf(
		ScreenBone("a", Offset(0f, 0f), Offset(100f, 0f)),
		ScreenBone("b", Offset(100f, 0f), Offset(200f, 0f)),
	)

	@Test
	fun tailsWinAndHeadsCanBeIgnored() {
		assertEquals("a" to BoneJoint.TAIL, BoneHitTest.joint(bones.take(1), Offset(95f, 3f), 10f))
		assertNull(BoneHitTest.joint(bones.take(1), Offset(3f, 0f), 10f, heads = false))
		assertEquals("b" to BoneJoint.HEAD, BoneHitTest.joint(bones.drop(1), Offset(103f, 0f), 10f))
	}

}
