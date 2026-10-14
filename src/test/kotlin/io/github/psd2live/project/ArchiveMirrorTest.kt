package io.github.psd2live.project

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.*

/** Saves link the revisions the working store has split before; the archives they write are the same. */
class ArchiveMirrorTest {
	@TempDir lateinit var temp: Path

	private fun manifest(archive: Path): Map<String, String> = ZipFile(archive.toFile()).use { zip ->
		Json.parseToJsonElement(zip.getInputStream(zip.getEntry(ProjectArchive.MANIFEST)).readBytes().decodeToString())
			.jsonObject.getValue("files").jsonObject.mapValues { it.value.jsonPrimitive.content }
	}

	private fun records(store: Path): Long = store.resolve("${ArchiveMirror.FOLDER}/revisions").let { folder ->
		if (Files.isDirectory(folder)) Files.list(folder).use { it.count() } else 0
	}

	@Test fun aSecondSaveLinksEveryRevisionAndWritesTheSameArchive() = runBlocking<Unit> {
		val project = SyntheticProject(temp.resolve("p"), layers = 6, size = 16, revisions = 12)
		val repository = ProjectRepository(writeHeadCache = false)
		val first = temp.resolve("first.psd2live")
		repository.save(project.capture(), first)
		val live = project.store.projectRoot(SyntheticProject.PROJECT)
		assertEquals(project.tree.selections().map { it.node.snapshotHash }.distinct().size.toLong(), records(live))
		// A revision whose mirrored node went missing is split again.
		Files.walk(live.resolve("${ArchiveMirror.FOLDER}/document/nodes/layers")).use { paths -> paths.filter(Files::isRegularFile).findFirst().get() }.let(Files::delete)
		val second = temp.resolve("second.psd2live")
		repository.save(project.capture(), second)
		assertEquals(manifest(first), manifest(second))
	}

	@Test fun theFirstSaveAfterOpeningLinksTheOpenedRevisions() = runBlocking<Unit> {
		val project = SyntheticProject(temp.resolve("p"), layers = 4, size = 16, revisions = 6)
		val saved = temp.resolve("saved.psd2live")
		ProjectRepository(writeHeadCache = false).save(project.capture(), saved)
		ProjectRepository().open(saved).use { opened ->
			assertEquals(project.tree.selections().map { it.node.snapshotHash }.distinct().size.toLong(), records(opened.store.projectRoot(opened.projectId)))
			val again = temp.resolve("again.psd2live")
			ProjectRepository(writeHeadCache = false).save(ProjectSaveCapture(opened.projectId, opened.history.state(), JsonObject(emptyMap()),
				opened.source, opened.store), again)
			val before = manifest(saved).filterKeys { it.startsWith("document/") || it.startsWith("history/") || it.startsWith("assets/") }
			assertEquals(before, manifest(again).filterKeys { it.startsWith("document/") || it.startsWith("history/") || it.startsWith("assets/") })
		}
	}
}
