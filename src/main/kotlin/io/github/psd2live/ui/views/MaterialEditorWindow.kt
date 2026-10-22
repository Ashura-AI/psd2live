package io.github.psd2live.ui.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.rememberTextMeasurer
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
import io.github.psd2live.application.WorkspacePaintSession
import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.MaterialItem
import io.github.psd2live.project.MaterialKind
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.PAINT_TOOLS
import io.github.psd2live.ui.PaintShape
import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.ShortcutAction
import io.github.psd2live.ui.state.ShortcutScope
import io.github.psd2live.ui.state.buttonBindingOf
import io.github.psd2live.ui.state.isPrimaryPressed
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.frostedGlass
import io.github.psd2live.ui.tooloptions.OptionRole
import io.github.psd2live.ui.tooloptions.stepOption
import io.github.psd2live.ui.tooloptions.pickVariant
import io.github.psd2live.ui.views.texture.AccentButton
import io.github.psd2live.ui.views.texture.BarChip
import io.github.psd2live.ui.views.texture.BarDivider
import io.github.psd2live.ui.views.texture.FloatingBar
import io.github.psd2live.ui.views.texture.FloatingMenu
import io.github.psd2live.ui.views.texture.FloatingMenuDivider
import io.github.psd2live.ui.views.texture.FloatingMenuRow
import io.github.psd2live.ui.views.texture.FloatingMenuSection
import io.github.psd2live.ui.views.texture.FloatingMenuSlider
import io.github.psd2live.ui.views.texture.canvasChrome
import io.github.psd2live.ui.views.tooloptions.ToolOptionsBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class MaterialBackdrop(val labelKey: String) { CHECKER("materials.backdrop.checker"), WHITE("materials.backdrop.white"), BLACK("materials.backdrop.black") }

/**
 * The material window: the material [PSD2LiveViewModel.materialEditor] names, in a window of its own that sizes and
 * moves freely, painted the way the canvas's Paint mode paints a layer (see [MaterialEditor]) and adjusted (flip, hue,
 * saturation, lightness, contrast).
 */
@Composable
internal fun MaterialEditorWindow(state: PSD2LiveState, vm: PSD2LiveViewModel) {
	val id by vm.materialEditor.collectAsState()
	val current = id ?: return
	val item = state.layout.materials.item(current)
	if (item == null) { LaunchedEffect(current) { vm.closeMaterialEditor() }; return }
	val windowState = rememberWindowState(size = DpSize(1040.dp, 760.dp), position = WindowPosition(Alignment.Center))
	Window(onCloseRequest = vm::closeMaterialEditor, state = windowState, title = "${tr("materials.editor.title")} - ${item.name}") {
		androidx.compose.runtime.CompositionLocalProvider(LocalAwtWindow provides window) {
			CompactToolTheme(colors = state.toolColors, uiScale = AppSettings.uiScale, fontScale = AppSettings.fontScale) {
				key(item.id) { MaterialEditor(item, state, vm) }
			}
		}
	}
}

/** Takes [from]'s paint colours and tip settings, so a brush carries between the canvas and the material window. */
private fun CanvasEditor.takePaintSettings(from: CanvasEditor) {
	paintColor = from.paintColor; paintSecondaryColor = from.paintSecondaryColor
	paintBrushSize = from.paintBrushSize; paintPencilSize = from.paintPencilSize; paintEraserSize = from.paintEraserSize
	paintShapeSize = from.paintShapeSize; paintHardness = from.paintHardness; paintOpacity = from.paintOpacity
	paintTolerance = from.paintTolerance; paintShapeFilled = from.paintShapeFilled
	if (paintShape != from.paintShape) selectPaintShape(from.paintShape)
}

/**
 * Paint mode on a material: a canvas editor of its own, in Paint mode, painting a paint session on [item]'s pixels
 * ([WorkspacePaintSession.materialId]). Everything else is the canvas's: the tool bar, the options bar, the session bar
 * (undo, redo, discard, apply), the context menu, the tip and pick overlays, the strokes landing as the pointer moves
 * and the keys of the keymap. Apply writes the session as one edit (`material_set_pixels`) and every layer showing the
 * material follows; leaving the material or closing the window applies what waits. The colours and tip settings come
 * from the active canvas and go back to it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun MaterialEditor(item: MaterialItem, state: PSD2LiveState, vm: PSD2LiveViewModel) {
	val colors = LocalToolColors.current
	val keymap = state.keymap
	val base = item.raster
	val sessionId = WorkspacePaintSession.MATERIAL_PREFIX + item.id
	val users = remember(state.layout.layerMaterials, item.id) { state.layout.layerMaterials.count { it.value == item.id } }
	val busy = state.workspaceEditBusy || state.canvasEditBusy

	val editor = remember(item.id) {
		CanvasEditor(vm, state.activeWorkspace.id, "material-window").apply {
			val canvas = vm.canvasEditor
			takePaintSettings(canvas)
			hierarchyMode = EditHierarchyMode.PAINT
			tool = canvas.tool.takeIf { it in PAINT_TOOLS } ?: CanvasTool.PAINT_BRUSH
		}
	}
	// The session follows the material: opened on it, and opened again when its pixels change while nothing waits.
	var sessionBase by remember { mutableStateOf<org.umamo.format.art.LayerRaster?>(null) }
	LaunchedEffect(base) {
		val session = editor.paintSession
		if (session == null || sessionBase !== base && !session.isDirty) {
			editor.startPaintSession(sessionId, forceReload = true)
			sessionBase = base
		}
	}
	DisposableEffect(editor) {
		onDispose {
			vm.canvasEditor.takePaintSettings(editor)
			val session = editor.paintSession
			if (session != null && session.isDirty) vm.savePaintSession(session.handle, false, false, tr("editor.paint.commitSummary", item.name)) {}
			else editor.closePaintSession()
		}
	}

	var backdrop by remember { mutableStateOf(MaterialBackdrop.CHECKER) }
	var adjusting by remember { mutableStateOf(false) }
	var adjustment by remember { mutableStateOf(WorkspaceMaterialPixels.Adjustment()) }
	var menuAt by remember { mutableStateOf<Offset?>(null) }

	// The view: material pixels to screen pixels, fitted on open and on demand.
	var viewSize by remember { mutableStateOf(IntSize.Zero) }
	var scale by remember { mutableStateOf(0f) }
	var origin by remember { mutableStateOf(Offset.Zero) }
	fun fit() {
		if (viewSize.width == 0 || viewSize.height == 0) return
		scale = minOf(viewSize.width * 0.82f / base.width, viewSize.height * 0.82f / base.height).coerceIn(0.02f, 64f)
		origin = Offset((viewSize.width - base.width * scale) / 2f, (viewSize.height - base.height * scale) / 2f)
	}
	fun actual() {
		scale = 1f
		origin = Offset((viewSize.width - base.width) / 2f, (viewSize.height - base.height) / 2f)
	}
	LaunchedEffect(viewSize) { if (scale == 0f) fit() }
	LaunchedEffect(base.width, base.height) { if (viewSize != IntSize.Zero) fit() }
	fun zoomAt(at: Offset, factor: Float) {
		val next = (scale * factor).coerceIn(0.02f, 64f)
		origin = at - (at - origin) * (next / scale)
		scale = next
	}
	val viewport = CanvasViewport(scale.toDouble().coerceAtLeast(1e-3), origin.x.toDouble(), origin.y.toDouble(), base.width.toFloat(), base.height.toFloat())

	// The adjusted look, made off the UI thread while the sliders move.
	var adjusted by remember(base) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
	LaunchedEffect(base, adjustment) {
		if (!adjustment.colour) { adjusted = null; return@LaunchedEffect }
		delay(60)
		adjusted = withContext(Dispatchers.Default) {
			WorkspaceMaterialPixels.adjust(base, adjustment.copy(flip = null)).let { io.github.psd2live.ui.utils.rgbaImageBitmap(it.width, it.height, it.rgba) }
		}
	}
	val dirty = editor.paintSession?.isDirty == true

	val focus = remember { FocusRequester() }
	val refocus: () -> Unit = { runCatching { focus.requestFocus() } }
	LaunchedEffect(Unit) { refocus() }
	// The canvas's keys, as the canvas runs them in Paint mode.
	fun onKey(event: KeyEvent): Boolean {
		if (event.key == Key.Spacebar) { editor.space = event.type == KeyEventType.KeyDown; return true }
		if (event.key == Key.AltLeft || event.key == Key.AltRight) { editor.altHeld = event.type == KeyEventType.KeyDown; return false }
		if (event.type != KeyEventType.KeyDown) return false
		val action = keymap.match(event, ShortcutScope.CANVAS) ?: keymap.match(event, ShortcutScope.APP) ?: return false
		when (action) {
			ShortcutAction.UNDO -> if (editor.canUndoPaint()) editor.undoPaint() else vm.undoHistory()
			ShortcutAction.REDO -> if (editor.canRedoPaint()) editor.redoPaint() else vm.redoHistory()
			ShortcutAction.FRAME_VIEW -> fit()
			ShortcutAction.RESET_CAMERA -> actual()
			ShortcutAction.CANCEL -> if (menuAt != null) menuAt = null else editor.cancel()
			ShortcutAction.FINISH_PATH -> if (!editor.finishPaintPolygon()) editor.promptCommitPaintSession()
			ShortcutAction.SELECT_ALL -> editor.selectAllPaint()
			ShortcutAction.INVERT_SELECTION -> editor.invertPaintSelection()
			ShortcutAction.DELETE_SELECTION -> editor.deleteInPaint()
			ShortcutAction.TOOL_PAINT_BRUSH -> editor.activateTool(CanvasTool.PAINT_BRUSH)
			ShortcutAction.TOOL_PAINT_PENCIL -> editor.activateTool(CanvasTool.PAINT_PENCIL)
			ShortcutAction.TOOL_PAINT_ERASER -> editor.activateTool(CanvasTool.PAINT_ERASER)
			ShortcutAction.TOOL_PAINT_BUCKET -> editor.activateTool(CanvasTool.PAINT_BUCKET)
			ShortcutAction.TOOL_PAINT_EYEDROPPER -> editor.activateTool(CanvasTool.PAINT_EYEDROPPER)
			ShortcutAction.TOOL_PAINT_LINE -> editor.selectPaintShape(PaintShape.LINE)
			ShortcutAction.TOOL_PAINT_RECT -> editor.selectPaintShape(PaintShape.RECTANGLE)
			ShortcutAction.TOOL_PAINT_ELLIPSE -> editor.selectPaintShape(PaintShape.ELLIPSE)
			ShortcutAction.PAINT_SHAPE_CYCLE -> editor.cyclePaintShape()
			ShortcutAction.BRUSH_RADIUS_DOWN -> editor.stepOption(OptionRole.SIZE, up = false)
			ShortcutAction.BRUSH_RADIUS_UP -> editor.stepOption(OptionRole.SIZE, up = true)
			ShortcutAction.BRUSH_HARDNESS_DOWN -> editor.stepOption(OptionRole.HARDNESS, up = false)
			ShortcutAction.BRUSH_HARDNESS_UP -> editor.stepOption(OptionRole.HARDNESS, up = true)
			ShortcutAction.PAINT_OPACITY_DOWN -> editor.stepOption(OptionRole.OPACITY, up = false)
			ShortcutAction.PAINT_OPACITY_UP -> editor.stepOption(OptionRole.OPACITY, up = true)
			// In Paint mode X swaps the foreground and background colours, as in every paint program.
			ShortcutAction.AXIS_CONSTRAIN_X -> editor.swapPaintColors()
			ShortcutAction.PICK_OPTION_1, ShortcutAction.PICK_OPTION_2, ShortcutAction.PICK_OPTION_3,
			ShortcutAction.PICK_OPTION_4, ShortcutAction.PICK_OPTION_5 -> editor.pickVariant(action.pickIndex!!)
			else -> if (!editor.paintShortcut(action)) return false
		}
		return true
	}

	val textMeasurer = rememberTextMeasurer()
	var panning by remember { mutableStateOf(false) }
	var lastPan by remember { mutableStateOf(Offset.Zero) }
	var adjustButton by remember { mutableStateOf<PointerButton?>(null) }
	Box(Modifier.fillMaxSize().background(colors.windowBackground).focusRequester(focus).focusable().onPreviewKeyEvent(::onKey)) {
		Canvas(Modifier.fillMaxSize().clipToBounds().onSizeChanged { viewSize = it }
			.pointerHoverIcon(PointerIcon(if (panning) java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.MOVE_CURSOR) else editor.activeCursor()))
			.onPointerEvent(PointerEventType.Scroll) { event ->
				val change = event.changes.first()
				zoomAt(change.position, if (change.scrollDelta.y < 0) 1.2f else 1f / 1.2f)
				change.consume()
			}
			.onPointerEvent(PointerEventType.Press) { event ->
				val change = event.changes.firstOrNull() ?: return@onPointerEvent
				refocus()
				val modifiers = event.keyboardModifiers
				editor.altHeld = modifiers.isAltPressed
				val gesture = keymap.gesture(buttonBindingOf(event.button, modifiers))
				if (editor.adjustingBrush) { adjustButton = null; editor.endBrushAdjust(cancel = false) }
				// Alt + right drag retunes the tip, as on the canvas.
				if ((gesture == ShortcutAction.BRUSH_ADJUST_DRAG || gesture == ShortcutAction.BRUSH_ADJUST_ALT_DRAG) && !editor.inGesture &&
					editor.beginBrushAdjust(change.position, shift = gesture == ShortcutAction.BRUSH_ADJUST_ALT_DRAG)) {
					adjustButton = event.button; change.consume(); return@onPointerEvent
				}
				if (event.button == PointerButton.Secondary && gesture == null && !editor.inGesture) {
					menuAt = change.position; change.consume(); return@onPointerEvent
				}
				if (CanvasNavigation.pans(gesture, event.button, editor.space)) {
					panning = true; lastPan = change.position; change.consume(); return@onPointerEvent
				}
				if (event.button == PointerButton.Primary) {
					if (menuAt != null) { menuAt = null; change.consume(); return@onPointerEvent }
					if (editor.press(change.position, viewport, modifiers.isShiftPressed, modifiers.isAltPressed, modifiers.isPrimaryPressed)) change.consume()
				}
			}
			.onPointerEvent(PointerEventType.Move) { event ->
				val change = event.changes.firstOrNull() ?: return@onPointerEvent
				editor.altHeld = event.keyboardModifiers.isAltPressed
				when {
					panning -> { origin += change.position - lastPan; lastPan = change.position }
					editor.adjustingBrush -> if (!event.buttons.areAnyPressed) { adjustButton = null; editor.endBrushAdjust(cancel = false) }
						else editor.updateBrushAdjust(change.position)
					else -> editor.move(change.position, viewport, event.keyboardModifiers.isShiftPressed, event.keyboardModifiers.isAltPressed,
						event.keyboardModifiers.isPrimaryPressed)
				}
			}
			.onPointerEvent(PointerEventType.Release) { event ->
				when {
					editor.adjustingBrush && (event.button == adjustButton || !event.buttons.areAnyPressed) -> { adjustButton = null; editor.endBrushAdjust(cancel = false) }
					panning -> panning = false
					event.button == PointerButton.Primary -> editor.release()
				}
			}
			.onPointerEvent(PointerEventType.Exit) {
				if (editor.adjustingBrush) { adjustButton = null; editor.endBrushAdjust(cancel = false) }
				editor.clearHover()
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
				val quality = if (scale >= 2f) FilterQuality.None else FilterQuality.Low
				val shown = adjusted
				val session = editor.paintSession
				if (shown != null) drawImage(shown, srcOffset = IntOffset.Zero, srcSize = IntSize(base.width, base.height),
					dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()), dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
					filterQuality = quality)
				// The session's pixels as it paints them, tile by tile.
				else if (session != null) for (tile in session.previewTiles) {
					val left = (origin.x + tile.x * scale).roundToInt(); val top = (origin.y + tile.y * scale).roundToInt()
					val right = (origin.x + (tile.x + tile.width) * scale).roundToInt(); val bottom = (origin.y + (tile.y + tile.height) * scale).roundToInt()
					drawImage(tile.image, dstOffset = IntOffset(left, top), dstSize = IntSize((right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1)),
						filterQuality = quality)
				}
			}
			drawRect(colors.border, origin, Size(w, h), style = Stroke(1f))
			drawPaintOverlays(editor, viewport, colors, textMeasurer)
		}

		// The Paint mode's own bars: the tools down the left, the session at the top, the tool's values at the bottom.
		CanvasToolBar(editor = editor, keymap = keymap, focus = refocus)
		// Between the info bar at the top left and the window's right edge.
		var infoBarWidth by remember { mutableStateOf(0) }
		PaintSessionBar(editor, modeBarWidth = infoBarWidth, endBarWidth = 0, focus = refocus)
		ToolOptionsBar(editor, refocus)
		menuAt?.let { at -> CanvasContextMenu(editor, expanded = true, clickOffset = at, onDismissRequest = { menuAt = null; refocus() }, onAction = refocus) }

		// What the material is, and its adjustments, at the top left.
		FloatingBar(Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 8.dp).onSizeChanged { infoBarWidth = it.width }) {
			Text(item.name, color = colors.textPrimary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
				modifier = Modifier.widthIn(max = 200.dp).padding(start = 6.dp))
			val details = listOfNotNull("${base.width}×${base.height}", if (item.kind == MaterialKind.VECTOR) "SVG" else null,
				"${(scale * 100).roundToInt()}%", if (users > 0) tr("materials.references", users) else tr("materials.unused"))
			Text(details.joinToString(" · "), color = colors.textMuted, fontSize = 10.5.sp, maxLines = 1, modifier = Modifier.padding(end = 4.dp))
			BarDivider()
			Box {
				BarChip(tr("materials.editor.adjust"), adjusting || adjustment.colour, { adjusting = !adjusting }, chevron = true, open = adjusting,
					enabled = !dirty && !busy, tooltip = if (dirty) tr("materials.editor.adjustAfterApply") else null)
				FloatingMenu(expanded = adjusting, onDismiss = { adjusting = false; refocus() }, width = 260.dp) {
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
		FloatingBar(Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
			for (entry in MaterialBackdrop.entries) BarChip(null, backdrop == entry, { backdrop = entry; refocus() }, tooltip = tr(entry.labelKey)) {
				Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).border(1.dp, it, RoundedCornerShape(2.dp)).background(when (entry) {
					MaterialBackdrop.WHITE -> Color.White; MaterialBackdrop.BLACK -> Color.Black; MaterialBackdrop.CHECKER -> Color(0xFFBFBFBF)
				}))
			}
			BarDivider()
			BarChip(tr("materials.editor.fit"), false, { fit(); refocus() }, tooltip = keymap.labelFor(ShortcutAction.FRAME_VIEW))
			BarChip("1:1", false, { actual(); refocus() }, tooltip = keymap.labelFor(ShortcutAction.RESET_CAMERA))
		}
		if (item.kind == MaterialKind.VECTOR) Text(tr("materials.editor.vectorNote"), color = colors.textMuted, fontSize = 10.5.sp,
			modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 52.dp).canvasChrome().frostedGlass(RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
	}
}
