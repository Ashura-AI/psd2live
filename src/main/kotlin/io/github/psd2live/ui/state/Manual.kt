package io.github.psd2live.ui.state

import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.ui.tutorial.TutorialPath

/**
 * The help page's manual and tutorials, laid out like the Cubism Editor manual and tutorial site: the same folders in
 * the same order, each page marked by how it compares with Cubism.
 *
 * The tree is language-neutral (`manual/index.txt`); each language's titles and pages are in `manual/<tag>/<root>.md`.
 * A language without a page reads the English, then the Chinese.
 */
internal object Manual {
	/** The step-by-step tutorials for newcomers: where the tutorial catalog opens. */
	const val FIRST_TUTORIAL = "interactive-beginner"

	/** The manual's first page. */
	const val FIRST_MANUAL = "workflow"

	/** How a page's feature compares with Cubism Editor. */
	enum class Status(val labelKey: String) {
		/** Works as in Cubism. */
		SAME("help.manual.status.same"),
		/** Does what Cubism does, by a noticeably different operation. */
		DIFFERENT("help.manual.status.different"),
		/** PSD2Live's own; Cubism has no such feature. */
		EXCLUSIVE("help.manual.status.exclusive"),
		/** In Cubism, not yet here: listed but cannot be opened. */
		MISSING("help.manual.status.missing"),
	}

	/** The two trees: the manual and the tutorials. */
	enum class Root(val key: String, val cubismSite: String) {
		MANUAL("manual", "cubism-editor-manual"),
		TUTORIALS("tutorials", "cubism-editor-tutorials"),
	}

	sealed interface Node {
		val id: String
	}

	data class Folder(override val id: String, val children: List<Node>) : Node

	/**
	 * A page. [cubism] is the slug of its Cubism counterpart, [path] makes it a step-by-step tutorial path, [embed]
	 * shows one of the help page's generated tables under its text.
	 */
	data class Page(
		override val id: String,
		val root: Root,
		val status: Status,
		val cubism: String? = null,
		val path: TutorialPath? = null,
		val embed: String? = null,
	) : Node {
		val opens: Boolean get() = status != Status.MISSING
	}

	/** A title and, for a page, its text. */
	data class Text(val title: String, val body: String)

	val trees: Map<Root, List<Node>> by lazy { parseIndex(resource("/manual/index.txt") ?: "") }

	val pages: List<Page> by lazy { trees.values.flatMap { flatten(it) }.filterIsInstance<Page>() }

	private val pageById: Map<String, Page> by lazy { pages.associateBy { it.id } }

	fun page(id: String): Page? = pageById[id]

	/** The folders from the root down to [id], [id] not included. */
	fun ancestors(id: String): List<Folder> {
		for (tree in trees.values) {
			val found = mutableListOf<Folder>()
			if (findPath(tree, id, found)) return found
		}
		return emptyList()
	}

	/** The pages that open, in reading order, for the previous and next links. */
	fun readingOrder(root: Root): List<Page> = flatten(trees[root].orEmpty()).filterIsInstance<Page>().filter { it.opens }

	private fun findPath(nodes: List<Node>, id: String, path: MutableList<Folder>): Boolean {
		for (node in nodes) {
			if (node.id == id) return true
			if (node is Folder) {
				path += node
				if (findPath(node.children, id, path)) return true
				path.removeAt(path.lastIndex)
			}
		}
		return false
	}

	fun flatten(nodes: List<Node>): List<Node> = nodes.flatMap { if (it is Folder) listOf(it) + flatten(it.children) else listOf(it) }

	private val cache = mutableMapOf<AppLanguage, Map<String, Text>>()

	/** Every title and page in [language], falling back page by page to English and then Chinese. */
	fun texts(language: AppLanguage): Map<String, Text> = synchronized(cache) {
		cache.getOrPut(language) {
			val own = read(language.tag)
			val english = if (language == AppLanguage.ENGLISH) emptyMap() else read(AppLanguage.ENGLISH.tag)
			val chinese = if (language == AppLanguage.CHINESE) emptyMap() else read(AppLanguage.CHINESE.tag)
			chinese + english + own
		}
	}

	/** One language's own texts, nothing filled in from another. */
	fun read(tag: String): Map<String, Text> =
		Root.entries.mapNotNull { resource("/manual/$tag/${it.key}.md") }.fold(emptyMap()) { all, text -> all + parseTexts(text) }

	/** The Cubism page [page] corresponds to, in the reader's language where the Cubism site has one. */
	fun cubismUrl(page: Page, language: AppLanguage): String? {
		val slug = page.cubism ?: return null
		val prefix = when (language) {
			AppLanguage.CHINESE -> "/zh-CHS"
			AppLanguage.ENGLISH -> "/en"
			AppLanguage.JAPANESE -> ""
			AppLanguage.KOREAN -> "/ko"
		}
		return "https://docs.live2d.com$prefix/${page.root.cubismSite}/$slug/"
	}

	private fun resource(path: String): String? =
		Manual::class.java.getResourceAsStream(path)?.use { it.readBytes().decodeToString().replace("\r\n", "\n") }

	/**
	 * Reads the index: a `[manual]` or `[tutorials]` line starts a tree; under it each line is `+ id` for a folder or
	 * `- id status key=value…` for a page, nested by two-space indentation. `#` starts a comment.
	 */
	fun parseIndex(text: String): Map<Root, List<Node>> {
		class Open(val id: String?, val depth: Int, val children: MutableList<Node> = mutableListOf())
		val trees = linkedMapOf<Root, List<Node>>()
		var root: Root? = null
		val stack = ArrayDeque<Open>()
		fun closeTo(depth: Int) {
			while (stack.size > 1 && stack.last().depth >= depth) {
				val done = stack.removeLast()
				stack.last().children += Folder(done.id!!, done.children.toList())
			}
		}
		fun closeRoot() {
			closeTo(0)
			root?.let { trees[it] = stack.firstOrNull()?.children?.toList().orEmpty() }
			stack.clear()
		}
		for (raw in text.lineSequence()) {
			val line = raw.substringBefore('#').trimEnd()
			if (line.isBlank()) continue
			val trimmed = line.trimStart()
			if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
				closeRoot()
				val key = trimmed.removeSurrounding("[", "]")
				root = Root.entries.first { it.key == key }
				stack += Open(null, -1)
				continue
			}
			val depth = (line.length - trimmed.length) / 2
			val currentRoot = requireNotNull(root) { "manual index: \"$line\" before a tree" }
			closeTo(depth)
			val words = trimmed.substring(1).trim().split(Regex("\\s+"))
			when (trimmed.first()) {
				'+' -> stack += Open(words.first(), depth)
				'-' -> {
					val options = words.drop(2).associate { it.substringBefore('=') to it.substringAfter('=') }
					stack.last().children += Page(
						id = words[0],
						root = currentRoot,
						status = Status.valueOf(words[1].uppercase()),
						cubism = options["cubism"],
						path = options["path"]?.let { TutorialPath.valueOf(it) },
						embed = options["embed"],
					)
				}
				else -> error("manual index: \"$line\"")
			}
		}
		closeRoot()
		return trees
	}

	/** Reads a language file: `@@ id | Title` starts an entry, the lines up to the next one are its text. */
	fun parseTexts(text: String): Map<String, Text> {
		val out = linkedMapOf<String, Text>()
		var id: String? = null
		var title = ""
		val body = StringBuilder()
		fun close() {
			id?.let { out[it] = Text(title, body.toString().trim('\n', ' ')) }
			body.clear()
		}
		for (line in text.lineSequence()) {
			if (line.startsWith("@@ ")) {
				close()
				id = line.removePrefix("@@ ").substringBefore('|').trim()
				title = line.substringAfter('|', "").trim()
			} else if (id != null) {
				body.append(line).append('\n')
			}
		}
		close()
		return out
	}
}
