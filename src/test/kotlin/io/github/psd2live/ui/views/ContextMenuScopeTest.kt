package io.github.psd2live.ui.views

import io.github.psd2live.i18n.tr
import kotlin.test.*

/** The menus that name a single object must act on exactly that object, or say that they act on more. */
class ContextMenuScopeTest {
	@Test fun oneLinkOfACollapsedChainDeletesOnlyThatLink() {
		val rows = deformerDeleteRows(listOf("head", "middle", "tail"), "middle")

		assertEquals(2, rows.size)
		assertEquals("canvas.hierarchy.deleteDeformerCurrent", rows[0].labelKey)
		assertEquals(0, rows[0].count)
		assertEquals(listOf("middle"), rows[0].idsInnermostFirst)
		assertEquals("canvas.hierarchy.deleteDeformerChain", rows[1].labelKey)
		assertEquals(3, rows[1].count)
		// Deleting innermost first keeps the remaining links' children baking into the right parent.
		assertEquals(listOf("tail", "middle", "head"), rows[1].idsInnermostFirst)
	}

	@Test fun aSingleDeformerChainKeepsTheExistingDeleteRow() {
		assertEquals(
			listOf(DeformerDeleteRow("canvas.hierarchy.deleteDeformer", 0, listOf("only"))),
			deformerDeleteRows(listOf("only"), "only"),
		)
	}

	@Test fun oneReferencingLayerLocatesDirectly() {
		val located = mutableListOf<String>()
		val line = assertNotNull(materialLocateLine(
			users = listOf("layer-a"),
			layerNames = mapOf("layer-a" to "Layer A"),
			deletedLayerIds = emptySet(),
			locate = { located += it },
		))

		val action = assertIs<MenuLine.Action>(line)
		assertEquals(tr("materials.menu.locate", 1), action.label)
		action.run()
		assertEquals(listOf("layer-a"), located)
	}

	@Test fun severalReferencingLayersOpenTheirOwnSubmenu() {
		val located = mutableListOf<String>()
		val line = assertNotNull(materialLocateLine(
			users = listOf("layer-a", "layer-b"),
			layerNames = mapOf("layer-a" to "Layer A", "layer-b" to "Layer B"),
			deletedLayerIds = emptySet(),
			locate = { located += it },
		))

		val submenu = assertIs<MenuLine.Submenu>(line)
		assertEquals(tr("materials.menu.locate", 2), submenu.label)
		assertEquals(listOf("Layer A", "Layer B"), submenu.actions.map { it.label })
		submenu.actions[1].run()
		assertEquals(listOf("layer-b"), located)
	}

	@Test fun deletedAndUnknownLayersAreNotOffered() {
		val located = mutableListOf<String>()
		val line = assertNotNull(materialLocateLine(
			users = listOf("layer-a", "deleted", "missing"),
			layerNames = mapOf("layer-a" to "Layer A", "deleted" to "Deleted"),
			deletedLayerIds = setOf("deleted"),
			locate = { located += it },
		))

		val action = assertIs<MenuLine.Action>(line)
		assertEquals(tr("materials.menu.locate", 1), action.label)
		action.run()
		assertEquals(listOf("layer-a"), located)
		assertNull(materialLocateLine(listOf("deleted"), mapOf("deleted" to "Deleted"), setOf("deleted")) { fail("nothing to locate") })
	}

	@Test fun aNamelessLayerFallsBackToItsId() {
		val line = assertNotNull(materialLocateLine(
			users = listOf("layer-a", "layer-b"),
			layerNames = mapOf("layer-a" to " ", "layer-b" to "Layer B"),
			deletedLayerIds = emptySet(),
			locate = {},
		))

		val submenu = assertIs<MenuLine.Submenu>(line)
		assertEquals("layer-a", submenu.actions[0].label)
		assertEquals("Layer B", submenu.actions[1].label)
	}
}
