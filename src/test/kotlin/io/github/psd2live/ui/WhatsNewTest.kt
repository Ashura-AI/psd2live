package io.github.psd2live.ui

import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.ui.state.WhatsNew
import io.github.psd2live.ui.tutorial.TutorialId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhatsNewTest {
	@Test fun everyLanguageHasTheSameReleasesAndChanges() {
		val english = assertNotNull(WhatsNew.read(AppLanguage.ENGLISH.tag))
		assertTrue(english.isNotEmpty())
		AppLanguage.entries.forEach { language ->
			val notes = assertNotNull(WhatsNew.read(language.tag), "whatsnew/${language.tag}.md")
			assertEquals(english.map { it.version to it.date }, notes.map { it.version to it.date }, "$language")
			assertEquals(english.map { release -> release.groups.map { it.entries.size } }, notes.map { release -> release.groups.map { it.entries.size } },
				"$language")
			assertEquals(english.flatMap { it.groups }.flatMap { it.entries }.map { it.tutorial },
				notes.flatMap { it.groups }.flatMap { it.entries }.map { it.tutorial }, "$language")
		}
	}

	@Test fun everyTutorialMarkNamesATutorial() {
		AppLanguage.entries.forEach { language ->
			val text = WhatsNew::class.java.getResourceAsStream("/whatsnew/${language.tag}.md")!!.readBytes().decodeToString()
			Regex("""\{tutorial=([^}]*)}""").findAll(text).forEach { match ->
				assertTrue(TutorialId.entries.any { it.name == match.groupValues[1] }, "${language.tag}: ${match.value}")
			}
		}
	}

	@Test fun anEntryReadsItsTitleTextAndTutorial() {
		val releases = WhatsNew.parse("""
			## v1.2.0 · 2026-01-02
			### Fixed
			- **Saving**: it works. {tutorial=PROJECT_HISTORY}
			- **保存**：可以了。
			- Plain text
			not a change
		""".trimIndent())
		val release = releases.single()
		assertEquals("v1.2.0", release.version)
		assertEquals("2026-01-02", release.date)
		val (first, second, third) = release.groups.single().entries
		assertEquals(WhatsNew.Entry("Saving", "it works.", TutorialId.PROJECT_HISTORY), first)
		assertEquals(WhatsNew.Entry("保存", "可以了。", null), second)
		assertEquals(WhatsNew.Entry(null, "Plain text", null), third)
		assertNull(WhatsNew.parse("## next\n### New\n- a").single().date)
	}
}
