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
 * The reference images, at the top of the hierarchy: each can be shown or hidden, locked, put under or over the model,
 * faded, reordered and removed; selecting one puts its handles on the edit canvas. [onAdd] picks image files to add.
 */
@Composable
internal fun ReferenceImagesSection(state: PSD2LiveState, viewModel: PSD2LiveViewModel, onAdd: () -> Unit) {
	val colors = LocalToolColors.current
	val references = state.layout.references
	val selected by viewModel.selectedReferenceId.collectAsState()
	var open by remember { mutableStateOf(true) }
	Column(Modifier.fillMaxWidth().background(colors.windowBackground)) {
		Row(Modifier.fillMaxWidth().height(24.dp).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
			Box(Modifier.weight(1f).handCursor().clickable(remember { MutableInteractionSource() }, indication = null) { open = !open }) {
				Text((if (open) "▾ " else "▸ ") + tr("references.title", references.size), color = colors.textMuted, fontSize = 10.5.sp, maxLines = 1)
			}
			CompactIconButton(onClick = onAdd, tooltip = tr("references.add"), size = 20.dp) { IconAdd(Modifier.size(10.dp), colors.textPrimary) }
		}
		if (open) for ((index, reference) in references.withIndex()) {
			val name = state.layout.materials.item(reference.materialId)?.name ?: reference.materialId
			val isSelected = selected == reference.id
			Row(Modifier.fillMaxWidth().height(24.dp)
				.background(if (isSelected) colors.accent.copy(alpha = 0.18f) else colors.windowBackground)
				.selectOrOpen(true, onSelect = { viewModel.selectReference(if (isSelected) null else reference.id) }, onOpen = { viewModel.selectReference(reference.id) })
				.padding(start = 14.dp, end = 4.dp),
				verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
				CompactIconButton(onClick = { viewModel.editReference(reference.id) { put("visible", !reference.visible) } },
					tooltip = tr(if (reference.visible) "references.hide" else "references.show"), size = 20.dp) {
					IconEye(reference.visible, Modifier.size(13.dp), if (reference.visible) colors.textPrimary else colors.textMuted)
				}
				Text(name, color = if (reference.visible) colors.textPrimary else colors.textMuted, fontSize = 11.sp, maxLines = 1,
					overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
				// The fade steps a quarter at a time, as the material menu's does.
				CompactIconButton(onClick = { viewModel.editReference(reference.id) { put("opacity", if (reference.opacity >= 0.99f) 0.25f else minOf(1f, reference.opacity + 0.25f)) } },
					tooltip = tr("references.opacity"), size = 20.dp) {
					Text("${(reference.opacity * 100).roundToInt()}", color = colors.textMuted, fontSize = 9.5.sp)
				}
				CompactIconButton(onClick = { viewModel.editReference(reference.id) { put("under", !reference.under) } },
					tooltip = tr(if (reference.under) "references.under" else "references.over"), size = 20.dp) {
					Text(if (reference.under) "▼" else "▲", color = colors.textMuted, fontSize = 9.sp)
				}
				CompactIconButton(onClick = { viewModel.editReference(reference.id) { put("locked", !reference.locked) } },
					tooltip = tr(if (reference.locked) "references.unlock" else "references.lock"), size = 20.dp) {
					IconLock(reference.locked, Modifier.size(12.dp), if (reference.locked) colors.textPrimary else colors.textMuted)
				}
				if (references.size > 1) CompactIconButton(onClick = { viewModel.orderReference(reference.id, if (index == 0) references.lastIndex else index - 1) },
					tooltip = tr("references.order"), size = 20.dp) {
					Text("⇅", color = colors.textMuted, fontSize = 10.sp)
				}
				CompactIconButton(onClick = { viewModel.removeReference(reference.id) }, tooltip = tr("references.remove"), size = 20.dp) {
					IconTrash(Modifier.size(11.dp), colors.textMuted)
				}
			}
		}
	}
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
