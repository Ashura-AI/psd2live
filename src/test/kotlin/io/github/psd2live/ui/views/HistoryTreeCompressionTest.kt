package io.github.psd2live.ui.views

import io.github.psd2live.project.HistoryAnnotation
import io.github.psd2live.project.WorkspaceHistoryNodeSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The compact history view: linear runs of versions share one card, forks stay separate, and the
 * full view keeps the per-version cards the tree has always drawn.
 */
class HistoryTreeCompressionTest {
	private fun node(id: String, parentId: String?, head: Boolean = false) = WorkspaceHistoryNodeSnapshot(
		id = id,
		parentId = parentId,
		revisionId = "rev-$id",
		summary = "Edit $id",
		actor = "user",
		taskId = null,
		createdAt = "2026-01-01T00:00:00Z",
		isHead = head,
	)

	private fun linear(vararg ids: String) = ids.mapIndexed { index, id ->
		node(id, ids.getOrNull(index - 1), head = index == ids.lastIndex)
	}

	/** a -> b -> c -> d -> e -> f, then a checkout at c that commits g -> h: the example this view is for. */
	private fun forked(): List<WorkspaceHistoryNodeSnapshot> = listOf(
		node("a", null),
		node("b", "a"),
		node("c", "b"),
		node("d", "c"),
		node("e", "d"),
		node("f", "e"),
		node("g", "c"),
		node("h", "g"),
	).map { it.copy(isHead = it.id == "h") }

	@Test fun aLinearHistoryShowsTheRootAndOneMergedRun() {
		val grouping = groupHistory(linear("a", "b", "c", "d", "e", "f"), compressed = true)
		assertEquals(listOf("a", "b"), grouping.groups.map { it.id })
		val root = grouping.groups[0]
		val run = grouping.groups[1]
		assertEquals(listOf("a"), root.nodes.map { it.id })
		assertNull(root.parentId)
		assertEquals(listOf("b", "c", "d", "e", "f"), run.nodes.map { it.id })
		assertEquals("a", run.parentId)
		assertTrue(run.isHead)
		assertEquals("f", run.target.id)
		assertEquals("b", grouping.byNodeId["f"])
	}

	@Test fun aForkFromAnInnerVersionSplitsTheRunAtTheForkPoint() {
		val grouping = groupHistory(forked(), compressed = true)
		assertEquals(listOf("a", "b", "d", "g"), grouping.groups.map { it.id })
		val byGroup = grouping.byGroupId
		assertEquals(listOf("b", "c"), byGroup.getValue("b").nodes.map { it.id })
		assertEquals(listOf("d", "e", "f"), byGroup.getValue("d").nodes.map { it.id })
		assertEquals(listOf("g", "h"), byGroup.getValue("g").nodes.map { it.id })
		assertEquals("a", byGroup.getValue("b").parentId)
		assertEquals("b", byGroup.getValue("d").parentId)
		assertEquals("b", byGroup.getValue("g").parentId)
		// The fork point closes the run above it, so its own branch is where it can be edited from.
		assertEquals("b", grouping.byNodeId["c"])
		assertFalse(byGroup.getValue("d").isHead)
		assertTrue(byGroup.getValue("g").isHead)
		assertEquals("h", byGroup.getValue("g").target.id)
	}

	@Test fun aHeadInsideALinearRunIsMarkedOnTheMergedCard() {
		val nodes = linear("a", "b", "c", "d", "e").map { it.copy(isHead = it.id == "d") }
		val grouping = groupHistory(nodes, compressed = true)
		assertEquals(2, grouping.groups.size)
		assertTrue(grouping.groups[1].isHead)
		assertEquals("b", grouping.byNodeId["d"])
	}

	@Test fun theFullViewKeepsOneCardPerVersion() {
		val nodes = forked()
		val grouping = groupHistory(nodes, compressed = false)
		assertEquals(nodes.map { it.id }, grouping.groups.map { it.id })
		assertEquals(nodes.map { it.parentId }, grouping.groups.map { it.parentId })
		assertEquals(nodes.associate { it.id to it.id }, grouping.byNodeId)
		assertEquals(nodes.size, grouping.layoutNodes().size)
	}

	@Test fun theLayoutReceivesTheCardsWithTheirOwnParents() {
		val grouping = groupHistory(forked(), compressed = true)
		val layout = calculateTreeLayout(grouping.layoutNodes())
		val byId = layout.allNodes.associateBy { it.node.id }
		assertEquals(setOf("d", "g"), byId.getValue("b").children.map { it.node.id }.toSet())
		assertEquals("b", byId.getValue("d").node.parentId)
		assertEquals("b", byId.getValue("g").node.parentId)
		assertTrue(byId.getValue("d").y > byId.getValue("b").y)
		assertTrue(byId.getValue("g").y > byId.getValue("b").y)
		assertTrue(layout.width > 0f)
	}

	@Test fun aMergedCardMatchesASearchInAnyOfItsSteps() {
		val grouping = groupHistory(forked(), compressed = true)
		val run = grouping.byGroupId.getValue("d")
		assertTrue(run.matchesSearch("Edit e", emptyMap()))
		assertTrue(run.matchesSearch("d", emptyMap()))
		assertTrue(run.matchesSearch("renamed", mapOf("e" to HistoryAnnotation(title = "renamed"))))
		assertFalse(run.matchesSearch("missing", emptyMap()))
		assertFalse(run.matchesSearch("Edit h", emptyMap()))
	}

	@Test fun aSingleVersionHistoryAndAnEmptySnapshotBothStayUsable() {
		val single = groupHistory(listOf(node("a", null, head = true)), compressed = true)
		assertEquals(1, single.groups.size)
		assertEquals("a", single.groups.single().id)
		assertTrue(single.groups.single().isHead)
		assertTrue(groupHistory(emptyList(), compressed = true).groups.isEmpty())
	}
}
