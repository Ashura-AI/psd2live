package io.github.psd2live.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.CanvasGuide
import io.github.psd2live.ui.CanvasAidColor
import io.github.psd2live.ui.CanvasAidOptions
import io.github.psd2live.ui.CanvasGrid
import io.github.psd2live.ui.CanvasRuler
import io.github.psd2live.ui.RandomPoseScope
import io.github.psd2live.ui.components.GridIcon
import io.github.psd2live.ui.components.ICON_FINE
import io.github.psd2live.ui.state.RandomPoseControls
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.ToolColors
import io.github.psd2live.ui.views.texture.BarTooltip
import io.github.psd2live.ui.views.texture.FloatingMenuDivider
import io.github.psd2live.ui.views.texture.FloatingMenuRadio
import io.github.psd2live.ui.views.texture.FloatingMenuRow
import io.github.psd2live.ui.views.texture.FloatingMenuSection
import io.github.psd2live.ui.views.texture.FloatingMenuSlider
import io.github.psd2live.ui.views.texture.FloatingMenuSwatches
import io.github.psd2live.ui.views.texture.FloatingMenuSwitch
import io.github.psd2live.ui.views.texture.glideItem
import kotlin.math.floor
import kotlin.math.roundToInt

/** The rulers' width along the top and left of an edit canvas while its guides show. */
internal val CANVAS_RULER_WIDTH = 16.dp

/** Screen distance, in dp, at which a press takes a guide. */
internal const val GUIDE_HIT_DP = 4f

/** The theme colour an aid is drawn in. */
internal fun CanvasAidColor.color(colors: ToolColors): Color = when (this) {
	CanvasAidColor.TEXT -> colors.textPrimary
	CanvasAidColor.ACCENT -> colors.accent
	CanvasAidColor.HIGHLIGHT -> colors.highlight
	CanvasAidColor.SUCCESS -> colors.success
	CanvasAidColor.WARNING -> colors.warning
	CanvasAidColor.ERROR -> colors.error
}

/**
 * A guide being dragged on an edit canvas: out of a ruler as a new one, or an existing one moved. Only the canvas
 * draw and the pointer handlers read it, so a drag redraws the aids without composing the canvas again; the guide
 * lands in the project when the button is released.
 */
internal class CanvasGuideDrag {
	var draft: CanvasGuide? by mutableStateOf(null)
	/** The pointer is back over the ruler: the release removes the guide. */
	var overRuler: Boolean by mutableStateOf(false)
	/** The guide under the pointer, for the cursor. */
	var hovered: CanvasGuide? by mutableStateOf(null)
	val active: Boolean get() = draft != null
}

/**
 * The grid over the document, the guides across the canvas and, with the guides, the rulers they come from, all
 * at [viewport]'s camera. [density] keeps line widths and spacing in dp, so every UI scale reads the same.
 */
internal fun DrawScope.drawCanvasAids(
	viewport: CanvasViewport,
	aids: CanvasAidOptions,
	guides: List<CanvasGuide>,
	drag: CanvasGuideDrag,
	colors: ToolColors,
	textMeasurer: TextMeasurer,
	/** The rulers' numbers; drawn smaller than [labelStyle]'s size so they fit the ruler's width. */
	labelStyle: TextStyle,
) {
	val w = size.width
	val h = size.height
	val hairline = 1.dp.toPx().coerceAtLeast(1f)
	fun crisp(v: Double): Float = floor(v).toFloat() + 0.5f
	val dpScale = viewport.scale / density
	if (aids.showGrid) {
		// The grid covers the document, clipped to what is on screen.
		val left = viewport.x(0f).coerceAtLeast(0.0)
		val right = viewport.x(viewport.canvasWidth).coerceAtMost(w.toDouble())
		val top = (viewport.offsetY).coerceAtLeast(0.0)
		val bottom = (viewport.offsetY + viewport.canvasHeight * viewport.scale).coerceAtMost(h.toDouble())
		if (right > left && bottom > top) {
			val tint = aids.gridColor.color(colors)
			val major = tint.copy(alpha = aids.gridOpacity)
			val minor = tint.copy(alpha = aids.gridOpacity * 0.45f)
			val majors = ArrayList<Offset>()
			val minors = ArrayList<Offset>()
			for (line in CanvasGrid.lines(aids.gridSpacing, aids.gridSubdivisions, viewport.canvasX(left.toFloat()),
				viewport.canvasX(right.toFloat()), dpScale)) {
				val x = crisp(viewport.x(line.position))
				(if (line.major) majors else minors).apply { add(Offset(x, top.toFloat())); add(Offset(x, bottom.toFloat())) }
			}
			for (line in CanvasGrid.lines(aids.gridSpacing, aids.gridSubdivisions, viewport.canvasY(top.toFloat()),
				viewport.canvasY(bottom.toFloat()), dpScale)) {
				val y = crisp(viewport.offsetY + line.position * viewport.scale)
				(if (line.major) majors else minors).apply { add(Offset(left.toFloat(), y)); add(Offset(right.toFloat(), y)) }
			}
			if (minors.isNotEmpty()) drawPoints(minors, PointMode.Lines, minor, strokeWidth = hairline)
			if (majors.isNotEmpty()) drawPoints(majors, PointMode.Lines, major, strokeWidth = hairline)
		}
	}
	if (!aids.showGuides) return
	val ruler = CANVAS_RULER_WIDTH.toPx()
	val guideTint = aids.guideColor.color(colors)
	val dash = if (aids.guidesLocked) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null
	val draft = drag.draft
	fun drawGuide(guide: CanvasGuide, strong: Boolean) {
		val tint = guideTint.copy(alpha = if (strong) 1f else 0.8f)
		val width = if (strong) hairline * 2 else hairline
		if (guide.vertical) {
			val x = crisp(viewport.x(guide.position))
			drawLine(tint, Offset(x, ruler), Offset(x, h), width, pathEffect = dash)
		} else {
			val y = crisp(viewport.offsetY + guide.position * viewport.scale)
			drawLine(tint, Offset(ruler, y), Offset(w, y), width, pathEffect = dash)
		}
	}
	for (guide in guides) if (guide.id != draft?.id) drawGuide(guide, strong = guide.id == drag.hovered?.id)
	if (draft != null && !drag.overRuler) drawGuide(draft, strong = true)

	// The rulers, in canvas pixels: numbered every 1, 2 or 5 times a power of ten, five small ticks between.
	val plate = colors.panelBackground.copy(alpha = 0.9f)
	val tick = colors.textMuted
	val label = labelStyle.copy(color = colors.textMuted, fontSize = labelStyle.fontSize * 0.8f)
	drawRect(plate, Offset(ruler, 0f), Size((w - ruler).coerceAtLeast(0f), ruler))
	drawRect(plate, Offset(0f, ruler), Size(ruler, (h - ruler).coerceAtLeast(0f)))
	drawRect(plate, Offset.Zero, Size(ruler, ruler))
	drawLine(colors.border, Offset(ruler, ruler - hairline / 2), Offset(w, ruler - hairline / 2), hairline)
	drawLine(colors.border, Offset(ruler - hairline / 2, ruler), Offset(ruler - hairline / 2, h), hairline)
	clipRect(left = ruler, top = 0f, right = w, bottom = ruler) {
		for (t in CanvasRuler.ticks(viewport.canvasX(ruler), viewport.canvasX(w), dpScale)) {
			val x = crisp(viewport.x(t.position))
			val length = if (t.label) ruler * 0.6f else ruler * 0.25f
			drawLine(tick, Offset(x, ruler - length), Offset(x, ruler), hairline)
			if (t.label) drawText(textMeasurer.measure(rulerNumber(t.position), label), topLeft = Offset(x + 2.dp.toPx(), 0f))
		}
	}
	clipRect(left = 0f, top = ruler, right = ruler, bottom = h) {
		for (t in CanvasRuler.ticks(viewport.canvasY(ruler), viewport.canvasY(h), dpScale)) {
			val y = crisp(viewport.offsetY + t.position * viewport.scale)
			val length = if (t.label) ruler * 0.6f else ruler * 0.25f
			drawLine(tick, Offset(ruler - length, y), Offset(ruler, y), hairline)
			if (t.label) {
				val text = textMeasurer.measure(rulerNumber(t.position), label)
				// Read bottom to top, as a vertical ruler's numbers are.
				rotate(-90f, Offset(0f, y)) { drawText(text, topLeft = Offset(2.dp.toPx(), 0f)) }
			}
		}
	}
	// Where the dragged guide sits, marked on its ruler.
	if (draft != null) {
		val marker = if (drag.overRuler) colors.error else guideTint
		if (draft.vertical) {
			val x = crisp(viewport.x(draft.position))
			drawLine(marker, Offset(x, 0f), Offset(x, ruler), hairline * 2)
		} else {
			val y = crisp(viewport.offsetY + draft.position * viewport.scale)
			drawLine(marker, Offset(0f, y), Offset(ruler, y), hairline * 2)
		}
	}
}

private fun rulerNumber(value: Float): String {
	val rounded = value.roundToInt()
	return if (kotlin.math.abs(value - rounded) < 0.01f) "$rounded" else "%.1f".format(value)
}

/**
 * The canvas aids' settings, in the display rail's menu: grid, guides, snapping, onion skin and the random pose.
 * Changes apply as they are made; the onion skin's source line says what its ghosts show at the moment.
 */
@Composable
internal fun CanvasAidsMenuContent(
	aids: CanvasAidOptions,
	onAidsChange: (CanvasAidOptions) -> Unit,
	guideCount: Int,
	onClearGuides: () -> Unit,
	/** Puts back the guides the last clear removed, while there are some to put back. */
	onRestoreGuides: (() -> Unit)?,
	onionSource: String?,
	randomPose: RandomPoseControls?,
	editingParameters: Int,
	onRandomPose: () -> Unit,
	/** Puts back the pose the first random pose replaced, while it still belongs to the project on screen. */
	onRestorePose: (() -> Unit)?,
	keyLabel: (io.github.psd2live.ui.state.ShortcutAction) -> String?,
	/** The grid, guides and onion skin are the edit canvas's; a preview canvas has only the random pose. */
	showCanvasAids: Boolean = true,
) {
	if (showCanvasAids) CanvasAidsSections(aids, onAidsChange, guideCount, onClearGuides, onRestoreGuides, onionSource)
	if (randomPose != null) {
		if (showCanvasAids) FloatingMenuDivider()
		FloatingMenuSection(tr("canvas.aids.random"))
		FloatingMenuRadio(tr("canvas.aids.random.all"), randomPose.scope == RandomPoseScope.ALL,
			onSelect = { randomPose.scope = RandomPoseScope.ALL })
		FloatingMenuRadio(tr("canvas.aids.random.editing", editingParameters), randomPose.scope == RandomPoseScope.EDITING,
			onSelect = { randomPose.scope = RandomPoseScope.EDITING },
			hint = if (editingParameters == 0) tr("canvas.aids.random.editingNone") else null)
		FloatingMenuSlider(tr("canvas.aids.random.amount"), randomPose.amount, { randomPose.amount = it }, 0.1f..1f,
			"${(randomPose.amount * 100).roundToInt()}%")
		FloatingMenuRow(tr("canvas.aids.random.apply"), onRandomPose,
			trailing = keyLabel(io.github.psd2live.ui.state.ShortcutAction.RANDOM_POSE))
		FloatingMenuRow(tr("canvas.aids.random.restore"), { onRestorePose?.invoke() }, enabled = onRestorePose != null,
			trailing = keyLabel(io.github.psd2live.ui.state.ShortcutAction.RESTORE_RANDOM_POSE))
	}
}

@Composable
private fun CanvasAidsSections(
	aids: CanvasAidOptions,
	onAidsChange: (CanvasAidOptions) -> Unit,
	guideCount: Int,
	onClearGuides: () -> Unit,
	onRestoreGuides: (() -> Unit)?,
	onionSource: String?,
) {
	FloatingMenuSection(tr("canvas.aids.grid"))
	FloatingMenuSwitch(tr("canvas.aids.grid.show"), aids.showGrid, { onAidsChange(aids.copy(showGrid = it)) })
	FloatingMenuSlider(tr("canvas.aids.grid.spacing"), aids.gridSpacing, { onAidsChange(aids.copy(gridSpacing = it.roundToInt().toFloat())) },
		CanvasAidOptions.MIN_GRID_SPACING..1024f, "${aids.gridSpacing.roundToInt()} px", logarithmic = true)
	FloatingMenuSlider(tr("canvas.aids.grid.subdivisions"), aids.gridSubdivisions.toFloat(),
		{ onAidsChange(aids.copy(gridSubdivisions = it.roundToInt())) }, 1f..CanvasAidOptions.MAX_GRID_SUBDIVISIONS.toFloat(),
		"${aids.gridSubdivisions}")
	FloatingMenuSlider(tr("canvas.aids.opacity"), aids.gridOpacity, { onAidsChange(aids.copy(gridOpacity = it)) }, 0.05f..1f,
		"${(aids.gridOpacity * 100).roundToInt()}%")
	AidColorRow(tr("canvas.aids.color"), aids.gridColor) { onAidsChange(aids.copy(gridColor = it)) }
	FloatingMenuDivider()

	FloatingMenuSection(tr("canvas.aids.guides"))
	FloatingMenuSwitch(tr("canvas.aids.guides.show"), aids.showGuides, { onAidsChange(aids.copy(showGuides = it)) },
		hint = tr("canvas.aids.guides.hint"))
	FloatingMenuSwitch(tr("canvas.aids.guides.lock"), aids.guidesLocked, { onAidsChange(aids.copy(guidesLocked = it)) })
	AidColorRow(tr("canvas.aids.color"), aids.guideColor) { onAidsChange(aids.copy(guideColor = it)) }
	FloatingMenuRow(tr("canvas.aids.guides.clear"), onClearGuides, enabled = guideCount > 0, danger = true,
		trailing = if (guideCount > 0) "$guideCount" else null)
	if (onRestoreGuides != null) FloatingMenuRow(tr("canvas.aids.guides.restore"), onRestoreGuides)
	FloatingMenuDivider()
	FloatingMenuSwitch(tr("canvas.aids.snap"), aids.snap, { onAidsChange(aids.copy(snap = it)) },
		hint = tr("canvas.aids.snap.hint"))
	FloatingMenuDivider()

	FloatingMenuSection(tr("canvas.aids.onion"))
	FloatingMenuSwitch(tr("canvas.aids.onion.show"), aids.onionSkin, { onAidsChange(aids.copy(onionSkin = it)) },
		hint = onionSource)
	FloatingMenuSlider(tr("canvas.aids.onion.before"), aids.onionBefore.toFloat(), { onAidsChange(aids.copy(onionBefore = it.roundToInt())) },
		0f..CanvasAidOptions.MAX_ONION_GHOSTS.toFloat(), "${aids.onionBefore}")
	FloatingMenuSlider(tr("canvas.aids.onion.after"), aids.onionAfter.toFloat(), { onAidsChange(aids.copy(onionAfter = it.roundToInt())) },
		0f..CanvasAidOptions.MAX_ONION_GHOSTS.toFloat(), "${aids.onionAfter}")
	FloatingMenuSlider(tr("canvas.aids.onion.step"), aids.onionFrameStep.toFloat(), { onAidsChange(aids.copy(onionFrameStep = it.roundToInt())) },
		1f..CanvasAidOptions.MAX_ONION_FRAME_STEP.toFloat(), "${aids.onionFrameStep}")
	FloatingMenuSlider(tr("canvas.aids.opacity"), aids.onionOpacity, { onAidsChange(aids.copy(onionOpacity = it)) }, 0.05f..1f,
		"${(aids.onionOpacity * 100).roundToInt()}%")
	AidColorRow(tr("canvas.aids.onion.beforeColor"), aids.onionBeforeColor) { onAidsChange(aids.copy(onionBeforeColor = it)) }
	AidColorRow(tr("canvas.aids.onion.afterColor"), aids.onionAfterColor) { onAidsChange(aids.copy(onionAfterColor = it)) }
}

/** The colour swatches of an aid, one per [CanvasAidColor]. */
@Composable
private fun AidColorRow(label: String, selected: CanvasAidColor, onSelect: (CanvasAidColor) -> Unit) {
	val colors = LocalToolColors.current
	FloatingMenuSwatches(label, CanvasAidColor.entries.map { tr("canvas.aids.color.${it.name.lowercase()}") to it.color(colors) },
		selected.ordinal) { onSelect(CanvasAidColor.entries[it]) }
}

/** The grid: a square of cells. */
@Composable
internal fun IconCanvasGrid(tint: Color) = GridIcon(Modifier.size(14.dp), tint) {
	box(2.4f, 2.4f, 13.2f, 13.2f, 0.8f)
	line(6.8f, 2.4f, 6.8f, 15.6f, ICON_FINE)
	line(11.2f, 2.4f, 11.2f, 15.6f, ICON_FINE)
	line(2.4f, 6.8f, 15.6f, 6.8f, ICON_FINE)
	line(2.4f, 11.2f, 15.6f, 11.2f, ICON_FINE)
}

/** Guides: the corner of two rulers and a guide dragged out of each. */
@Composable
internal fun IconCanvasGuides(tint: Color) = GridIcon(Modifier.size(14.dp), tint) {
	fillBox(2f, 2f, 14f, 2.6f, 0.4f, tint.copy(alpha = 0.45f))
	fillBox(2f, 2f, 2.6f, 14f, 0.4f, tint.copy(alpha = 0.45f))
	line(10.4f, 5.6f, 10.4f, 16f)
	line(5.6f, 9.6f, 16f, 9.6f)
}

/** The onion skin: a figure with two fainter ones behind it. */
@Composable
internal fun IconOnionSkin(tint: Color) = GridIcon(Modifier.size(14.dp), tint) {
	ring(5.4f, 9f, 3.4f, ICON_FINE, tint.copy(alpha = 0.4f))
	ring(8.2f, 9f, 3.6f, ICON_FINE, tint.copy(alpha = 0.65f))
	ring(11.4f, 9f, 4f)
}

/** The random pose: a die. */
@Composable
internal fun IconRandomPose(tint: Color) = GridIcon(Modifier.size(14.dp), tint) {
	box(2.6f, 2.6f, 12.8f, 12.8f, 2.4f)
	dot(6f, 6f, 1.2f)
	dot(9f, 9f, 1.2f)
	dot(12f, 12f, 1.2f)
}

/** Restore the pose: an arrow turning back. */
@Composable
internal fun IconRestorePose(tint: Color) = GridIcon(Modifier.size(14.dp), tint) {
	arcArrow(9f, 9.6f, 5.4f, 200f, -110f)
}

/** The aids' settings: three sliders. */
@Composable
internal fun IconAidSettings(tint: Color) = GridIcon(Modifier.size(14.dp), tint) {
	line(2.6f, 4.6f, 15.4f, 4.6f, ICON_FINE)
	line(2.6f, 9f, 15.4f, 9f, ICON_FINE)
	line(2.6f, 13.4f, 15.4f, 13.4f, ICON_FINE)
	dot(6f, 4.6f, 1.6f)
	dot(11.6f, 9f, 1.6f)
	dot(7.6f, 13.4f, 1.6f)
}
