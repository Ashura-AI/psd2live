package io.github.psd2live.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.ui.state.CarriedFiles
import io.github.psd2live.ui.state.FileDropIntent
import io.github.psd2live.ui.state.FileDropRouter
import io.github.psd2live.ui.state.ReferenceMode
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.frostedGlass
import io.github.psd2live.i18n.tr

/**
 * Registers this element with [router] as file-drop zone [id] while it shows: [resolve] says what files dropped at a
 * point inside it (in its own coordinates, and in the window's) do, or null to leave them to the zones and fallback below.
 */
@Composable
internal fun Modifier.fileDropZone(router: FileDropRouter, id: String, priority: Int = 0,
                                   resolve: (CarriedFiles, Offset, Offset) -> FileDropIntent?): Modifier {
	val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
	val current by rememberUpdatedState(resolve)
	DisposableEffect(router, id, priority) {
		router.register(id, priority) zone@{ carried, x, y ->
			val at = coordinates[0]?.takeIf { it.isAttached } ?: return@zone null
			val local = at.windowToLocal(Offset(x, y))
			if (local.x < 0f || local.y < 0f || local.x > at.size.width || local.y > at.size.height) null else current(carried, local, Offset(x, y))
		}
		onDispose { router.unregister(id) }
	}
	return onGloballyPositioned { coordinates[0] = it }
}

/** Outlines this element in the accent while it is the zone files would drop into. */
@Composable
internal fun Modifier.fileDropOutline(router: FileDropRouter, id: String, shape: RoundedCornerShape = RoundedCornerShape(4.dp)): Modifier {
	val drag by router.drag.collectAsState()
	val target = drag?.let { it.zone == id && it.intent != null && it.intent !is FileDropIntent.Refuse } == true
	if (!target) return this
	val colors = LocalToolColors.current
	return background(colors.accent.copy(alpha = 0.08f), shape).border(1.5.dp, colors.accent, shape)
}

/** What a drop where the pointer is would do, beside the pointer while files are carried over the window. */
@Composable
internal fun FileDragLabel(viewModel: PSD2LiveViewModel) {
	val drag by viewModel.fileDrops.drag.collectAsState()
	val current = drag ?: return
	val text = current.intent?.label ?: current.hint ?: return
	val colors = LocalToolColors.current
	val acts = current.intent != null && current.intent !is FileDropIntent.Refuse
	Box(
		Modifier
			.offset { IntOffset(current.x.toInt() + 18, current.y.toInt() + 20) }
			.widthIn(max = 360.dp)
			.frostedGlass(RoundedCornerShape(6.dp), elevation = 6.dp, alpha = 0.92f)
			.border(0.5.dp, if (acts) colors.accent.copy(alpha = 0.6f) else colors.divider, RoundedCornerShape(6.dp))
			.padding(horizontal = 9.dp, vertical = 5.dp),
	) {
		Text(text, color = if (acts) colors.textPrimary else colors.textMuted, fontSize = 11.sp, maxLines = 2)
	}
}

/**
 * Over an edit canvas while images or a material are carried: big targets, each saying what a drop there does and
 * whether the result is in the model - a reference behind the model, in the layer stack (in the model, an ArtMesh)
 * or in front of it, the selected layer's new image ([selectedLayer]: ID and name), or the library only. A drop on the
 * canvas around them places a layer where it lands.
 */
@Composable
internal fun CanvasDropTiles(viewModel: PSD2LiveViewModel, canvasId: String, selectedLayer: Pair<String, String>?, modifier: Modifier) {
	val drag by viewModel.fileDrops.drag.collectAsState()
	val carried = drag?.carried ?: return
	if (!carried.placeable) return
	val router = viewModel.fileDrops
	val colors = LocalToolColors.current
	Column(modifier.widthIn(max = 640.dp).frostedGlass(RoundedCornerShape(10.dp), elevation = 8.dp, alpha = 0.9f).padding(10.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Text(tr("drop.tiles.title"), color = colors.textMuted, fontSize = 11.sp)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			for (mode in ReferenceMode.entries) DropTile(router, "canvas-reference-${mode.key}:$canvasId", tr(mode.labelKey) + " · " + tr("references.badge"),
				tr(mode.descriptionKey), mode == ReferenceMode.LAYER, Modifier.weight(1f)) { label -> FileDropIntent.SetReference(mode, label) }
		}
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			if (selectedLayer != null) DropTile(router, "canvas-replace:$canvasId", tr("drop.intent.replace", selectedLayer.second), tr("drop.tiles.replace"),
				true, Modifier.weight(1f)) { label -> FileDropIntent.ReplaceImage(selectedLayer.first, label) }
			if (carried.material == null) DropTile(router, "canvas-library:$canvasId", tr("drop.intent.libraryOnly"), tr("drop.tiles.library"),
				false, Modifier.weight(1f)) { label -> FileDropIntent.ImportMaterials(null, label) }
		}
	}
}

/** One target of [CanvasDropTiles]: its title, what it means, and whether it is in the model; lit while the pointer is on it. */
@Composable
private fun DropTile(router: FileDropRouter, id: String, title: String, description: String, inModel: Boolean, modifier: Modifier,
                     intent: (String) -> FileDropIntent) {
	val drag by router.drag.collectAsState()
	val colors = LocalToolColors.current
	val lit = drag?.zone == id
	val shape = RoundedCornerShape(8.dp)
	Column(modifier.height(76.dp).fileDropZone(router, id, priority = 10) { _, _, _ -> intent(title) }
		.background(if (lit) colors.accent.copy(alpha = 0.26f) else colors.panelBackground.copy(alpha = 0.6f), shape)
		.border(if (lit) 1.5.dp else 1.dp, if (lit) colors.accent else colors.divider, shape)
		.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
		Text(title, color = if (lit) colors.textPrimary else colors.textPrimary.copy(alpha = 0.9f), fontSize = 13.sp,
			fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1)
		Text(tr(if (inModel) "references.inModel" else "references.notInModel"), color = if (inModel) colors.accent else colors.textMuted, fontSize = 10.5.sp, maxLines = 1)
		Text(description, color = colors.textMuted, fontSize = 10.sp, maxLines = 2)
	}
}
