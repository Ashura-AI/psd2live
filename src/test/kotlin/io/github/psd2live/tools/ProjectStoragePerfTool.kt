package io.github.psd2live.tools

import io.github.psd2live.project.ProjectProgress
import io.github.psd2live.project.ProjectRepository
import io.github.psd2live.project.ProjectSaveCapture
import io.github.psd2live.project.ProjectStage
import io.github.psd2live.project.WorkspaceStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.name
import kotlin.test.Test

/**
 * Time and space of a real project's history: loading a working store, saving it (twice, the second unchanged),
 * opening the archive, and where the bytes go, in the working store and in the archive.
 *
 * PSD2LIVE_TOOLS=1 PSD2LIVE_STORE=<working store directory of one project> ./gradlew test --tests '*ProjectStoragePerfTool'
 * The directory (as under `%LOCALAPPDATA%/PSD2Live/agent-workspaces/<project id>`) is copied first and never
 * changed. Writes build/tools/project-storage/report.txt.
 */
class ProjectStoragePerfTool {
	@Test fun profile() {
		requireTools()
		val store = Path.of(requireNotNull(System.getenv("PSD2LIVE_STORE")) { "Set PSD2LIVE_STORE to a project's working store" })
		val out = output("project-storage")
		val directory = Files.createTempDirectory("storage-perf-")
		val lines = ArrayList<String>()
		try {
			val id = store.name
			val copy = directory.resolve("store").resolve(id)
			store.toFile().copyRecursively(copy.toFile())
			lines += "working store:"
			lines += breakdown(copy)
			val (history, load) = timed { WorkspaceStore(directory.resolve("store")).loadHistory(id)!! }
			val state = history.state()
			lines += "revisions ${state.selections.size}, distinct snapshots ${state.selections.map { it.node.snapshotHash }.distinct().size}"
			lines += "load history: %.0f ms, heap after load %d MiB".format(load, heapMiB())
			val repository = ProjectRepository(writeHeadCache = false)
			val target = directory.resolve("project.psd2live")
			val workspace = WorkspaceStore(directory.resolve("store"))
			runBlocking {
				repeat(2) { run ->
					val start = System.nanoTime()
					val stages = Stages(start)
					repository.save(ProjectSaveCapture(id, state, JsonObject(emptyMap()), null, workspace), target, stages)
					lines += "save ${run + 1}: %.0f ms (${stages.report(since(start))})".format(since(start))
				}
				lines += "archive: %.1f MiB".format(Files.size(target) / 1048576.0)
				lines += archiveBreakdown(target)
				repeat(2) { run ->
					val start = System.nanoTime()
					val stages = Stages(start)
					repository.open(target, stages).use { opened ->
						val total = since(start)
						lines += "open ${run + 1}: %.0f ms (${stages.report(total)}), heap %d MiB (revisions ${opened.history.selections().size})".format(total, heapMiB())
					}
				}
			}
		} finally {
			lines.forEach(::println)
			out.resolve("report.txt").writeText(lines.joinToString("\n", postfix = "\n"))
			directory.toFile().deleteRecursively()
		}
	}

	/** When each stage, and each staging step, began. */
	private class Stages(private val start: Long) : ProjectProgress {
		private val marks = LinkedHashMap<String, Double>()
		override fun report(stage: ProjectStage, fraction: Double) {
			val key = if (stage == ProjectStage.STAGING) "staging ${Math.round(fraction * 6)}" else stage.name.lowercase()
			synchronized(marks) { marks.putIfAbsent(key, since(start)) }
		}
		fun report(total: Double): String {
			val list = marks.entries.toList()
			return list.mapIndexed { i, (key, at) -> "%s %.0f".format(key, (list.getOrNull(i + 1)?.value ?: total) - at) }.joinToString(", ")
		}
	}

	private fun heapMiB(): Long {
		repeat(2) { System.gc() }
		val runtime = Runtime.getRuntime()
		return (runtime.totalMemory() - runtime.freeMemory()) / 1048576
	}

	private fun breakdown(root: Path): List<String> {
		val sizes = sortedMapOf<String, LongArray>()
		Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).forEach { file ->
			val parts = root.relativize(file).toString().replace('\\', '/').split('/')
			val key = if (parts.size > 2 && parts[0] == "history") parts.take(2).joinToString("/") else if (parts.size > 1) parts[0] else "(files)"
			sizes.getOrPut(key) { LongArray(2) }.let { it[0]++; it[1] += Files.size(file) }
		} }
		return sizes.map { (key, value) -> "  %-28s %6d files %9.2f MiB".format(key, value[0], value[1] / 1048576.0) }
	}

	private fun archiveBreakdown(archive: Path): List<String> = ZipFile(archive.toFile()).use { zip ->
		val sizes = sortedMapOf<String, LongArray>()
		for (entry in zip.entries()) {
			val parts = entry.name.split('/')
			val key = if (parts.size > 3 && parts[0] == "document") parts.take(3).joinToString("/") else if (parts.size > 2) parts.take(2).joinToString("/") else parts[0]
			sizes.getOrPut(key) { LongArray(3) }.let { it[0]++; it[1] += entry.size; it[2] += entry.compressedSize }
		}
		sizes.map { (key, value) -> "  %-28s %6d files %9.2f MiB, %9.2f MiB packed".format(key, value[0], value[1] / 1048576.0, value[2] / 1048576.0) }
	}
}
