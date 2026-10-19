package io.github.psd2live.ui.state

import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.scenario.Characters
import org.umamo.runtime.model.ParameterLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReferencePinRowsTest {
	@Test
	fun linkedParametersShareA2dPadAndMovedOnesAreListed() {
		val puppet = kotlinx.coroutines.runBlocking { WorkspacePreviewBuilder().build(Characters.head(false)) }.rig.puppet
		val (x, y, z) = puppet.parameters.take(3)
		val linked = puppet.copy(parameterLinks = listOf(ParameterLink(x.id, y.id)))
		// Nothing moved: the 2D pads are offered.
		assertEquals(listOf(ReferencePinRows.Pad(x, y)), ReferencePinRows.of(linked, emptyMap(), emptyMap()))
		// A moved parameter lists itself; one pinned stays listed at the default pose.
		val moved = ReferencePinRows.of(linked, mapOf(z.id.raw to z.max), mapOf(x.id to x.max))
		assertTrue(ReferencePinRows.Pad(x, y) in moved && ReferencePinRows.Axis(z) in moved)
	}
}
