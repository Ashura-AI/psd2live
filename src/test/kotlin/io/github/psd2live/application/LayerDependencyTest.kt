package io.github.psd2live.application

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The layers a host without a window runs on never reach into the editor: the application layer and the agent,
 * project, core and history packages and the headless entry do not name the UI package or Compose.
 */
class LayerDependencyTest {
	private val sources = Path.of("src/main/kotlin/io/github/psd2live")
	private val forbidden = Regex("""\bio\.github\.psd2live\.ui\b|\bandroidx\.compose\b""")

	@Test fun headlessLayersNameNoUi() {
		val offenders = listOf("application", "agent", "project", "core", "history").flatMap { layer ->
			Files.walk(sources.resolve(layer)).use { paths ->
				paths.filter { it.extension == "kt" }.toList().mapNotNull { file ->
					forbidden.find(file.readText())?.let { "${sources.relativize(file)}: ${it.value}" }
				}
			}
		} + listOf(sources.resolve("HeadlessMcp.kt")).mapNotNull { file ->
			forbidden.find(file.readText())?.let { "${sources.relativize(file)}: ${it.value}" }
		}
		assertEquals(emptyList(), offenders, "these files reach into the UI")
	}
}
