package io.github.psd2live

import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.testing.MESSAGE_BUNDLES
import io.github.psd2live.testing.messageBundle
import io.github.psd2live.ui.state.WhatsNew
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * One version, one place: a release bumps `version` in `build.gradle.kts`, which stamps it into
 * `psd2live/version.properties` (read through [AppVersion]) and into `packageVersion`. The strings a release still
 * edits by hand — the command-line help in every language and the newest What's new release — must say the same, or
 * the app disagrees with its own help. `WhatsNewTest` keeps the four What's new files in step with each other.
 */
class VersionConsistencyTest {
	private val releaseVersion = Regex("""\d+\.\d+\.\d+""")

	@Test fun theStampedVersionLooksLikeARelease() {
		assertTrue(releaseVersion.matches(AppVersion.value), "version.properties was not stamped: ${AppVersion.value}")
	}

	@Test fun everyLanguageNamesTheBuildVersionOnTheCommandLine() {
		val version = AppVersion.value
		for (bundle in MESSAGE_BUNDLES) {
			val usage = assertNotNull(messageBundle(bundle).getProperty("cli.usage"), "$bundle has no cli.usage")
			assertTrue(usage.startsWith("PSD2Live $version\n"), "$bundle: ${usage.lineSequence().first()}")
		}
	}

	@Test fun whatsNewStartsWithTheBuildVersion() {
		val version = AppVersion.value
		for (language in AppLanguage.entries) {
			val releases = assertNotNull(WhatsNew.read(language.tag), "whatsnew/${language.tag}.md")
			// Unreleased changes may sit above it under `## next`; the newest release is the build's.
			assertEquals("v$version", releases.first { it.version != WhatsNew.NEXT }.version, "${language.tag} starts What's new with another release")
		}
	}
}
