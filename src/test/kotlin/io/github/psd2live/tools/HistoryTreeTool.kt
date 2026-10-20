package io.github.psd2live.tools

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import io.github.psd2live.project.WorkspaceHistoryNodeSnapshot
import io.github.psd2live.project.WorkspaceHistorySnapshot
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.ToolColors
import io.github.psd2live.ui.views.HistoryTreeView
import io.github.psd2live.ui.views.calculateTreeLayout
import io.github.psd2live.ui.views.groupHistory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test

/**
 * The history tree with a branched synthetic history, for a look at the compact view: linear runs
 * merged into one card with their step count, forks kept as separate cards, the same tree in the
 * full per-node view, and the merged card's tooltip. Writes PNGs to build/tools/history-tree/.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*HistoryTreeTool'
 */
class HistoryTreeTool {
	private fun node(id: String, parent: String?, summary: String, minute: Int, actor: String = "user") =
		WorkspaceHistoryNodeSnapshot(
			id = id,
			parentId = parent,
			revisionId = "rev-$id",
			summary = summary,
			actor = actor,
			taskId = if (actor == "agent") "task-$id" else null,
			createdAt = "2026-01-01T%02d:%02d:00Z".format(minute / 60, minute % 60),
			isHead = false,
		)

	/** a -> b -> c -> d -> e -> f, and a branch at c: g -> h, with HEAD on the branch. */
	private fun branchedHistory(): WorkspaceHistorySnapshot {
		val nodes = listOf(
			node("a", null, "Workspace opened", 0, "system"),
			node("b", "a", "Import PSD", 1),
			node("c", "b", "Generate rig", 2),
			node("d", "c", "Paint the eye highlight", 3),
			node("e", "d", "Refine the mouth outline", 4),
			node("f", "e", "Adjust physics", 5),
			node("g", "c", "Bind the arm bones", 6, "agent"),
			node("h", "g", "Tune the arm weights", 7, "agent"),
		).map { if (it.id == "h") it.copy(isHead = true) else it }
		return WorkspaceHistorySnapshot("h", nodes)
	}

	@OptIn(ExperimentalComposeUiApi::class)
	@Test fun render() = runBlocking {
		requireTools()
		val out = output("history-tree").absoluteFile
		out.listFiles()?.filter { it.extension == "png" }?.forEach { it.delete() }
		val history = branchedHistory()
		PSD2LiveViewModel().use { viewModel ->
			withContext(Dispatchers.Main) {
				suspend fun scene(compressed: Boolean, generation: Long) = ImageComposeScene(
					1100, 700, density = Density(1f), coroutineContext = currentCoroutineContext(),
				) {
					CompactToolTheme(colors = ToolColors.Dark) {
						Box(Modifier.fillMaxSize()) {
							HistoryTreeView(
								state = PSD2LiveState(
									historySnapshot = history,
									projectOpenGeneration = generation,
									historyCompressed = compressed,
								),
								viewModel = viewModel,
							)
						}
					}
				}

				fun save(scene: ImageComposeScene, name: String, timeNanos: Long) {
					val image = scene.render(timeNanos)
					try { File(out, "$name.png").writeBytes(requireNotNull(image.encodeToData()).bytes) } finally { image.close() }
				}

				for ((compressed, generation, name) in listOf(
					Triple(true, 1L, "compact"),
					Triple(false, 2L, "full"),
				)) {
					val scene = scene(compressed, generation)
					try {
						repeat(30) { frame -> scene.render(frame * 16_000_000L).close() }
						save(scene, name, 31 * 16_000_000L)
					} finally {
						scene.close()
					}
				}

				// Hover the merged "b, c" card so its tooltip lists the steps the tree folded away. The tree is
				// drawn at 100% with the view's 24dp canvas padding, 184x46dp cards, the 208dp operation list
				// beside it and the toolbar above it.
				val scene = scene(compressed = true, generation = 3L)
				try {
					repeat(30) { frame -> scene.render(frame * 16_000_000L).close() }
					val grouping = groupHistory(history.nodes, compressed = true)
					val run = calculateTreeLayout(grouping.layoutNodes()).allNodes.first { it.node.id == "b" }
					val center = Offset(209f + run.x + 24f + 92f, 27f + run.y + 24f + 23f)
					scene.sendPointerEvent(PointerEventType.Enter, center, type = PointerType.Mouse)
					scene.sendPointerEvent(PointerEventType.Move, center, type = PointerType.Mouse)
					scene.render(32 * 16_000_000L).close()
					delay(700)
					save(scene, "compact-tooltip", 60 * 16_000_000L)
				} finally {
					scene.close()
				}
			}
		}
		println("HISTORY TREE shots: ${out.listFiles()?.map { it.name }}")
	}
}
