package io.github.psd2live.ui.views

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.IconPlay
import io.github.psd2live.ui.state.WhatsNew
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.handCursor
import io.github.psd2live.ui.tutorial.TutorialId
import io.github.psd2live.ui.utils.DesktopUtils

/** The text column's widest; past it lines get too long to read. */
private val ReadableWidth = 680.dp

/**
 * What changed, release by release, as text to read: each version's heading stays at the top while its changes
 * scroll under it. A change a tutorial shows ends with a link to it; the whole history is a click away.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WhatsNewPanel(language: AppLanguage, enabled: Boolean, onStartTutorial: ((TutorialId) -> Unit)?) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val releases = remember(language) { WhatsNew.releases(language) }
	Column(Modifier.fillMaxSize()) {
		PanelToolbar {
			PanelToolbarTitle(tr("whatsnew.intro"), Modifier.weight(1f))
			PanelToolButton(tr("whatsnew.full"), showLabel = true, onClick = { DesktopUtils.openBrowser(DesktopUtils.GITHUB_RELEASES_URL) },
				enabled = true, tooltip = DesktopUtils.GITHUB_RELEASES_URL) {}
		}
		LazyColumn(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
			releases.forEach { release ->
				stickyHeader(key = "v:" + release.version) {
					Row(
						Modifier.fillMaxWidth().background(colors.panelBackground).padding(horizontal = 14.dp).padding(top = 12.dp, bottom = 6.dp),
						horizontalArrangement = Arrangement.Center,
					) {
						Row(Modifier.widthIn(max = ReadableWidth).fillMaxWidth(), verticalAlignment = Alignment.Bottom,
							horizontalArrangement = Arrangement.spacedBy(8.dp)) {
							Text(if (release.version == WhatsNew.NEXT) tr("whatsnew.next") else release.version,
								style = typography.header.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = colors.textPrimary)
							release.date?.let { Text(it, style = typography.monoSmall.copy(fontSize = 10.5.sp), color = colors.textMuted) }
						}
					}
				}
				release.groups.forEach { group ->
					item(key = "g:${release.version}:${group.title}") {
						Text(group.title, style = typography.caption.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium), color = colors.textMuted,
							modifier = Modifier.widthIn(max = ReadableWidth).fillMaxWidth().padding(horizontal = 14.dp).padding(top = 6.dp, bottom = 2.dp))
					}
					items(group.entries) { entry ->
						WhatsNewEntry(entry, enabled, onStartTutorial)
					}
				}
				item(key = "end:" + release.version) { Spacer(Modifier.height(8.dp)) }
			}
		}
	}
}

@Composable
private fun WhatsNewEntry(entry: WhatsNew.Entry, enabled: Boolean, onStartTutorial: ((TutorialId) -> Unit)?) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(Modifier.widthIn(max = ReadableWidth).fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp)) {
		Text("•", style = typography.body.copy(fontSize = 11.5.sp), color = colors.textMuted)
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
			Text(
				buildAnnotatedString {
					entry.title?.let {
						withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.textPrimary)) { append(it) }
						append("  ")
					}
					append(entry.text)
				},
				style = typography.body.copy(fontSize = 11.5.sp, lineHeight = 17.sp),
				color = colors.textPrimary,
			)
			val tutorial = entry.tutorial
			if (tutorial != null && onStartTutorial != null) TutorialLink(enabled) { onStartTutorial(tutorial) }
		}
	}
}

/** "Show me": starts the tutorial for a change, in the accent, underlined while hovered. */
@Composable
private fun TutorialLink(enabled: Boolean, onClick: () -> Unit) {
	val colors = LocalToolColors.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	Row(
		Modifier.hoverable(interaction).clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
			.handCursor(enabled),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(4.dp),
	) {
		IconPlay(modifier = Modifier.size(9.dp), tint = if (enabled) colors.accent else colors.textDisabled)
		Text(tr("whatsnew.tutorial"), style = LocalToolTypography.current.caption.copy(fontSize = 10.5.sp,
			textDecoration = if (hovered && enabled) androidx.compose.ui.text.style.TextDecoration.Underline else null),
			color = if (enabled) colors.accent else colors.textDisabled)
	}
}
