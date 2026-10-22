package io.github.psd2live.ui.state

import androidx.compose.ui.input.key.Key
import io.github.psd2live.ui.CanvasAidOptions
import io.github.psd2live.ui.EditHierarchyMode
import kotlin.test.Test
import kotlin.test.assertEquals

class CanvasAidsStateTest {
	@Test fun keymapListKeepsTheSemicolonKey() {
		val bindings = listOf(KeyBinding(Key.Semicolon, ctrl = true), KeyBinding(Key.Semicolon, alt = true, ctrl = true),
			KeyBinding(Key.Semicolon), KeyBinding(Key.A, shift = true))
		val joined = bindings.joinToString(";") { it.format() }
		assertEquals(bindings, splitKeyBindingList(joined).map { parseKeyBinding(it) })
		// Lists without the key split as a plain split does.
		assertEquals(listOf("Ctrl+A", "Shift+B"), splitKeyBindingList("Ctrl+A;Shift+B"))
		assertEquals(listOf("Ctrl+A"), splitKeyBindingList("Ctrl+A"))
	}

	@Test fun canvasAidsStayAcrossHierarchyModes() {
		val aids = CanvasAidOptions(showGrid = true, showGuides = true, gridSpacing = 32f)
		val session = CanvasModeSession(TabViewOptions(aids = aids), viewMode = EditHierarchyMode.SELECT)
		val paint = session.withHierarchyView(EditHierarchyMode.PAINT)
		assertEquals(aids, paint.view.aids)
		val changed = paint.copy(view = paint.view.copy(aids = aids.copy(showGrid = false)))
		assertEquals(false, changed.withHierarchyView(EditHierarchyMode.SELECT).view.aids.showGrid)
	}
}
