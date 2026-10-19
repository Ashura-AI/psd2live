package io.github.psd2live.ui.state

import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.scenario.Characters
import org.umamo.runtime.model.ParameterLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReferencePinRowsTest {
	@Test
	fun keyedParametersAreRowsAndLinkedOnesShareA2dPad() {
		val puppet = kotlinx.coroutines.runBlocking { WorkspacePreviewBuilder().build(Characters.head(false)) }.rig.puppet
		val (x, y, z) = puppet.parameters.take(3)
		val linked = puppet.copy(parameterLinks = listOf(ParameterLink(x.id, y.id)))
		val rows = ReferencePinRows.of(linked, setOf(x.id, y.id), emptyList())
		assertEquals(listOf(ReferencePinRows.Pad(x, y)), rows)
		// A parameter a point pins is listed even without keys; the point is on its row, so none is loose.
		val pinned = ReferencePinRows.of(linked, setOf(x.id, y.id), listOf(mapOf(z.id.raw to z.max)))
		assertTrue(ReferencePinRows.Axis(z) in pinned)
		assertTrue(ReferencePinRows.loose(pinned, listOf(mapOf(z.id.raw to z.max))).isEmpty())
		// A point across a pad and another parameter fits no row.
		val across = mapOf(x.id.raw to x.max, z.id.raw to z.max)
		assertEquals(listOf(across), ReferencePinRows.loose(pinned, listOf(across)))
		assertTrue(ReferencePinRows.same(mapOf(z.id.raw to 1f), mapOf(z.id.raw to 1.00001f)))
		assertFalse(ReferencePinRows.same(mapOf(z.id.raw to 1f), mapOf(x.id.raw to 1f)))
	}
}
