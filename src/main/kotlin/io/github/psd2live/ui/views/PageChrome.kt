package io.github.psd2live.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.handCursor

/*
 * The app pages' shared parts, so the start, help and settings pages read as one: a contents panel of
 * [PageNavItem] rows under [PageNavGroup] captions, and topic panels whose text sits in a [PageBody].
 * A topic panel opens with a PanelToolbar only when it has tools; its dock header already names it.
 */

/** A page's text column is never wider than this; past it lines get too long to read. */
internal val PageReadableWidth = 760.dp

/**
 * A page's contents: rows picked with a click or stepped through with the arrow keys ([onStep] gets -1 or 1), and
 * a [footer] pinned under them for the page's own actions.
 */
@Composable
internal fun PageNavList(
	onStep: (Int) -> Unit,
	footer: (@Composable ColumnScope.() -> Unit)? = null,
	content: @Composable ColumnScope.() -> Unit,
) {
	Column(Modifier.fillMaxSize()) {
		Column(
			Modifier.weight(1f).fillMaxWidth()
				.onPreviewKeyEvent { event ->
					if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
					when (event.key) {
						Key.DirectionUp -> onStep(-1)
						Key.DirectionDown -> onStep(1)
						else -> return@onPreviewKeyEvent false
					}
					true
				}
				.focusable()
				.verticalScroll(rememberScrollState())
				.padding(vertical = 4.dp),
			content = content,
		)
		if (footer != null) {
			Box(Modifier.fillMaxWidth().height(0.5.dp).background(LocalToolColors.current.divider))
			Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = footer)
		}
	}
}

/** A caption over a group of contents rows. */
@Composable
internal fun PageNavGroup(text: String) {
	Text(
		text,
		style = LocalToolTypography.current.caption.copy(fontSize = 10.sp),
		color = LocalToolColors.current.textMuted,
		modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 3.dp),
	)
}

/**
 * A contents row: the tree's selection colours with the menus' accent edge on the selected one. [indent] nests it under
 * a folder row; [trailingContent] sits at its end after [trailing]. A [gutter] colour turns the edge into a thin status
 * bar, like a diff gutter, shown whether or not the row is selected; selection then shows by the row's fill alone.
 */
@Composable
internal fun PageNavItem(
	label: String,
	selected: Boolean,
	trailing: String? = null,
	enabled: Boolean = true,
	badge: Boolean = false,
	onClick: () -> Unit,
	indent: Dp = 0.dp,
	bold: Boolean = false,
	trailingContent: (@Composable () -> Unit)? = null,
	gutter: Color? = null,
	icon: (@Composable (Color) -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val tint = when {
		!enabled -> colors.textDisabled
		selected -> colors.selectionText
		else -> colors.textPrimary
	}
	Row(
		Modifier.fillMaxWidth().height(24.dp)
			// The gutter fills the row's whole height, square, outside the row's rounded fill, so the bars of
			// neighbouring rows join into one column; it is drawn over the row so hover and selection never hide it.
			.then(if (gutter == null) Modifier else Modifier.drawWithContent {
				drawContent()
				drawRect(if (enabled) gutter else gutter.copy(alpha = 0.45f), topLeft = Offset(4.dp.toPx(), 0f), size = Size(3.dp.toPx(), size.height))
			})
			.padding(horizontal = 4.dp)
			.clip(RoundedCornerShape(3.dp))
			.background(when {
				selected -> colors.selection
				hovered && enabled -> colors.controlHover.copy(alpha = 0.45f)
				else -> Color.Transparent
			})
			.hoverable(interaction)
			.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
			.handCursor(enabled),
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (gutter != null) {
			Spacer(Modifier.width(10.dp + indent))
		} else {
			Box(Modifier.width(2.dp).height(14.dp).clip(RoundedCornerShape(1.dp)).background(if (selected) colors.accent else Color.Transparent))
			Spacer(Modifier.width(8.dp + indent))
		}
		if (icon != null) {
			Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
				icon(if (selected) colors.selectionText else if (enabled) colors.textMuted else colors.textDisabled)
			}
			Spacer(Modifier.width(8.dp))
		}
		// The title and its badge take every width the trailing items leave, so those always sit flush right.
		Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
			Text(label, style = typography.body.copy(fontSize = 11.5.sp,
				fontWeight = if (bold) FontWeight.SemiBold else if (selected) FontWeight.Medium else FontWeight.Normal),
				color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
			if (badge) {
				Spacer(Modifier.width(6.dp))
				NewBadge()
			}
		}
		trailing?.takeIf { it.isNotBlank() }?.let {
			Text(it, style = typography.monoSmall.copy(fontSize = 10.sp), color = colors.textMuted, modifier = Modifier.padding(end = 6.dp))
		}
		if (trailingContent != null) {
			Spacer(Modifier.width(6.dp))
			trailingContent()
			Spacer(Modifier.width(6.dp))
		}
	}
}

/** "New", on what a recent release added, in the highlight colour the history uses for what is current. */
@Composable
private fun NewBadge(modifier: Modifier = Modifier) {
	val colors = LocalToolColors.current
	Text(
		tr("start.badge.new"),
		fontSize = 8.sp,
		fontWeight = FontWeight.Bold,
		color = colors.highlight,
		maxLines = 1,
		modifier = modifier.clip(RoundedCornerShape(3.dp)).background(colors.highlightContainer).padding(horizontal = 3.dp),
	)
}

/** A topic's text: scrolling, padded the same on every page, and centred at [PageReadableWidth] in a wide panel. */
@Composable
internal fun ColumnScope.PageBody(content: @Composable ColumnScope.() -> Unit) {
	Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
		Column(
			modifier = Modifier.widthIn(max = PageReadableWidth).fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
			content = content,
		)
	}
}
