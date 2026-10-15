package io.github.psd2live.ui

import io.github.psd2live.ui.state.HairMode
import io.github.psd2live.ui.state.StartPresetChoices
import io.github.psd2live.ui.state.StartQuickPreset
import io.github.psd2live.ui.state.validLayoutCanvasSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StartPresetsTest {
	@Test fun choicesSurviveTheirPreference() {
		StartQuickPreset.entries.forEach { preset ->
			assertEquals(preset.choices, StartPresetChoices.decode(preset.choices.encode()))
		}
		val custom = StartPresetChoices(headStrength = 1.35f, mouthOutline = false, frontHair = HairMode.SIMULATION, backHair = HairMode.OFF)
		assertEquals(custom, StartPresetChoices.decode(custom.encode()))
	}

	@Test fun anUnreadablePreferenceReadsAsNone() {
		assertNull(StartPresetChoices.decode(null))
		assertNull(StartPresetChoices.decode(""))
		assertNull(StartPresetChoices.decode("{\"frontHair\":\"BRAIDED\"}"))
		// Fields a later version adds do not lose the rest.
		assertEquals(StartPresetChoices(motionBasic = false), StartPresetChoices.decode("{\"motionBasic\":false,\"wings\":true}"))
	}

	@Test fun layoutCanvasSizesStayWithinTheProjectLimits() {
		assertTrue(validLayoutCanvasSize(2048, 2048))
		assertTrue(validLayoutCanvasSize(4096, 4096))
		assertTrue(validLayoutCanvasSize(8192, 2048))
		assertFalse(validLayoutCanvasSize(8192, 8192))
		assertFalse(validLayoutCanvasSize(0, 1024))
		assertFalse(validLayoutCanvasSize(8193, 1))
		assertFalse(validLayoutCanvasSize(null, 1024))
	}
}
