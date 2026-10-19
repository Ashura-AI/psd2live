package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import io.github.psd2live.ui.views.texture.glideItem
import io.github.psd2live.ui.views.texture.MenuGlide
import io.github.psd2live.ui.theme.handCursor
import io.github.psd2live.ui.theme.LocalToolTypography
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.I18n
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.Keymap
import io.github.psd2live.ui.state.Manual
import io.github.psd2live.ui.state.ShortcutAction
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.ToolColors
import io.github.psd2live.ui.tutorial.TutorialId
import io.github.psd2live.ui.tutorial.TutorialPath
import io.github.psd2live.agent.AgentOperationDoc

/*
 * A manual or tutorial page, set out like a Cubism manual page: the folders it sits in, its title with the tag that
 * says how it compares with Cubism, a link to the Cubism page, its text, and the pages before and after it.
 *
 * Page text is a small Markdown: `## ` and `### ` headings, paragraphs, `- ` lists, `1. ` numbered steps, `| a | b |`
 * tables, `> ` notes, `!> ` cautions and `*> ` points, and `{tutorial=ID}` on its own line for a step-by-step tutorial.
 * Inline, `**bold**`, `` `code` ``, `[[page]]` or `[[page|text]]` links, `{key:ACTION}` for an action's current keys
 * and `{kbd:Ctrl+G}` for literal ones.
 */

internal sealed interface ManualBlock {
	data class Heading(val level: Int, val text: String) : ManualBlock
	data class Paragraph(val text: String) : ManualBlock
	data class Bullets(val items: List<String>) : ManualBlock
	data class Steps(val items: List<String>) : ManualBlock
	data class Table(val rows: List<List<String>>) : ManualBlock
	data class Note(val kind: NoteKind, val text: String) : ManualBlock
	data class Tutorial(val id: TutorialId) : ManualBlock
	data class Path(val path: TutorialPath) : ManualBlock
}

internal enum class NoteKind { NOTE, CAUTION, POINT }

private val TutorialLine = Regex("""^\{tutorial=([A-Z0-9_]+)}$""")
private val PathLine = Regex("""^\{path=([A-Z0-9_]+)}$""")
private val StepLine = Regex("""^\d+[.)]\s+(.*)$""")

internal fun parseManualBlocks(body: String): List<ManualBlock> {
	val blocks = mutableListOf<ManualBlock>()
	val lines = body.lines()
	var i = 0
	fun collect(prefix: (String) -> String?): List<String> {
		val items = mutableListOf<String>()
		while (i < lines.size) {
			val item = prefix(lines[i].trim()) ?: break
			items += item
			i++
		}
		return items
	}
	while (i < lines.size) {
		val line = lines[i].trim()
		when {
			line.isEmpty() -> i++
			line.startsWith("### ") -> { blocks += ManualBlock.Heading(3, line.removePrefix("### ").trim()); i++ }
			line.startsWith("## ") -> { blocks += ManualBlock.Heading(2, line.removePrefix("## ").trim()); i++ }
			line.startsWith("- ") -> blocks += ManualBlock.Bullets(collect { if (it.startsWith("- ")) it.removePrefix("- ").trim() else null })
			StepLine.matches(line) -> blocks += ManualBlock.Steps(collect { StepLine.find(it)?.groupValues?.get(1)?.trim() })
			line.startsWith("|") -> {
				val rows = collect { if (it.startsWith("|")) it else null }
					.filterNot { row -> row.trim('|', ' ').all { it == '-' || it == '|' || it == ':' || it == ' ' } }
					.map { row -> row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() } }
				blocks += ManualBlock.Table(rows)
			}
			line.startsWith("!> ") || line.startsWith("*> ") || line.startsWith("> ") -> {
				val kind = when (line[0]) { '!' -> NoteKind.CAUTION; '*' -> NoteKind.POINT; else -> NoteKind.NOTE }
				val marker = if (kind == NoteKind.NOTE) "> " else line.substring(0, 3)
				blocks += ManualBlock.Note(kind, collect { if (it.startsWith(marker)) it.removePrefix(marker) else null }.joinToString("\n"))
			}
			PathLine.matches(line) -> {
				TutorialPath.entries.firstOrNull { it.name == PathLine.find(line)!!.groupValues[1] }?.let { blocks += ManualBlock.Path(it) }
				i++
			}
			TutorialLine.matches(line) -> {
				TutorialId.entries.firstOrNull { it.name == TutorialLine.find(line)!!.groupValues[1] }?.let { blocks += ManualBlock.Tutorial(it) }
				i++
			}
			else -> {
				blocks += ManualBlock.Paragraph(line)
				i++
			}
		}
	}
	return blocks
}

private val InlineMark = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[\[([^\]|]+)(?:\|([^\]]+))?]]|\{key:([A-Za-z0-9_]+)}|\{kbd:([^}]+)}""")

/** Inline text with its marks turned to styles and links; [onOpenPage] follows a link to a page that opens. */
internal fun manualInline(
	text: String,
	colors: ToolColors,
	keymap: Keymap,
	titles: Map<String, Manual.Text>,
	onOpenPage: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
	val keyStyle = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = colors.accent, background = colors.controlBackground)
	var last = 0
	for (match in InlineMark.findAll(text)) {
		append(text.substring(last, match.range.first))
		val (bold, code, link, linkText, key, kbd) = match.destructured
		when {
			bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
			code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.controlBackground)) { append(code) }
			link.isNotEmpty() -> {
				val label = linkText.ifEmpty { titles[link]?.title ?: link }
				val target = Manual.page(link)
				if (target != null && target.opens) {
					withLink(LinkAnnotation.Clickable(link, TextLinkStyles(SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline),
						hoveredStyle = SpanStyle(color = colors.accentHover, textDecoration = TextDecoration.Underline))) { onOpenPage(link) }) { append(label) }
				} else {
					withStyle(SpanStyle(color = colors.textDisabled)) { append(label) }
				}
			}
			key.isNotEmpty() -> {
				val action = runCatching { ShortcutAction.valueOf(key) }.getOrNull()
				val label = action?.let { keymap.labelFor(it) } ?: tr("help.shortcuts.unbound")
				withStyle(keyStyle) { append(" $label ") }
			}
			kbd.isNotEmpty() -> withStyle(keyStyle) { append(" $kbd ") }
		}
		last = match.range.last + 1
	}
	append(text.substring(last))
}

/** The chip in a page's heading that says how it compares with Cubism, in the status' colour. */
@Composable
internal fun ManualStatusTag(status: Manual.Status, modifier: Modifier = Modifier) {
	val color = manualStatusColor(status)
	Text(tr(status.labelKey), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1, softWrap = false,
		modifier = modifier.clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 6.dp, vertical = 1.dp))
}

/** A status' colour: the manual contents' gutter bars and the page heading's chip. */
@Composable
internal fun manualStatusColor(status: Manual.Status): Color {
	val colors = LocalToolColors.current
	return when (status) {
		Manual.Status.SAME -> colors.success
		Manual.Status.DIFFERENT -> colors.warning
		Manual.Status.EXCLUSIVE -> colors.accent
		Manual.Status.MISSING -> colors.textDisabled
	}
}

/**
 * One manual or tutorial page. [embed] draws a generated table a page names; [onStartTutorial] starts a tutorial;
 * [operations] are the MCP operations as the running server describes them, for the page's MCP section.
 */
@Composable
internal fun ManualPageContent(
	page: Manual.Page,
	keymap: Keymap,
	onOpenPage: (String) -> Unit,
	onOpenUrl: (String) -> Unit,
	onStartTutorial: ((TutorialId, TutorialPath) -> Unit)?,
	operations: List<AgentOperationDoc>,
	embed: @Composable (String) -> Unit,
) {
	val colors = LocalToolColors.current
	val language = I18n.currentLanguage
	val texts = remember(language) { Manual.texts(language) }
	val text = texts[page.id]
	val typography = io.github.psd2live.ui.theme.LocalToolTypography.current
	val bodyStyle = typography.caption.copy(fontSize = 11.5.sp, lineHeight = 17.sp, color = colors.textPrimary)

	// Where it is: the folders, as the Cubism pages' breadcrumb.
	val trail = remember(page.id, language) {
		listOf(tr(if (page.root == Manual.Root.MANUAL) "help.tab.manual" else "help.tab.tutorials")) +
			Manual.ancestors(page.id).map { texts[it.id]?.title ?: it.id }
	}
	Text(trail.joinToString("  ›  "), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)

	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(text?.title ?: page.id, style = typography.title.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold), color = colors.textPrimary,
				modifier = Modifier.weight(1f, fill = false))
			ManualStatusTag(page.status)
		}
		Text(tr(page.status.labelKey + ".desc"), style = typography.caption.copy(fontSize = 10.5.sp), color = colors.textMuted)
		Manual.cubismUrl(page, language)?.let { url ->
			Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				CompactButton(tr(if (page.root == Manual.Root.MANUAL) "help.manual.cubismPage" else "help.manual.cubismTutorial"),
					onClick = { onOpenUrl(url) }, height = 22.dp)
			}
		}
	}
	Divider(color = colors.divider, thickness = 1.dp)

	val blocks = remember(text?.body) { parseManualBlocks(text?.body.orEmpty()) }
	fun inline(value: String) = manualInline(value, colors, keymap, texts, onOpenPage)
	blocks.forEach { block ->
		when (block) {
			is ManualBlock.Heading -> if (block.level == 2) {
				Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
					Text(inline(block.text), style = typography.title.copy(fontSize = 14.5.sp, fontWeight = FontWeight.Bold), color = colors.textPrimary)
					Box(Modifier.fillMaxWidth().height(2.dp).background(colors.accent.copy(alpha = 0.5f)))
				}
			} else {
				Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					Box(Modifier.width(3.dp).height(13.dp).background(colors.accent))
					Text(inline(block.text), style = typography.body.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold), color = colors.textPrimary)
				}
			}
			is ManualBlock.Paragraph -> Text(inline(block.text), style = bodyStyle)
			is ManualBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
				block.items.forEach { item ->
					Row {
						Text("•", style = bodyStyle, color = colors.textMuted, modifier = Modifier.width(14.dp))
						Text(inline(item), style = bodyStyle, modifier = Modifier.weight(1f))
					}
				}
			}
			is ManualBlock.Steps -> ManualTableFrame {
				// Cubism's step tables: a number column beside each step.
				block.items.forEachIndexed { index, item ->
					if (index > 0) Divider(color = colors.divider, thickness = 1.dp)
					Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
						Box(Modifier.width(30.dp).fillMaxHeight().background(colors.controlBackground), contentAlignment = Alignment.Center) {
							Text("${index + 1}", style = typography.monoSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold), color = colors.accent)
						}
						Text(inline(item), style = bodyStyle, modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp))
					}
				}
			}
			is ManualBlock.Table -> ManualTableFrame {
				block.rows.forEachIndexed { r, row ->
					if (r > 0) Divider(color = colors.divider, thickness = 1.dp)
					Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(if (r == 0) colors.controlBackground else Color.Transparent)) {
						row.forEachIndexed { c, cell ->
							if (c > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(colors.divider))
							Text(inline(cell), style = bodyStyle.copy(fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal),
								modifier = Modifier.weight(if (c == 0 && row.size > 2) 0.7f else 1f).padding(horizontal = 8.dp, vertical = 5.dp))
						}
					}
				}
			}
			is ManualBlock.Note -> {
				val (accent, labelKey) = when (block.kind) {
					NoteKind.CAUTION -> colors.warning to "help.manual.caution"
					NoteKind.POINT -> colors.success to "help.manual.point"
					NoteKind.NOTE -> colors.accent to "help.manual.note"
				}
				Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(4.dp)).background(accent.copy(alpha = 0.08f))) {
					Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
					Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
						Text(tr(labelKey), style = typography.caption.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold), color = accent)
						block.text.lines().forEach { Text(inline(it), style = bodyStyle) }
					}
				}
			}
			is ManualBlock.Tutorial -> if (onStartTutorial != null) ManualTutorialCard(block.id) { onStartTutorial(block.id, TutorialPath.defaultFor(block.id)) }
			is ManualBlock.Path -> if (onStartTutorial != null) ManualTutorialPathCard(block.path, onStartTutorial)
		}
	}
	page.embed?.let { embed(it) }
	Manual.operations[page.id]?.let { ids ->
		val described = ids.mapNotNull { id -> operations.firstOrNull { it.id == id } }
		if (described.isNotEmpty()) ManualOperationsSection(described)
	}

	// The pages before and after this one, as the Cubism pages end.
	val order = remember(page.root) { Manual.readingOrder(page.root) }
	val index = order.indexOfFirst { it.id == page.id }
	if (index >= 0) {
		Divider(color = colors.divider, thickness = 1.dp)
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			order.getOrNull(index - 1)?.let { previous ->
				CompactButton("‹  " + (texts[previous.id]?.title ?: previous.id), onClick = { onOpenPage(previous.id) }, height = 24.dp,
					modifier = Modifier.widthIn(max = 320.dp))
			}
			Spacer(Modifier.weight(1f))
			order.getOrNull(index + 1)?.let { next ->
				CompactButton((texts[next.id]?.title ?: next.id) + "  ›", onClick = { onOpenPage(next.id) }, height = 24.dp,
					modifier = Modifier.widthIn(max = 320.dp))
			}
		}
	}
}

@Composable
private fun ManualTableFrame(content: @Composable () -> Unit) {
	val colors = LocalToolColors.current
	Column(Modifier.fillMaxWidth().background(colors.panelElevated).border(BorderStroke(1.dp, colors.divider))) { content() }
}

/** A small coloured label, the shape of the status tags, for what a card is. */
@Composable
private fun ManualChip(text: String, color: Color) {
	Text(text, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1, softWrap = false,
		modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 5.dp))
}

/** A step-by-step tutorial chapter inside an article: its tag, title and what it teaches, and the button that starts it. */
@Composable
private fun ManualTutorialCard(id: TutorialId, onStart: () -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(
		Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(4.dp)).background(colors.highlight.copy(alpha = 0.07f))
			.border(BorderStroke(1.dp, colors.highlight.copy(alpha = 0.35f)), RoundedCornerShape(4.dp)),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(Modifier.width(3.dp).fillMaxHeight().background(colors.highlight))
		Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				ManualChip(tr("help.manual.interactive"), colors.highlight)
				Text(tr(id.titleKey), style = typography.caption.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold), color = colors.textPrimary)
			}
			Text(tr(id.descKey), style = typography.caption.copy(fontSize = 10.5.sp, lineHeight = 14.sp), color = colors.textMuted)
		}
		CompactButton(tr("tutorial.path.start"), onClick = onStart, isPrimary = true, height = 22.dp, modifier = Modifier.padding(end = 10.dp))
	}
}

/** A whole step-by-step path inside an article: its chapters, each started on its own, or all in turn. */
@Composable
private fun ManualTutorialPathCard(path: TutorialPath, onStart: (TutorialId, TutorialPath) -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Column(
		Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(colors.highlight.copy(alpha = 0.07f))
			.border(BorderStroke(1.dp, colors.highlight.copy(alpha = 0.35f)), RoundedCornerShape(4.dp)).padding(10.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			ManualChip(tr("help.manual.interactivePath"), colors.highlight)
			Text(tr(path.titleKey), style = typography.body.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold), color = colors.textPrimary,
				modifier = Modifier.weight(1f))
			CompactButton(tr("tutorial.path.start"), onClick = { onStart(path.chapters.first(), path) }, isPrimary = true, height = 22.dp)
		}
		Text(tr(path.descKey), style = typography.caption.copy(fontSize = 10.5.sp, lineHeight = 14.sp), color = colors.textMuted)
		MenuGlide {
			Column(Modifier.fillMaxWidth()) {
				path.chapters.forEachIndexed { index, id ->
					Row(
						Modifier.fillMaxWidth().glideItem(enabled = true)
							.clickable(indication = null, interactionSource = null) { onStart(id, path) }.handCursor()
							.padding(horizontal = 4.dp, vertical = 4.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(8.dp),
					) {
						Text("%02d".format(index + 1), style = typography.monoSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold), color = colors.highlight)
						Text(tr(id.titleKey), style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = colors.textPrimary)
						Text(tr(id.descKey), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted, maxLines = 1,
							overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
					}
				}
			}
		}
	}
}

/**
 * The MCP operations that do what the page describes, read from the running server: how the compact profile calls
 * each one, its kind, and, opened, what it does and its request fields.
 */
@Composable
private fun ManualOperationsSection(operations: List<AgentOperationDoc>) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val bodyStyle = typography.caption.copy(fontSize = 11.sp, lineHeight = 15.sp, color = colors.textPrimary)
	Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Text(tr("help.manual.mcp.title"), style = typography.title.copy(fontSize = 14.5.sp, fontWeight = FontWeight.Bold), color = colors.textPrimary)
		Box(Modifier.fillMaxWidth().height(2.dp).background(colors.accent.copy(alpha = 0.5f)))
	}
	Text(tr("help.manual.mcp.intro"), style = bodyStyle.copy(color = colors.textMuted))
	ManualTableFrame {
		operations.forEachIndexed { index, operation ->
			if (index > 0) Divider(color = colors.divider, thickness = 1.dp)
			var open by remember(operation.id) { mutableStateOf(false) }
			Row(
				Modifier.fillMaxWidth().clickable(indication = null, interactionSource = null) { open = !open }.handCursor()
					.padding(horizontal = 8.dp, vertical = 6.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				IconChevron(open, Modifier.size(11.dp))
				Text(operation.id, style = typography.monoSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = colors.accent)
				ManualChip(tr("help.manual.mcp.kind.${operation.kind}"), colors.textMuted)
				if (operation.jobBacked) ManualChip(tr("help.manual.mcp.job"), colors.warning)
				val compact = when {
					operation.coreOperation == null -> operation.coreTool
					operation.coreTool == "workspace_call" -> "workspace_call · operation=${operation.coreOperation}"
					else -> "${operation.coreTool} · op=${operation.coreOperation}"
				}
				Text(tr("help.manual.mcp.core", compact), style = typography.monoSmall.copy(fontSize = 9.5.sp), color = colors.textMuted,
					maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
			}
			if (open) Column(Modifier.fillMaxWidth().padding(start = 25.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Text(operation.description, style = bodyStyle)
				if (operation.fields.isNotEmpty()) {
					Column(Modifier.fillMaxWidth().border(BorderStroke(1.dp, colors.divider))) {
						val header = listOf(tr("help.manual.mcp.field"), tr("help.manual.mcp.type"), tr("help.manual.mcp.about"))
						(listOf(null) + operation.fields).forEachIndexed { r, field ->
							if (r > 0) Divider(color = colors.divider, thickness = 1.dp)
							Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(if (field == null) colors.controlBackground else Color.Transparent)) {
								val cells = if (field == null) header else listOf(field.name + if (field.required) " *" else "", field.type, field.description)
								cells.forEachIndexed { c, cell ->
									if (c > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(colors.divider))
									val style = when {
										field == null -> bodyStyle.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
										c < 2 -> typography.monoSmall.copy(fontSize = 10.sp, color = if (c == 1) colors.codeIdentifier else colors.textPrimary)
										else -> bodyStyle.copy(fontSize = 10.5.sp)
									}
									Text(cell, style = style, modifier = Modifier.weight(when (c) { 0 -> 0.9f; 1 -> 1f; else -> 2f }).padding(horizontal = 6.dp, vertical = 4.dp))
								}
							}
						}
					}
					Text(tr("help.manual.mcp.required"), style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textMuted)
				}
			}
		}
	}
}
