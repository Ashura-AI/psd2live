package io.github.psd2live.ui.state

import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.ui.tutorial.TutorialId

/**
 * What changed in recent releases, as text to read: one [Release] per version, newest first. Each language has its
 * own `whatsnew/<tag>.md`; a language without one reads the English.
 */
internal object WhatsNew {
	/** The version heading of changes not yet released. */
	const val NEXT = "next"

	data class Release(val version: String, val date: String?, val groups: List<Group>)
	data class Group(val title: String, val entries: List<Entry>)
	/** One change: a bold [title] when it has one, its [text], and the tutorial that shows it, if any. */
	data class Entry(val title: String?, val text: String, val tutorial: TutorialId?)

	private val cache = mutableMapOf<AppLanguage, List<Release>>()

	fun releases(language: AppLanguage): List<Release> = synchronized(cache) {
		cache.getOrPut(language) { read(language.tag) ?: read(AppLanguage.ENGLISH.tag).orEmpty() }
	}

	fun read(tag: String): List<Release>? =
		WhatsNew::class.java.getResourceAsStream("/whatsnew/$tag.md")?.use { parse(it.readBytes().decodeToString()) }

	/**
	 * Reads the notes' few forms: `## version · date`, `### group`, and `- **title**: text {tutorial=ID}` bullets whose
	 * title and tutorial are both optional. Anything else is ignored.
	 */
	fun parse(markdown: String): List<Release> {
		val releases = mutableListOf<Release>()
		var version: String? = null
		var date: String? = null
		val groups = mutableListOf<Group>()
		var groupTitle: String? = null
		val entries = mutableListOf<Entry>()
		fun closeGroup() {
			groupTitle?.let { if (entries.isNotEmpty()) groups += Group(it, entries.toList()) }
			entries.clear()
			groupTitle = null
		}
		fun closeRelease() {
			closeGroup()
			version?.let { releases += Release(it, date, groups.toList()) }
			groups.clear()
			version = null
			date = null
		}
		for (raw in markdown.lineSequence()) {
			val line = raw.trim()
			when {
				line.startsWith("### ") -> {
					closeGroup()
					groupTitle = line.removePrefix("### ").trim()
				}
				line.startsWith("## ") -> {
					closeRelease()
					val heading = line.removePrefix("## ").split('·').map { it.trim() }
					version = heading.first()
					date = heading.getOrNull(1)?.takeIf { it.isNotEmpty() }
				}
				line.startsWith("- ") && groupTitle != null -> entries += entry(line.removePrefix("- "))
			}
		}
		closeRelease()
		return releases
	}

	private val TutorialMark = Regex("""\s*\{tutorial=([A-Z0-9_]+)}\s*$""")
	private val TitleMark = Regex("""^\*\*(.+?)\*\*\s*[:：]?\s*""")

	private fun entry(body: String): Entry {
		val tutorialMatch = TutorialMark.find(body)
		val tutorial = tutorialMatch?.let { match -> TutorialId.entries.firstOrNull { it.name == match.groupValues[1] } }
		val rest = if (tutorialMatch != null) body.removeRange(tutorialMatch.range) else body
		val titleMatch = TitleMark.find(rest)
		return Entry(titleMatch?.groupValues?.get(1), if (titleMatch != null) rest.removeRange(titleMatch.range).trim() else rest.trim(), tutorial)
	}
}
