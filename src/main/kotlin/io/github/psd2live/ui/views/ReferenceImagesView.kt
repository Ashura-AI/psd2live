package io.github.psd2live.ui.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.LayoutReference
import io.github.psd2live.project.PinPoint
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.IconEye
import io.github.psd2live.ui.components.IconLock
import io.github.psd2live.ui.components.IconPin
import io.github.psd2live.ui.components.IconTrash
import io.github.psd2live.ui.parameterKeyMarks
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.ReferenceMode
import io.github.psd2live.ui.state.ReferencePinRows
import io.github.psd2live.ui.state.ReferenceTarget
import io.github.psd2live.ui.state.referenceConversion
import io.github.psd2live.ui.tooloptions.OptionIcon
import io.github.psd2live.ui.views.tooloptions.OptionIconView
import io.github.psd2live.ui.views.tooloptions.invokeMenuAction
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.handCursor
import io.github.psd2live.ui.views.texture.FloatingMenuRow
import io.github.psd2live.ui.views.texture.FloatingMenuSection
import io.github.psd2live.ui.views.texture.FloatingMenuSlider
import io.github.psd2live.ui.views.texture.FloatingMenuSubmenuRow
import io.github.psd2live.ui.views.tooloptions.GlidingColumn
import io.github.psd2live.ui.views.tooloptions.MenuCascade
import io.github.psd2live.ui.views.tooloptions.MenuSecondLevel
import kotlin.math.roundToInt
import kotlinx.serialization.json.put
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel

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
internal fun referenceLayerRow(state: PSD2LiveState, layerId: String, name: String): ReferenceRow {
	val model = state.previewModel
	val opacity = model?.rig?.puppet?.drawables?.firstOrNull { model.rig.ownLayerIdByDrawableId[it.id.raw] == layerId }?.opacity
		?: state.analysis?.source?.layers?.firstOrNull { it.id.raw == layerId }?.opacity
	return ReferenceRow(ReferenceTarget.Layer(layerId), name, ReferenceMode.LAYER, state.isLayerVisible(layerId),
		layerId in state.layout.referencePins, false, opacity)
}

/** Canvas and hierarchy resolve the same selected member and use the same actions. */
internal fun selectedReferenceRow(state: PSD2LiveState): ReferenceRow? {
	state.selectedReferenceId?.let { id -> return (referenceRows(state, false) + referenceRows(state, true)).firstOrNull { (it.target as ReferenceTarget.Image).id == id } }
	val layer = state.selectedLayerId?.takeIf { it in state.layout.referenceLayerIds } ?: return null
	val name = state.analysis?.source?.layers?.firstOrNull { it.id.raw == layer }?.name ?: layer
	return referenceLayerRow(state, layer, name)
}

/**
 * A reference in the hierarchy: a member like a mesh, with the reference icon, where it shows (behind, in the stack,
 * in front) and its pin; a click selects it - an image for its canvas handles, a layer as any layer - and its menu
 * holds what a reference does.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun ReferenceTreeRow(row: ReferenceRow, startPadding: androidx.compose.ui.unit.Dp, state: PSD2LiveState, viewModel: PSD2LiveViewModel) {
	val colors = LocalToolColors.current
	val selectedImage = state.selectedReferenceId
	val selected = when (val t = row.target) {
		is ReferenceTarget.Image -> selectedImage == t.id
		is ReferenceTarget.Layer -> state.selectedLayerId == t.layerId || t.layerId in state.selectedLayerIds
	}
	var hovered by remember { mutableStateOf(false) }
	var menu by remember { mutableStateOf(false) }
	var menuAt by remember { mutableStateOf(Offset.Zero) }
	fun select() = when (val t = row.target) {
		is ReferenceTarget.Image -> viewModel.selectReference(t.id)
		is ReferenceTarget.Layer -> viewModel.selectLayer(t.layerId)
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
		PressMenu(expanded = menu, onDismissRequest = { menu = false }, press = menuAt) {
			ReferenceMenu(row, state, viewModel) { menu = false }
		}
	}
}

/** Reference actions share the canvas menu's rows, continuous values and cascading pin panel. */
@Composable
internal fun ReferenceMenu(row: ReferenceRow, state: PSD2LiveState, viewModel: PSD2LiveViewModel, onAction: () -> Unit = {}, close: () -> Unit) {
	val image = row.target as? ReferenceTarget.Image
	val layer = row.target as? ReferenceTarget.Layer
	val puppet = state.previewModel?.rig?.puppet
	var pinsOpen by remember { mutableStateOf(false) }
	var opacity by remember(row.target, row.opacity) { mutableStateOf(row.opacity ?: 1f) }
	val editable = !state.isBusy && !state.workspaceEditBusy && !state.editorDraftBusy
    fun act(command: () -> Unit) = invokeMenuAction(close, onAction, action = command)
	val rows = mutableListOf<Triple<Boolean, Boolean, @Composable () -> Unit>>()
	fun add(highlight: Boolean = true, danger: Boolean = false, content: @Composable () -> Unit) { rows += Triple(highlight, danger, content) }
	add(false) { FloatingMenuSection(row.name) }
	if (layer != null) add(highlight = state.referenceConversion(layer.layerId).enabled) { ReferenceConversionMenuRow(layer.layerId, state, viewModel, close, onAction) }
	for (mode in ReferenceMode.entries) add(highlight = editable) {
		FloatingMenuRow(tr(mode.labelKey), { act { if (mode != row.mode) viewModel.setReferenceMode(row.target, mode) } },
			selected = mode == row.mode, enabled = editable, trailing = tr(if (mode.inModel) "references.inModel" else "references.notInModel"))
	}
	add(highlight = editable) { FloatingMenuRow(tr(if (row.visible) "references.hide" else "references.show"), { act { viewModel.setReferenceVisible(row.target, !row.visible) } },
		enabled = editable, icon = { IconEye(row.visible, Modifier.size(12.dp), it) }) }
	if (row.opacity != null) {
		add(false) {
			FloatingMenuSlider(tr("references.opacity"), opacity, { opacity = it; viewModel.previewReferenceOpacity(row.target, it) },
				0f..1f, "${(opacity * 100).roundToInt()}%", enabled = editable,
				onValueChangeFinished = { viewModel.commitReferenceOpacity(row.target, opacity) })
		}
	}
	if (image != null) {
		add(highlight = editable) { FloatingMenuRow(tr(if (row.locked) "references.unlock" else "references.lock"), { act { viewModel.editReference(image.id) { put("locked", !row.locked) } } },
			enabled = editable, icon = { IconLock(row.locked, Modifier.size(12.dp), it) }) }
	}
	if (puppet != null && puppet.parameters.isNotEmpty()) add {
		FloatingMenuSubmenuRow(tr("layers.pin.menu"), pinsOpen, { pinsOpen = true },
			icon = { IconPin(row.pinned, Modifier.size(12.dp), it) })
	}
	add(highlight = editable, danger = true) { FloatingMenuRow(tr("references.remove"), { act { viewModel.removeReference(row.target) } }, enabled = editable,
		danger = true, icon = { IconTrash(Modifier.size(12.dp), it) }) }
	val pinIndex = if (puppet != null && puppet.parameters.isNotEmpty()) rows.lastIndex - 1 else -1
	MenuCascade {
		GlidingColumn(Modifier.width(236.dp), rows.size, { rows[it].first }, onHover = { if (it != pinIndex) pinsOpen = false }, dangers = { rows[it].second }) { rows[it].third() }
		MenuSecondLevel("pins".takeIf { pinsOpen }) {
			if (puppet != null) {
				val points = row.target.pin(state.layout)
				GlidingColumn(Modifier.width(236.dp), if (points.isEmpty()) 2 else 3, { it == 2 && editable }) { i ->
					when (i) {
						0 -> FloatingMenuSection(tr("layers.pin.menu"))
						1 -> ReferencePinPanel(puppet, state.parameterValues, points, enabled = editable && state.analysis != null) { viewModel.toggleReferencePoint(row.target, it) }
						2 -> FloatingMenuRow(tr("layers.pin.clear"), { viewModel.setReferencePin(row.target, emptyList()) }, enabled = editable)
					}
				}
			}
		}
	}
}

/** The first action under a layer's name, with the destination's icon, in both directions. */
@Composable
internal fun ReferenceConversionMenuRow(layerId: String, state: PSD2LiveState, viewModel: PSD2LiveViewModel, close: () -> Unit, onAction: () -> Unit = {}) {
	val conversion = state.referenceConversion(layerId)
	FloatingMenuRow(tr(conversion.labelKey), {
        invokeMenuAction(close, onAction) { viewModel.markLayersReference(listOf(layerId), conversion.toReference) }
	}, enabled = conversion.enabled, icon = {
		OptionIconView(if (conversion.toReference) OptionIcon.REFERENCE else OptionIcon.MESH, it)
	})
}
