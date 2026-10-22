package io.github.psd2live.ui.state

import androidx.compose.ui.input.key.Key
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

}
