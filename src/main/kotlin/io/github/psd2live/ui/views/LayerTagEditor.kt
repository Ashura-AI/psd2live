package io.github.psd2live.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.LayerTags
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.localizedName
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.views.texture.FloatingMenu
import io.github.psd2live.ui.views.texture.FloatingMenuDivider
import io.github.psd2live.ui.views.texture.FloatingMenuRow
import io.github.psd2live.ui.views.texture.FloatingMenuSection

/** A tag's display name: a vocabulary word in the interface language, a user's tag as typed. */
internal fun tagLabel(tag: String): String = if (LayerTags.isSemantic(tag)) tr("tag.word.$tag") else tag

/**
 * The tags a typed entry adds: a vocabulary word as itself, a layer-name alias as the words it stands for
 * (`前发` gives hair and front), anything else as the user's own tag.
 */
internal fun typedTags(text: String): Set<String> {
	val normalized = LayerTags.normalize(text)
	if (normalized.isEmpty()) return emptySet()
	if (LayerTags.isSemantic(normalized)) return setOf(normalized)
	LayerTags.vocabulary.firstOrNull { tagLabel(it).equals(normalized, ignoreCase = true) }?.let { return setOf(it) }
	return LayerTags.fromName(normalized).ifEmpty { setOf(normalized) }
}

/**
 * A layer's tags as a row of chips; a click opens a menu that edits them. [onSet] receives the whole new tag set,
 * committed as one `layer_tag` edit. [pending] marks tags that wait for the next generation.
 */
@Composable
internal fun LayerTagCell(tags: Set<String>, pending: Boolean, onSet: (Set<String>) -> Unit, modifier: Modifier = Modifier) {
	val colors = LocalToolColors.current
	var open by remember { mutableStateOf(false) }
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	Box(modifier) {
		Row(
			Modifier
				.fillMaxWidth()
				.height(20.dp)
				.clip(RoundedCornerShape(3.dp))
				.background(if (hovered || open) colors.controlHover.copy(alpha = 0.6f) else colors.inputBackground)
				.border(0.5.dp, if (pending) colors.accent.copy(alpha = 0.7f) else colors.border, RoundedCornerShape(3.dp))
				.hoverable(interaction)
				.clickable(interactionSource = interaction, indication = null) { open = true }
				.padding(horizontal = 3.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(3.dp),
		) {
			if (tags.isEmpty()) Text(tr("layers.tags.none"), color = colors.textDisabled, fontSize = 10.sp, maxLines = 1)
			for (tag in orderedTags(tags)) TagChip(tag)
		}
		FloatingMenu(expanded = open, onDismiss = { open = false }, width = 240.dp) {
			TagMenu(tags, onSet)
		}
	}
}

/** Vocabulary words in vocabulary order, then the user's own tags alphabetically. */
private fun orderedTags(tags: Set<String>): List<String> =
	LayerTags.vocabulary.filter { it in tags } + tags.filterNot(LayerTags::isSemantic).sorted()

@Composable
private fun TagChip(tag: String) {
	val colors = LocalToolColors.current
	val semantic = LayerTags.isSemantic(tag)
	Text(
		tagLabel(tag),
		color = if (semantic) colors.accent else colors.textPrimary,
		fontSize = 10.sp,
		maxLines = 1,
		overflow = TextOverflow.Ellipsis,
		modifier = Modifier
			.clip(RoundedCornerShape(3.dp))
			.background(if (semantic) colors.accent.copy(alpha = 0.14f) else colors.panelElevated)
			.padding(horizontal = 4.dp, vertical = 1.dp),
	)
}

@Composable
private fun TagMenu(tags: Set<String>, onSet: (Set<String>) -> Unit) {
	var text by remember { mutableStateOf("") }
	fun add(value: String) {
		val added = typedTags(value)
		if (added.isEmpty()) return
		// A side replaces the other side.
		val sides = setOf(LayerTags.LEFT, LayerTags.RIGHT)
		val base = if (added.any { it in sides }) tags - sides else tags
		onSet(base + added)
		text = ""
	}
	FloatingMenuSection(tr("layers.tags.section.current"))
	if (tags.isEmpty()) FloatingMenuRow(tr("layers.tags.none"), {}, enabled = false)
	for (tag in orderedTags(tags)) {
		FloatingMenuRow(tagLabel(tag), { onSet(tags - tag) }, hint = if (LayerTags.isSemantic(tag)) tag else null,
			trailing = tr("layers.tags.remove"))
	}
	Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
		CompactTextField(text, { text = it }, placeholder = tr("layers.tags.add.placeholder"), height = 22.dp,
			onCommit = { add(text) }, modifier = Modifier.fillMaxWidth())
	}
	val typed = text.trim()
	if (typed.isNotEmpty()) {
		val suggestions = (typedTags(typed).toList() + LayerTags.vocabulary.filter { word ->
			word.startsWith(typed, ignoreCase = true) || tagLabel(word).contains(typed, ignoreCase = true)
		}).distinct().filterNot { it in tags }.take(6)
		for (suggestion in suggestions) FloatingMenuRow(tagLabel(suggestion), { add(suggestion) },
			hint = if (LayerTags.isSemantic(suggestion)) suggestion else tr("layers.tags.user"))
	}
	FloatingMenuDivider()
	FloatingMenuSection(tr("layers.tags.section.part"))
	val resolution = LayerTags.resolve(tags)
	Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
		for ((part, words) in LayerTags.parts) {
			FloatingMenuRow(part.localizedName(), {
				// A part replaces the words of the current part; the side and the user's tags stay.
				val current = LayerTags.parts[resolution.tag].orEmpty()
				onSet(tags - current + words)
			}, selected = resolution.tag == part, hint = words.joinToString(" + ") { tagLabel(it) })
		}
		FloatingMenuRow(SemanticTag.UNKNOWN.localizedName(), { onSet(tags - LayerTags.parts[resolution.tag].orEmpty()) },
			selected = resolution.tag == SemanticTag.UNKNOWN)
	}
	FloatingMenuDivider()
	FloatingMenuSection(tr("layers.tags.section.side"))
	for (side in Side.entries) {
		FloatingMenuRow(side.localizedName(), {
			val base = tags - LayerTags.LEFT - LayerTags.RIGHT
			onSet(when (side) { Side.LEFT -> base + LayerTags.LEFT; Side.RIGHT -> base + LayerTags.RIGHT; Side.NONE -> base })
		}, selected = resolution.side == side)
	}
}
