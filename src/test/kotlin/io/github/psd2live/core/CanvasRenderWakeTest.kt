package io.github.psd2live.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanvasRenderWakeTest {
	@Test fun compileCompletionDuringRenderingSchedulesAnotherFrame() {
		val wake = CanvasRenderWake()
		assertTrue(wake.request())
		wake.begin()
		// A compile publishes a new model after the renderer captured the old one.
		assertFalse(wake.request())
		assertTrue(wake.finish())
		wake.begin()
		assertFalse(wake.finish())
	}

	@Test fun requestsBeforeTheTaskStartsCoalesceAndRequestsAfterItEndsWakeIt() {
		val wake = CanvasRenderWake()
		assertTrue(wake.request())
		assertFalse(wake.request())
		wake.begin()
		assertFalse(wake.finish())
		assertTrue(wake.request())
		wake.begin()
		assertFalse(wake.finish())
	}

}
