package io.github.psd2live.application

import io.github.psd2live.scenario.Characters
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** A folder imports with its nesting: subfolders become material folders, hidden ones are left out, names read in number order. */
class WorkspaceMaterialFilesTest {
	@TempDir lateinit var temp: Path

	@Test fun aNestedFolderKeepsItsStructure() {
		val root = Files.createDirectories(temp.resolve("parts"))
		val arm = Files.createDirectories(root.resolve("arm"))
		Characters.disc(arm, "arm10.png"); Characters.disc(arm, "arm2.png")
		Characters.disc(Files.createDirectories(arm.resolve("hand").resolve("finger")), "tip.png")
		Characters.disc(Files.createDirectories(root.resolve(".git")), "ignored.png")
		Files.writeString(root.resolve("notes.txt"), "not an image")

		val entries = WorkspaceMaterialFiles.read(listOf(root))
		assertEquals(listOf("arm2" to listOf("parts", "arm"), "arm10" to listOf("parts", "arm"), "tip" to listOf("parts", "arm", "hand", "finger")),
			entries.map { it.name to it.folder })

		val library = WorkspaceMaterialEdits.add(Characters.figure(), entries, null).layout.materials
		val tip = library.items.single { it.name == "tip" }
		assertEquals(listOf("finger", "hand", "arm", "parts"), library.ancestry(tip.folderId).map { it.name })
	}
}
