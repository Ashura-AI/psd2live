package io.github.psd2live.ui.views

import kotlinx.serialization.json.put
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.LayerCanvasRect
import io.github.psd2live.project.LayoutReference
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactMenuDivider
import io.github.psd2live.ui.components.CompactMenuHeader
import io.github.psd2live.ui.components.CompactMenuItem
import io.github.psd2live.ui.components.CompactMenuSection
import io.github.psd2live.ui.components.IconPin
import io.github.psd2live.ui.components.TreeContextMenu
import io.github.psd2live.ui.parameterKeyMarks
import io.github.psd2live.ui.state.ReferenceMode
import io.github.psd2live.ui.state.ReferencePinRows
import io.github.psd2live.ui.state.ReferenceTarget
import androidx.compose.ui.input.pointer.onPointerEvent
import org.umamo.runtime.model.Parameter
import io.github.psd2live.project.PinPoint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import io.github.psd2live.ui.components.IconAdd
import io.github.psd2live.ui.components.IconEye
import io.github.psd2live.ui.components.IconLock
import io.github.psd2live.ui.components.IconTrash
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.handCursor
import io.github.psd2live.ui.theme.selectOrOpen
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** [reference]'s pixels where it sits on the canvas, turned about its centre, as [viewport] shows the canvas. */
internal fun DrawScope.drawReference(reference: LayoutReference, image: ImageBitmap, viewport: CanvasViewport) {
	val r = reference.rect
	val left = viewport.x(r.left).toFloat(); val top = (viewport.offsetY + r.top * viewport.scale).toFloat()
	val width = (r.width * viewport.scale).toFloat(); val height = (r.height * viewport.scale).toFloat()
	rotate(reference.rotation, Offset(left + width / 2f, top + height / 2f)) {
		drawImage(image, IntOffset.Zero, IntSize(image.width, image.height), IntOffset(left.roundToInt(), top.roundToInt()),
			IntSize(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1)),
			alpha = reference.opacity, filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium)
	}
}

/**
 * A reference's keyform pin as a compact copy of the parameter panel: a track for each keyed parameter and a 2D pad
 * for each linked pair ([ReferencePinRows]), drawn as the panel draws them but only to pin. Each keyform dot - a key
 * on a track, a crossing of keys on a pad - is a point the reference may show at: a click adds it ([onToggle]) or
 * takes it away, and the reference shows at any point it holds. The pose the canvas shows is the faint marker.
 * Points pinned some other way are listed beneath, each to take away.
 */
@Composable
internal fun ReferencePinPanel(puppet: PuppetModel, values: Map<ParameterId, Float>, points: List<PinPoint>, enabled: Boolean,
                               onToggle: (PinPoint) -> Unit) {
	val colors = LocalToolColors.current
	val marks = remember(puppet) { puppet.parameterKeyMarks() }
	val keyed = remember(marks) { marks.filterValues { it.allKeys.isNotEmpty() }.keys }
	val rows = remember(puppet, keyed, points) { ReferencePinRows.of(puppet, keyed, points) }
	if (rows.isEmpty()) {
		Text(tr("layers.pin.none"), color = colors.textMuted, fontSize = 10.5.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
		return
	}
	fun keys(parameter: Parameter) = (marks[parameter.id]?.allKeys.orEmpty() + points.mapNotNull { it[parameter.id.raw] }.filter { it in parameter.min..parameter.max })
		.distinct().sorted()
	fun pinned(point: PinPoint) = points.any { ReferencePinRows.same(it, point) }
	fun current(parameter: Parameter) = values[parameter.id] ?: parameter.default
	Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState())
		.padding(start = 10.dp, end = 8.dp, top = 2.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
		for (row in rows) {
			val count = points.count { point -> point.keys == row.parameters.mapTo(HashSet()) { it.id.raw } }
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text(row.parameters.joinToString(" × ") { it.name.ifBlank { it.id.raw } }, color = if (count > 0) colors.textPrimary else colors.textMuted,
					fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
				if (count > 0) Text(tr("layers.pin.count", count), color = colors.accent, fontSize = 9.5.sp)
			}
			when (row) {
				is ReferencePinRows.Axis -> {
					val id = row.parameter.id.raw
					PinKeyTrack(row.parameter, keys(row.parameter), { key -> pinned(mapOf(id to key)) }, current(row.parameter), enabled) { key -> onToggle(mapOf(id to key)) }
				}
				is ReferencePinRows.Pad -> {
					val h = row.horizontal.id.raw; val v = row.vertical.id.raw
					PinKeyPad(row.horizontal, row.vertical, keys(row.horizontal), keys(row.vertical), { x, y -> pinned(mapOf(h to x, v to y)) },
						current(row.horizontal) to current(row.vertical), enabled) { x, y -> onToggle(mapOf(h to x, v to y)) }
				}
			}
		}
		for (point in ReferencePinRows.loose(rows, points)) Row(verticalAlignment = Alignment.CenterVertically) {
			Text(point.entries.joinToString(", ") { (id, value) -> "${puppet.parameters.firstOrNull { it.id.raw == id }?.name ?: id} ${formatAxisValue(value)}" },
				color = colors.textPrimary, fontSize = 10.sp, maxLines = 2, modifier = Modifier.weight(1f))
			CompactIconButton(onClick = { onToggle(point) }, enabled = enabled, tooltip = tr("layers.pin.unpinRow"), size = 20.dp) {
				IconPin(true, Modifier.size(11.dp), colors.accent)
			}
		}
	}
}

private val PinInset = 6.dp
private val PinKeyRadius = 3.2.dp
private val PinReach = 9.dp

/** One parameter's keys as the parameter panel's track draws them, each a point to pin; [current] is the edit pose. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PinKeyTrack(parameter: Parameter, keys: List<Float>, pinned: (Float) -> Boolean, current: Float, enabled: Boolean, onToggle: (Float) -> Unit) {
	val colors = LocalToolColors.current
	var hovered by remember(keys) { mutableStateOf<Float?>(null) }
	val span = (parameter.max - parameter.min).takeIf { it > 0f } ?: 1f
	fun nearest(x: Float, width: Float, inset: Float, reach: Float): Float? = keys.minByOrNull { kotlin.math.abs(inset + (it - parameter.min) / span * (width - inset * 2) - x) }
		?.takeIf { kotlin.math.abs(inset + (it - parameter.min) / span * (width - inset * 2) - x) <= reach }
	val density = LocalDensity.current
	Canvas(Modifier.fillMaxWidth().height(20.dp).handCursor(enabled && hovered != null)
		.onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move) { event ->
			val p = event.changes.first().position
			hovered = with(density) { nearest(p.x, size.width.toFloat(), PinInset.toPx(), PinReach.toPx()) }
		}
		.onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Exit) { hovered = null }
		.onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press) { event ->
			val change = event.changes.first()
			if (!enabled || event.button != androidx.compose.ui.input.pointer.PointerButton.Primary) return@onPointerEvent
			with(density) { nearest(change.position.x, size.width.toFloat(), PinInset.toPx(), PinReach.toPx()) }?.let { onToggle(it); change.consume() }
		}) {
		val inset = PinInset.toPx(); val y = size.height / 2f
		fun x(value: Float) = inset + ((value - parameter.min) / span).coerceIn(0f, 1f) * (size.width - inset * 2)
		drawLine(colors.textMuted.copy(alpha = 0.45f), Offset(inset, y), Offset(size.width - inset, y), 1.dp.toPx())
		drawLine(colors.textMuted.copy(alpha = 0.55f), Offset(x(current), y - 6.dp.toPx()), Offset(x(current), y + 6.dp.toPx()), 1.dp.toPx())
		val r = PinKeyRadius.toPx()
		for (key in keys) {
			val at = Offset(x(key), y)
			if (pinned(key)) drawCircle(colors.accent, r * 1.35f, at)
			else {
				drawCircle(colors.panelBackground, r, at)
				drawCircle(if (hovered == key) colors.accent else colors.textMuted, r, at, style = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx()))
			}
		}
	}
}

/** A linked pair's keys as the parameter panel's 2D pad draws them, each crossing a point to pin; [current] is the edit pose. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PinKeyPad(horizontal: Parameter, vertical: Parameter, xKeys: List<Float>, yKeys: List<Float>, pinned: (Float, Float) -> Boolean,
                      current: Pair<Float, Float>, enabled: Boolean, onToggle: (Float, Float) -> Unit) {
	val colors = LocalToolColors.current
	var hovered by remember(xKeys, yKeys) { mutableStateOf<Pair<Float, Float>?>(null) }
	val spanX = (horizontal.max - horizontal.min).takeIf { it > 0f } ?: 1f
	val spanY = (vertical.max - vertical.min).takeIf { it > 0f } ?: 1f
	val density = LocalDensity.current
	fun place(width: Float, height: Float, inset: Float, x: Float, y: Float) =
		Offset(inset + ((x - horizontal.min) / spanX).coerceIn(0f, 1f) * (width - inset * 2),
			height - inset - ((y - vertical.min) / spanY).coerceIn(0f, 1f) * (height - inset * 2))
	fun nearest(p: Offset, width: Float, height: Float, inset: Float, reach: Float): Pair<Float, Float>? =
		xKeys.flatMap { x -> yKeys.map { y -> x to y } }.minByOrNull { (x, y) -> (place(width, height, inset, x, y) - p).getDistance() }
			?.takeIf { (x, y) -> (place(width, height, inset, x, y) - p).getDistance() <= reach }
	Canvas(Modifier.fillMaxWidth().height(76.dp).handCursor(enabled && hovered != null)
		.onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move) { event ->
			hovered = with(density) { nearest(event.changes.first().position, size.width.toFloat(), size.height.toFloat(), PinInset.toPx(), PinReach.toPx()) }
		}
		.onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Exit) { hovered = null }
		.onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press) { event ->
			val change = event.changes.first()
			if (!enabled || event.button != androidx.compose.ui.input.pointer.PointerButton.Primary) return@onPointerEvent
			with(density) { nearest(change.position, size.width.toFloat(), size.height.toFloat(), PinInset.toPx(), PinReach.toPx()) }
				?.let { (x, y) -> onToggle(x, y); change.consume() }
		}) {
		val inset = PinInset.toPx()
		drawRect(colors.textMuted.copy(alpha = 0.45f), Offset(inset, inset), androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
			style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
		for (x in xKeys) { val at = place(size.width, size.height, inset, x, vertical.min).x
			drawLine(colors.textMuted.copy(alpha = 0.18f), Offset(at, inset), Offset(at, size.height - inset), 1.dp.toPx()) }
		for (y in yKeys) { val at = place(size.width, size.height, inset, horizontal.min, y).y
			drawLine(colors.textMuted.copy(alpha = 0.18f), Offset(inset, at), Offset(size.width - inset, at), 1.dp.toPx()) }
		val now = place(size.width, size.height, inset, current.first, current.second)
		val tick = 5.dp.toPx()
		drawLine(colors.textMuted.copy(alpha = 0.6f), now - Offset(tick, 0f), now + Offset(tick, 0f), 1.dp.toPx())
		drawLine(colors.textMuted.copy(alpha = 0.6f), now - Offset(0f, tick), now + Offset(0f, tick), 1.dp.toPx())
		val r = PinKeyRadius.toPx()
		for (x in xKeys) for (y in yKeys) {
			val at = place(size.width, size.height, inset, x, y)
			if (pinned(x, y)) drawCircle(colors.accent, r * 1.35f, at)
			else {
				drawCircle(colors.panelBackground, r, at)
				drawCircle(if (hovered == (x to y)) colors.accent else colors.textMuted, r, at, style = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx()))
			}
		}
	}
}

/** What a reference row shows: the reference, its name and where it shows. */
internal data class ReferenceRow(val target: ReferenceTarget, val name: String, val mode: ReferenceMode, val visible: Boolean,
                                 val pinned: Boolean, val locked: Boolean, val opacity: Float?)

/** The hierarchy's reference image rows: those in front of the model ([under] false, front first) or those behind it. */
internal fun referenceRows(state: PSD2LiveState, under: Boolean): List<ReferenceRow> = state.layout.references.filter { it.under == under }
	.let { if (under) it else it.reversed() }.map { r ->
		ReferenceRow(ReferenceTarget.Image(r.id), state.layout.materials.item(r.materialId)?.name ?: r.materialId,
			if (r.under) ReferenceMode.BACKGROUND else ReferenceMode.FOREGROUND, r.visible, r.pins.isNotEmpty(), r.locked, r.opacity)
	}

/** A reference layer row: in the stack with the ArtMeshes. */
internal fun referenceLayerRow(state: PSD2LiveState, layerId: String, name: String) = ReferenceRow(ReferenceTarget.Layer(layerId), name,
	ReferenceMode.LAYER, state.isLayerVisible(layerId), layerId in state.layout.referencePins, false, null)

/**
 * A reference in the hierarchy: a member like a mesh, with the reference icon, where it shows (behind, in the stack,
 * in front) and its pin; a click selects it - an image for its canvas handles, a layer as any layer - and its menu
 * holds what a reference does.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun ReferenceTreeRow(row: ReferenceRow, startPadding: androidx.compose.ui.unit.Dp, state: PSD2LiveState, viewModel: PSD2LiveViewModel) {
	val colors = LocalToolColors.current
	val selectedImage by viewModel.selectedReferenceId.collectAsState()
	val selected = when (val t = row.target) {
		is ReferenceTarget.Image -> selectedImage == t.id
		is ReferenceTarget.Layer -> state.selectedLayerId == t.layerId || t.layerId in state.selectedLayerIds
	}
	var hovered by remember { mutableStateOf(false) }
	var menu by remember { mutableStateOf(false) }
	var menuAt by remember { mutableStateOf(Offset.Zero) }
	fun select() = when (val t = row.target) {
		is ReferenceTarget.Image -> viewModel.selectReference(if (selected) null else t.id)
		is ReferenceTarget.Layer -> viewModel.selectLayer(if (selected) null else t.layerId)
	}
	val text = when {
		!row.visible -> colors.textDisabled
		selected -> colors.selectionText
		else -> colors.textPrimary
	}
	Box(Modifier.fillMaxWidth()) {
		Row(Modifier.fillMaxWidth().height(20.dp)
			.background(when { selected -> colors.selection; hovered -> colors.controlHover.copy(alpha = 0.3f); else -> androidx.compose.ui.graphics.Color.Transparent })
			.onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Enter) { hovered = true }
			.onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Exit) { hovered = false }
			.onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press) { event ->
				val change = event.changes.firstOrNull() ?: return@onPointerEvent
				if (event.button == androidx.compose.ui.input.pointer.PointerButton.Secondary) {
					menuAt = change.position
					if (!selected) select()
					menu = true
					change.consume()
				} else if (event.button == androidx.compose.ui.input.pointer.PointerButton.Primary) {
					select(); change.consume()
				}
			}
			.padding(start = startPadding, end = 6.dp),
			verticalAlignment = Alignment.CenterVertically) {
			Spacer(Modifier.width(1.dp))
			IconReferenceImage(tint = if (selected) colors.selectionText else colors.accent, size = 12.dp)
			Spacer(Modifier.width(14.dp))
			Text(row.name, color = text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
			Text(tr(row.mode.labelKey), color = if (selected) colors.selectionText else colors.textMuted, fontSize = 9.5.sp, maxLines = 1)
			if (row.pinned) { Spacer(Modifier.width(4.dp)); IconPin(true, Modifier.size(11.dp), if (selected) colors.selectionText else colors.accent) }
			if (row.locked) { Spacer(Modifier.width(4.dp)); IconLock(true, Modifier.size(11.dp), if (selected) colors.selectionText else colors.textMuted) }
			Spacer(Modifier.width(4.dp))
			Box(Modifier.size(16.dp).handCursor().clickable { viewModel.setReferenceVisible(row.target, !row.visible) }, contentAlignment = Alignment.Center) {
				IconEye(row.visible, Modifier.size(12.dp), if (row.visible) (if (selected) colors.selectionText else colors.textMuted) else colors.textDisabled.copy(alpha = 0.5f))
			}
		}
		TreeContextMenu(expanded = menu, onDismissRequest = { menu = false }, clickOffset = menuAt, minWidth = 236.dp) {
			ReferenceMenu(row, state, viewModel) { menu = false }
		}
	}
}

/**
 * What a reference does, the same for an image and a layer: where it shows, shown or hidden, its pin as a compact
 * parameter panel, and removing it; an image also fades and locks, a layer can go back to the model.
 */
@Composable
private fun ReferenceMenu(row: ReferenceRow, state: PSD2LiveState, viewModel: PSD2LiveViewModel, close: () -> Unit) {
	val colors = LocalToolColors.current
	CompactMenuHeader(row.name, badge = tr("references.badge"), icon = { IconReferenceImage(tint = colors.accent, size = 12.dp) })
	CompactMenuSection(tr("references.mode"))
	// Where it shows says whether it is in the model: only in the layer stack is it an ArtMesh of the rig.
	for (mode in ReferenceMode.entries) CompactMenuItem(tr(mode.labelKey), onClick = { close(); if (mode != row.mode) viewModel.setReferenceMode(row.target, mode) },
		active = mode == row.mode, trailingText = tr(if (mode.inModel) "references.inModel" else "references.notInModel"))
	CompactMenuDivider()
	CompactMenuItem(tr(if (row.visible) "references.hide" else "references.show"), onClick = { close(); viewModel.setReferenceVisible(row.target, !row.visible) },
		icon = { IconEye(row.visible, Modifier.size(12.dp), colors.textMuted) })
	val image = row.target as? ReferenceTarget.Image
	if (image != null && row.opacity != null) {
		val opacity = row.opacity
		// The fade steps a quarter at a time and keeps the menu open.
		CompactMenuItem(tr("references.opacity"), onClick = {
			viewModel.editReference(image.id) { put("opacity", if (opacity >= 0.99f) 0.25f else minOf(1f, opacity + 0.25f)) }
		}, trailingText = "${(opacity * 100).roundToInt()}%")
		CompactMenuItem(tr(if (row.locked) "references.unlock" else "references.lock"), onClick = { close(); viewModel.editReference(image.id) { put("locked", !row.locked) } },
			icon = { IconLock(row.locked, Modifier.size(12.dp), colors.textMuted) })
	}
	val layer = row.target as? ReferenceTarget.Layer
	if (layer != null) CompactMenuItem(tr("references.toModel"), onClick = { close(); viewModel.markLayersReference(listOf(layer.layerId), false) })
	val puppet = state.previewModel?.rig?.puppet
	if (puppet != null && puppet.parameters.isNotEmpty()) {
		CompactMenuSection(tr("layers.pin.menu"))
		val points = row.target.pin(state.layout)
		ReferencePinPanel(puppet, state.parameterValues, points, enabled = state.analysis != null) { point -> viewModel.toggleReferencePoint(row.target, point) }
		if (points.isNotEmpty()) CompactMenuItem(tr("layers.pin.clear"), onClick = { viewModel.setReferencePin(row.target, emptyList()) })
	}
	CompactMenuDivider()
	CompactMenuItem(tr("references.remove"), onClick = { close(); viewModel.removeReference(row.target) }, danger = true,
		icon = { IconTrash(Modifier.size(12.dp), colors.textMuted) })
}

/** What a gesture on a [ReferenceHandles] does. */
private enum class ReferenceDrag { MOVE, SCALE, ROTATE }

/**
 * Handles for the selected [reference] on an edit canvas laid out by [viewport]: drag inside to move it, a corner to
 * scale it about its centre, the knob above to turn it. [onDraft] shows the gesture as it goes (null: as it was);
 * [onCommit] keeps it, once per gesture. The handles take the pointer only near the reference.
 */
@Composable
internal fun ReferenceHandles(reference: LayoutReference, viewport: CanvasViewport, onDraft: (LayoutReference?) -> Unit,
                              onCommit: (LayerCanvasRect, Float) -> Unit) {
	val colors = LocalToolColors.current
	val density = LocalDensity.current
	val margin = with(density) { 34.dp.toPx() }
	val knob = with(density) { 22.dp.toPx() }
	val reach = with(density) { 10.dp.toPx() }
	var draft by remember(reference) { mutableStateOf(reference) }
	val shown = draft
	val scale = viewport.scale.toFloat()
	val width = shown.rect.width * scale; val height = shown.rect.height * scale
	val centre = Offset(viewport.x(shown.rect.left).toFloat() + width / 2f, (viewport.offsetY + shown.rect.top * viewport.scale).toFloat() + height / 2f)
	val turn = Math.toRadians(shown.rotation.toDouble())
	// The box holding the turned reference and its knob, upright: the gesture reads the pointer in screen directions.
	val extentX = (kotlin.math.abs(width / 2f * cos(turn)) + kotlin.math.abs(height / 2f * sin(turn))).toFloat() + margin
	val extentY = (kotlin.math.abs(width / 2f * sin(turn)) + kotlin.math.abs(height / 2f * cos(turn))).toFloat() + margin
	val current by rememberUpdatedState(shown)
	val start by rememberUpdatedState(reference)
	Box(Modifier
		.offset { IntOffset((centre.x - extentX).roundToInt(), (centre.y - extentY).roundToInt()) }
		.size(with(density) { (extentX * 2f).toDp() }, with(density) { (extentY * 2f).toDp() })
		.drawBehind {
			val c = Offset(size.width / 2f, size.height / 2f)
			rotate(shown.rotation, c) {
				val topLeft = Offset(c.x - width / 2f, c.y - height / 2f)
				drawRect(colors.accent, topLeft, androidx.compose.ui.geometry.Size(width, height), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
				val half = 4.5.dp.toPx()
				for (corner in listOf(topLeft, topLeft + Offset(width, 0f), topLeft + Offset(0f, height), topLeft + Offset(width, height))) {
					drawRect(colors.panelBackground, corner - Offset(half, half), androidx.compose.ui.geometry.Size(half * 2, half * 2))
					drawRect(colors.accent, corner - Offset(half, half), androidx.compose.ui.geometry.Size(half * 2, half * 2), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
				}
				val knobAt = Offset(c.x, topLeft.y - knob)
				drawLine(colors.accent, Offset(c.x, topLeft.y), knobAt, 1.dp.toPx())
				drawCircle(colors.accent, half, knobAt)
			}
		}
		.pointerInput(Unit) {
			awaitEachGesture {
				val down = awaitFirstDown()
				val from = start
				val c = Offset(size.width / 2f, size.height / 2f)
				// Where the press lands on the reference itself, turned back upright.
				fun upright(p: Offset): Offset {
					val t = -Math.toRadians(current.rotation.toDouble()); val d = p - c
					return Offset((d.x * cos(t) - d.y * sin(t)).toFloat(), (d.x * sin(t) + d.y * cos(t)).toFloat())
				}
				val w = from.rect.width * scale / 2f; val h = from.rect.height * scale / 2f
				val at = upright(down.position)
				val kind = when {
					hypot(at.x, at.y + h + knob) <= reach -> ReferenceDrag.ROTATE
					listOf(-w to -h, w to -h, -w to h, w to h).any { (x, y) -> hypot(at.x - x, at.y - y) <= reach } -> ReferenceDrag.SCALE
					kotlin.math.abs(at.x) <= w && kotlin.math.abs(at.y) <= h -> ReferenceDrag.MOVE
					else -> return@awaitEachGesture
				}
				down.consume()
				val grabbed = down.position - c
				val grabAngle = atan2(grabbed.y.toDouble(), grabbed.x.toDouble())
				var moved = from
				var last = from
				while (true) {
					val event = awaitPointerEvent()
					val change = event.changes.firstOrNull { it.id == down.id } ?: break
					if (!change.pressed) break
					change.consume()
					val p = change.position - Offset(size.width / 2f, size.height / 2f)
					val next = when (kind) {
						ReferenceDrag.MOVE -> {
							val d = change.position - change.previousPosition
							moved = moved.copy(rect = moved.rect.copy(left = moved.rect.left + d.x / scale, top = moved.rect.top + d.y / scale))
							moved
						}
						ReferenceDrag.SCALE -> {
							val factor = (hypot(p.x, p.y) / hypot(grabbed.x, grabbed.y).coerceAtLeast(1f)).coerceIn(0.02f, 50f)
							val nw = from.rect.width * factor; val nh = from.rect.height * factor
							val cx = from.rect.left + from.rect.width / 2f; val cy = from.rect.top + from.rect.height / 2f
							from.copy(rect = LayerCanvasRect(cx - nw / 2f, cy - nh / 2f, nw, nh))
						}
						ReferenceDrag.ROTATE -> {
							val angle = Math.toDegrees(atan2(p.y.toDouble(), p.x.toDouble()) - grabAngle).toFloat()
							from.copy(rotation = (((from.rotation + angle) % 360f) + 360f) % 360f)
						}
					}
					last = next
					draft = next
					onDraft(next)
				}
				if (last != from) onCommit(last.rect, last.rotation) else onDraft(null)
			}
		})
}
