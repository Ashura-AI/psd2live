package io.github.psd2live.ui.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.WorkspaceGeneration
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.views.texture.AccentSplitButton
import io.github.psd2live.ui.views.texture.BarDivider
import io.github.psd2live.ui.views.texture.FloatingBar
import io.github.psd2live.ui.views.texture.FloatingMenu
import io.github.psd2live.ui.views.texture.FloatingMenuRow
import io.github.psd2live.ui.views.texture.FloatingMenuSection
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import io.github.psd2live.ui.theme.Motion

/**
 * What waits for generation, at the top right of the edit canvas: "not generated" before the first generation, else
 * how many layers wait (placed since, reclassified, or with a replaced image that may refresh its geometry), and the
 * accent button that generates the rig - or generates it again, keeping the user's edits. Its menu chooses whether
 * replaced images mesh again from their new pixels.
 */
@Composable
internal fun BoxScope.LayoutGenerationBar(viewModel: PSD2LiveViewModel, focus: () -> Unit, compact: Boolean = false,
                                          onWidth: (Int) -> Unit = {}) {
	val generation by remember(viewModel) { viewModel.state.map { it.layout.generation }.distinctUntilChanged() }
		.collectAsState(viewModel.state.value.layout.generation)
	val busy by remember(viewModel) { viewModel.state.map { it.canvasEditBusy || it.workspaceEditBusy }.distinctUntilChanged() }
		.collectAsState(false)
	val ready by remember(viewModel) { viewModel.state.map { it.previewModel != null }.distinctUntilChanged() }.collectAsState(false)
	val waiting = generation.pendingLayerIds.size + generation.pendingClassifications.keys.count { it !in generation.pendingLayerIds }
	val visible = !generation.generated || waiting > 0 || generation.refreshLayerIds.isNotEmpty()
	var menu by remember { mutableStateOf(false) }
	AnimatedVisibility(
		visible = visible,
		// Its width, 0 once gone, keeps the session bar between it and the mode bar.
		modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp)
			.onSizeChanged { onWidth(it.width) },
		enter = slideInVertically(tween(Motion.Expand, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(Motion.Fold)),
		exit = slideOutVertically(tween(Motion.Fold, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(Motion.Fade)),
	) {
		val colors = LocalToolColors.current
		FloatingBar {
			val status = when {
				!generation.generated -> tr("layout.status.notGenerated")
				waiting > 0 -> tr("layout.status.pending", waiting)
				else -> tr("layout.status.refreshable", generation.refreshLayerIds.size)
			}
			// Where room is short the status moves into the button's tooltip.
			if (!compact) {
				Text(status, color = colors.textMuted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp))
				BarDivider()
			}
			Box(Modifier.onGloballyPositioned { MaterialsPanelProbe.generateButton = it.boundsInRoot() }) {
				AccentSplitButton(
					label = if (generation.generated) tr("layout.generate.again") else tr("layout.generate"),
					icon = { tint -> io.github.psd2live.ui.components.IconPlay(androidx.compose.ui.Modifier.size(11.dp), tint) },
					onClick = { viewModel.generateLayout(refresh = true); focus() },
					open = menu,
					onMenu = { menu = !menu },
					enabled = !busy && ready,
					tooltip = (if (compact) "$status\n" else "") +
						if (generation.generated) tr("layout.generate.again.tip") else tr("layout.generate.tip"),
					menuTooltip = tr("layout.generate.options"),
				)
				FloatingMenu(expanded = menu, onDismiss = { menu = false }, width = 250.dp, alignment = Alignment.TopEnd) {
					FloatingMenuSection(tr("layout.generate.options"))
					FloatingMenuRow(tr("layout.generate.refresh"), { menu = false; viewModel.generateLayout(refresh = true); focus() },
						trailing = generation.refreshLayerIds.size.takeIf { it > 0 }?.toString(), enabled = !busy && ready)
					FloatingMenuRow(tr("layout.generate.keep"), { menu = false; viewModel.generateLayout(refresh = false); focus() },
						enabled = !busy && ready && generation != WorkspaceGeneration.Generated)
				}
			}
		}
	}
}
