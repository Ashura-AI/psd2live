package io.github.psd2live.ui.views

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactSlider
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.components.GridIcon
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.IconAdd
import io.github.psd2live.ui.components.IconCheck
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.IconFolder
import io.github.psd2live.ui.components.IconInfo
import io.github.psd2live.ui.components.IconPhysics
import io.github.psd2live.ui.components.IconPlay
import io.github.psd2live.ui.components.IconSkeleton
import io.github.psd2live.ui.components.document
import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.state.HairMode
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.RecentFile
import io.github.psd2live.ui.state.RecentFileKind
import io.github.psd2live.ui.state.ShortcutAction
import io.github.psd2live.ui.state.StartPresetChoices
import io.github.psd2live.ui.state.StartQuickPreset
import io.github.psd2live.ui.state.StartSection
import io.github.psd2live.ui.state.WorkspacePreset
import io.github.psd2live.ui.state.rememberExistingRecentFiles
import io.github.psd2live.ui.state.rememberRecentFileDetails
import io.github.psd2live.ui.state.validLayoutCanvasSize
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.tutorial.TutorialId
import io.github.psd2live.ui.views.texture.AccentButton
import io.github.psd2live.ui.views.texture.AccentSplitButton
import io.github.psd2live.ui.views.texture.BarTooltip
import io.github.psd2live.ui.views.texture.FloatingMenu
import io.github.psd2live.ui.views.texture.FloatingMenuDivider
import io.github.psd2live.ui.views.texture.FloatingMenuRadio
import io.github.psd2live.ui.views.texture.FloatingMenuRow
import io.github.psd2live.ui.views.texture.FloatingMenuSection
import io.github.psd2live.ui.theme.Motion
import io.github.psd2live.ui.theme.handCursor
import java.awt.Cursor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Below this width the inspector leaves the row and opens as a drawer over the content. */
private val InspectorDockWidth = 900.dp
private val PanelGap = 5.dp

/** Canvas sizes offered for a new Layout project, with the `start.layout.size.<key>` that names each. */
private val LayoutCanvasSizes = listOf(
	Triple("square1k", 1024, 1024),
	Triple("square2k", 2048, 2048),
	Triple("square4k", 4096, 4096),
	Triple("portrait", 2048, 4096),
	Triple("landscape", 4096, 2048),
)

/** The start page's updates: the tutorial each one opens and its `canvas.start.update.<key>` strings. */
internal val StartCanvasUpdates = listOf(
	TutorialId.TOOL_DETAILS to "tools",
	TutorialId.DEFORM_MODE to "deform",
	TutorialId.WORKSPACE to "texture",
	TutorialId.PROJECT_HISTORY to "regenerate",
	TutorialId.LIVE2D_BRIDGE to "export",
)

private val DateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

/** The canvas size typed for a new Layout project, shared by the content's preview and the inspector's fields. */
@Stable
private class LayoutDraft {
	var width by mutableStateOf("2048")
	var height by mutableStateOf("2048")
	val w: Int? get() = width.trim().toIntOrNull()
	val h: Int? get() = height.trim().toIntOrNull()
	val valid: Boolean get() = validLayoutCanvasSize(w, h)

	fun set(width: Int, height: Int) {
		this.width = width.toString()
		this.height = height.toString()
	}
}

/** What the page's lists have picked while it shows. */
@Stable
private class StartSelection {
	/** The recent PSD the viewport previews; the generate action takes it, or asks for a file without one. */
	var psdPath by mutableStateOf<String?>(null)
	var recentPath by mutableStateOf<String?>(null)
	var update by mutableStateOf(StartCanvasUpdates.first().first)
	var recentFilter by mutableStateOf("")
	var showProjects by mutableStateOf(true)
	var showPsds by mutableStateOf(true)
	var recentGrid by mutableStateOf(true)
	val open = mutableStateMapOf<String, Boolean>()
}

/** The panel sizes the splitters drag, in dp, kept in the preferences when a drag ends. */
@Stable
private class StartPanelSizes {
	var sidebar by mutableStateOf(AppSettings.startPanelSize("sidebar", 176f, SIDEBAR))
	var inspector by mutableStateOf(AppSettings.startPanelSize("inspector", 272f, INSPECTOR))
	var strip by mutableStateOf(AppSettings.startPanelSize("strip", 164f, STRIP))
	var tile by mutableStateOf(AppSettings.startPanelSize("tile", 104f, TILE))
	var card by mutableStateOf(AppSettings.startPanelSize("card", 168f, CARD))

	fun save() {
		AppSettings.setStartPanelSize("sidebar", sidebar)
		AppSettings.setStartPanelSize("inspector", inspector)
		AppSettings.setStartPanelSize("strip", strip)
		AppSettings.setStartPanelSize("tile", tile)
		AppSettings.setStartPanelSize("card", card)
	}

	companion object {
		val SIDEBAR = 140f..320f
		val INSPECTOR = 220f..480f
		val STRIP = 96f..380f
		val TILE = 64f..200f
		val CARD = 120f..300f
	}
}

/**
 * The start page, shown instead of the dock while the strip's Start tab is active. Like a DCC's project browser it is
 * panels with splitters between them: the sidebar picks a section, the middle shows it, the inspector sets what the
 * section's action will do, and the bar beneath carries that action. Generation stays one gesture: a PSD dropped
 * anywhere on the window, or a recent PSD double-clicked, is generated with the presets the inspector shows.
 */
@Composable
internal fun StartWorkspace(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	filesDragging: Boolean,
	onOpenTutorialCatalog: (() -> Unit)?,
	onStartTutorial: ((TutorialId) -> Unit)?,
	onOpenProject: (() -> Unit)?,
	onOpenPsd: (() -> Unit)?,
	onOpenCmo3: (() -> Unit)?,
	onRevealFile: (String) -> Unit,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val enabled = !state.isBusy
	val recent = rememberExistingRecentFiles(state.recentFiles)
	val recentPsds = recent.orEmpty().filter { it.kind == RecentFileKind.PSD }
	val layout = remember { LayoutDraft() }
	val selection = remember { StartSelection() }
	val sizes = remember { StartPanelSizes() }
	val section = viewModel.startSection
	val focus = remember { FocusRequester() }
	LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
	// The recent list opens on its newest file, so the inspector and the action have something to show.
	LaunchedEffect(recent) {
		// A first launch has nothing recent to show: it opens where a PSD can be dropped instead.
		if (recent != null) viewModel.settleStartLanding(recent.isNotEmpty())
		if (recent != null && recent.none { it.path == selection.recentPath }) selection.recentPath = recent.firstOrNull()?.path
		if (recent != null && recentPsds.none { it.path == selection.psdPath }) selection.psdPath = null
	}
	val selectedRecent = recent.orEmpty().firstOrNull { it.path == selection.recentPath }
	val selectedPsd = recentPsds.firstOrNull { it.path == selection.psdPath }
	val generate: () -> Unit = {
		if (selectedPsd != null) viewModel.openRecentFile(selectedPsd.path) else onOpenPsd?.invoke()
	}
	val primary: () -> Unit = {
		when (section) {
			StartSection.GENERATE -> generate()
			StartSection.LAYOUT -> if (layout.valid) viewModel.createLayoutProject(layout.w!!, layout.h!!)
			StartSection.CMO3 -> onOpenCmo3?.invoke()
			StartSection.RECENT -> selectedRecent?.let { viewModel.openRecentFile(it.path) }
			StartSection.UPDATES -> onStartTutorial?.invoke(selection.update)
		}
	}

	BoxWithConstraints(
		modifier
			.fillMaxSize()
			.background(colors.windowBackground)
			.focusRequester(focus)
			.focusable()
			// Fields take Enter first: a typed canvas size reaches here only once its field lets it go.
			.onKeyEvent { event ->
				if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter) && enabled) {
					primary()
					true
				} else false
			}
			.padding(top = 4.dp),
	) {
		val docked = maxWidth >= InspectorDockWidth
		var drawer by remember { mutableStateOf(false) }
		val inspector: @Composable (Modifier) -> Unit = { panelModifier ->
			StartInspector(section, viewModel, layout, selection, selectedRecent, enabled, onRevealFile, panelModifier)
		}
		Column(Modifier.fillMaxSize()) {
			Row(Modifier.weight(1f).fillMaxWidth()) {
				StartSidebar(
					section = section,
					recentCount = recent?.size,
					enabled = enabled,
					openProjectShortcut = state.keymap.labelFor(ShortcutAction.OPEN_PROJECT),
					onSelect = viewModel::setStartSection,
					onOpenProject = onOpenProject,
					onQuickStart = onOpenTutorialCatalog,
					modifier = Modifier.width(sizes.sidebar.dp).fillMaxHeight(),
				)
				Splitter(horizontal = true, onDrag = { sizes.sidebar = (sizes.sidebar + it.value).coerceIn(StartPanelSizes.SIDEBAR) }, onDragEnd = sizes::save)
				Box(Modifier.weight(1f).fillMaxHeight()) {
					val toggle: (@Composable RowScope.() -> Unit)? = if (docked) null else ({
						PanelHeaderToggle(tr("start.inspector"), drawer) { drawer = !drawer }
					})
					when (section) {
						StartSection.GENERATE -> Column(Modifier.fillMaxSize()) {
							GenerateViewport(
								file = selectedPsd,
								choices = viewModel.startChoices,
								enabled = enabled && onOpenPsd != null,
								dragging = filesDragging,
								shortcut = state.keymap.labelFor(ShortcutAction.OPEN_PSD),
								onOpenPsd = { onOpenPsd?.invoke() },
								onGenerate = generate,
								onClear = { selection.psdPath = null },
								trailing = toggle,
								modifier = Modifier.weight(1f).fillMaxWidth(),
							)
							Splitter(horizontal = false, onDrag = { sizes.strip = (sizes.strip - it.value).coerceIn(StartPanelSizes.STRIP) }, onDragEnd = sizes::save)
							RecentPsdStrip(
								files = recentPsds,
								selectedPath = selection.psdPath,
								tile = sizes.tile,
								enabled = enabled,
								onTile = { sizes.tile = it; sizes.save() },
								onSelect = { selection.psdPath = it },
								onGenerate = { viewModel.openRecentFile(it) },
								onBrowse = { onOpenPsd?.invoke() },
								modifier = Modifier.height(sizes.strip.dp).fillMaxWidth(),
							)
						}
						else -> StartPanel(sectionTitle(section), Modifier.fillMaxSize(), trailing = toggle) {
							when (section) {
								StartSection.LAYOUT -> LayoutContent(layout, enabled) { w, h -> viewModel.createLayoutProject(w, h) }
								StartSection.CMO3 -> Cmo3Content(enabled, onOpenCmo3)
								StartSection.RECENT -> RecentBrowser(recent, selection, sizes, enabled, viewModel)
								else -> UpdatesContent(selection, enabled, onStartTutorial)
							}
						}
					}
					// A file carried over the window lands wherever it is dropped; outside the viewport the page says so.
					if (filesDragging && section != StartSection.GENERATE) DropHighlight(tr("start.drop.overlay", startPresetName(viewModel.startChoices)))
					if (!docked && drawer) {
						Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { drawer = false })
						inspector(Modifier.align(Alignment.TopEnd).padding(top = 26.dp, end = 4.dp, bottom = 4.dp).width(sizes.inspector.dp).fillMaxHeight())
					}
				}
				if (docked) {
					Splitter(horizontal = true, onDrag = { sizes.inspector = (sizes.inspector - it.value).coerceIn(StartPanelSizes.INSPECTOR) }, onDragEnd = sizes::save)
					inspector(Modifier.width(sizes.inspector.dp).fillMaxHeight())
				}
			}
			StartActionBar(section, viewModel, layout, selection, selectedRecent, selectedPsd, enabled, state, onOpenPsd, onOpenCmo3, onStartTutorial, primary)
		}
	}
}

private fun sectionTitle(section: StartSection) = when (section) {
	StartSection.GENERATE -> tr("start.nav.generate")
	StartSection.LAYOUT -> tr("start.nav.layout")
	StartSection.CMO3 -> tr("start.nav.cmo3")
	StartSection.RECENT -> tr("start.nav.recent")
	StartSection.UPDATES -> tr("start.nav.updates")
}

/** The quick set [choices] match, or null for a custom set. */
private fun matchedPreset(choices: StartPresetChoices) = StartQuickPreset.entries.firstOrNull { it.choices == choices }

/** The name of the preset set [choices] are: a quick set's, or "Custom". */
internal fun startPresetName(choices: StartPresetChoices) = matchedPreset(choices)?.let { tr(it.key) } ?: tr("start.quick.custom")

// ---------------------------------------------------------------------------------------------------------------------
// Panels, splitters and sidebar
// ---------------------------------------------------------------------------------------------------------------------

/** A panel as the dock draws one: a rounded frame on the panel background under a 22 dp title bar. */
@Composable
private fun StartPanel(
	title: String,
	modifier: Modifier = Modifier,
	trailing: (@Composable RowScope.() -> Unit)? = null,
	content: @Composable ColumnScope.() -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val shape = RoundedCornerShape(3.dp)
	Column(modifier.clip(shape).background(colors.panelBackground).border(0.5.dp, colors.divider, shape)) {
		Row(
			Modifier.fillMaxWidth().height(22.dp).background(colors.panelElevated)
				.drawBehind { drawLine(colors.divider, Offset(0f, size.height), Offset(size.width, size.height), 0.5.dp.toPx()) }
				.padding(horizontal = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			Text(title, style = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium), color = colors.textPrimary,
				maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
			trailing?.invoke(this)
		}
		content()
	}
}

/** The gap between two panels, dragged to resize them: across for [horizontal], else up and down. */
@Composable
private fun Splitter(horizontal: Boolean, onDrag: (Dp) -> Unit, onDragEnd: () -> Unit) {
	val colors = LocalToolColors.current
	val density = LocalDensity.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	var dragging by remember { mutableStateOf(false) }
	val drag by rememberUpdatedState(onDrag)
	val end by rememberUpdatedState(onDragEnd)
	val line by animateColorAsState(when {
		dragging -> colors.accent
		hovered -> colors.borderHover
		else -> Color.Transparent
	}, tween(Motion.Instant))
	Box(
		(if (horizontal) Modifier.width(PanelGap).fillMaxHeight() else Modifier.height(PanelGap).fillMaxWidth())
			.hoverable(interaction)
			.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(if (horizontal) Cursor.E_RESIZE_CURSOR else Cursor.N_RESIZE_CURSOR)))
			.pointerInput(horizontal) {
				detectDragGestures(
					onDragStart = { dragging = true },
					onDragEnd = { dragging = false; end() },
					onDragCancel = { dragging = false; end() },
				) { change, amount ->
					change.consume()
					drag(with(density) { (if (horizontal) amount.x else amount.y).toDp() })
				}
			}
			.drawBehind {
				val stroke = 1.dp.toPx()
				if (horizontal) drawLine(line, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), stroke)
				else drawLine(line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), stroke)
			},
	)
}

@Composable
private fun PanelHeaderToggle(label: String, active: Boolean, onClick: () -> Unit) {
	val colors = LocalToolColors.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	Text(
		label,
		fontSize = 10.5.sp,
		color = if (active) colors.selectionText else colors.textMuted,
		modifier = Modifier.clip(RoundedCornerShape(3.dp))
			.background(if (active) colors.selection else if (hovered) colors.controlHover else Color.Transparent)
			.hoverable(interaction)
			.clickable(interactionSource = interaction, indication = null, onClick = onClick)
			.handCursor()
			.padding(horizontal = 6.dp, vertical = 1.dp),
	)
}

@Composable
private fun StartSidebar(
	section: StartSection,
	recentCount: Int?,
	enabled: Boolean,
	openProjectShortcut: String?,
	onSelect: (StartSection) -> Unit,
	onOpenProject: (() -> Unit)?,
	onQuickStart: (() -> Unit)?,
	modifier: Modifier,
) {
	StartPanel(tr("start.page.tab"), modifier) {
		Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
			NavItem(tr("start.nav.openProject"), false, trailing = openProjectShortcut, enabled = enabled && onOpenProject != null,
				onClick = { onOpenProject?.invoke() }) { IconFolder(modifier = Modifier.size(13.dp), tint = it) }
			NavItem(tr("start.nav.recent"), section == StartSection.RECENT, trailing = recentCount?.toString(),
				onClick = { onSelect(StartSection.RECENT) }) { WorkspacePresetIcon(WorkspacePreset.HISTORY, it, Modifier.size(13.dp)) }
			NavGroup(tr("start.nav.group.new"))
			NavItem(tr("start.nav.generate"), section == StartSection.GENERATE, onClick = { onSelect(StartSection.GENERATE) }) {
				WorkspacePresetIcon(WorkspacePreset.EDIT, it, Modifier.size(13.dp))
			}
			NavItem(tr("start.nav.layout"), section == StartSection.LAYOUT, badge = true, onClick = { onSelect(StartSection.LAYOUT) }) {
				WorkspacePresetIcon(WorkspacePreset.LAYOUT, it, Modifier.size(13.dp))
			}
			NavItem(tr("start.nav.cmo3"), section == StartSection.CMO3, onClick = { onSelect(StartSection.CMO3) }) {
				IconDocument(Modifier.size(13.dp), it)
			}
			NavGroup(tr("start.nav.group.learn"))
			NavItem(tr("canvas.start.quickStart"), false, enabled = onQuickStart != null,
				onClick = { onQuickStart?.invoke() }) { IconPlay(modifier = Modifier.size(12.dp), tint = it) }
			NavItem(tr("start.nav.updates"), section == StartSection.UPDATES, onClick = { onSelect(StartSection.UPDATES) }) {
				IconInfo(modifier = Modifier.size(13.dp), tint = it)
			}
		}
	}
}

@Composable
private fun NavGroup(text: String) {
	Text(
		text,
		style = LocalToolTypography.current.caption.copy(fontSize = 10.sp),
		color = LocalToolColors.current.textMuted,
		modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 3.dp),
	)
}

/** A sidebar row: the tree's selection colours with the menus' accent edge on the selected section. */
@Composable
private fun NavItem(
	label: String,
	selected: Boolean,
	trailing: String? = null,
	enabled: Boolean = true,
	badge: Boolean = false,
	onClick: () -> Unit,
	icon: @Composable (Color) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val tint = when {
		!enabled -> colors.textDisabled
		selected -> colors.selectionText
		else -> colors.textPrimary
	}
	Row(
		Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 4.dp)
			.clip(RoundedCornerShape(3.dp))
			.background(when {
				selected -> colors.selection
				hovered && enabled -> colors.controlHover.copy(alpha = 0.45f)
				else -> Color.Transparent
			})
			.hoverable(interaction)
			.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
			.handCursor(enabled),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(Modifier.width(2.dp).height(14.dp).clip(RoundedCornerShape(1.dp)).background(if (selected) colors.accent else Color.Transparent))
		Spacer(Modifier.width(8.dp))
		Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) { icon(if (selected) colors.selectionText else if (enabled) colors.textMuted else colors.textDisabled) }
		Spacer(Modifier.width(8.dp))
		Text(label, style = typography.body.copy(fontSize = 11.5.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
			color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
		if (badge) {
			Spacer(Modifier.width(6.dp))
			NewBadge()
		}
		Spacer(Modifier.weight(1f))
		trailing?.takeIf { it.isNotBlank() }?.let {
			Text(it, style = typography.monoSmall.copy(fontSize = 10.sp), color = colors.textMuted, modifier = Modifier.padding(end = 6.dp))
		}
	}
}

// ---------------------------------------------------------------------------------------------------------------------
// New badge
// ---------------------------------------------------------------------------------------------------------------------

/** "New", on what a recent release added, in the highlight colour the history uses for what is current. */
@Composable
private fun NewBadge(modifier: Modifier = Modifier) {
	val colors = LocalToolColors.current
	Text(
		tr("start.badge.new"),
		fontSize = 8.sp,
		fontWeight = FontWeight.Bold,
		color = colors.highlight,
		maxLines = 1,
		modifier = modifier.clip(RoundedCornerShape(3.dp)).background(colors.highlightContainer).padding(horizontal = 3.dp),
	)
}

// ---------------------------------------------------------------------------------------------------------------------
// Generate: the viewport and the recent PSD strip
// ---------------------------------------------------------------------------------------------------------------------

/**
 * The PSD about to be generated, large on a checkerboard like the canvas, or the drop target while none is picked.
 * The whole viewport takes a dropped file; while one is carried over the window it says what releasing will do.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GenerateViewport(
	file: RecentFile?,
	choices: StartPresetChoices,
	enabled: Boolean,
	dragging: Boolean,
	shortcut: String?,
	onOpenPsd: () -> Unit,
	onGenerate: () -> Unit,
	onClear: () -> Unit,
	trailing: (@Composable RowScope.() -> Unit)?,
	modifier: Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val details = file?.let { rememberRecentFileDetails(it) }
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	StartPanel(
		title = file?.name ?: tr("start.viewport"),
		modifier = modifier,
		trailing = {
			if (details?.canvasWidth != null && details.canvasHeight != null) {
				Text("${details.canvasWidth} × ${details.canvasHeight}", style = typography.monoSmall.copy(fontSize = 10.sp), color = colors.textMuted)
			}
			if (file != null) PanelHeaderToggle(tr("start.viewport.clear"), false, onClear)
			trailing?.invoke(this)
		},
	) {
		BoxWithConstraints(
			Modifier.weight(1f).fillMaxWidth()
				.background(colors.inputBackground)
				.hoverable(interaction)
				.combinedClickable(interactionSource = interaction, indication = null, enabled = enabled,
					onClick = { if (file == null) onOpenPsd() }, onDoubleClick = { if (file != null) onGenerate() else onOpenPsd() })
				.handCursor(enabled),
			contentAlignment = Alignment.Center,
		) {
			val image = details?.thumbnail
			when {
				image != null -> {
					// The picture fitted into the viewport, the checkerboard under the picture only.
					val ratio = image.width.toFloat() / image.height.coerceAtLeast(1)
					val roomW = maxWidth - 56.dp
					val roomH = maxHeight - 64.dp
					val w = minOf(roomW, roomH * ratio).coerceAtLeast(1.dp)
					val h = (w / ratio).coerceAtLeast(1.dp)
					Box(Modifier.size(w, h).drawBehind { drawChecker(Offset.Zero, size, colors.checkerLight, colors.checkerDark, 8.dp.toPx()) }
						.border(0.5.dp, colors.border)) {
						Image(image, contentDescription = file?.name, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
					}
				}
				file != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
					IconDocument(Modifier.size(28.dp), colors.textMuted)
					Text(tr("start.viewport.noPreview"), style = typography.caption.copy(fontSize = 11.sp), color = colors.textMuted)
				}
				else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
					IconDocument(Modifier.size(30.dp), if (hovered && enabled) colors.accent else colors.textMuted)
					Spacer(Modifier.height(2.dp))
					Text(tr("start.generate.drop", startPresetName(choices)), style = typography.body.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
						color = colors.textPrimary)
					Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						Text(tr("start.generate.browse"), style = typography.caption.copy(fontSize = 11.sp),
							color = if (hovered && enabled) colors.accent else colors.textMuted)
						shortcut?.takeIf { it.isNotBlank() }?.let { Text(it, style = typography.monoSmall.copy(fontSize = 10.sp), color = colors.textDisabled) }
					}
				}
			}
			Text(
				tr(if (file != null) "start.viewport.hint.file" else "start.viewport.hint"),
				style = typography.caption.copy(fontSize = 10.5.sp),
				color = colors.textMuted,
				modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 8.dp),
			)
			if (dragging) DropHighlight(tr("start.drop.overlay", startPresetName(choices)))
		}
	}
}

/** What a file carried over the page will do on release, over the area it covers. */
@Composable
private fun DropHighlight(text: String) {
	val colors = LocalToolColors.current
	Box(
		Modifier.fillMaxSize().padding(6.dp).clip(RoundedCornerShape(4.dp))
			.background(colors.accent.copy(alpha = 0.10f))
			.drawBehind {
				val inset = 1.dp.toPx()
				drawRoundRect(colors.accent, Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2), CornerRadius(4.dp.toPx()),
					style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 4.dp.toPx()))))
			},
		contentAlignment = Alignment.Center,
	) {
		Text(text, style = LocalToolTypography.current.body.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium), color = colors.accent,
			modifier = Modifier.padding(16.dp))
	}
}

/** The recent PSDs as a film strip: a click previews one in the viewport, a double click generates it. */
@Composable
private fun RecentPsdStrip(
	files: List<RecentFile>,
	selectedPath: String?,
	tile: Float,
	enabled: Boolean,
	onTile: (Float) -> Unit,
	onSelect: (String) -> Unit,
	onGenerate: (String) -> Unit,
	onBrowse: () -> Unit,
	modifier: Modifier,
) {
	val colors = LocalToolColors.current
	StartPanel(tr("start.generate.recent"), modifier, trailing = {
		CompactSlider(value = tile, onValueChange = onTile, valueRange = StartPanelSizes.TILE, height = 12.dp, modifier = Modifier.width(72.dp))
	}) {
		Row(Modifier.weight(1f).fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp)) {
			files.forEach { file ->
				RecentTile(file, file.path == selectedPath, tile.dp, enabled, onClick = { onSelect(file.path) }, onDoubleClick = { onGenerate(file.path) })
			}
			AddTile(tile.dp, enabled, onBrowse, if (files.isEmpty()) tr("start.generate.recent.empty") else tr("start.generate.recent.add"))
		}
	}
}

/** The strip's last place, sized like its pictures: another PSD from the file picker. */
@Composable
private fun AddTile(size: Dp, enabled: Boolean, onClick: () -> Unit, label: String) {
	val colors = LocalToolColors.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val shape = RoundedCornerShape(5.dp)
	Column(
		Modifier.padding(3.dp).clip(shape)
			.background(if (hovered && enabled) colors.controlHover.copy(alpha = 0.35f) else Color.Transparent)
			.hoverable(interaction)
			.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
			.handCursor(enabled)
			.padding(4.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Box(Modifier.size(size, size * 0.75f).clip(RoundedCornerShape(3.dp)).background(colors.inputBackground), contentAlignment = Alignment.Center) {
			IconAdd(Modifier.size(14.dp), if (hovered && enabled) colors.accent else colors.textMuted)
		}
		Text(label, color = colors.textMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
			textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.width(size).padding(top = 3.dp))
	}
}

// ---------------------------------------------------------------------------------------------------------------------
// Layout
// ---------------------------------------------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LayoutContent(layout: LayoutDraft, enabled: Boolean, onCreate: (Int, Int) -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			CanvasSizePreview(layout.w, layout.h, layout.valid, Modifier.weight(1f).fillMaxHeight())
			Column(Modifier.width(200.dp).fillMaxHeight()) {
				SubHeader(tr("start.layout.sizes"), null)
				Spacer(Modifier.height(4.dp))
				Column(Modifier.fillMaxWidth().border(0.5.dp, colors.divider)) {
					LayoutCanvasSizes.forEach { (key, w, h) ->
						val interaction = remember { MutableInteractionSource() }
						val hovered by interaction.collectIsHoveredAsState()
						val selected = layout.w == w && layout.h == h
						Row(
							Modifier.fillMaxWidth().height(26.dp)
								.background(if (selected) colors.selection else if (hovered && enabled) colors.controlHover.copy(alpha = 0.35f) else Color.Transparent)
								.hoverable(interaction)
								.combinedClickable(interactionSource = interaction, indication = null, enabled = enabled,
									onClick = { layout.set(w, h) }, onDoubleClick = { layout.set(w, h); onCreate(w, h) })
								.handCursor(enabled)
								.padding(horizontal = 8.dp),
							verticalAlignment = Alignment.CenterVertically,
						) {
							Text(tr("start.layout.size.$key"), style = typography.body.copy(fontSize = 11.5.sp),
								color = if (selected) colors.selectionText else colors.textPrimary, modifier = Modifier.weight(1f))
							Text("$w × $h", style = typography.monoSmall.copy(fontSize = 10.sp), color = if (selected) colors.selectionText else colors.textMuted)
						}
					}
				}
				Spacer(Modifier.height(6.dp))
				Text(tr("start.layout.sizes.hint"), style = typography.caption.copy(fontSize = 10.sp, lineHeight = 14.sp), color = colors.textMuted)
			}
		}
		// The way through a Layout project, as one line under the preview.
		Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			listOf("import", "place", "generate").forEachIndexed { index, step ->
				if (index > 0) Text("›", fontSize = 11.sp, color = colors.textDisabled)
				Text("${index + 1}", style = typography.monoSmall.copy(fontSize = 10.sp), color = colors.accent)
				Text(tr("start.layout.step.$step"), fontSize = 11.sp, color = colors.textPrimary)
				Text(tr("start.layout.step.$step.desc"), fontSize = 10.sp, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f, fill = false))
			}
		}
	}
}

/** The canvas drawn to scale inside the panel, its size beneath, outlined in warning colour while it is out of range. */
@Composable
private fun CanvasSizePreview(width: Int?, height: Int?, valid: Boolean, modifier: Modifier) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Box(modifier.clip(RoundedCornerShape(3.dp)).background(colors.inputBackground).border(0.5.dp, colors.divider, RoundedCornerShape(3.dp))) {
		Box(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 40.dp).drawBehind {
			val w = (width ?: 0).coerceAtLeast(1).toFloat()
			val h = (height ?: 0).coerceAtLeast(1).toFloat()
			val scale = minOf(size.width / w, size.height / h)
			val rect = Size(w * scale, h * scale)
			val origin = Offset((size.width - rect.width) / 2f, (size.height - rect.height) / 2f)
			drawChecker(origin, rect, colors.checkerLight, colors.checkerDark, 8.dp.toPx())
			drawRect(if (valid) colors.accent else colors.warning, origin, rect, style = Stroke(1.dp.toPx()))
		})
		Text(
			if (width != null && height != null) "$width × $height" else "—",
			style = typography.monoSmall.copy(fontSize = 11.sp),
			color = if (valid) colors.textPrimary else colors.warning,
			modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
		)
	}
}

private fun DrawScope.drawChecker(origin: Offset, area: Size, light: Color, dark: Color, cell: Float) {
	drawRect(light, origin, area)
	var y = 0f
	var row = 0
	while (y < area.height) {
		var x = if (row % 2 == 0) 0f else cell
		while (x < area.width) {
			drawRect(dark, Offset(origin.x + x, origin.y + y), Size(minOf(cell, area.width - x), minOf(cell, area.height - y)))
			x += cell * 2
		}
		y += cell
		row++
	}
}

// ---------------------------------------------------------------------------------------------------------------------
// CMO3
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun Cmo3Content(enabled: Boolean, onOpenCmo3: (() -> Unit)?) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Column(Modifier.fillMaxSize()) {
		ViewportPrompt(
			title = tr("start.cmo3.choose"),
			subtitle = tr("start.cmo3.choose.desc"),
			enabled = enabled && onOpenCmo3 != null,
			onClick = { onOpenCmo3?.invoke() },
			modifier = Modifier.weight(1f).fillMaxWidth(),
		) { IconDocument(Modifier.size(30.dp), it) }
		Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.divider))
		Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
			Text(tr("start.cmo3.keeps"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted,
				modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
			listOf("parameters", "deformers", "textures", "physics", "presets").forEach { key ->
				Row(Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					if (key == "presets") IconClose(Modifier.size(8.dp), colors.textMuted) else IconCheck(Modifier.size(10.dp), colors.accent)
					Text(tr("start.cmo3.keeps.$key"), fontSize = 11.sp, color = if (key == "presets") colors.textMuted else colors.textPrimary)
				}
			}
		}
	}
}

// ---------------------------------------------------------------------------------------------------------------------
// Recent
// ---------------------------------------------------------------------------------------------------------------------

/**
 * The recent files as the materials panel shows its images: a panel toolbar with the search and the kind switches,
 * then a grid of thumbnails or a list. A click selects, a double click opens. The start page opens here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentBrowser(
	recent: List<RecentFile>?,
	selection: StartSelection,
	sizes: StartPanelSizes,
	enabled: Boolean,
	viewModel: PSD2LiveViewModel,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val shown = recent.orEmpty().filter { file ->
		(if (file.kind == RecentFileKind.PROJECT) selection.showProjects else selection.showPsds) &&
			(selection.recentFilter.isBlank() || file.name.contains(selection.recentFilter.trim(), ignoreCase = true))
	}
	Column(Modifier.fillMaxSize()) {
		val kinds = listOf(tr("canvas.start.kind.project"), tr("canvas.start.kind.psd"))
		PanelToolbar(labels = kinds, iconCount = 4, search = PanelSearch(selection.recentFilter, { selection.recentFilter = it }, tr("start.recent.filter")),
			reservedWidth = 96.dp) { labelsShown ->
			PanelToolButton(kinds[0], showLabel = labelsShown > 0, onClick = { selection.showProjects = !selection.showProjects },
				enabled = true, tooltip = tr("start.recent.showProjects"), active = selection.showProjects) {
				IconFolder(Modifier.size(12.dp), if (selection.showProjects) colors.accent else colors.textMuted)
			}
			PanelToolButton(kinds[1], showLabel = labelsShown > 1, onClick = { selection.showPsds = !selection.showPsds },
				enabled = true, tooltip = tr("start.recent.showPsds"), active = selection.showPsds) {
				IconDocument(Modifier.size(12.dp), if (selection.showPsds) colors.accent else colors.textMuted)
			}
			Spacer(Modifier.weight(1f))
			if (selection.recentGrid) CompactSlider(value = sizes.card, onValueChange = { sizes.card = it; sizes.save() },
				valueRange = StartPanelSizes.CARD, height = 12.dp, modifier = Modifier.width(72.dp))
			PanelToolButton(tr("start.recent.grid"), showLabel = false, onClick = { selection.recentGrid = true }, enabled = true,
				tooltip = tr("start.recent.grid"), active = selection.recentGrid) { GridGlyph(colors.textPrimary) }
			PanelToolButton(tr("start.recent.list"), showLabel = false, onClick = { selection.recentGrid = false }, enabled = true,
				tooltip = tr("start.recent.list"), active = !selection.recentGrid) { ListGlyph(colors.textPrimary) }
		}
		when {
			// Nothing while the check runs, rather than "none" for a moment.
			recent == null -> Spacer(Modifier.weight(1f))
			shown.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
				Text(tr(if (recent.isEmpty()) "canvas.start.recent.empty" else "start.recent.noMatch"),
					style = typography.caption.copy(fontSize = 11.sp), color = colors.textDisabled)
			}
			selection.recentGrid -> FlowRow(
				Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(4.dp),
			) {
				shown.forEach { file ->
					RecentTile(file, file.path == selection.recentPath, sizes.card.dp, enabled,
						onClick = { selection.recentPath = file.path }, onDoubleClick = { viewModel.openRecentFile(file.path) })
				}
			}
			else -> Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
				shown.forEach { file ->
					RecentRow(file, file.path == selection.recentPath, enabled,
						onClick = { selection.recentPath = file.path }, onDoubleClick = { viewModel.openRecentFile(file.path) })
				}
			}
		}
	}
}

/** A recent file as the materials grid draws an image: its picture, its name under it, the selection behind both. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentTile(file: RecentFile, selected: Boolean, size: Dp, enabled: Boolean, onClick: () -> Unit, onDoubleClick: () -> Unit) {
	val colors = LocalToolColors.current
	val details = rememberRecentFileDetails(file)
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val shape = RoundedCornerShape(5.dp)
	val tip = listOfNotNull(
		file.path,
		kindLabel(file.kind),
		details?.canvasWidth?.let { "$it × ${details.canvasHeight}" },
		details?.let { DateFormat.format(Instant.ofEpochMilli(it.modifiedMillis)) + " · " + formatBytes(it.sizeBytes) },
	).joinToString("\n")
	BarTooltip(tip) {
		Column(
			Modifier.padding(3.dp).clip(shape)
				.background(when {
					selected -> colors.selection
					hovered && enabled -> colors.controlHover.copy(alpha = 0.35f)
					else -> Color.Transparent
				})
				.border(0.5.dp, if (selected) colors.accent.copy(alpha = 0.7f) else Color.Transparent, shape)
				.hoverable(interaction)
				.combinedClickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick, onDoubleClick = onDoubleClick)
				.handCursor(enabled)
				.padding(4.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Thumbnail(details?.thumbnail, file.kind, Modifier.size(size, size * 0.75f).clip(RoundedCornerShape(3.dp)))
			Text(file.name, color = if (selected) colors.selectionText else colors.textPrimary, fontSize = 10.sp, maxLines = 1,
				overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
				modifier = Modifier.width(size).padding(top = 3.dp))
		}
	}
}

/** A recent file as the materials list draws an image: a small picture, the name, then its facts in muted columns. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentRow(file: RecentFile, selected: Boolean, enabled: Boolean, onClick: () -> Unit, onDoubleClick: () -> Unit) {
	val colors = LocalToolColors.current
	val details = rememberRecentFileDetails(file)
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val muted = if (selected) colors.selectionText.copy(alpha = 0.75f) else colors.textMuted
	Row(
		Modifier.fillMaxWidth().height(30.dp)
			.background(when {
				selected -> colors.selection
				hovered && enabled -> colors.controlHover.copy(alpha = 0.3f)
				else -> Color.Transparent
			})
			.hoverable(interaction)
			.combinedClickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick, onDoubleClick = onDoubleClick)
			.handCursor(enabled)
			.padding(horizontal = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Thumbnail(details?.thumbnail, file.kind, Modifier.size(24.dp).clip(RoundedCornerShape(2.dp)))
		Text(file.name, color = if (selected) colors.selectionText else colors.textPrimary, fontSize = 11.sp, maxLines = 1,
			overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.45f))
		Text(file.directory, color = muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.55f))
		Text(kindLabel(file.kind), color = muted, fontSize = 10.sp)
		details?.canvasWidth?.let { Text("$it×${details.canvasHeight}", color = muted, fontSize = 10.sp) }
		Text(details?.let { DateFormat.format(Instant.ofEpochMilli(it.modifiedMillis)) }.orEmpty(), color = muted, fontSize = 10.sp)
		Text(details?.let { formatBytes(it.sizeBytes) }.orEmpty(), color = muted, fontSize = 10.sp,
			modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
	}
}

/** A file's picture on a checkerboard, or its kind's icon while it has none. */
@Composable
private fun Thumbnail(image: ImageBitmap?, kind: RecentFileKind, modifier: Modifier) {
	val colors = LocalToolColors.current
	Box(
		modifier.drawBehind {
			drawChecker(Offset.Zero, size, colors.checkerLight, colors.checkerDark, 5.dp.toPx())
		},
		contentAlignment = Alignment.Center,
	) {
		if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(2.dp))
		else when (kind) {
			RecentFileKind.PROJECT -> IconFolder(modifier = Modifier.size(16.dp), tint = colors.warning.copy(alpha = 0.8f))
			RecentFileKind.PSD -> IconDocument(Modifier.size(16.dp), colors.accent.copy(alpha = 0.85f))
		}
	}
}

private fun kindLabel(kind: RecentFileKind) = tr(when (kind) {
	RecentFileKind.PROJECT -> "canvas.start.kind.project"
	RecentFileKind.PSD -> "canvas.start.kind.psd"
})

private fun formatBytes(bytes: Long): String = when {
	bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
	bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
	bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
	else -> "$bytes B"
}

// ---------------------------------------------------------------------------------------------------------------------
// Updates
// ---------------------------------------------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UpdatesContent(selection: StartSelection, enabled: Boolean, onStartTutorial: ((TutorialId) -> Unit)?) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
		Text(tr("canvas.start.updates.desc"), style = typography.caption.copy(fontSize = 11.sp, lineHeight = 15.sp), color = colors.textMuted,
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
		Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.divider))
		StartCanvasUpdates.forEach { (tutorial, key) ->
			val interaction = remember { MutableInteractionSource() }
			val hovered by interaction.collectIsHoveredAsState()
			val selected = selection.update == tutorial
			Row(
				Modifier.fillMaxWidth()
					.background(if (selected) colors.selection else if (hovered) colors.controlHover.copy(alpha = 0.35f) else Color.Transparent)
					.hoverable(interaction)
					.combinedClickable(interactionSource = interaction, indication = null, onClick = { selection.update = tutorial },
						onDoubleClick = { if (enabled) onStartTutorial?.invoke(tutorial) })
					.handCursor()
					.padding(horizontal = 10.dp, vertical = 7.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(10.dp),
			) {
				UpdateIcon(tutorial, if (selected) colors.selectionText else colors.accent)
				Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
					Text(tr("canvas.start.update.$key.title"), style = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
						color = if (selected) colors.selectionText else colors.textPrimary)
					Text(tr("canvas.start.update.$key.desc"), style = typography.caption.copy(fontSize = 10.sp, lineHeight = 13.sp),
						color = if (selected) colors.selectionText.copy(alpha = 0.75f) else colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
				}
			}
		}
	}
}

@Composable
private fun UpdateIcon(tutorial: TutorialId, tint: Color) {
	when (tutorial) {
		TutorialId.ANIMATION -> IconPlay(modifier = Modifier.size(14.dp), tint = tint)
		TutorialId.SKELETON -> IconSkeleton(modifier = Modifier.size(15.dp), tint = tint)
		TutorialId.PROJECT_HISTORY -> WorkspacePresetIcon(WorkspacePreset.HISTORY, tint, Modifier.size(15.dp))
		TutorialId.WORKSPACE -> WorkspacePresetIcon(WorkspacePreset.TEXTURE, tint, Modifier.size(15.dp))
		TutorialId.DEFORM_MODE -> ModeIcon(EditHierarchyMode.DEFORM, tint, 15.dp)
		TutorialId.TOOL_DETAILS -> Box(Modifier.size(15.dp), contentAlignment = Alignment.Center) { ToolIcon(CanvasTool.BRUSH, tint) }
		TutorialId.PHYSICS -> IconPhysics(active = true, modifier = Modifier.size(15.dp), tint = tint)
		TutorialId.SIMULATION -> ModeIcon(EditHierarchyMode.SIMULATE, tint, 15.dp)
		else -> StartIcon(Modifier.size(14.dp), tint)
	}
}

// ---------------------------------------------------------------------------------------------------------------------
// Inspector
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun StartInspector(
	section: StartSection,
	viewModel: PSD2LiveViewModel,
	layout: LayoutDraft,
	selection: StartSelection,
	selectedRecent: RecentFile?,
	enabled: Boolean,
	onRevealFile: (String) -> Unit,
	modifier: Modifier,
) {
	val title = when (section) {
		StartSection.GENERATE -> tr("start.generate.presets")
		StartSection.LAYOUT -> tr("start.inspector.canvas")
		StartSection.CMO3 -> tr("start.nav.cmo3")
		StartSection.RECENT -> selectedRecent?.name ?: tr("start.inspector")
		StartSection.UPDATES -> tr("start.inspector")
	}
	StartPanel(title, modifier) {
		Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
			when (section) {
				StartSection.GENERATE -> PresetInspector(viewModel.startChoices, selection, enabled, viewModel::setStartChoices)
				StartSection.LAYOUT -> LayoutInspector(layout, enabled)
				StartSection.CMO3 -> InspectorNote(tr("start.cmo3.note"))
				StartSection.RECENT -> RecentInspector(selectedRecent, enabled, viewModel, onRevealFile)
				StartSection.UPDATES -> UpdateInspector(selection.update)
			}
		}
	}
}

/**
 * The presets a PSD import applies: the quick set picked from a list, then every value grouped as the model presets
 * panel groups them. Changing a value away from every set shows "Custom".
 */
@Composable
private fun PresetInspector(choices: StartPresetChoices, selection: StartSelection, enabled: Boolean, onChange: (StartPresetChoices) -> Unit) {
	PresetPicker(choices, enabled, onChange)
	fun group(key: String, title: String, summary: String, content: @Composable ColumnScope.() -> Unit): @Composable () -> Unit = {
		val open = selection.open[key] ?: true
		PanelSectionRow(title, open, { selection.open[key] = !open }, icon = null, trailing = {
			Text(summary, fontSize = 10.sp, color = LocalToolColors.current.textMuted, maxLines = 1)
		})
		if (open) Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp), content = content)
	}
	group("rig", tr("settings.group.rigging"), "%.2f× / %.2f×".format(choices.headStrength, choices.bodyStrength)) {
		StrengthRow(tr("settings.headStrength"), choices.headStrength, enabled) { onChange(choices.copy(headStrength = it)) }
		StrengthRow(tr("settings.bodyStrength"), choices.bodyStrength, enabled) { onChange(choices.copy(bodyStrength = it)) }
		CheckRow(tr("model.deformer.featureDisplacement"), choices.featureDisplacement, enabled) { onChange(choices.copy(featureDisplacement = it)) }
		CheckRow(tr("mouth.outline"), choices.mouthOutline, enabled) { onChange(choices.copy(mouthOutline = it)) }
	}()
	group("motions", tr("settings.group.motions"), onOff(choices.motionBasic || choices.motionSkeleton)) {
		CheckRow(tr("settings.motion.basic"), choices.motionBasic, enabled) { onChange(choices.copy(motionBasic = it)) }
		CheckRow(tr("settings.motion.skeleton"), choices.motionSkeleton, enabled) { onChange(choices.copy(motionSkeleton = it)) }
	}()
	group("simulation", tr("settings.group.simulation"), hairLabel(choices.frontHair, choices.backHair)) {
		HairRow(tr("presets.frontHair"), choices.frontHair, enabled) { onChange(choices.copy(frontHair = it)) }
		HairRow(tr("presets.backHair"), choices.backHair, enabled) { onChange(choices.copy(backHair = it)) }
		CheckRow(tr("presets.clothing"), choices.clothing, enabled) { onChange(choices.copy(clothing = it)) }
		CheckRow(tr("export.physics.eyeJelly"), choices.eyeJelly, enabled) { onChange(choices.copy(eyeJelly = it)) }
	}()
	InspectorNote(tr("start.presets.note"))
}

/** The quick sets as a list to pick from, on the one the values match, or on "Custom" while none does. */
@Composable
private fun PresetPicker(choices: StartPresetChoices, enabled: Boolean, onPick: (StartPresetChoices) -> Unit) {
	val matched = matchedPreset(choices)
	InspectorFormRow(tr("start.quick"), Modifier.padding(top = 8.dp, bottom = 6.dp, end = 8.dp)) {
		CompactDropdown(
			items = if (matched == null) StartQuickPreset.entries + null else StartQuickPreset.entries,
			selectedItem = matched,
			onItemSelected = { it?.let { preset -> onPick(preset.choices) } },
			itemLabel = { it?.let { preset -> tr(preset.key) } ?: tr("start.quick.custom") },
			itemEnabled = { it != null },
			enabled = enabled,
			height = 22.dp,
			modifier = Modifier.fillMaxWidth(),
		)
	}
}

private fun hairLabel(front: HairMode, back: HairMode): String {
	val mode = when {
		front == HairMode.SIMULATION || back == HairMode.SIMULATION -> HairMode.SIMULATION
		front == HairMode.CLASSIC || back == HairMode.CLASSIC -> HairMode.CLASSIC
		else -> HairMode.OFF
	}
	return tr(mode.key)
}

private fun onOff(on: Boolean) = tr(if (on) "start.on" else "start.off")

@Composable
private fun StrengthRow(label: String, value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
	InspectorFormRow(label, Modifier.height(22.dp).padding(end = 8.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			CompactSlider(value = value, onValueChange = { onChange((it * 20f).roundToInt() / 20f) }, valueRange = 0f..4f,
				enabled = enabled, height = 14.dp, modifier = Modifier.weight(1f))
			Text("%.2f×".format(value), style = LocalToolTypography.current.monoSmall, color = LocalToolColors.current.textPrimary,
				modifier = Modifier.width(40.dp).padding(start = 6.dp))
		}
	}
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
	InspectorFormRow(label, Modifier.height(22.dp).padding(end = 8.dp)) {
		CompactCheckbox(checked, onChange, enabled = enabled)
	}
}

@Composable
private fun HairRow(label: String, mode: HairMode, enabled: Boolean, onChange: (HairMode) -> Unit) {
	InspectorFormRow(label, Modifier.height(24.dp).padding(end = 8.dp)) {
		CompactDropdown(items = HairMode.entries, selectedItem = mode, onItemSelected = onChange, itemLabel = { tr(it.key) },
			enabled = enabled, height = 20.dp, modifier = Modifier.width(120.dp))
	}
}

@Composable
private fun LayoutInspector(layout: LayoutDraft, enabled: Boolean) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
		InspectorFormRow(tr("start.layout.width"), Modifier.padding(end = 8.dp)) {
			CompactTextField(layout.width, { layout.width = it }, isMono = true, selectAllOnFocus = true, enabled = enabled,
				height = 22.dp, modifier = Modifier.width(96.dp))
		}
		InspectorFormRow(tr("start.layout.height"), Modifier.padding(end = 8.dp)) {
			CompactTextField(layout.height, { layout.height = it }, isMono = true, selectAllOnFocus = true, enabled = enabled,
				height = 22.dp, modifier = Modifier.width(96.dp))
		}
		InspectorFormRow("", Modifier.padding(end = 8.dp)) {
			CompactButton(tr("start.layout.swap"), {
				val w = layout.width
				layout.width = layout.height
				layout.height = w
			}, enabled = enabled, height = 22.dp)
		}
		InspectorFormRow(tr("start.layout.pixels"), Modifier.padding(end = 8.dp)) {
			val w = layout.w
			val h = layout.h
			Text(if (w != null && h != null) "%.2f M".format(w.toLong() * h / 1_000_000.0) else "—",
				style = typography.monoSmall.copy(fontSize = 11.sp), color = colors.textPrimary)
		}
		if (!layout.valid) Text(tr("start.layout.invalid"), style = typography.caption.copy(fontSize = 10.5.sp), color = colors.warning,
			modifier = Modifier.padding(horizontal = 10.dp))
	}
	InspectorNote(tr("layout.new.hint"))
}

@Composable
private fun RecentInspector(file: RecentFile?, enabled: Boolean, viewModel: PSD2LiveViewModel, onRevealFile: (String) -> Unit) {
	if (file == null) {
		InspectorNote(tr("start.recent.pick"))
		return
	}
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val details = rememberRecentFileDetails(file)
	Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Thumbnail(details?.thumbnail, file.kind, Modifier.fillMaxWidth().height(180.dp).border(0.5.dp, colors.divider))
		InspectorFormRow(tr("start.recent.column.kind")) { Text(kindLabel(file.kind), fontSize = 11.sp, color = colors.textPrimary) }
		if (details?.canvasWidth != null && details.canvasHeight != null) InspectorFormRow(tr("start.recent.canvas")) {
			Text("${details.canvasWidth} × ${details.canvasHeight}", style = typography.monoSmall, color = colors.textPrimary)
		}
		InspectorFormRow(tr("start.recent.column.modified")) {
			Text(details?.let { DateFormat.format(Instant.ofEpochMilli(it.modifiedMillis)) }.orEmpty(), style = typography.monoSmall, color = colors.textPrimary)
		}
		InspectorFormRow(tr("start.recent.column.size")) {
			Text(details?.let { formatBytes(it.sizeBytes) }.orEmpty(), style = typography.monoSmall, color = colors.textPrimary)
		}
		InspectorFormRow(tr("start.recent.column.location")) {
			Text(file.directory, fontSize = 10.5.sp, color = colors.textMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
		}
		Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			CompactButton(tr("start.recent.reveal"), { onRevealFile(file.directory) }, height = 22.dp)
			CompactButton(tr("start.recent.forget"), { viewModel.forgetRecentFile(file.path) }, enabled = enabled, height = 22.dp)
		}
	}
}

@Composable
private fun UpdateInspector(tutorial: TutorialId) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val key = StartCanvasUpdates.firstOrNull { it.first == tutorial }?.second ?: return
	Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			UpdateIcon(tutorial, colors.accent)
			Text(tr("canvas.start.update.$key.title"), style = typography.body.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
				color = colors.textPrimary)
		}
		Text(tr("canvas.start.update.$key.desc"), style = typography.caption.copy(fontSize = 11.sp, lineHeight = 16.sp), color = colors.textMuted)
	}
}

@Composable
private fun InspectorNote(text: String) {
	Text(text, style = LocalToolTypography.current.caption.copy(fontSize = 10.5.sp, lineHeight = 14.sp),
		color = LocalToolColors.current.textMuted, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
}

// ---------------------------------------------------------------------------------------------------------------------
// Action bar
// ---------------------------------------------------------------------------------------------------------------------

/** The bar under the panels: what the section's action will do on the left, the action itself, solid, on the right. */
@Composable
private fun StartActionBar(
	section: StartSection,
	viewModel: PSD2LiveViewModel,
	layout: LayoutDraft,
	selection: StartSelection,
	selectedRecent: RecentFile?,
	selectedPsd: RecentFile?,
	enabled: Boolean,
	state: PSD2LiveState,
	onOpenPsd: (() -> Unit)?,
	onOpenCmo3: (() -> Unit)?,
	onStartTutorial: ((TutorialId) -> Unit)?,
	primary: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val preset = startPresetName(viewModel.startChoices)
	val hint = when (section) {
		StartSection.GENERATE -> selectedPsd?.let { tr("start.action.generate.file", it.name, preset) } ?: tr("start.action.generate.hint", preset)
		StartSection.LAYOUT -> if (layout.valid) tr("start.action.layout.hint", layout.w!!, layout.h!!) else tr("start.layout.invalid")
		StartSection.CMO3 -> tr("start.action.cmo3.hint")
		StartSection.RECENT -> selectedRecent?.path ?: tr("start.recent.pick")
		StartSection.UPDATES -> tr("start.action.updates.hint")
	}
	Row(
		Modifier.fillMaxWidth().height(40.dp).padding(top = 4.dp)
			.background(colors.panelBackground)
			.drawBehind { drawLine(colors.divider, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }
			.padding(horizontal = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(10.dp),
	) {
		Text(hint, style = typography.body.copy(fontSize = 11.sp), color = colors.textMuted, maxLines = 1,
			overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
		Text("Enter", style = typography.monoSmall.copy(fontSize = 10.sp), color = colors.textDisabled)
		when (section) {
			StartSection.GENERATE -> GenerateButton(state, viewModel, selectedPsd != null, enabled && (selectedPsd != null || onOpenPsd != null), primary)
			StartSection.LAYOUT -> AccentButton(tr("start.action.layout"), primary, enabled = enabled && layout.valid, emphasis = true) {
				WorkspacePresetIcon(WorkspacePreset.LAYOUT, it, Modifier.size(12.dp))
			}
			StartSection.CMO3 -> AccentButton(tr("start.action.cmo3"), primary, enabled = enabled && onOpenCmo3 != null, emphasis = true) {
				IconDocument(Modifier.size(12.dp), it)
			}
			StartSection.RECENT -> AccentButton(
				tr(if (selectedRecent?.kind == RecentFileKind.PSD) "start.action.recent.generate" else "start.action.recent.open"),
				primary, enabled = enabled && selectedRecent != null, emphasis = true,
			) { IconFolder(modifier = Modifier.size(12.dp), tint = it) }
			StartSection.UPDATES -> AccentButton(tr("start.action.updates"), primary, enabled = enabled && onStartTutorial != null, emphasis = true) {
				IconPlay(modifier = Modifier.size(11.dp), tint = it)
			}
		}
	}
}

/** Generate: the body generates the previewed PSD or asks for one; the menu switches presets or applies them to the open model. */
@Composable
private fun GenerateButton(state: PSD2LiveState, viewModel: PSD2LiveViewModel, hasFile: Boolean, enabled: Boolean, onGenerate: () -> Unit) {
	var menu by remember { mutableStateOf(false) }
	val matched = matchedPreset(viewModel.startChoices)
	Box {
		AccentSplitButton(
			label = tr(if (hasFile) "start.action.recent.generate" else "start.action.generate"),
			icon = { IconDocument(Modifier.size(12.dp), it) },
			onClick = onGenerate,
			open = menu,
			onMenu = { menu = !menu },
			enabled = enabled,
			menuTooltip = tr("start.action.generate.menu"),
			emphasis = true,
		)
		FloatingMenu(expanded = menu, onDismiss = { menu = false }, width = 240.dp, alignment = Alignment.BottomEnd, rise = true) {
			FloatingMenuSection(tr("start.quick"))
			StartQuickPreset.entries.forEach { preset ->
				FloatingMenuRadio(tr(preset.key), preset == matched, { viewModel.setStartChoices(preset.choices) })
			}
			if (matched == null) FloatingMenuRadio(tr("start.quick.custom"), true, {})
			FloatingMenuDivider()
			FloatingMenuRow(tr("start.action.applyCurrent"), {
				menu = false
				viewModel.applyStartChoicesToCurrent()
			}, enabled = viewModel.startPresetsApplyToCurrent(state))
		}
	}
}

// ---------------------------------------------------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------------------------------------------------

/** An empty viewport that opens a file on click: the generate viewport's look while it has nothing to show. */
@Composable
private fun ViewportPrompt(
	title: String,
	subtitle: String,
	enabled: Boolean,
	onClick: () -> Unit,
	modifier: Modifier,
	icon: @Composable (Color) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	Column(
		modifier
			.background(colors.inputBackground)
			.hoverable(interaction)
			.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
			.handCursor(enabled),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.Center,
	) {
		icon(if (hovered && enabled) colors.accent else colors.textMuted)
		Spacer(Modifier.height(8.dp))
		Text(title, style = typography.body.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium), color = colors.textPrimary)
		Spacer(Modifier.height(3.dp))
		Text(subtitle, style = typography.caption.copy(fontSize = 11.sp), color = if (hovered && enabled) colors.accent else colors.textMuted)
	}
}

@Composable
private fun SubHeader(title: String, hint: String?) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Text(title, style = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium), color = colors.textPrimary, modifier = Modifier.weight(1f))
		hint?.let { Text(it, style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted) }
	}
}

/** What the canvas shows with nothing open: the drop hint and the way back to the start page. */
@Composable
internal fun EmptyCanvasHint(enabled: Boolean, onShowStart: () -> Unit, modifier: Modifier = Modifier) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Column(
		modifier.padding(16.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
		Text(tr("start.page.emptyCanvas"), style = typography.caption.copy(fontSize = 11.sp), color = colors.textMuted)
		CompactButton(
			text = tr("start.page.goToStart"),
			onClick = onShowStart,
			enabled = enabled,
			leadingIcon = { StartIcon(tint = colors.textPrimary) },
		)
	}
}

/** A route that forks: a start and two ways on, the fork as a point. The start page's tab icon. */
@Composable
internal fun StartIcon(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color,
) = GridIcon(modifier, tint) {
	val stops = listOf(3.6f to 13f, 14.6f to 3.4f, 14.6f to 13.6f)
	val holes = ComposePath().apply { stops.forEach { (x, y) -> addPath(circlePath(x, y, 2f)) } }
	behind(holes) { stops.forEach { (x, y) -> line(9f, 6.2f, x, y) } }
	stops.forEach { (x, y) ->
		dot(x, y, 2f, soft)
		ring(x, y, 2f)
	}
	dot(9f, 6.2f, 1.7f)
}

@Composable
private fun IconDocument(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color,
) = GridIcon(modifier, tint) { document() }
