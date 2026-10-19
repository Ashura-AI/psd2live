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
 * A reference's keyform pin as a compact copy of the parameter panel: a track for each parameter and a 2D pad for
 * each linked pair ([ReferencePinRows]), showing the keys and the value it is pinned at (else the edit pose). Nothing
 * here moves a parameter; each row's push pin pins the reference there or frees it ([onToggle], the row's IDs).
 */
@Composable
internal fun ReferencePinPanel(puppet: PuppetModel, values: Map<ParameterId, Float>, pin: Map<String, Float>, enabled: Boolean,
                               onToggle: (List<String>) -> Unit) {
	val colors = LocalToolColors.current
	val rows = remember(puppet, pin, values) { ReferencePinRows.of(puppet, pin, values) }
	val marks = remember(puppet) { puppet.parameterKeyMarks() }
	if (rows.isEmpty()) {
		Text(tr("layers.pin.none"), color = colors.textMuted, fontSize = 10.5.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
		return
	}
	fun shown(parameter: Parameter) = pin[parameter.id.raw] ?: values[parameter.id] ?: parameter.default
	Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
		for (row in rows) {
			val ids = row.parameters.map { it.id.raw }
			val pinned = ids.all { it in pin }
			Row(verticalAlignment = Alignment.CenterVertically) {
				Column(Modifier.weight(1f)) {
					Row(verticalAlignment = Alignment.CenterVertically) {
						Text(row.parameters.joinToString(" × ") { it.name.ifBlank { it.id.raw } }, color = if (pinned) colors.textPrimary else colors.textMuted,
							fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
						Text(row.parameters.joinToString(", ") { formatAxisValue(shown(it)) }, color = if (pinned) colors.accent else colors.textMuted, fontSize = 9.5.sp)
					}
					when (row) {
						is ReferencePinRows.Axis -> ParameterTrack({ shown(row.parameter) }, {}, row.parameter.min..row.parameter.max,
							marks[row.parameter.id].toSliderMarks(), if (pinned) listOf(shown(row.parameter)) else emptyList(),
							Modifier.fillMaxWidth().height(18.dp), enabled = false)
						is ReferencePinRows.Pad -> ParameterPad2D(row.horizontal, row.vertical, { shown(row.horizontal) }, { shown(row.vertical) },
							xLocked = true, yLocked = true, horizontalKeys = marks[row.horizontal.id],
							highlightedX = if (pinned) listOf(shown(row.horizontal)) else emptyList(),
							highlightedY = if (pinned) listOf(shown(row.vertical)) else emptyList(),
							verticalKeys = marks[row.vertical.id], modifier = Modifier.fillMaxWidth().height(72.dp), onChange = { _, _ -> })
					}
				}
				CompactIconButton(onClick = { onToggle(ids) }, enabled = enabled, tooltip = tr(if (pinned) "layers.pin.unpinRow" else "layers.pin.pinRow"), size = 22.dp) {
					IconPin(pinned, Modifier.size(12.dp), if (pinned) colors.accent else colors.textMuted)
				}
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
	for (mode in ReferenceMode.entries) CompactMenuItem(tr(mode.labelKey), onClick = { close(); if (mode != row.mode) viewModel.setReferenceMode(row.target, mode) },
		active = mode == row.mode)
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
		val pin = row.target.pin(state.layout)
		ReferencePinPanel(puppet, state.parameterValues, pin, enabled = state.analysis != null) { ids -> viewModel.toggleReferencePin(row.target, ids) }
		val listed = remember(puppet, pin, state.parameterValues) { ReferencePinRows.of(puppet, pin, state.parameterValues).flatMap { r -> r.parameters.map { it.id.raw } } }
		if (listed.isNotEmpty()) CompactMenuItem(tr("layers.pin.pose"), onClick = { viewModel.pinReferencePose(row.target, listed) },
			icon = { IconPin(true, Modifier.size(12.dp), colors.textMuted) })
		if (pin.isNotEmpty()) CompactMenuItem(tr("layers.pin.clear"), onClick = { viewModel.setReferencePin(row.target, emptyMap()) })
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
