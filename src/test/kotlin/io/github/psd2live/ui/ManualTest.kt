package io.github.psd2live.ui

import io.github.psd2live.agent.agentOperationDocs
import io.github.psd2live.application.WorkspaceBackend
import io.github.psd2live.application.WorkspaceOperations
import io.github.psd2live.i18n.AppLanguage
import java.lang.reflect.Proxy
import io.github.psd2live.ui.components.ManualBlock
import io.github.psd2live.ui.components.NoteKind
import io.github.psd2live.ui.components.parseManualBlocks
import io.github.psd2live.ui.state.Manual
import io.github.psd2live.ui.state.ShortcutAction
import io.github.psd2live.ui.tutorial.TutorialId
import io.github.psd2live.ui.tutorial.TutorialPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ManualTest {
	private val nodes = Manual.trees.values.flatMap { Manual.flatten(it) }

	@Test fun theIndexHasBothTreesAndUniqueIds() {
		assertEquals(Manual.Root.entries.toSet(), Manual.trees.keys)
		val ids = nodes.map { it.id }
		assertEquals(ids.size, ids.toSet().size, "duplicate ids: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}")
		assertTrue(assertNotNull(Manual.page(Manual.FIRST_MANUAL)).opens)
		Manual.pages.mapNotNull { it.embed }.forEach { assertTrue(it in setOf("shortcuts", "psd_spec"), it) }
	}

	@Test fun everyLanguageTitlesEveryEntryAndWritesEveryPageThatOpens() {
		AppLanguage.entries.forEach { language ->
			val texts = Manual.read(language.tag)
			nodes.forEach { node -> assertTrue(texts[node.id]?.title?.isNotBlank() == true, "${language.tag}: no title for ${node.id}") }
			Manual.pages.filter { it.opens }.forEach { page ->
				assertTrue(texts[page.id]?.body?.isNotBlank() == true, "${language.tag}: no text for ${page.id}")
			}
			val known = nodes.map { it.id }.toSet()
			assertEquals(emptySet(), texts.keys - known, "${language.tag}: entries the index does not have")
		}
	}

	@Test fun everyMarkNamesSomethingThatExists() {
		val ids = nodes.map { it.id }.toSet()
		AppLanguage.entries.forEach { language ->
			Manual.read(language.tag).forEach { (id, text) ->
				Regex("""\[\[([^\]|]+)(?:\|[^\]]+)?]]""").findAll(text.body).forEach {
					assertTrue(it.groupValues[1] in ids, "${language.tag}/$id: ${it.value}")
				}
				Regex("""\{key:([A-Za-z0-9_]+)}""").findAll(text.body).forEach { match ->
					assertTrue(ShortcutAction.entries.any { it.name == match.groupValues[1] }, "${language.tag}/$id: ${match.value}")
				}
				Regex("""\{tutorial=([^}]*)}""").findAll(text.body).forEach { match ->
					assertTrue(TutorialId.entries.any { it.name == match.groupValues[1] }, "${language.tag}/$id: ${match.value}")
				}
				Regex("""\{path=([^}]*)}""").findAll(text.body).forEach { match ->
					assertTrue(TutorialPath.entries.any { it.name == match.groupValues[1] }, "${language.tag}/$id: ${match.value}")
				}
			}
		}
	}

	@Test fun everyMcpEntryNamesAnOpenPageAndARealOperation() {
		val workspace = Proxy.newProxyInstance(WorkspaceBackend::class.java.classLoader, arrayOf(WorkspaceBackend::class.java)) { _, method, _ ->
			error("Unexpected workspace access: ${method.name}")
		} as WorkspaceBackend
		val known = WorkspaceOperations(workspace).use { operations -> agentOperationDocs(operations.registry.definitions()).associateBy { it.id } }
		assertTrue(Manual.operations.isNotEmpty())
		Manual.operations.forEach { (page, operations) ->
			assertTrue(Manual.page(page)?.opens == true, "mcp.txt: $page is not a page that opens")
			assertTrue(operations.isNotEmpty(), "mcp.txt: $page lists nothing")
			operations.forEach { assertTrue(it in known, "mcp.txt: $page names unknown operation $it") }
		}
		val glue = known.getValue("canvas_glue")
		assertTrue(glue.fields.isNotEmpty() && glue.fields.none { it.name == "request_id" || it.name == "project_id" })
	}

	@Test fun theIndexNestsByIndentation() {
		val trees = Manual.parseIndex("""
			[manual]
			+ a
			  - p1 same cubism=x
			  + b
			    - p2 missing
			- p3 exclusive embed=shortcuts # comment
			[tutorials]
			- t1 different
		""".trimIndent())
		val manual = trees.getValue(Manual.Root.MANUAL)
		val a = manual[0] as Manual.Folder
		assertEquals(listOf("p1", "b"), a.children.map { it.id })
		assertEquals("p2", ((a.children[1] as Manual.Folder).children.single() as Manual.Page).id)
		assertEquals(Manual.Page("p3", Manual.Root.MANUAL, Manual.Status.EXCLUSIVE, embed = "shortcuts"), manual[1])
		assertEquals("t1", trees.getValue(Manual.Root.TUTORIALS).single().id)
	}

	@Test fun pageTextReadsItsBlocks() {
		val texts = Manual.parseTexts("@@ a | Title A\nline\n@@ folder | Folder\n@@ b | B\n")
		assertEquals(Manual.Text("Title A", "line"), texts["a"])
		assertEquals("", texts.getValue("folder").body)
		val blocks = parseManualBlocks("""
			Intro.
			## Section
			1. one
			2. two
			- x
			| h1 | h2 |
			|---|---|
			| c1 | c2 |
			!> careful
			!> still careful
			{tutorial=GLUE_NOT_A_TUTORIAL}
			{tutorial=SKELETON}
			{path=EXPERIENCED}
		""".trimIndent())
		assertEquals(listOf(
			ManualBlock.Paragraph("Intro."),
			ManualBlock.Heading(2, "Section"),
			ManualBlock.Steps(listOf("one", "two")),
			ManualBlock.Bullets(listOf("x")),
			ManualBlock.Table(listOf(listOf("h1", "h2"), listOf("c1", "c2"))),
			ManualBlock.Note(NoteKind.CAUTION, "careful\nstill careful"),
			ManualBlock.Tutorial(TutorialId.SKELETON),
			ManualBlock.Path(TutorialPath.EXPERIENCED),
		), blocks)
		assertEquals(mapOf("glue" to listOf("canvas_glue", "canvas_glue_edit")), Manual.parseOperations("# x\nglue: canvas_glue  canvas_glue_edit\n"))
	}
}
