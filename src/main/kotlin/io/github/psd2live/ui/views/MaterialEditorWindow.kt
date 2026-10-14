package io.github.psd2live.ui.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import io.github.psd2live.application.WorkspaceMaterialPixels
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.MaterialItem
import io.github.psd2live.project.MaterialKind
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.PaintShape
import io.github.psd2live.ui.components.PaintFgBgSwatch
import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.ShortcutAction
import io.github.psd2live.ui.state.ShortcutScope
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umamo.format.art.LayerRaster
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt

/** The paint mode's tools as the material window carries them: the same tools, icons, names and keys. */
private enum class MaterialTool(val canvasTool: CanvasTool, val action: ShortcutAction) {
	BRUSH(CanvasTool.PAINT_BRUSH, ShortcutAction.TOOL_PAINT_BRUSH),
	PENCIL(CanvasTool.PAINT_PENCIL, ShortcutAction.TOOL_PAINT_PENCIL),
	ERASER(CanvasTool.PAINT_ERASER, ShortcutAction.TOOL_PAINT_ERASER),
	BUCKET(CanvasTool.PAINT_BUCKET, ShortcutAction.TOOL_PAINT_BUCKET),
	SHAPE(CanvasTool.PAINT_SHAPE, ShortcutAction.TOOL_PAINT_LINE),
	EYEDROPPER(CanvasTool.PAINT_EYEDROPPER, ShortcutAction.TOOL_PAINT_EYEDROPPER);

	val labelKey: String get() = "editor.tool.${canvasTool.name.lowercase()}"
	/** The tools that stamp a tip along the pointer. */
	val stamps: Boolean get() = this == BRUSH || this == PENCIL || this == ERASER

	companion object {
		fun of(tool: CanvasTool): MaterialTool? = entries.firstOrNull { it.canvasTool == tool }
	}
}

private enum class MaterialBackdrop(val labelKey: String) { CHECKER("materials.backdrop.checker"), WHITE("materials.backdrop.white"), BLACK("materials.backdrop.black") }

/**
 * The material window: the material [PSD2LiveViewModel.materialEditor] names, in a window of its own that sizes and
 * moves freely. It paints the way the canvas's Paint mode does - the same tools, keys and settings - on the material's
 * own pixels, and adjusts them (flip, hue, saturation, lightness, contrast). See [MaterialEditor].
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
				key(item.id) { MaterialEditor(item, state, vm) }
			}
		}
	}
}

/** The size the material window's [tool] draws at: the Paint mode's own per-tool sizes, in material pixels. */
private fun CanvasEditor.sizeOf(tool: MaterialTool): Float = when (tool) {
	MaterialTool.PENCIL -> paintPencilSize
	MaterialTool.ERASER -> paintEraserSize
	MaterialTool.SHAPE -> paintShapeSize
	else -> paintBrushSize
}

private fun CanvasEditor.setSize(tool: MaterialTool, value: Float) {
	val size = value.coerceIn(1f, 1024f)
	when (tool) {
		MaterialTool.PENCIL -> paintPencilSize = size
		MaterialTool.ERASER -> paintEraserSize = size
		MaterialTool.SHAPE -> paintShapeSize = size
		else -> paintBrushSize = size
	}
}

/**
 * A painting session on [item], as the canvas's Paint mode runs one on a layer: strokes gather on a working copy
 * (undone and redone stroke by stroke) and Apply writes them as one edit - `material_paint` with the strokes as its
 * gestures - so every layer showing the material takes the result. Leaving the material or closing the window applies
 * what waits. The tools, keys (from the keymap), colours and sizes are the Paint mode's own: the settings are the
 * active canvas's, so they carry between the two. Alt picks a colour with any tool, Alt + right drag sizes the tip
 * (Shift: its opacity), the middle button or Space + drag pans and the wheel zooms.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun MaterialEditor(item: MaterialItem, state: PSD2LiveState, vm: PSD2LiveViewModel) {
	val colors = LocalToolColors.current
	val keymap = state.keymap
	val paint = remember { vm.canvasEditor }
	val base = item.raster
	val users = remember(state.layout.layerMaterials, item.id) { state.layout.layerMaterials.count { it.value == item.id } }
	val busy = state.workspaceEditBusy

	var tool by remember { mutableStateOf(MaterialTool.of(paint.tool) ?: MaterialTool.BRUSH) }
	var shape by remember { mutableStateOf(paint.paintShape) }
	var backdrop by remember { mutableStateOf(MaterialBackdrop.CHECKER) }
	var adjusting by remember { mutableStateOf(false) }
	var adjustment by remember { mutableStateOf(WorkspaceMaterialPixels.Adjustment()) }

	// The session: the strokes not yet applied, those undone, and the pixels they make.
	val strokes = remember { mutableStateListOf<JsonObject>() }
	val undone = remember { mutableStateListOf<JsonObject>() }
	var working by remember { mutableStateOf(base) }
	// Applied strokes show until the material's pixels change to them.
	var applying by remember { mutableStateOf(false) }
	LaunchedEffect(base) { if (strokes.isEmpty()) { working = base; applying = false } }
	LaunchedEffect(busy) { if (!busy && applying) { delay(300); if (applying && strokes.isEmpty()) { working = base; applying = false } } }
	suspend fun replay() {
		val list = strokes.toList()
		working = if (list.isEmpty()) base else withContext(Dispatchers.Default) {
			WorkspaceMaterialPixels.paint(base, buildJsonObject { put("gestures", JsonArray(list)) })
		}
	}
	val scope = rememberCoroutineScope()
	fun add(gesture: JsonObject, result: LayerRaster?) {
		strokes += gesture; undone.clear()
		if (result != null) working = result
		else { val from = working; scope.launch { working = withContext(Dispatchers.Default) { WorkspaceMaterialPixels.paint(from, gesture) } } }
	}
	fun apply() {
		if (strokes.isEmpty()) return
		// A session longer than one edit takes lands in turns.
		strokes.chunked(WorkspaceMaterialPixels.MAX_GESTURES).forEach { vm.paintMaterial(item.id, buildJsonObject { put("gestures", JsonArray(it)) }) }
		strokes.clear(); undone.clear(); applying = true
	}
	fun discard() { strokes.clear(); undone.clear(); working = base }
	fun undo() {
		if (strokes.isEmpty()) { vm.undoHistory(); return }
		undone += strokes.removeAt(strokes.lastIndex)
		scope.launch { replay() }
	}
	fun redo() {
		if (undone.isEmpty()) { vm.redoHistory(); return }
		strokes += undone.removeAt(undone.lastIndex)
		scope.launch { replay() }
	}
	// Leaving the material or closing the window keeps what was painted.
	val latestApply by rememberUpdatedState(::apply)
	DisposableEffect(Unit) { onDispose { latestApply() } }

	val image = remember(working) { rgbaImageBitmap(working.width, working.height, working.rgba) }

	// The view: material pixels to screen pixels, fitted on open and on demand.
	var viewport by remember { mutableStateOf(IntSize.Zero) }
	var scale by remember { mutableStateOf(0f) }
	var origin by remember { mutableStateOf(Offset.Zero) }
	fun fit() {
		if (viewport.width == 0 || viewport.height == 0) return
		scale = minOf(viewport.width * 0.86f / base.width, viewport.height * 0.86f / base.height).coerceIn(0.02f, 64f)
		origin = Offset((viewport.width - base.width * scale) / 2f, (viewport.height - base.height * scale) / 2f)
	}
	fun actual() {
		scale = 1f
		origin = Offset((viewport.width - base.width) / 2f, (viewport.height - base.height) / 2f)
	}
	LaunchedEffect(viewport) { if (scale == 0f) fit() }
	LaunchedEffect(base.width, base.height) { if (viewport != IntSize.Zero) fit() }
	fun zoomAt(at: Offset, factor: Float) {
		val next = (scale * factor).coerceIn(0.02f, 64f)
		origin = at - (at - origin) * (next / scale)
		scale = next
	}
	fun toImage(at: Offset) = (at - origin) / scale

	fun rgba(color: Color) = JsonArray(listOf(color.red, color.green, color.blue, color.alpha).map { JsonPrimitive((it * 255f).roundToInt()) })
	fun point(p: Offset) = JsonArray(listOf(JsonPrimitive(p.x), JsonPrimitive(p.y)))
	fun gesture(used: MaterialTool, points: List<Offset>, to: Offset? = null): JsonObject = buildJsonObject {
		when (used) {
			MaterialTool.SHAPE -> {
				put("mode", "shape"); put("from", point(points.first())); put("to", point(to ?: points.last()))
				put("shape", shape.name.lowercase()); put("stroke_width", paint.paintShapeSize.coerceIn(1f, 512f))
				put("filled", shape.canFill && paint.paintShapeFilled)
			}
			MaterialTool.BUCKET -> { put("mode", "bucket"); put("point", point(points.first())); put("tolerance", paint.paintTolerance) }
			else -> {
				put("mode", when (used) { MaterialTool.PENCIL -> "pencil"; MaterialTool.ERASER -> "eraser"; else -> "brush" })
				// The paint request takes at most 512 points: a long stroke keeps evenly spaced ones.
				val kept = if (points.size <= 512) points else List(512) { points[it * (points.size - 1) / 511] }
				put("points", JsonArray(kept.map(::point)))
				put("radius", (paint.sizeOf(used) / 2f).coerceIn(0.5f, 512f))
				if (used != MaterialTool.PENCIL) put("hardness", paint.paintHardness)
			}
		}
		put("color", rgba(paint.paintColor)); put("opacity", paint.paintOpacity.coerceIn(0f, 1f))
	}
	fun sample(at: Offset) {
		val x = at.x.toInt(); val y = at.y.toInt()
		if (x !in 0 until working.width || y !in 0 until working.height) return
		val o = (y * working.width + x) * 4
		if (working.rgba[o + 3].toInt() == 0) return
		paint.paintColor = Color(working.rgba[o].toInt() and 255, working.rgba[o + 1].toInt() and 255, working.rgba[o + 2].toInt() and 255)
	}

	// The stroke in hand, drawn with the raster engine itself as it grows.
	var live by remember { mutableStateOf<Pair<MaterialTool, List<Offset>>?>(null) }
	var liveTo by remember { mutableStateOf<Offset?>(null) }
	var preview by remember { mutableStateOf<Pair<LayerRaster, ImageBitmap>?>(null) }
	LaunchedEffect(Unit) {
		snapshotFlow { live?.let { (used, points) -> Triple(used, points, liveTo) } }.collectLatest { current ->
			if (current == null) { preview = null; return@collectLatest }
			delay(12)
			val from = working
			val request = gesture(current.first, current.second, current.third)
			val raster = withContext(Dispatchers.Default) { WorkspaceMaterialPixels.paint(from, request) }
			preview = raster to rgbaImageBitmap(raster.width, raster.height, raster.rgba)
		}
	}

	// The adjusted look, made off the UI thread while the sliders move.
	var adjusted by remember(working) { mutableStateOf<ImageBitmap?>(null) }
	LaunchedEffect(working, adjustment) {
		if (!adjustment.colour) { adjusted = null; return@LaunchedEffect }
		delay(60)
		adjusted = withContext(Dispatchers.Default) {
			WorkspaceMaterialPixels.adjust(working, adjustment.copy(flip = null)).let { rgbaImageBitmap(it.width, it.height, it.rgba) }
		}
	}
	fun adjust(next: WorkspaceMaterialPixels.Adjustment) { apply(); vm.adjustMaterial(item.id, next) }

	val focus = remember { FocusRequester() }
	LaunchedEffect(Unit) { focus.requestFocus() }
	var spaceDown by remember { mutableStateOf(false) }
	var altDown by remember { mutableStateOf(false) }
	var hover by remember { mutableStateOf<Offset?>(null) }
	fun step(up: Boolean, action: (Float) -> Unit, value: Float, delta: Float) = action(if (up) value + delta else value - delta)
	fun onKey(event: KeyEvent): Boolean {
		if (event.key == Key.Spacebar) { spaceDown = event.type == KeyEventType.KeyDown; return true }
		if (event.key == Key.AltLeft || event.key == Key.AltRight) { altDown = event.type == KeyEventType.KeyDown; return false }
		if (event.type != KeyEventType.KeyDown) return false
		val action = keymap.match(event, ShortcutScope.CANVAS) ?: keymap.match(event, ShortcutScope.APP) ?: return false
		when (action) {
			ShortcutAction.UNDO -> undo()
			ShortcutAction.REDO -> redo()
			ShortcutAction.FINISH_PATH -> apply()
			ShortcutAction.CANCEL -> { live = null; liveTo = null }
			ShortcutAction.FRAME_VIEW -> fit()
			ShortcutAction.RESET_CAMERA -> actual()
			// In Paint mode X swaps the foreground and background colours, as in every paint program.
			ShortcutAction.AXIS_CONSTRAIN_X -> paint.swapPaintColors()
			ShortcutAction.BRUSH_RADIUS_DOWN -> paint.setSize(tool, paint.sizeOf(tool) / 1.2f)
			ShortcutAction.BRUSH_RADIUS_UP -> paint.setSize(tool, paint.sizeOf(tool) * 1.2f)
			ShortcutAction.BRUSH_HARDNESS_DOWN, ShortcutAction.BRUSH_HARDNESS_UP ->
				step(action == ShortcutAction.BRUSH_HARDNESS_UP, { paint.paintHardness = it.coerceIn(0f, 1f) }, paint.paintHardness, 0.05f)
			ShortcutAction.PAINT_OPACITY_DOWN, ShortcutAction.PAINT_OPACITY_UP ->
				step(action == ShortcutAction.PAINT_OPACITY_UP, { paint.paintOpacity = it.coerceIn(0.01f, 1f) }, paint.paintOpacity, 0.05f)
			ShortcutAction.TOOL_PAINT_LINE -> { shape = PaintShape.LINE; tool = MaterialTool.SHAPE }
			ShortcutAction.TOOL_PAINT_RECT -> { shape = PaintShape.RECTANGLE; tool = MaterialTool.SHAPE }
			ShortcutAction.TOOL_PAINT_ELLIPSE -> { shape = PaintShape.ELLIPSE; tool = MaterialTool.SHAPE }
			ShortcutAction.PAINT_SHAPE_CYCLE -> { shape = PaintShape.entries[(shape.ordinal + 1) % PaintShape.entries.size]; tool = MaterialTool.SHAPE }
			else -> tool = MaterialTool.entries.firstOrNull { it.action == action } ?: return false
		}
		return true
	}

	Box(Modifier.fillMaxSize().background(colors.windowBackground).focusRequester(focus).focusable().onPreviewKeyEvent(::onKey)) {
		Canvas(Modifier.fillMaxSize().clipToBounds().onSizeChanged { viewport = it }
			.onPointerEvent(PointerEventType.Scroll) { event ->
				val change = event.changes.first()
				zoomAt(change.position, if (change.scrollDelta.y < 0) 1.2f else 1f / 1.2f)
				change.consume()
			}
			.onPointerEvent(PointerEventType.Move) { event -> hover = event.changes.first().position; altDown = event.keyboardModifiers.isAltPressed }
			.onPointerEvent(PointerEventType.Exit) { hover = null }
			.pointerInput(item.id) {
				awaitEachGesture {
					val down = awaitFirstDown(requireUnconsumed = false)
					val event = currentEvent
					val buttons = event.buttons
					val alt = event.keyboardModifiers.isAltPressed
					suspend fun AwaitPointerEventScope.track(onMove: (Offset) -> Unit): Boolean {
						while (true) {
							val next = awaitPointerEvent()
							val change = next.changes.firstOrNull { it.id == down.id } ?: return false
							if (!change.pressed) return true
							hover = change.position
							onMove(change.position); change.consume()
						}
					}
					when {
						buttons.isTertiaryPressed || spaceDown && buttons.isPrimaryPressed -> {
							var last = down.position
							track { at -> origin += at - last; last = at }
						}
						// Alt + right drag: sideways sizes the tip, up and down its hardness; with Shift, its opacity.
						buttons.isSecondaryPressed && alt && tool != MaterialTool.EYEDROPPER -> {
							val size0 = paint.sizeOf(tool); val hard0 = paint.paintHardness; val opacity0 = paint.paintOpacity
							val shift = event.keyboardModifiers.isShiftPressed
							track { at ->
								val d = at - down.position
								if (shift) paint.paintOpacity = (opacity0 + d.x / 200f).coerceIn(0.01f, 1f)
								else { paint.setSize(tool, size0 * exp(d.x / 120f)); paint.paintHardness = (hard0 - d.y / 200f).coerceIn(0f, 1f) }
							}
						}
						!buttons.isPrimaryPressed -> Unit
						// The eyedropper, or Alt with any tool, picks the colour under the pointer.
						tool == MaterialTool.EYEDROPPER || alt -> { sample(toImage(down.position)); track { sample(toImage(it)) } }
						tool == MaterialTool.BUCKET -> {
							val at = toImage(down.position)
							if (at.x in 0f..base.width.toFloat() && at.y in 0f..base.height.toFloat()) add(gesture(MaterialTool.BUCKET, listOf(at)), null)
						}
						tool == MaterialTool.SHAPE -> {
							val from = toImage(down.position)
							live = MaterialTool.SHAPE to listOf(from); liveTo = from
							val finished = track { liveTo = toImage(it) }
							val to = liveTo
							if (finished && to != null && live != null && to != from) add(gesture(MaterialTool.SHAPE, listOf(from), to), null)
							live = null; liveTo = null
						}
						else -> {
							val used = tool
							val points = mutableListOf(toImage(down.position))
							live = used to points.toList()
							val finished = track { at ->
								val next = toImage(at)
								// Keep points a fraction of the tip apart: a slow stroke stays short.
								if (hypot(next.x - points.last().x, next.y - points.last().y) >= maxOf(0.5f, paint.sizeOf(used) * 0.1f)) {
									points += next; live = used to points.toList()
								}
							}
							if (finished && live != null) add(gesture(used, points), null)
							live = null
						}
					}
				}
			}) {
			val w = base.width * scale; val h = base.height * scale
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
				val shown = preview?.second?.takeIf { live != null } ?: adjusted ?: image
				drawImage(shown, srcOffset = IntOffset.Zero, srcSize = IntSize(base.width, base.height),
					dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()), dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
					filterQuality = if (scale >= 2f) FilterQuality.None else FilterQuality.Medium)
			}
			drawRect(colors.border, origin, Size(w, h), style = Stroke(1f))
			// The tip under the pointer, as the canvas draws it.
			hover?.takeIf { tool.stamps && !altDown }?.let { at ->
				val r = (paint.sizeOf(tool) / 2f * scale).coerceAtLeast(2f)
				drawCircle(Color.Black.copy(alpha = 0.5f), r, at, style = Stroke(1.5f))
				drawCircle(Color.White.copy(alpha = 0.85f), r, at, style = Stroke(0.75f))
				if (tool != MaterialTool.PENCIL && paint.paintHardness < 1f)
					drawCircle(Color.White.copy(alpha = 0.4f), r * paint.paintHardness, at, style = Stroke(0.75f))
			}
		}

		// The tools, down the left as the canvas's toolbar.
		Column(Modifier.align(Alignment.CenterStart).padding(start = 10.dp).canvasChrome().frostedGlass(RoundedCornerShape(6.dp)).padding(3.dp),
			verticalArrangement = Arrangement.spacedBy(2.dp)) {
			for (entry in MaterialTool.entries) {
				val keyLabel = keymap.labelFor(entry.action)
				BarChip(null, tool == entry, { tool = entry; focus.requestFocus() },
					tooltip = listOfNotNull(tr(entry.labelKey), keyLabel).joinToString("  ")) {
					ToolIcon(entry.canvasTool, it, paintShape = shape)
				}
			}
		}

		// The session, top centre: what waits, undo and redo, discard or apply - and the adjustments.
		FloatingBar(Modifier.align(Alignment.TopCenter).padding(top = 10.dp)) {
			Text(if (strokes.isEmpty()) item.name else tr("materials.editor.pending", strokes.size), color = colors.textMuted, fontSize = 11.sp,
				maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp).padding(horizontal = 6.dp))
			BarChip(null, false, ::undo, enabled = strokes.isNotEmpty() || !busy, tooltip = listOfNotNull(tr("help.shortcuts.undo"), keymap.labelFor(ShortcutAction.UNDO)).joinToString("  ")) {
				Text("↶", color = it, fontSize = 13.sp)
			}
			BarChip(null, false, ::redo, enabled = undone.isNotEmpty() || !busy, tooltip = listOfNotNull(tr("help.shortcuts.redo"), keymap.labelFor(ShortcutAction.REDO)).joinToString("  ")) {
				Text("↷", color = it, fontSize = 13.sp)
			}
			BarDivider()
			BarChip(tr("texture.session.discard"), false, ::discard, enabled = strokes.isNotEmpty())
			AccentButton(if (strokes.isEmpty()) tr("texture.session.apply") else tr("editor.paint.applyCount", strokes.size), ::apply,
				enabled = strokes.isNotEmpty() && !busy, tooltip = tr("materials.editor.applyHint"))
			BarDivider()
			Box {
				BarChip(tr("materials.editor.adjust"), adjusting || adjustment.colour, { adjusting = !adjusting }, chevron = true, open = adjusting)
				FloatingMenu(expanded = adjusting, onDismiss = { adjusting = false }, width = 260.dp, alignment = Alignment.TopEnd) {
					FloatingMenuSection(tr("materials.editor.transform"))
					FloatingMenuRow(tr("materials.editor.flipH"), { adjust(WorkspaceMaterialPixels.Adjustment(flip = "horizontal")) })
					FloatingMenuRow(tr("materials.editor.flipV"), { adjust(WorkspaceMaterialPixels.Adjustment(flip = "vertical")) })
					FloatingMenuDivider()
					FloatingMenuSection(tr("materials.editor.colour"))
					FloatingMenuSlider(tr("materials.editor.hue"), adjustment.hue, { adjustment = adjustment.copy(hue = it.roundToInt().toFloat()) }, -180f..180f, "${adjustment.hue.roundToInt()}°")
					FloatingMenuSlider(tr("materials.editor.saturation"), adjustment.saturation, { adjustment = adjustment.copy(saturation = it.roundToInt().toFloat()) }, -100f..100f, "${adjustment.saturation.roundToInt()}")
					FloatingMenuSlider(tr("materials.editor.lightness"), adjustment.lightness, { adjustment = adjustment.copy(lightness = it.roundToInt().toFloat()) }, -100f..100f, "${adjustment.lightness.roundToInt()}")
					FloatingMenuSlider(tr("materials.editor.contrast"), adjustment.contrast, { adjustment = adjustment.copy(contrast = it.roundToInt().toFloat()) }, -100f..100f, "${adjustment.contrast.roundToInt()}")
					Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
						BarChip(tr("materials.editor.reset"), false, { adjustment = WorkspaceMaterialPixels.Adjustment() }, enabled = adjustment.colour)
						AccentButton(tr("materials.editor.apply"), {
							adjust(adjustment); adjustment = WorkspaceMaterialPixels.Adjustment(); adjusting = false
						}, enabled = adjustment.colour && !busy)
					}
				}
			}
		}

		// The tool's options, bottom left as the canvas's options bar: the colours, then the tool's own values.
		FloatingBar(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 10.dp, end = 260.dp)) {
			PaintFgBgSwatch(foreground = paint.paintColor, background = paint.paintSecondaryColor,
				onForegroundChanged = { paint.paintColor = it }, onBackgroundChanged = { paint.paintSecondaryColor = it },
				onSwap = { paint.swapPaintColors(); focus.requestFocus() }, squareSize = 15.dp, modifier = Modifier.padding(horizontal = 3.dp))
			Text(paint.paintColor.hex(), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = colors.textMuted, modifier = Modifier.padding(end = 2.dp))
			if (tool != MaterialTool.EYEDROPPER) BarDivider()
			if (tool == MaterialTool.SHAPE) {
				for (entry in PaintShape.entries) BarChip(null, shape == entry, { shape = entry }, tooltip = tr(entry.labelKey)) { PaintShapeIcon(entry, it) }
				BarDivider()
			}
			if (tool.stamps || tool == MaterialTool.SHAPE) {
				val size = paint.sizeOf(tool)
				BarValueChip(tr(if (tool == MaterialTool.SHAPE) "editor.width" else "editor.paint.size"), "${size.roundToInt()} px", size, { paint.setSize(tool, it) },
					1f..1024f, scrub = { start, dx -> start * exp(dx / 120f) }, logarithmic = true, rise = true)
			}
			if (tool == MaterialTool.BRUSH || tool == MaterialTool.ERASER) BarValueChip(tr("editor.hardness"), "${(paint.paintHardness * 100).roundToInt()}%",
				paint.paintHardness, { paint.paintHardness = it }, 0f..1f, scrub = { start, dx -> (start + dx / 240f).coerceIn(0f, 1f) }, rise = true)
			if (tool == MaterialTool.BUCKET) BarValueChip(tr("editor.tolerance"), "${paint.paintTolerance}", paint.paintTolerance.toFloat(),
				{ paint.paintTolerance = it.roundToInt().coerceIn(0, 255) }, 0f..255f, scrub = { start, dx -> (start + dx).coerceIn(0f, 255f) }, rise = true)
			if (tool != MaterialTool.EYEDROPPER && tool != MaterialTool.BUCKET) BarValueChip(tr("editor.opacity"), "${(paint.paintOpacity * 100).roundToInt()}%",
				paint.paintOpacity, { paint.paintOpacity = it }, 0.01f..1f, scrub = { start, dx -> (start + dx / 240f).coerceIn(0.01f, 1f) }, rise = true)
			if (tool == MaterialTool.SHAPE && shape.canFill) {
				BarChip(tr("editor.outline"), !paint.paintShapeFilled, { paint.paintShapeFilled = false })
				BarChip(tr("editor.filled"), paint.paintShapeFilled, { paint.paintShapeFilled = true })
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
			BarChip(tr("materials.editor.fit"), false, ::fit, tooltip = keymap.labelFor(ShortcutAction.FRAME_VIEW))
			BarChip("1:1", false, ::actual, tooltip = keymap.labelFor(ShortcutAction.RESET_CAMERA))
		}

		// What the material is, top left.
		Row(Modifier.align(Alignment.TopStart).padding(10.dp).canvasChrome().frostedGlass(RoundedCornerShape(12.dp))
			.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			val details = listOfNotNull("${base.width}×${base.height}", if (item.kind == MaterialKind.VECTOR) "SVG" else null,
				"${(scale * 100).roundToInt()}%", if (users > 0) tr("materials.references", users) else tr("materials.unused"),
				if (busy) tr("materials.editor.saving") else null)
			Text(details.joinToString(" · "), color = colors.textMuted, fontSize = 10.5.sp, maxLines = 1)
		}
		if (item.kind == MaterialKind.VECTOR) Text(tr("materials.editor.vectorNote"), color = colors.textMuted, fontSize = 10.5.sp,
			modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp).canvasChrome().frostedGlass(RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
	}
}

private fun Color.hex(): String = "#%02X%02X%02X".format((red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt())
