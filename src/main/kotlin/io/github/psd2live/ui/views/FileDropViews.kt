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
 * Above an edit canvas while images are carried: drop on one to use them as the reference image, as the selected
 * layer's new image ([selectedLayer]: ID and name), or only to add them to the material library.
 */
@Composable
internal fun CanvasFileDropSlots(viewModel: PSD2LiveViewModel, canvasId: String, selectedLayer: Pair<String, String>?, modifier: Modifier) {
	val drag by viewModel.fileDrops.drag.collectAsState()
	val carried = drag?.carried ?: return
	if (!carried.unknown && carried.rasters.isEmpty()) return
	val router = viewModel.fileDrops
	io.github.psd2live.ui.views.texture.FloatingBar(modifier) {
		DropSlot(router, "canvas-reference:$canvasId", tr("drop.intent.reference")) { FileDropIntent.SetReference(it) }
		if (selectedLayer != null) {
			DropSlot(router, "canvas-replace:$canvasId", tr("drop.intent.replace", selectedLayer.second)) { FileDropIntent.ReplaceImage(selectedLayer.first, it) }
		}
		DropSlot(router, "canvas-library:$canvasId", tr("drop.intent.libraryOnly")) { FileDropIntent.ImportMaterials(null, it) }
	}
}

/** One target of [CanvasFileDropSlots]: lit while the pointer is on it. */
@Composable
private fun DropSlot(router: FileDropRouter, id: String, label: String, intent: (String) -> FileDropIntent) {
	val drag by router.drag.collectAsState()
	Box(Modifier.fileDropZone(router, id, priority = 10) { _, _, _ -> intent(label) }) {
		io.github.psd2live.ui.views.texture.BarChip(label, selected = drag?.zone == id, onClick = {})
	}
}
