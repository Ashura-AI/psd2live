package io.github.psd2live.ui.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanvasRailEntryTest {
	@Test
	fun `pins round trip and skip unknown ids`() {
		val pins = setOf(CanvasRailEntry.MESH, CanvasRailEntry.DENSITY, CanvasRailEntry.GUIDES)
		assertEquals(pins, CanvasRailEntry.parse(CanvasRailEntry.encode(pins)))
		assertEquals(setOf(CanvasRailEntry.WARP), CanvasRailEntry.parse("warp, onion,,gone"))
		assertEquals(emptySet(), CanvasRailEntry.parse(""))
	}

	@Test
	fun `defaults keep the rarely used toggles in the menu`() {
		val defaults = CanvasRailEntry.Defaults
		assertTrue(CanvasRailEntry.TEXTURE in defaults && CanvasRailEntry.MESH in defaults)
		assertFalse(CanvasRailEntry.DENSITY in defaults)
		assertFalse(CanvasRailEntry.SOURCE_PIXELS in defaults)
		assertFalse(CanvasRailEntry.PATH_WIDTH in defaults)
	}

	@Test
	fun `a toggle with nothing to show stays off the rail`() {
		val empty = CanvasViewContent(edit = true, textures = false, reference = false, referenceLayers = false,
			warps = false, rotations = false, paths = false, skeleton = false)
		assertFalse(empty.shows(CanvasRailEntry.DENSITY))
		assertFalse(empty.shows(CanvasRailEntry.WARP))
		assertFalse(empty.shows(CanvasRailEntry.SKELETON))
		assertTrue(empty.shows(CanvasRailEntry.GRID))
		val rigged = empty.copy(textures = true, warps = true, paths = true)
		assertTrue(rigged.shows(CanvasRailEntry.DENSITY))
		assertTrue(rigged.shows(CanvasRailEntry.WARP))
		assertTrue(rigged.shows(CanvasRailEntry.PATHS))
		assertFalse(rigged.shows(CanvasRailEntry.ROTATION))
		// A preview canvas offers no edit overlays.
		val preview = rigged.copy(edit = false)
		assertTrue(preview.shows(CanvasRailEntry.WARP))
		assertFalse(preview.shows(CanvasRailEntry.DENSITY))
		assertFalse(preview.shows(CanvasRailEntry.PATHS))
		assertFalse(preview.shows(CanvasRailEntry.GRID))
	}
}
