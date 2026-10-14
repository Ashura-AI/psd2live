package io.github.psd2live.ui.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import io.github.psd2live.application.WorkspaceMaterialPixels
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.MaterialItem
import io.github.psd2live.project.MaterialKind
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.components.PsColorPickerPopupContent
import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.frostedGlass
import io.github.psd2live.ui.utils.rgbaImageBitmap
import io.github.psd2live.ui.views.texture.AccentButton
import io.github.psd2live.ui.views.texture.BarChip
import io.github.psd2live.ui.views.texture.BarDivider
import io.github.psd2live.ui.views.texture.BarValueChip
import io.github.psd2live.ui.views.texture.FloatingBar
import io.github.psd2live.ui.views.texture.FloatingMenu
import io.github.psd2live.ui.views.texture.FloatingMenuDivider
import io.github.psd2live.ui.views.texture.FloatingMenuRow
import io.github.psd2live.ui.views.texture.FloatingMenuSection
import io.github.psd2live.ui.views.texture.FloatingMenuSlider
import io.github.psd2live.ui.views.texture.canvasChrome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.hypot
import kotlin.math.roundToInt

/** The tools of the material window: painting on the material's own pixels, or picking a colour from them. */
private enum class MaterialTool(val canvasTool: CanvasTool, val labelKey: String, val key: Key, val keyLabel: String) {
	BRUSH(CanvasTool.PAINT_BRUSH, "materials.editor.brush", Key.B, "B"),
	PENCIL(CanvasTool.PAINT_PENCIL, "materials.editor.pencil", Key.P, "P"),
	ERASER(CanvasTool.PAINT_ERASER, "materials.editor.eraser", Key.E, "E"),
	BUCKET(CanvasTool.PAINT_BUCKET, "materials.editor.bucket", Key.G, "G"),
	EYEDROPPER(CanvasTool.PAINT_EYEDROPPER, "materials.editor.eyedropper", Key.I, "I"),
}

private enum class MaterialBackdrop(val labelKey: String) { CHECKER("materials.backdrop.checker"), WHITE("materials.backdrop.white"), BLACK("materials.backdrop.black") }

/**
 * The material window: the material [PSD2LiveViewModel.materialEditor] names, in a window of its own that sizes and
 * moves freely. The view zooms and pans over the material at any size; the brush, pencil, eraser and bucket paint on
 * its pixels and the adjustments flip and recolour them. Each stroke or adjustment is one document edit
 * (`material_paint`, `material_adjust`), and every layer showing the material takes the new pixels in place.
 */
@Composable
internal fun MaterialEditorWindow(state: PSD2LiveState, vm: PSD2LiveViewModel) {
	val id by vm.materialEditor.collectAsState()
	val current = id ?: return
	val item = state.layout.materials.item(current)
	if (item == null) { LaunchedEffect(current) { vm.closeMaterialEditor() }; return }
	val windowState = rememberWindowState(size = DpSize(960.dp, 720.dp), position = WindowPosition(Alignment.Center))
	Window(onCloseRequest = vm::closeMaterialEditor, state = windowState, title = "${tr("materials.editor.title")} - ${item.name}") {
		androidx.compose.runtime.CompositionLocalProvider(LocalAwtWindow provides window) {
			CompactToolTheme(colors = state.toolColors, uiScale = AppSettings.uiScale, fontScale = AppSettings.fontScale) {
				MaterialEditor(item, state, vm)
			}
		}
	}
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun MaterialEditor(item: MaterialItem, state: PSD2LiveState, vm: PSD2LiveViewModel) {
	val colors = LocalToolColors.current
	val raster = item.raster
	val image = remember(raster) { rgbaImageBitmap(raster.width, raster.height, raster.rgba) }
	val users = remember(state.layout.layerMaterials, item.id) { state.layout.layerMaterials.count { it.value == item.id } }

	var tool by remember { mutableStateOf(MaterialTool.BRUSH) }
	var radius by remember { mutableStateOf(6f) }
	var hardness by remember { mutableStateOf(0.8f) }
	var opacity by remember { mutableStateOf(1f) }
	var tolerance by remember { mutableStateOf(32f) }
	var color by remember { mutableStateOf(Color.Black) }
	var picking by remember { mutableStateOf(false) }
	var backdrop by remember { mutableStateOf(MaterialBackdrop.CHECKER) }
	var adjusting by remember { mutableStateOf(false) }
	var adjustment by remember(item.id) { mutableStateOf(WorkspaceMaterialPixels.Adjustment()) }

	// The view: image pixels to screen pixels, fitted on open and on demand.
	var viewport by remember { mutableStateOf(IntSize.Zero) }
	var scale by remember(item.id) { mutableStateOf(0f) }
	var origin by remember(item.id) { mutableStateOf(Offset.Zero) }
	fun fit() {
		if (viewport.width == 0 || viewport.height == 0) return
		scale = minOf(viewport.width * 0.9f / raster.width, viewport.height * 0.9f / raster.height).coerceIn(0.02f, 64f)
		origin = Offset((viewport.width - raster.width * scale) / 2f, (viewport.height - raster.height * scale) / 2f)
	}
	fun actual() {
		scale = 1f
		origin = Offset((viewport.width - raster.width) / 2f, (viewport.height - raster.height) / 2f)
	}
	LaunchedEffect(viewport, item.id) { if (scale == 0f) fit() }
	// A material changing size (a new resolution) fits again.
	LaunchedEffect(raster.width, raster.height) { if (viewport != IntSize.Zero) fit() }
	fun zoomAt(at: Offset, factor: Float) {
		val next = (scale * factor).coerceIn(0.02f, 64f)
		origin = at - (at - origin) * (next / scale)
		scale = next
	}
	fun toImage(at: Offset) = (at - origin) / scale

	// Strokes drawn but not yet in the material: they show over it until its pixels change.
	val pending = remember { mutableStateListOf<Pair<List<Offset>, MaterialTool>>() }
	LaunchedEffect(raster) { pending.clear() }
	val busy = state.workspaceEditBusy
	LaunchedEffect(busy) { if (!busy) { delay(400); pending.clear() } }
	var stroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
	var hover by remember { mutableStateOf<Offset?>(null) }

	fun rgba(): JsonArray {
		val c = color
		return JsonArray(listOf(c.red, c.green, c.blue, c.alpha).map { JsonPrimitive((it * 255f).roundToInt()) })
	}
	fun commitStroke(points: List<Offset>, used: MaterialTool) {
		if (points.isEmpty()) return
		// The paint request takes at most 512 points: a long stroke keeps evenly spaced ones.
		val kept = if (points.size <= 512) points else List(512) { points[it * (points.size - 1) / 511] }
		pending += kept to used
		vm.paintMaterial(item.id, buildJsonObject {
			put("mode", when (used) { MaterialTool.PENCIL -> "pencil"; MaterialTool.ERASER -> "eraser"; else -> "brush" })
			put("points", JsonArray(kept.map { JsonArray(listOf(JsonPrimitive(it.x), JsonPrimitive(it.y))) }))
			put("radius", radius.coerceIn(0.5f, 512f))
			if (used != MaterialTool.PENCIL) put("hardness", hardness)
			put("color", rgba()); put("opacity", opacity)
		})
	}
	fun sample(at: Offset) {
		val x = at.x.toInt(); val y = at.y.toInt()
		if (x !in 0 until raster.width || y !in 0 until raster.height) return
		val o = (y * raster.width + x) * 4
		val a = raster.rgba[o + 3].toInt() and 255
		if (a == 0) return
		color = Color(raster.rgba[o].toInt() and 255, raster.rgba[o + 1].toInt() and 255, raster.rgba[o + 2].toInt() and 255)
	}

	// The adjusted look, made off the UI thread while the sliders move.
	var adjusted by remember(raster) { mutableStateOf<ImageBitmap?>(null) }
	LaunchedEffect(raster, adjustment, adjusting) {
		if (!adjustment.colour) { adjusted = null; return@LaunchedEffect }
		delay(60)
		adjusted = withContext(Dispatchers.Default) {
			WorkspaceMaterialPixels.adjust(raster, adjustment.copy(flip = null)).let { rgbaImageBitmap(it.width, it.height, it.rgba) }
		}
	}

	val focus = remember { FocusRequester() }
	LaunchedEffect(Unit) { focus.requestFocus() }
	var spaceDown by remember { mutableStateOf(false) }
	Box(Modifier.fillMaxSize().background(colors.windowBackground).focusRequester(focus).focusable()
		.onPreviewKeyEvent { event ->
			if (event.key == Key.Spacebar) { spaceDown = event.type == KeyEventType.KeyDown; return@onPreviewKeyEvent true }
			if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
			when {
				event.isCtrlPressed && event.key == Key.Z && !event.isShiftPressed -> { vm.undoHistory(); true }
				event.isCtrlPressed && (event.key == Key.Y || event.key == Key.Z && event.isShiftPressed) -> { vm.redoHistory(); true }
				event.isCtrlPressed && event.key == Key.Zero -> { fit(); true }
				event.isCtrlPressed && event.key == Key.One -> { actual(); true }
				event.key == Key.LeftBracket -> { radius = (radius / 1.2f).coerceAtLeast(0.5f); true }
				event.key == Key.RightBracket -> { radius = (radius * 1.2f).coerceAtMost(512f); true }
				else -> MaterialTool.entries.firstOrNull { it.key == event.key && !event.isCtrlPressed }?.let { tool = it; true } ?: false
			}
		}) {
		// The material over its backdrop; the view clips to the window.
		Canvas(Modifier.fillMaxSize().clipToBounds().onSizeChanged { viewport = it }
			.onPointerEvent(PointerEventType.Scroll) { event ->
				val change = event.changes.first()
				zoomAt(change.position, if (change.scrollDelta.y < 0) 1.2f else 1f / 1.2f)
				change.consume()
			}
			.onPointerEvent(PointerEventType.Move) { event -> hover = event.changes.first().position }
			.onPointerEvent(PointerEventType.Exit) { hover = null }
			.pointerInput(item.id, tool, spaceDown) {
				awaitEachGesture {
					val down = awaitFirstDown(requireUnconsumed = false)
					val buttons = currentEvent.buttons
					val panning = buttons.isTertiaryPressed || buttons.isSecondaryPressed || spaceDown
					if (panning) {
						var last = down.position
						while (true) {
							val event = awaitPointerEvent()
							val change = event.changes.firstOrNull { it.id == down.id } ?: break
							if (!change.pressed) break
							origin += change.position - last; last = change.position; change.consume()
						}
						return@awaitEachGesture
					}
					when (tool) {
						MaterialTool.EYEDROPPER -> { sample(toImage(down.position)); down.consume() }
						MaterialTool.BUCKET -> {
							val at = toImage(down.position)
							if (at.x in 0f..raster.width.toFloat() && at.y in 0f..raster.height.toFloat())
								vm.paintMaterial(item.id, buildJsonObject {
									put("mode", "bucket"); put("point", JsonArray(listOf(JsonPrimitive(at.x), JsonPrimitive(at.y))))
									put("tolerance", tolerance.roundToInt()); put("color", rgba()); put("opacity", opacity)
								})
							down.consume()
						}
						else -> {
							val used = tool
							val points = mutableListOf(toImage(down.position))
							stroke = points.toList()
							down.consume()
							while (true) {
								val event = awaitPointerEvent()
								val change = event.changes.firstOrNull { it.id == down.id } ?: break
								hover = change.position
								if (!change.pressed) break
								val next = toImage(change.position)
								// Keep points a fraction of the tip apart: a slow stroke stays short.
								if (hypot(next.x - points.last().x, next.y - points.last().y) >= maxOf(0.5f, radius * 0.2f)) {
									points += next; stroke = points.toList()
								}
								change.consume()
							}
							stroke = emptyList()
							commitStroke(points, used)
						}
					}
				}
			}) {
			val w = raster.width * scale; val h = raster.height * scale
			clipRect(origin.x, origin.y, origin.x + w, origin.y + h) {
				when (backdrop) {
					MaterialBackdrop.WHITE -> drawRect(Color.White, origin, Size(w, h))
					MaterialBackdrop.BLACK -> drawRect(Color.Black, origin, Size(w, h))
					MaterialBackdrop.CHECKER -> {
						drawRect(Color(0xFFF2F2F2), origin, Size(w, h))
						// Only the cells in sight: a deep zoom does not walk the whole material.
						val cell = 8.dp.toPx()
						val firstColumn = ((-origin.x) / cell).toInt().coerceAtLeast(0); val lastColumn = ((size.width - origin.x) / cell).toInt().coerceAtMost((w / cell).toInt())
						val firstRow = ((-origin.y) / cell).toInt().coerceAtLeast(0); val lastRow = ((size.height - origin.y) / cell).toInt().coerceAtMost((h / cell).toInt())
						for (row in firstRow..lastRow) for (column in firstColumn..lastColumn) if ((row + column) % 2 == 1)
							drawRect(Color(0xFFD9D9D9), Offset(origin.x + column * cell, origin.y + row * cell), Size(cell, cell))
					}
				}
				drawImage(adjusted ?: image, srcOffset = IntOffset.Zero, srcSize = IntSize(raster.width, raster.height),
					dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()), dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
					filterQuality = if (scale >= 2f) FilterQuality.None else FilterQuality.Medium)
				// Strokes waiting for their commit, and the one being drawn.
				for ((points, used) in pending + listOfNotNull(stroke.takeIf { it.isNotEmpty() }?.let { it to tool })) {
					val path = Path()
					points.forEachIndexed { index, p ->
						val at = origin + p * scale
						if (index == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y)
					}
					if (points.size == 1) { val at = origin + points[0] * scale; path.lineTo(at.x + 0.01f, at.y) }
					val tint = if (used == MaterialTool.ERASER) Color.White.copy(alpha = 0.6f) else color.copy(alpha = color.alpha * opacity)
					drawPath(path, tint, style = Stroke(width = (radius * 2f * scale).coerceAtLeast(1f), cap = StrokeCap.Round, join = StrokeJoin.Round))
				}
			}
			drawRect(colors.border, origin, Size(w, h), style = Stroke(1f))
			// The tip under the pointer.
			hover?.takeIf { tool != MaterialTool.EYEDROPPER && tool != MaterialTool.BUCKET }?.let { at ->
				drawCircle(Color.Black.copy(alpha = 0.5f), (radius * scale).coerceAtLeast(2f), at, style = Stroke(1.5f))
				drawCircle(Color.White.copy(alpha = 0.8f), (radius * scale).coerceAtLeast(2f), at, style = Stroke(0.75f))
			}
		}

		// The tools, over the top of the view.
		FloatingBar(Modifier.align(Alignment.TopCenter).padding(top = 10.dp)) {
			for (entry in MaterialTool.entries) BarChip(null, tool == entry, { tool = entry }, tooltip = "${tr(entry.labelKey)} (${entry.keyLabel})") {
				ToolIcon(entry.canvasTool, it)
			}
			BarDivider()
			Box {
				Box(Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).background(color).border(1.dp, colors.border, RoundedCornerShape(4.dp))
					.clickable { picking = !picking })
				if (picking) Popup(offset = IntOffset(0, 28), onDismissRequest = { picking = false }, properties = PopupProperties(focusable = true)) {
					PsColorPickerPopupContent(color, { color = it }, { picking = false }, title = tr("materials.editor.color"))
				}
			}
			if (tool != MaterialTool.EYEDROPPER && tool != MaterialTool.BUCKET) {
				BarValueChip(tr("materials.editor.size"), "${(radius * 2).let { if (it < 10) "%.1f".format(it) else it.roundToInt().toString() }}px", radius, { radius = it },
					0.5f..512f, scrub = { start, dx -> (start * kotlin.math.exp(dx / 120f)).coerceIn(0.5f, 512f) }, logarithmic = true)
				if (tool != MaterialTool.PENCIL) BarValueChip(tr("materials.editor.hardness"), "${(hardness * 100).roundToInt()}%", hardness, { hardness = it },
					0f..1f, scrub = { start, dx -> (start + dx / 200f).coerceIn(0f, 1f) })
			}
			if (tool == MaterialTool.BUCKET) BarValueChip(tr("materials.editor.tolerance"), "${tolerance.roundToInt()}", tolerance, { tolerance = it },
				0f..255f, scrub = { start, dx -> (start + dx / 2f).coerceIn(0f, 255f) })
			if (tool != MaterialTool.EYEDROPPER) BarValueChip(tr("materials.editor.opacity"), "${(opacity * 100).roundToInt()}%", opacity, { opacity = it },
				0f..1f, scrub = { start, dx -> (start + dx / 200f).coerceIn(0f, 1f) })
			BarDivider()
			Box {
				BarChip(tr("materials.editor.adjust"), adjusting || adjustment.colour, { adjusting = !adjusting }, chevron = true, open = adjusting)
				FloatingMenu(expanded = adjusting, onDismiss = { adjusting = false }, width = 260.dp, alignment = Alignment.TopEnd) {
					FloatingMenuSection(tr("materials.editor.transform"))
					FloatingMenuRow(tr("materials.editor.flipH"), { vm.adjustMaterial(item.id, WorkspaceMaterialPixels.Adjustment(flip = "horizontal")) })
					FloatingMenuRow(tr("materials.editor.flipV"), { vm.adjustMaterial(item.id, WorkspaceMaterialPixels.Adjustment(flip = "vertical")) })
					FloatingMenuDivider()
					FloatingMenuSection(tr("materials.editor.colour"))
					FloatingMenuSlider(tr("materials.editor.hue"), adjustment.hue, { adjustment = adjustment.copy(hue = it.roundToInt().toFloat()) }, -180f..180f, "${adjustment.hue.roundToInt()}°")
					FloatingMenuSlider(tr("materials.editor.saturation"), adjustment.saturation, { adjustment = adjustment.copy(saturation = it.roundToInt().toFloat()) }, -100f..100f, "${adjustment.saturation.roundToInt()}")
					FloatingMenuSlider(tr("materials.editor.lightness"), adjustment.lightness, { adjustment = adjustment.copy(lightness = it.roundToInt().toFloat()) }, -100f..100f, "${adjustment.lightness.roundToInt()}")
					FloatingMenuSlider(tr("materials.editor.contrast"), adjustment.contrast, { adjustment = adjustment.copy(contrast = it.roundToInt().toFloat()) }, -100f..100f, "${adjustment.contrast.roundToInt()}")
					Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
						BarChip(tr("materials.editor.reset"), false, { adjustment = WorkspaceMaterialPixels.Adjustment() }, enabled = adjustment.colour)
						AccentButton(tr("materials.editor.apply"), {
							vm.adjustMaterial(item.id, adjustment); adjustment = WorkspaceMaterialPixels.Adjustment(); adjusting = false
						}, enabled = adjustment.colour && !busy)
					}
				}
			}
		}

		// The view: backdrop, fit and one to one.
		FloatingBar(Modifier.align(Alignment.BottomEnd).padding(10.dp)) {
			for (entry in MaterialBackdrop.entries) BarChip(null, backdrop == entry, { backdrop = entry }, tooltip = tr(entry.labelKey)) {
				Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).border(1.dp, it, RoundedCornerShape(2.dp)).background(when (entry) {
					MaterialBackdrop.WHITE -> Color.White; MaterialBackdrop.BLACK -> Color.Black; MaterialBackdrop.CHECKER -> Color(0xFFBFBFBF)
				}))
			}
			BarDivider()
			BarChip(tr("materials.editor.fit"), false, ::fit, tooltip = "Ctrl+0")
			BarChip("1:1", false, ::actual, tooltip = "Ctrl+1")
		}

		// What the material is, bottom left.
		Row(Modifier.align(Alignment.BottomStart).padding(10.dp).canvasChrome().frostedGlass(RoundedCornerShape(12.dp))
			.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(item.name, color = colors.textPrimary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
			val details = listOfNotNull("${raster.width}×${raster.height}", if (item.kind == MaterialKind.VECTOR) "SVG" else null,
				"${(scale * 100).roundToInt()}%", if (users > 0) tr("materials.references", users) else tr("materials.unused"),
				if (busy) tr("materials.editor.saving") else null)
			Text(details.joinToString(" · "), color = colors.textMuted, fontSize = 10.5.sp, maxLines = 1)
		}
		if (item.kind == MaterialKind.VECTOR) Text(tr("materials.editor.vectorNote"), color = colors.textMuted, fontSize = 10.5.sp,
			modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp).canvasChrome().frostedGlass(RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
	}
}
