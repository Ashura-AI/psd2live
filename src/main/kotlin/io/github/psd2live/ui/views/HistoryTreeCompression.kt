package io.github.psd2live.ui.views

import io.github.psd2live.project.HistoryAnnotation
import io.github.psd2live.project.WorkspaceHistoryNodeSnapshot

/**
 * One card of the history tree: a maximal run of consecutive linear versions in the compact view, or
 * a single version in the full view.
 *
 * A run starts at the root (which always keeps a card of its own, so the starting point stays
 * visible), at the first version of a branch, or at a detached version. It runs on while each version
 * has exactly one child, and ends after the first version that forks or ends the line, so a fork point
 * and a leaf belong to the run above them. Forks therefore stay separate cards.
 */
internal data class HistoryNodeGroup(
	val id: String,
	val parentId: String?,
	val nodes: List<WorkspaceHistoryNodeSnapshot>,
) {
	init {
		require(nodes.isNotEmpty()) { "A history group must hold at least one node" }
	}

	val first: WorkspaceHistoryNodeSnapshot get() = nodes.first()
	val last: WorkspaceHistoryNodeSnapshot get() = nodes.last()
	val size: Int get() = nodes.size
	val isHead: Boolean get() = nodes.any { it.isHead }

	/** The version the card stands for: checking the card out returns to the end of its run. */
	val target: WorkspaceHistoryNodeSnapshot get() = last

	fun contains(nodeId: String): Boolean = nodes.any { it.id == nodeId }
}

/** Branches keyed by the grouping: [byNodeId] maps every version to the card that shows it. */
internal data class HistoryGrouping(
	val groups: List<HistoryNodeGroup>,
	val byNodeId: Map<String, String>,
) {
	val byGroupId: Map<String, HistoryNodeGroup> by lazy { groups.associateBy { it.id } }

	fun groupOf(nodeId: String): HistoryNodeGroup? = byNodeId[nodeId]?.let(byGroupId::get)

	/**
	 * The cards as layout nodes: the tree layout only reads IDs, parents and HEAD, so each card is
	 * represented by its first version with the card's own identity.
	 */
	fun layoutNodes(): List<WorkspaceHistoryNodeSnapshot> = groups.map { group ->
		group.first.copy(id = group.id, parentId = group.parentId, isHead = group.isHead)
	}
}

/**
 * Groups [nodes] into the cards the history tree draws. With [compressed] false every version is its
 * own card and the layout is exactly the one the full view has always drawn.
 *
 * Input order is commit order, which lays a fork's children out in the order they were made.
 */
internal fun groupHistory(nodes: List<WorkspaceHistoryNodeSnapshot>, compressed: Boolean): HistoryGrouping {
	if (nodes.isEmpty()) return HistoryGrouping(emptyList(), emptyMap())
	if (!compressed) {
		return HistoryGrouping(
			groups = nodes.map { node -> HistoryNodeGroup(node.id, node.parentId, listOf(node)) },
			byNodeId = nodes.associate { node -> node.id to node.id },
		)
	}

	val byId = nodes.associateBy { it.id }
	// A parent that is not in the snapshot (a hidden or detached version) reads as no parent, as the layout does.
	fun parentOf(node: WorkspaceHistoryNodeSnapshot): String? = node.parentId?.takeIf(byId::containsKey)
	val childrenByParent = nodes.groupBy { parentOf(it) }

	val groups = ArrayList<HistoryNodeGroup>(minOf(nodes.size, 64))
	val groupOfNode = HashMap<String, String>(nodes.size)
	val visited = HashSet<String>(nodes.size)
	val pending = ArrayDeque<WorkspaceHistoryNodeSnapshot>()
	nodes.forEach { node -> if (parentOf(node) == null) pending.addLast(node) }

	while (pending.isNotEmpty()) {
		val start = pending.removeFirst()
		if (!visited.add(start.id)) continue
		val run = ArrayList<WorkspaceHistoryNodeSnapshot>()
		run.add(start)
		// The root (or a detached line) keeps its card to itself; every other start continues while linear.
		if (parentOf(start) != null) {
			var current = start
			while (true) {
				val children = childrenByParent[current.id].orEmpty()
				if (children.size != 1) break
				val child = children.first()
				if (!visited.add(child.id)) break
				run.add(child)
				current = child
			}
		}
		val groupId = start.id
		val group = HistoryNodeGroup(groupId, parentOf(start)?.let(groupOfNode::get), run)
		groups.add(group)
		run.forEach { node -> groupOfNode[node.id] = groupId }
		// A forking or ending run only queues the children it did not consume; linear runs consume theirs.
		childrenByParent[run.last().id].orEmpty().forEach { child ->
			if (child.id !in visited) pending.addLast(child)
		}
	}

	// A cyclic or otherwise unreachable version still gets a card, so nothing vanishes from the view.
	nodes.forEach { node ->
		if (visited.add(node.id)) {
			groups.add(HistoryNodeGroup(node.id, null, listOf(node)))
			groupOfNode[node.id] = node.id
		}
	}
	return HistoryGrouping(groups, groupOfNode)
}

/** Whether a card matches [query]: any version inside it does, by summary, ID, actor or annotation title. */
internal fun HistoryNodeGroup.matchesSearch(query: String, annotations: Map<String, HistoryAnnotation>): Boolean {
	if (query.isBlank()) return true
	return nodes.any { node ->
		node.summary.contains(query, ignoreCase = true) ||
			node.id.contains(query, ignoreCase = true) ||
			node.actor.contains(query, ignoreCase = true) ||
			annotations[node.id]?.title?.contains(query, ignoreCase = true) == true
	}
}
