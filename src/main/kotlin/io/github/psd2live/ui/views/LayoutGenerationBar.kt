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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** Asks for the canvas size of a new Layout project, in canvas units. */
@Composable
internal fun CanvasSizeDialog(onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
	val colors = LocalToolColors.current
	var width by remember { mutableStateOf("2048") }
	var height by remember { mutableStateOf("2048") }
	val w = width.trim().toIntOrNull(); val h = height.trim().toIntOrNull()
	val valid = w != null && h != null && w in 1..8192 && h in 1..8192 && w.toLong() * h <= 16_777_216
	androidx.compose.ui.window.Popup(alignment = Alignment.Center, onDismissRequest = onDismiss,
		properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
		androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.width(260.dp)
			.clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).background(colors.panelElevated)
			.border(0.5.dp, colors.border, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(12.dp),
			verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
			Text(tr("layout.new.title"), color = colors.textPrimary, fontSize = 12.sp)
			Text(tr("layout.new.hint"), color = colors.textMuted, fontSize = 10.5.sp)
			androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
				io.github.psd2live.ui.components.CompactTextField(width, { width = it }, isMono = true, selectAllOnFocus = true,
					modifier = androidx.compose.ui.Modifier.width(80.dp))
				Text("×", color = colors.textMuted, fontSize = 11.sp)
				io.github.psd2live.ui.components.CompactTextField(height, { height = it }, isMono = true, selectAllOnFocus = true,
					onCommit = { if (valid) onConfirm(w!!, h!!) }, modifier = androidx.compose.ui.Modifier.width(80.dp))
			}
			androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
				Text(tr("action.cancel"), color = colors.textMuted, fontSize = 11.sp, modifier = androidx.compose.ui.Modifier
					.clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).clickable(onClick = onDismiss).padding(horizontal = 8.dp, vertical = 3.dp))
				Text(tr("layout.new.create"), color = if (valid) colors.accent else colors.textDisabled, fontSize = 11.sp, modifier = androidx.compose.ui.Modifier
					.clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).clickable(enabled = valid) { onConfirm(w!!, h!!) }
					.padding(horizontal = 8.dp, vertical = 3.dp))
			}
		}
	}
}

/**
 * What waits for generation, at the top right of the edit canvas: "not generated" before the first generation, else
 * how many layers wait (placed since, reclassified, or with a replaced image that may refresh its geometry), and the
 * accent button that generates the rig - or generates it again, keeping the user's edits. Its menu chooses whether
 * replaced images mesh again from their new pixels.
 */
@Composable
internal fun BoxScope.LayoutGenerationBar(viewModel: PSD2LiveViewModel, focus: () -> Unit) {
	val generation by remember(viewModel) { viewModel.state.map { it.layout.generation }.distinctUntilChanged() }
		.collectAsState(viewModel.state.value.layout.generation)
	val busy by remember(viewModel) { viewModel.state.map { it.canvasEditBusy || it.workspaceEditBusy }.distinctUntilChanged() }
		.collectAsState(false)
	val layers by remember(viewModel) { viewModel.state.map { it.analysis?.source?.layers?.size ?: 0 }.distinctUntilChanged() }.collectAsState(0)
	val waiting = generation.pendingLayerIds.size + generation.pendingClassifications.keys.count { it !in generation.pendingLayerIds }
	val visible = !generation.generated || waiting > 0 || generation.refreshLayerIds.isNotEmpty()
	var menu by remember { mutableStateOf(false) }
	AnimatedVisibility(
		visible = visible,
		modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
		enter = slideInVertically(tween(200, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(160)),
		exit = slideOutVertically(tween(160, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(120)),
	) {
		val colors = LocalToolColors.current
		FloatingBar {
			val status = when {
				!generation.generated -> tr("layout.status.notGenerated")
				waiting > 0 -> tr("layout.status.pending", waiting)
				else -> tr("layout.status.refreshable", generation.refreshLayerIds.size)
			}
			Text(status, color = colors.textMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp))
			BarDivider()
			Box(Modifier.onGloballyPositioned { MaterialsPanelProbe.generateButton = it.boundsInRoot() }) {
				AccentSplitButton(
					label = if (generation.generated) tr("layout.generate.again") else tr("layout.generate"),
					icon = { tint -> io.github.psd2live.ui.components.IconPlay(androidx.compose.ui.Modifier.size(11.dp), tint) },
					onClick = { viewModel.generateLayout(refresh = true); focus() },
					open = menu,
					onMenu = { menu = !menu },
					enabled = !busy && layers > 0,
					tooltip = if (generation.generated) tr("layout.generate.again.tip") else tr("layout.generate.tip"),
					menuTooltip = tr("layout.generate.options"),
				)
				FloatingMenu(expanded = menu, onDismiss = { menu = false }, width = 250.dp, alignment = Alignment.TopEnd) {
					FloatingMenuSection(tr("layout.generate.options"))
					FloatingMenuRow(tr("layout.generate.refresh"), { menu = false; viewModel.generateLayout(refresh = true); focus() },
						trailing = generation.refreshLayerIds.size.takeIf { it > 0 }?.toString(), enabled = !busy && layers > 0)
					FloatingMenuRow(tr("layout.generate.keep"), { menu = false; viewModel.generateLayout(refresh = false); focus() },
						enabled = !busy && layers > 0 && generation != WorkspaceGeneration.Generated)
				}
			}
		}
	}
}
