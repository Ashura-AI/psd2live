package io.github.psd2live.ui.views

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import io.github.psd2live.ui.views.tooloptions.submenuPosition
import kotlin.test.*

class MenuCascadeTest {
    @Test fun submenuUsesTheLeftSideWithoutMovingThePrimaryWhenTheRightSideIsFull() {
        val anchor = IntRect(970, 100, 970, 100)
        val (position, left) = submenuPosition(anchor, IntSize(1000, 700), IntSize(244, 360), 236, 4, 8)
        assertTrue(left)
        assertEquals(486, position.x)
        assertEquals(100, position.y)
        assertTrue(position.x + 244 <= anchor.left - 236)
    }

    @Test fun narrowWindowsStillClampTheMenuToTheirVisibleArea() {
        val (position, _) = submenuPosition(IntRect(280, 30, 280, 30), IntSize(400, 200), IntSize(244, 360), 236, 4, 8)
        assertTrue(position.x >= 8 && position.x + 244 <= 392)
        assertEquals(8, position.y)
    }
}
