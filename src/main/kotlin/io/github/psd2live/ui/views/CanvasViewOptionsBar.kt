package io.github.psd2live.ui.views

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.AppMenuHeader
import io.github.psd2live.ui.components.AppMenuItem
import io.github.psd2live.ui.components.AppMenuSeparator
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.IconContextualWarp
import io.github.psd2live.ui.components.IconDimUnselected
import io.github.psd2live.ui.components.IconPin
import io.github.psd2live.ui.components.IconReset
import io.github.psd2live.ui.components.IconSelectionBounds
import io.github.psd2live.ui.components.IconWarpShowIndices
import io.github.psd2live.ui.components.IconWarpShowNames
import io.github.psd2live.ui.components.IconDeformPath
import io.github.psd2live.ui.components.IconPathHardness
import io.github.psd2live.ui.components.IconPathWidth
import io.github.psd2live.ui.components.IconSkeleton
import io.github.psd2live.ui.components.IconMeshWireframe
import io.github.psd2live.ui.components.IconRotationDeformer
import io.github.psd2live.ui.components.IconSelectedOnly
import io.github.psd2live.ui.components.IconTextureView
import io.github.psd2live.ui.components.IconWarpDeformer
import io.github.psd2live.ui.components.GridIcon
import io.github.psd2live.ui.components.ICON_FINE
import io.github.psd2live.ui.state.CanvasRailEntry
import io.github.psd2live.ui.state.CanvasViewContent
import io.github.psd2live.ui.state.ShortcutAction
import io.github.psd2live.ui.state.TabViewOptions
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.frostedGlass
import io.github.psd2live.ui.views.texture.BarTooltip
import io.github.psd2live.ui.views.texture.FloatingMenu
import io.github.psd2live.ui.views.texture.canvasChrome
import io.github.psd2live.ui.tutorial.TutorialTargetId
import io.github.psd2live.ui.tutorial.tutorialTarget
import io.github.psd2live.ui.theme.Motion

/**
 * What a row of a [CanvasOptionsRail] needs to animate with it: whether the rail is open, its labels' fade and
 * slide, and which edge it hangs from ([leading] for the tool palettes on the left).
 */
internal class CanvasRailScope(
	val expanded: Boolean,
	val textAlpha: Float,
	val textOffset: androidx.compose.ui.unit.Dp,
	val leading: Boolean = false,
)

/**
 * A canvas rail: a column of icon rows on one edge that, while hovered, widens and reveals each row's label.
 * The display rail sits bottom-right ([CanvasViewOptionsBar], the atlas page) and the tool palettes of the edit
 * and preview canvases on the left ([leading]); all build their rows with [RailItem], [RailToggle] and
 * [RailDivider]. [pinned] holds it open, for a menu dropped from one of its rows; [scrollable] lets a long
 * palette scroll on a short canvas.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun CanvasOptionsRail(
	modifier: Modifier = Modifier,
	leading: Boolean = false,
	expandedWidth: androidx.compose.ui.unit.Dp = 168.dp,
	pinned: Boolean = false,
	scrollable: Boolean = false,
	content: @Composable CanvasRailScope.() -> Unit,
) {
	val toolbarInteractionSource = remember { MutableInteractionSource() }
	val isHoveredBySource by toolbarInteractionSource.collectIsHoveredAsState()
	var isHoveredByEvent by remember { mutableStateOf(false) }
	val isToolbarHovered = isHoveredBySource || isHoveredByEvent || pinned

	val animatedWidth by animateDpAsState(
		targetValue = if (isToolbarHovered) expandedWidth else 34.dp,
		animationSpec = tween(durationMillis = Motion.Expand, easing = FastOutSlowInEasing),
	)
	val textAlpha by animateFloatAsState(
		targetValue = if (isToolbarHovered) 1f else 0f,
		animationSpec = tween(
			durationMillis = if (isToolbarHovered) Motion.Fade else Motion.Instant,
			delayMillis = if (isToolbarHovered) Motion.LabelDelay else 0,
			easing = FastOutSlowInEasing,
		),
	)
	// Labels slide in from the rail's own edge.
	val textOffset by animateDpAsState(
		targetValue = if (isToolbarHovered) 0.dp else if (leading) (-6).dp else 6.dp,
		animationSpec = tween(
			durationMillis = if (isToolbarHovered) Motion.Fold else Motion.Instant,
			delayMillis = if (isToolbarHovered) Motion.LabelDelay else 0,
			easing = FastOutSlowInEasing,
		),
	)
	val elevation by animateDpAsState(
		targetValue = if (isToolbarHovered) 8.dp else 2.dp,
		animationSpec = tween(durationMillis = Motion.Expand),
	)
	val scope = CanvasRailScope(animatedWidth > 42.dp, textAlpha, textOffset, leading)
	Column(
		modifier = modifier
			.width(animatedWidth)
			.canvasChrome()
			.frostedGlass(
				shape = RoundedCornerShape(6.dp),
				isHovered = isToolbarHovered,
				elevation = elevation,
				alpha = 0.78f,
			)
			.hoverable(toolbarInteractionSource)
			.onPointerEvent(PointerEventType.Enter) { isHoveredByEvent = true }
			.onPointerEvent(PointerEventType.Exit) { isHoveredByEvent = false }
			.let { if (scrollable) it.verticalScroll(rememberScrollState()) else it }
			.padding(3.dp),
		verticalArrangement = Arrangement.spacedBy(2.dp),
		horizontalAlignment = if (leading) Alignment.Start else Alignment.End,
	) { scope.content() }
}

/** A thin rule between groups of a [CanvasOptionsRail]; [strong] for the rule above a tool's own variants. */
@Composable
internal fun CanvasRailScope.RailDivider(strong: Boolean = false) {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 4.dp, vertical = 2.dp)
			.height(1.dp)
			.background(LocalToolColors.current.border.copy(alpha = if (strong) 0.45f else 0.35f)),
	)
}

/** One toggle of a [CanvasOptionsRail]: its icon on the rail's edge, its label while the rail is open. */
@Composable
internal fun CanvasRailScope.RailToggle(label: String, isChecked: Boolean, icon: @Composable (Color) -> Unit, onClick: () -> Unit) =
	RailItem(label, isChecked, icon = icon, onClick = onClick)

/**
 * One row of a [CanvasOptionsRail]: a tool or a display toggle. [keyLabel] is the chord that reaches it, boxed as a
 * key cap when [keyCap] (a tool) or plain. [tooltip] says why a row is disabled.
 */
@Composable
internal fun CanvasRailScope.RailItem(
	label: String,
	selected: Boolean,
	enabled: Boolean = true,
	keyLabel: String = "",
	keyCap: Boolean = false,
	tooltip: String? = null,
	icon: @Composable (Color) -> Unit,
	onClick: () -> Unit,
) {
	val colors = LocalToolColors.current
	val itemInteractionSource = remember { MutableInteractionSource() }
	val isItemHovered by itemInteractionSource.collectIsHoveredAsState()

	val bg = when {
		selected -> colors.accent.copy(alpha = 0.24f)
		isItemHovered && enabled -> colors.controlHover.copy(alpha = 0.7f)
		else -> Color.Transparent
	}
	val tint = when {
		!enabled -> colors.textDisabled
		selected -> colors.accent
		isItemHovered -> colors.textPrimary
		else -> colors.textMuted
	}
	BarTooltip(tooltip) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.height(28.dp)
				.clip(RoundedCornerShape(3.dp))
				.background(bg)
				.semantics { contentDescription = if (keyLabel.isEmpty()) label else "$label  $keyLabel" }
				.hoverable(itemInteractionSource)
				.clickable(
					interactionSource = itemInteractionSource,
					indication = null,
					enabled = enabled,
					onClick = onClick,
				),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = if (leading) Arrangement.Start else Arrangement.End,
		) {
			if (leading) RailIcon(tint, icon)
			if (expanded) {
				Row(
					modifier = Modifier
						.weight(1f)
						.offset(x = textOffset)
						.alpha(textAlpha)
						.padding(start = if (leading) 2.dp else 6.dp, end = if (leading) 4.dp else 2.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = label,
						color = when {
							!enabled -> colors.textDisabled
							selected || isItemHovered -> colors.textPrimary
							else -> colors.textMuted
						},
						fontSize = 11.5.sp,
						fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.weight(1f),
					)
					if (keyLabel.isNotEmpty()) RailKeyLabel(keyLabel, keyCap, selected)
				}
			}
			if (!leading) RailIcon(tint, icon)
		}
	}
}

/** A rail row's icon. */
@Composable
private fun RailIcon(tint: Color, icon: @Composable (Color) -> Unit) {
	Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) { icon(tint) }
}

/** The chord of a rail row: a key cap for a tool, plain text for a tool's variant. */
@Composable
private fun RailKeyLabel(keyLabel: String, keyCap: Boolean, selected: Boolean) {
	val colors = LocalToolColors.current
	if (keyCap) {
		Box(
			modifier = Modifier
				.background(if (selected) colors.accent.copy(alpha = 0.18f) else colors.panelElevated, RoundedCornerShape(3.dp))
				.border(0.5.dp, if (selected) colors.accent.copy(alpha = 0.4f) else colors.border.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
				.padding(horizontal = 4.dp, vertical = 1.dp),
			contentAlignment = Alignment.Center,
		) {
			Text(keyLabel, color = if (selected) colors.accent else colors.textDisabled, fontSize = 9.5.sp,
				fontFamily = FontFamily.Monospace, maxLines = 1)
		}
	} else {
		Text(keyLabel, color = colors.textMuted.copy(alpha = 0.75f), fontSize = 9.5.sp, fontFamily = FontFamily.Monospace,
			maxLines = 1, modifier = Modifier.padding(end = 4.dp))
	}
}

/**
 * Bottom-right mirror of the left tool palette: the display toggles the user pinned ([pins]), so the View menu does
 * not have to be opened mid-edit. A pinned toggle shows only while [content] has something for it to show; the View
 * menu carries every toggle and pins them. Nothing to show leaves the rail out.
 */
@Composable
internal fun CanvasViewOptionsBar(
	options: TabViewOptions,
	onOptionsChange: (TabViewOptions) -> Unit,
	content: CanvasViewContent,
	pins: Set<CanvasRailEntry>,
	/** The skin weight wash over the skeleton's meshes. */
	skinWeights: Boolean = false,
	onSkinWeightsChange: (Boolean) -> Unit = {},
	/** The grid and guides' rows and settings: the edit canvas's; null leaves them out. */
	aids: CanvasAidsRail? = null,
	modifier: Modifier = Modifier,
) {
	fun apply(updated: TabViewOptions) {
		onOptionsChange(updated.normalized())
	}
	fun shown(entry: CanvasRailEntry) = entry in pins && content.shows(entry) && when (entry) {
		// A path's width and hardness are drawn on the paths, so they come with them.
		CanvasRailEntry.PATH_WIDTH, CanvasRailEntry.PATH_HARDNESS -> options.showDeformPaths
		else -> true
	}
	val display = CanvasRailEntry.entries.filter { it !in RAIL_FOCUS_AND_AIDS && shown(it) }
	val focus = shown(CanvasRailEntry.SELECTED_ONLY)
	if (display.isEmpty() && !focus && aids == null) return
	var aidsMenu by remember { mutableStateOf(false) }
	// An open settings menu keeps the rail open under it.
	CanvasOptionsRail(modifier.tutorialTarget(TutorialTargetId.VIEW_OPTIONS_BAR), pinned = aidsMenu) {
		for (entry in display) {
			val toggle = canvasViewToggle(entry, options, skinWeights)
			RailToggle(
				label = toggle.label,
				isChecked = toggle.checked,
				icon = toggle.icon,
				onClick = { if (entry == CanvasRailEntry.SKIN_WEIGHTS) onSkinWeightsChange(!skinWeights) else apply(toggle.toggled()) },
			)
		}
		if (focus) {
			if (display.isNotEmpty()) RailDivider()
			val toggle = canvasViewToggle(CanvasRailEntry.SELECTED_ONLY, options, skinWeights)
			RailToggle(toggle.label, toggle.checked, toggle.icon) { apply(toggle.toggled()) }
		}
		if (aids != null) {
			if (display.isNotEmpty() || focus) RailDivider()
			val a = options.aids
			fun applyAids(updated: io.github.psd2live.ui.CanvasAidOptions) = apply(options.copy(aids = updated))
			if (shown(CanvasRailEntry.GRID)) RailItem(tr("canvas.aids.grid"), a.showGrid,
				keyLabel = aids.keyLabel(ShortcutAction.TOGGLE_GRID).orEmpty(),
				icon = { IconCanvasGrid(it) }, onClick = { applyAids(a.copy(showGrid = !a.showGrid)) })
			if (shown(CanvasRailEntry.GUIDES)) RailItem(tr("canvas.aids.guides"), a.showGuides,
				keyLabel = aids.keyLabel(ShortcutAction.TOGGLE_GUIDES).orEmpty(),
				icon = { IconCanvasGuides(it) }, onClick = { applyAids(a.copy(showGuides = !a.showGuides)) })
			Box {
				RailItem(tr("canvas.aids.settings"), aidsMenu, icon = { IconAidSettings(it) }, onClick = { aidsMenu = !aidsMenu })
				FloatingMenu(expanded = aidsMenu, onDismiss = { aidsMenu = false; aids.onMenuClosed() }, width = 260.dp,
					alignment = Alignment.BottomEnd, rise = true) {
					CanvasAidsMenuContent(a, ::applyAids, aids.guideCount, aids.onClearGuides, aids.onRestoreGuides)
				}
			}
		}
	}
}

/** The rail's own groups after the display toggles: the selection focus, then the grid and guides. */
private val RAIL_FOCUS_AND_AIDS = setOf(CanvasRailEntry.SELECTED_ONLY, CanvasRailEntry.GRID, CanvasRailEntry.GUIDES)

/** One display toggle as the rail and the View menu show it: its label, state and icon, and the options it sets. */
internal class CanvasViewToggle(
	val label: String,
	val checked: Boolean,
	val icon: @Composable (Color) -> Unit,
	val toggled: () -> TabViewOptions,
)

/**
 * [entry]'s toggle over [options]. [skinWeights] is the edit canvas's own state rather than a view option, so its
 * [CanvasViewToggle.toggled] leaves the options as they are.
 */
@Composable
internal fun canvasViewToggle(entry: CanvasRailEntry, options: TabViewOptions, skinWeights: Boolean): CanvasViewToggle {
	val o = options
	val a = o.aids
	return when (entry) {
		CanvasRailEntry.TEXTURE -> CanvasViewToggle(tr("canvas.visibility.texture"), o.showTexture,
			{ IconTextureView(tint = it, modifier = Modifier.size(14.dp)) }) { o.copy(showTexture = !o.showTexture) }
		CanvasRailEntry.SOURCE_PIXELS -> CanvasViewToggle(tr("canvas.visibility.sourcePixels"), o.sourcePixels,
			{ IconPixelSource(source = o.sourcePixels, tint = it, modifier = Modifier.size(14.dp)) }) { o.copy(sourcePixels = !o.sourcePixels) }
		CanvasRailEntry.REFERENCE -> CanvasViewToggle(tr("canvas.visibility.reference"), o.showReference,
			{ IconReferenceImage(tint = it) }) { o.copy(showReference = !o.showReference) }
		CanvasRailEntry.REFERENCE_LAYERS_OVER -> CanvasViewToggle(tr("canvas.visibility.referenceLayersOver"), o.referenceLayersOver,
			{ IconReferenceImage(tint = it) }) { o.copy(referenceLayersOver = !o.referenceLayersOver) }
		CanvasRailEntry.DENSITY -> CanvasViewToggle(tr("canvas.visibility.density"), o.densityHeatmap,
			{ io.github.psd2live.ui.views.texture.IconHeatmap() }) { o.copy(densityHeatmap = !o.densityHeatmap) }
		CanvasRailEntry.MESH -> CanvasViewToggle(tr("canvas.visibility.mesh"), o.showMesh,
			{ IconMeshWireframe(tint = it, modifier = Modifier.size(14.dp)) }) { o.copy(showMesh = !o.showMesh) }
		CanvasRailEntry.WARP -> CanvasViewToggle(tr("canvas.visibility.warp"), o.showWarp,
			{ IconWarpDeformer(tint = it, modifier = Modifier.size(14.dp)) }) { o.copy(showWarp = !o.showWarp) }
		CanvasRailEntry.ROTATION -> CanvasViewToggle(tr("canvas.visibility.rotation"), o.showRotation,
			{ IconRotationDeformer(tint = it, modifier = Modifier.size(14.dp)) }) { o.copy(showRotation = !o.showRotation) }
		CanvasRailEntry.PATHS -> CanvasViewToggle(tr("canvas.visibility.paths"), o.showDeformPaths,
			{ IconDeformPath(tint = it, modifier = Modifier.size(14.dp)) }) { o.copy(showDeformPaths = !o.showDeformPaths) }
		CanvasRailEntry.PATH_WIDTH -> CanvasViewToggle(tr("canvas.information.pathWidth"), o.pathShowWidth,
			{ IconPathWidth(tint = it) }) { o.copy(pathShowWidth = !o.pathShowWidth) }
		CanvasRailEntry.PATH_HARDNESS -> CanvasViewToggle(tr("canvas.information.pathHardness"), o.pathShowHardness,
			{ IconPathHardness(tint = it) }) { o.copy(pathShowHardness = !o.pathShowHardness) }
		CanvasRailEntry.SKELETON -> CanvasViewToggle(tr("canvas.visibility.skeleton"), o.showSkeleton,
			{ IconSkeleton(tint = it, modifier = Modifier.size(14.dp)) }) { o.copy(showSkeleton = !o.showSkeleton) }
		CanvasRailEntry.SKIN_WEIGHTS -> CanvasViewToggle(tr("skeleton.pose.weights"), skinWeights, { IconSkinWeights(tint = it) }) { o }
		CanvasRailEntry.SELECTED_ONLY -> CanvasViewToggle(tr("canvas.information.selectedOnly"), o.filterSelectedOnly,
			{ IconSelectedOnly(tint = it, modifier = Modifier.size(14.dp)) }) { o.copy(filterSelectedOnly = !o.filterSelectedOnly) }
		CanvasRailEntry.GRID -> CanvasViewToggle(tr("canvas.aids.grid"), a.showGrid, { IconCanvasGrid(it) }) {
			o.copy(aids = a.copy(showGrid = !a.showGrid))
		}
		CanvasRailEntry.GUIDES -> CanvasViewToggle(tr("canvas.aids.guides"), a.showGuides, { IconCanvasGuides(it) }) {
			o.copy(aids = a.copy(showGuides = !a.showGuides))
		}
	}
}

/**
 * The canvas's View menu: every per-tab display toggle, the single place for the ones that are not pinned to the
 * display rail. A toggle's pin puts it on the rail or takes it off ([pins]); the rail still shows a pinned toggle only
 * while the canvas has something for it to show.
 *
 * Sections: the canvas and model, the selection and focus (edit canvases), the annotations and canvas aids.
 * Path guides, the skeleton and the selection chrome are edit-canvas overlays, so a preview canvas leaves them out.
 */
@Composable
internal fun CanvasViewMenuItems(
	options: TabViewOptions,
	onOptionsChange: (TabViewOptions) -> Unit,
	onDismiss: () -> Unit,
	content: CanvasViewContent,
	pins: Set<CanvasRailEntry>,
	onPinsChange: (Set<CanvasRailEntry>) -> Unit,
	skinWeights: Boolean,
	onSkinWeightsChange: (Boolean) -> Unit,
	onReset: () -> Unit,
) {
	val edit = content.edit
	fun apply(updated: TabViewOptions) {
		onOptionsChange(updated.normalized())
		onDismiss()
	}
	@Composable
	fun Entry(entry: CanvasRailEntry, enabled: Boolean = true, isChild: Boolean = false, isLastChild: Boolean = false) {
		val toggle = canvasViewToggle(entry, options, skinWeights)
		val pinned = entry in pins
		AppMenuItem(
			text = toggle.label,
			icon = toggle.icon,
			isChecked = toggle.checked,
			enabled = enabled,
			isChild = isChild,
			isLastChild = isLastChild,
			trailing = {
				CompactIconButton(
					onClick = { onPinsChange(if (pinned) pins - entry else pins + entry) },
					tooltip = tr(if (pinned) "canvas.rail.unpin" else "canvas.rail.pin"),
					size = 18.dp,
				) { IconPin(pinned, tint = if (pinned) LocalToolColors.current.accent else LocalToolColors.current.textDisabled) }
			},
			onClick = {
				if (entry == CanvasRailEntry.SKIN_WEIGHTS) { onSkinWeightsChange(!skinWeights); onDismiss() } else apply(toggle.toggled())
			},
		)
	}
	@Composable
	fun Plain(label: String, checked: Boolean, icon: @Composable (Color) -> Unit, updated: () -> TabViewOptions) =
		AppMenuItem(text = label, icon = icon, isChecked = checked, onClick = { apply(updated()) })

	AppMenuHeader(tr("menu.view.category.canvas"))
	Entry(CanvasRailEntry.TEXTURE)
	if (edit) Entry(CanvasRailEntry.SOURCE_PIXELS)
	if (edit && content.reference) Entry(CanvasRailEntry.REFERENCE)
	if (edit && content.referenceLayers) Entry(CanvasRailEntry.REFERENCE_LAYERS_OVER)
	if (edit) Entry(CanvasRailEntry.DENSITY)
	Entry(CanvasRailEntry.MESH)
	Entry(CanvasRailEntry.WARP)
	Entry(CanvasRailEntry.ROTATION)
	if (edit) {
		Entry(CanvasRailEntry.PATHS)
		// A path's width and hardness hang off the paths: disabled, not hidden, while they are off, so the pair never
		// jumps out of the menu and their remembered state stays in sight.
		Entry(CanvasRailEntry.PATH_WIDTH, enabled = options.showDeformPaths, isChild = true)
		Entry(CanvasRailEntry.PATH_HARDNESS, enabled = options.showDeformPaths, isChild = true, isLastChild = true)
		Entry(CanvasRailEntry.SKELETON)
		if (content.skeleton) Entry(CanvasRailEntry.SKIN_WEIGHTS)

		AppMenuSeparator()
		AppMenuHeader(tr("menu.view.category.selection"))
		Plain(tr("canvas.information.selectionBounds"), options.showSelectionBounds, { IconSelectionBounds(tint = it) }) {
			options.copy(showSelectionBounds = !options.showSelectionBounds)
		}
		Plain(tr("canvas.information.contextualWarp"), options.contextualWarp, { IconContextualWarp(tint = it) }) {
			options.copy(contextualWarp = !options.contextualWarp)
		}
		Entry(CanvasRailEntry.SELECTED_ONLY)
		Plain(tr("canvas.visibility.dimUnselected"), options.dimUnselected, { IconDimUnselected(tint = it) }) {
			options.copy(dimUnselected = !options.dimUnselected)
		}
	}

	AppMenuSeparator()
	AppMenuHeader(tr("menu.view.category.overlays"))
	Plain(tr("canvas.information.names"), options.warpShowNames, { IconWarpShowNames(tint = it) }) {
		options.copy(warpShowNames = !options.warpShowNames)
	}
	Plain(tr("canvas.information.indices"), options.warpShowIndices, { IconWarpShowIndices(tint = it) }) {
		options.copy(warpShowIndices = !options.warpShowIndices)
	}
	if (edit) {
		Entry(CanvasRailEntry.GRID)
		Entry(CanvasRailEntry.GUIDES)
	}

	AppMenuSeparator()
	AppMenuItem(
		text = tr("tab.resetView"),
		icon = { IconReset(tint = it, modifier = Modifier.size(14.dp)) },
		indentCheckSpace = true,
		onClick = onReset,
	)
	AppMenuItem(
		text = tr("canvas.rail.resetPins"),
		indentCheckSpace = true,
		enabled = pins != CanvasRailEntry.Defaults,
		onClick = { onPinsChange(CanvasRailEntry.Defaults) },
	)
}

/** What the display rail's grid and guide rows need beyond the view options. */
internal class CanvasAidsRail(
	val guideCount: Int,
	val onClearGuides: () -> Unit,
	/** Puts back the guides the last clear removed; null when there are none to put back. */
	val onRestoreGuides: (() -> Unit)?,
	val keyLabel: (ShortcutAction) -> String?,
	/** Hands the focus back to the canvas. */
	val onMenuClosed: () -> Unit,
)

/** The reference image: a picture frame with a figure's head and shoulders. */
@Composable
internal fun IconReferenceImage(tint: Color, size: Dp = 14.dp) = GridIcon(Modifier.size(size), tint) {
	box(2f, 2f, 14f, 14f, 1.2f)
	ring(9f, 7.4f, 2.4f, 1.2f)
	arc(9f, 15.6f, 4.6f, 180f, 180f, 1.2f)
}

/** Atlas pixels: a coarse 2x2 grid; source pixels: a fine 4x4 grid. */
@Composable
internal fun IconPixelSource(source: Boolean, tint: Color, modifier: Modifier) = GridIcon(modifier, tint) {
	val cells = if (source) 4 else 2
	val side = 13.2f / cells
	for (y in 0 until cells) for (x in 0 until cells) {
		if ((x + y) % 2 == 0) fillBox(2.4f + x * side, 2.4f + y * side, side, side, 0f)
	}
	box(2.4f, 2.4f, 13.2f, 13.2f, 0.6f, ICON_FINE)
}

/** Skin weights: a bone over a wash that fades from it. */
@Composable
internal fun IconSkinWeights(tint: Color) = GridIcon(Modifier.size(14.dp), tint) {
	fillBox(2.4f, 9.6f, 4.4f, 6f, 0.8f, tint.copy(alpha = 0.9f))
	fillBox(6.8f, 9.6f, 4.4f, 6f, 0f, tint.copy(alpha = 0.55f))
	fillBox(11.2f, 9.6f, 4.4f, 6f, 0.8f, tint.copy(alpha = 0.25f))
	line(3.4f, 6f, 14.6f, 3f)
	dot(3.4f, 6f, 1.8f)
}
