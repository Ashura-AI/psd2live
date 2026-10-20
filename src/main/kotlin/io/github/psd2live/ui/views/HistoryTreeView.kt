package io.github.psd2live.ui.views

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.project.WorkspaceHistoryNodeSnapshot
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.PaintSession
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.GridIcon
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.IconEye
import io.github.psd2live.ui.components.IconRedo
import io.github.psd2live.ui.components.IconUndo
import io.github.psd2live.ui.components.brackets
import io.github.psd2live.ui.components.copySheets
import io.github.psd2live.ui.components.document
import io.github.psd2live.project.HistoryAnnotation
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.frostedGlass
import java.awt.Cursor
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt
import io.github.psd2live.ui.theme.Motion
import io.github.psd2live.ui.theme.handCursor
import io.github.psd2live.ui.theme.selectOrOpen

private const val NODE_WIDTH_DP = 184f
private const val NODE_HEIGHT_DP = 46f
private const val HORIZONTAL_GAP_DP = 20f
private const val VERTICAL_GAP_DP = 30f
private const val CANVAS_PADDING_DP = 24f
private const val MIN_SCALE = 0.25f
private const val MAX_SCALE = 2.5f
private const val ZOOM_STEP = 1.15f
private const val VIEW_SAVE_DELAY_MS = 300L

/** The camera, filters and view mode of the history view, as the project opened as [generation] saves them. */
private data class HistoryViewState(
	val scale: Float,
	val pan: Offset,
	val search: String,
	val showHidden: Boolean,
	val compressed: Boolean,
	val generation: Long,
) {
	fun save(viewModel: PSD2LiveViewModel) {
		if (viewModel.state.value.projectOpenGeneration != generation) return
		viewModel.setHistoryView(scale, pan.x, pan.y, search, showHidden)
		viewModel.setHistoryCompressed(compressed)
	}
}

internal class TreeNodeLayout(
	val node: WorkspaceHistoryNodeSnapshot,
	var x: Float = 0f,
	var y: Float = 0f,
	val children: MutableList<TreeNodeLayout> = mutableListOf(),
)

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryTreeView(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val density = LocalDensity.current
	val densityFactor = density.density

	var showHidden by remember(state.projectOpenGeneration) { mutableStateOf(state.historyShowHidden) }
	// Compact view merges consecutive linear versions into one card; the full view keeps one card per version.
	var compressed by remember(state.projectOpenGeneration) { mutableStateOf(state.historyCompressed) }
	val fullHistory = state.historySnapshot
	val hiddenIds = remember(fullHistory, state.historyAnnotations, showHidden) {
		val hidden = if (showHidden) mutableSetOf() else state.historyAnnotations.filterValues { it.hidden }.keys.toMutableSet()
		val children = fullHistory?.nodes.orEmpty().groupBy { it.parentId }
		val byId = fullHistory?.nodes.orEmpty().associateBy { it.id }
		val pending = java.util.ArrayDeque(hidden)
		while (pending.isNotEmpty()) children[pending.removeFirst()].orEmpty().forEach { if (hidden.add(it.id)) pending.add(it.id) }
		var cursor = fullHistory?.headNodeId
		while (cursor != null) { hidden.remove(cursor); cursor = byId[cursor]?.parentId }
		hidden
	}
	// Kept across recompositions: the remembers below key on it, and a new copy would compare every node each time.
	val historySnapshot = remember(fullHistory, hiddenIds) {
		if (hiddenIds.isEmpty()) fullHistory else fullHistory?.copy(nodes = fullHistory.nodes.filterNot { it.id in hiddenIds })
	}
	var searchQuery by remember(state.projectOpenGeneration) { mutableStateOf(state.historySearch) }
	var scale by remember(state.projectOpenGeneration) { mutableStateOf(state.historyZoom.coerceIn(MIN_SCALE, MAX_SCALE)) }
	var panOffset by remember(state.projectOpenGeneration) { mutableStateOf(Offset(state.historyPanX, state.historyPanY)) }
	var isDragging by remember { mutableStateOf(false) }
	var viewportSize by remember { mutableStateOf(IntSize(800, 600)) }
	var isInspectionPanelOpen by remember { mutableStateOf(true) }
	var isOperationListOpen by remember { mutableStateOf(true) }

	// The view is saved once it settles: each step of a pan or zoom would update the whole app state.
	val savedView by rememberUpdatedState(
		HistoryViewState(scale, panOffset, searchQuery, showHidden, compressed, state.projectOpenGeneration)
	)
	LaunchedEffect(scale, panOffset, searchQuery, showHidden, compressed) {
		delay(VIEW_SAVE_DELAY_MS)
		savedView.save(viewModel)
	}
	DisposableEffect(Unit) { onDispose { savedView.save(viewModel) } }

	val selectedNodeId = state.selectedHistoryNodeId ?: historySnapshot?.headNodeId
	val selectedNode = remember(historySnapshot, selectedNodeId) {
		historySnapshot?.nodes?.firstOrNull { it.id == selectedNodeId }
	}

	if (historySnapshot == null || historySnapshot.nodes.isEmpty()) {
		Box(
			modifier = modifier
				.fillMaxSize()
				.background(colors.windowBackground),
			contentAlignment = Alignment.Center,
		) {
			Text(
				text = tr("history.empty"),
				style = typography.caption.copy(fontSize = 11.sp),
				color = colors.textMuted,
			)
		}
		return
	}

	// The chain the current state was built from, oldest first: what the operation list shows and
	// what one undo walks back. Hidden branches never shorten it -- the filter above keeps HEAD's
	// ancestors no matter what the annotations say.
	val operationChain = remember(historySnapshot) {
		val byId = historySnapshot.nodes.associateBy { it.id }
		val chain = ArrayDeque<WorkspaceHistoryNodeSnapshot>()
		var cursor: String? = historySnapshot.headNodeId
		while (cursor != null) {
			val node = byId[cursor] ?: break
			chain.addFirst(node)
			cursor = node.parentId
		}
		chain.toList()
	}
	// Compact view: consecutive linear versions share one card, while forks keep separate cards.
	val grouping = remember(historySnapshot, compressed) { groupHistory(historySnapshot.nodes, compressed) }
	// Cards on the path to HEAD, so the branch line reads as one path like the operation list.
	val headLine = remember(operationChain, grouping) {
		operationChain.mapNotNullTo(HashSet()) { node -> grouping.byNodeId[node.id] }
	}
	val selectedGroupId = remember(grouping, selectedNodeId) { selectedNodeId?.let(grouping.byNodeId::get) }

	// Calculate Tree Layout in world DP units
	val layoutNodes = remember(grouping) { grouping.layoutNodes() }
	val (_, allLayoutNodes, boundsWidth, boundsHeight) = remember(layoutNodes) {
		calculateTreeLayout(layoutNodes)
	}

	fun worldToScreenX(worldXDp: Float): Float {
		return (worldXDp * scale + CANVAS_PADDING_DP * scale) * densityFactor + panOffset.x
	}

	fun worldToScreenY(worldYDp: Float): Float {
		return (worldYDp * scale + CANVAS_PADDING_DP * scale) * densityFactor + panOffset.y
	}

	fun fitToView() {
		val vpW = viewportSize.width.toFloat()
		val vpH = viewportSize.height.toFloat()
		if (boundsWidth <= 0f || boundsHeight <= 0f || vpW <= 0f || vpH <= 0f) return
		val marginPx = 16f * densityFactor
		val availableW = (vpW - marginPx * 2f).coerceAtLeast(100f)
		val availableH = (vpH - marginPx * 2f).coerceAtLeast(100f)
		val totalContentWDp = boundsWidth + CANVAS_PADDING_DP * 2f
		val totalContentHDp = boundsHeight + CANVAS_PADDING_DP * 2f
		val targetScale = minOf(
			availableW / (totalContentWDp * densityFactor),
			availableH / (totalContentHDp * densityFactor),
		).coerceIn(MIN_SCALE, 1.2f)
		scale = targetScale
		val drawnContentWPx = totalContentWDp * targetScale * densityFactor
		val targetPanX = ((vpW - drawnContentWPx) * 0.5f).coerceAtLeast(0f)
		panOffset = Offset(targetPanX, marginPx)
	}

	/** Pans so the card holding node [id] sits in the middle of the viewport, at the current zoom. */
	fun centerOn(id: String) {
		val groupId = grouping.byNodeId[id] ?: id
		val layout = allLayoutNodes.firstOrNull { it.node.id == groupId } ?: return
		val worldX = layout.x + NODE_WIDTH_DP / 2f + CANVAS_PADDING_DP
		val worldY = layout.y + NODE_HEIGHT_DP / 2f + CANVAS_PADDING_DP
		panOffset = Offset(viewportSize.width / 2f - worldX * scale * densityFactor, viewportSize.height / 2f - worldY * scale * densityFactor)
	}

	// The newest version, wherever HEAD has been checked out to: what "back to latest" returns to.
	val newestNode = remember(fullHistory) { fullHistory?.nodes?.maxByOrNull { it.createdAt } }
	// Centred once the checkout lands and the tree is laid out again with it.
	var pendingCenter by remember { mutableStateOf<String?>(null) }
	LaunchedEffect(allLayoutNodes, grouping, pendingCenter) {
		val id = pendingCenter ?: return@LaunchedEffect
		val groupId = grouping.byNodeId[id] ?: id
		if (allLayoutNodes.any { it.node.id == groupId && it.node.isHead }) { centerOn(id); pendingCenter = null }
	}

	/** Zooms by [factor] about [anchor], a point in the viewport that stays put. */
	fun zoomAbout(factor: Float, anchor: Offset = Offset(viewportSize.width / 2f, viewportSize.height / 2f)) {
		val nextScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
		if (nextScale == scale) return
		val worldX = (anchor.x - panOffset.x) / (scale * densityFactor) - CANVAS_PADDING_DP
		val worldY = (anchor.y - panOffset.y) / (scale * densityFactor) - CANVAS_PADDING_DP
		scale = nextScale
		panOffset = Offset(
			anchor.x - (worldX + CANVAS_PADDING_DP) * nextScale * densityFactor,
			anchor.y - (worldY + CANVAS_PADDING_DP) * nextScale * densityFactor,
		)
	}

	Column(
		modifier = modifier
			.fillMaxSize()
			.background(colors.windowBackground)
			.clipRectToBounds(),
	) {
		HistoryToolbar(
			operationListOpen = isOperationListOpen,
			onToggleOperationList = { isOperationListOpen = !isOperationListOpen },
			showHidden = showHidden,
			onToggleHidden = { showHidden = !showHidden },
			compressed = compressed,
			onToggleCompressed = { compressed = !compressed },
			searchQuery = searchQuery,
			onSearchChange = { searchQuery = it },
			onUndo = viewModel::undoHistory,
			onRedo = viewModel::redoHistory,
			latestEnabled = newestNode != null && !state.workspaceEditBusy,
			onLatest = {
				val newest = newestNode
				if (newest != null) {
					if (newest.isHead) centerOn(newest.id)
					else { pendingCenter = newest.id; viewModel.checkoutHistoryNode(newest.id) }
				}
			},
		)

		Row(
			modifier = Modifier
				.weight(1f)
				.fillMaxWidth()
				.clipRectToBounds(),
		) {
			// Photoshop-style operation list: the chain that led to the current state. Every step stays
			// listed here even when the tree merges a linear run into one card, so this is where a step
			// inside a run is picked for checkout -- and continuing to edit from it starts a new branch.
			if (isOperationListOpen) {
				OperationListSidebar(
					chain = operationChain,
					annotations = state.historyAnnotations,
					enabled = !state.workspaceEditBusy,
					onCheckout = { viewModel.checkoutHistoryNode(it) },
					paintSession = viewModel.canvasEditor.paintSession,
					onJumpToPaintStroke = { viewModel.canvasEditor.jumpToPaintStroke(it) },
					compressed = compressed,
					groupOfNode = grouping.byNodeId,
					selectedNodeId = selectedNodeId,
				)
			}

			Box(
				modifier = Modifier
					.weight(1f)
					.fillMaxHeight()
					// clipToBounds() would keep this graph on a layer that ignores dock moves.
					.clipRectToBounds(),
			) {
				// Interactive tree canvas
				Box(
					modifier = Modifier
						.fillMaxSize()
						.onSizeChanged { viewportSize = it }
						.pointerHoverIcon(
							PointerIcon(
								if (isDragging) Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
								else Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
							)
						)
						.pointerInput(Unit) {
							detectDragGestures(
								onDragStart = { isDragging = true },
								onDragEnd = { isDragging = false },
								onDragCancel = { isDragging = false },
								onDrag = { change, dragAmount ->
									change.consume()
									panOffset = Offset(panOffset.x + dragAmount.x, panOffset.y + dragAmount.y)
								},
							)
						}
						.onPointerEvent(PointerEventType.Scroll) { event ->
							val change = event.changes.firstOrNull() ?: return@onPointerEvent
							val delta = change.scrollDelta.y
							if (delta != 0f) zoomAbout(if (delta < 0) ZOOM_STEP else 1f / ZOOM_STEP, change.position)
						},
				) {
					// Background grid and the edges between parent and child nodes
					Canvas(modifier = Modifier.fillMaxSize()) {
						val gridSpacing = 24f * scale * densityFactor
						if (gridSpacing >= 12f) {
							val gridColor = colors.textPrimary.copy(alpha = 0.035f)
							val ox = (panOffset.x % gridSpacing + gridSpacing) % gridSpacing
							val oy = (panOffset.y % gridSpacing + gridSpacing) % gridSpacing
							var x = ox
							while (x < size.width) {
								drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
								x += gridSpacing
							}
							var y = oy
							while (y < size.height) {
								drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
								y += gridSpacing
							}
						}

						val lineScale = scale.coerceIn(0.6f, 1.6f) * densityFactor
						for (parent in allLayoutNodes) {
							val px = worldToScreenX(parent.x + NODE_WIDTH_DP / 2f)
							val py = worldToScreenY(parent.y + NODE_HEIGHT_DP)
							for (child in parent.children) {
								val cx = worldToScreenX(child.x + NODE_WIDTH_DP / 2f)
								val cy = worldToScreenY(child.y)
								// The curve stays inside the box of its ends.
								if (maxOf(px, cx) < 0f || minOf(px, cx) > size.width || maxOf(py, cy) < 0f || minOf(py, cy) > size.height) continue
								val path = Path().apply {
									moveTo(px, py)
									cubicTo(px, py + (cy - py) * 0.5f, cx, cy - (cy - py) * 0.5f, cx, cy)
								}
								// The whole path to HEAD reads as one line, like the operation list.
								val onHeadLine = child.node.id in headLine
								drawPath(
									path = path,
									color = if (onHeadLine) colors.highlight else colors.textMuted.copy(alpha = 0.35f),
									style = Stroke(width = (if (onHeadLine) 2f else 1.2f) * lineScale, cap = StrokeCap.Round),
								)
							}
						}
					}

					val cardShape = RoundedCornerShape((4 * scale.coerceIn(0.5f, 1.2f)).dp)
					for (layoutNode in allLayoutNodes) {
						val group = grouping.byGroupId[layoutNode.node.id] ?: continue
						val cardX = worldToScreenX(layoutNode.x).roundToInt()
						val cardY = worldToScreenY(layoutNode.y).roundToInt()
						// Culling outside viewport
						if (cardX + (NODE_WIDTH_DP * scale * densityFactor) < -100 ||
							cardX > viewportSize.width + 100 ||
							cardY + (NODE_HEIGHT_DP * scale * densityFactor) < -100 ||
							cardY > viewportSize.height + 100
						) {
							continue
						}
						HistoryNodeCard(
							group = group,
							annotations = state.historyAnnotations,
							selected = group.id == selectedGroupId,
							dimmed = !group.matchesSearch(searchQuery, state.historyAnnotations),
							scale = scale,
							shape = cardShape,
							onClick = {
								viewModel.selectHistoryNode(group.target.id)
								isInspectionPanelOpen = true
							},
							onDoubleClick = {
								if (!group.target.isHead && !state.workspaceEditBusy) viewModel.checkoutHistoryNode(group.target.id)
							},
							modifier = Modifier
								.offset { IntOffset(cardX, cardY) }
								.size(width = (NODE_WIDTH_DP * scale).dp, height = (NODE_HEIGHT_DP * scale).dp),
						)
					}
				}

				// Bottom-left: node count and zoom, where the edit canvas keeps its stats pill.
				ZoomPill(
					nodeCount = historySnapshot.nodes.size,
					segmentCount = if (compressed) grouping.groups.size else null,
					scale = scale,
					onZoomOut = { zoomAbout(1f / ZOOM_STEP) },
					onZoomIn = { zoomAbout(ZOOM_STEP) },
					onReset = {
						scale = 1f
						panOffset = Offset.Zero
					},
					onFit = { fitToView() },
					modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
				)

				if (selectedNode != null && isInspectionPanelOpen) {
					NodeInspector(
						node = selectedNode,
						annotation = state.historyAnnotations[selectedNode.id] ?: HistoryAnnotation(),
						canCheckout = !state.workspaceEditBusy,
						onClose = { isInspectionPanelOpen = false },
						onApply = { title, note, hidden -> viewModel.editHistoryAnnotation(selectedNode.id, title, note, hidden) },
						onCheckout = { viewModel.checkoutHistoryNode(selectedNode.id) },
						modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
					)
				}
			}
		}
	}
}

/** Search, the operation list, undo and redo, the compact view and whether hidden nodes show. */
@Composable
private fun HistoryToolbar(
	operationListOpen: Boolean,
	onToggleOperationList: () -> Unit,
	showHidden: Boolean,
	onToggleHidden: () -> Unit,
	compressed: Boolean,
	onToggleCompressed: () -> Unit,
	searchQuery: String,
	onSearchChange: (String) -> Unit,
	onUndo: () -> Unit,
	onRedo: () -> Unit,
	latestEnabled: Boolean,
	onLatest: () -> Unit,
) {
	val colors = LocalToolColors.current
	// Labels appear in this order as the panel widens, each only once everything before it fits.
	val labels = listOf(tr("history.latest"), tr("history.operations"), tr("history.compact"), tr("project.historyShow"))
	PanelToolbar(
		labels = labels,
		iconCount = 6,
		search = PanelSearch(searchQuery, onSearchChange, tr("history.search")),
	) { labelsShown ->
		PanelToolButton(
			label = labels[0],
			showLabel = labelsShown > 0,
			onClick = onLatest,
			enabled = latestEnabled,
			tooltip = tr("history.latest.tooltip"),
		) {
			Text("⇥", style = LocalToolTypography.current.body.copy(fontSize = 12.sp), color = colors.textPrimary)
		}
		PanelToolbarSeparator()
		PanelToolButton(
			label = labels[1],
			showLabel = labelsShown > 1,
			onClick = onToggleOperationList,
			enabled = true,
			active = operationListOpen,
			tooltip = tr("history.operations"),
		) {
			IconOperationList(tint = if (operationListOpen) colors.accent else colors.textPrimary)
		}
		PanelToolbarSeparator()
		PanelIconButton(onClick = onUndo, tooltip = tr("project.undo")) {
			IconUndo(modifier = Modifier.size(12.dp), tint = colors.textPrimary)
		}
		PanelIconButton(onClick = onRedo, tooltip = tr("project.redo")) {
			IconRedo(modifier = Modifier.size(12.dp), tint = colors.textPrimary)
		}
		PanelToolbarSeparator()
		PanelToolButton(
			label = labels[2],
			showLabel = labelsShown > 2,
			onClick = onToggleCompressed,
			enabled = true,
			active = compressed,
			tooltip = tr("history.compact.tooltip"),
		) {
			IconCompactHistory(tint = if (compressed) colors.accent else colors.textPrimary)
		}
		PanelToolButton(
			label = labels[3],
			showLabel = labelsShown > 3,
			onClick = onToggleHidden,
			enabled = true,
			active = showHidden,
			tooltip = tr("project.historyShow"),
		) {
			IconEye(
				visible = showHidden,
				modifier = Modifier.size(12.dp),
				tint = if (showHidden) colors.accent else colors.textPrimary,
			)
		}
	}
}

/**
 * One card of the history tree: a single version in the full view, or a merged linear run in the
 * compact view. HEAD carries a highlight stripe down its left edge, the selected card takes the
 * selection color, and cards outside a search fade back.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryNodeCard(
	group: HistoryNodeGroup,
	annotations: Map<String, HistoryAnnotation>,
	selected: Boolean,
	dimmed: Boolean,
	scale: Float,
	shape: Shape,
	onClick: () -> Unit,
	onDoubleClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val node = group.target
	val annotation = annotations[node.id]
	val isHead = group.isHead
	val isRun = group.size > 1
	// A hidden branch still drawn because "show hidden" is on reads as set aside.
	val fade = when {
		dimmed -> 0.35f
		group.nodes.any { annotations[it.id]?.hidden == true } -> 0.6f
		else -> 1f
	}
	val textScale = scale.coerceIn(0.6f, 1.4f)
	val stripe = (3 * scale.coerceIn(0.6f, 1.2f)).dp
	TooltipArea(
		tooltip = {
			if (isRun) GroupTooltip(group, annotations, hint = if (node.isHead) null else tr("history.hint.groupDoubleClick"))
			else NodeTooltip(node, annotation, hint = if (isHead) null else tr("history.hint.doubleClick"))
		},
		modifier = modifier,
		delayMillis = Motion.TooltipDelay,
	) {
		Box(
			modifier = Modifier
				.fillMaxSize()
				.clipShape(shape)
				.background(
					when {
						selected -> colors.selection
						hovered -> colors.controlHover
						else -> colors.panelBackground
					}.copy(alpha = fade)
				)
				.border(
					BorderStroke(
						if (selected || isHead) 1.5.dp else 1.dp,
						when {
							isHead -> colors.highlight
							selected -> colors.accent
							hovered -> colors.borderHover
							else -> colors.border
						}.copy(alpha = fade),
					),
					shape,
				)
				.drawBehind {
					if (isHead) drawRect(colors.highlight, size = Size(stripe.toPx(), size.height))
				}
				.hoverable(interaction)
				.selectOrOpen(true, onClick, onDoubleClick)
				.padding(start = stripe + (5 * scale).dp, end = (6 * scale).dp),
			contentAlignment = Alignment.CenterStart,
		) {
			val title = annotation?.title?.takeIf { it.isNotBlank() } ?: node.summary
			val titleColor = (if (selected) colors.selectionText else colors.textPrimary).copy(alpha = fade)
			val metaColor = colors.textMuted.copy(alpha = fade)
			if (scale < 0.55f) {
				// Zoomed far out only the title is legible.
				Text(
					text = title,
					style = typography.body.copy(fontSize = (11 * textScale).sp, fontWeight = FontWeight.Medium),
					color = titleColor,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				return@Box
			}
			Column(verticalArrangement = Arrangement.spacedBy((2 * scale).dp)) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						text = title,
						style = typography.body.copy(
							fontSize = (11 * scale).sp,
							lineHeight = (13 * scale).sp,
							fontWeight = if (selected || isHead) FontWeight.SemiBold else FontWeight.Normal,
						),
						color = titleColor,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.weight(1f),
					)
					if (isRun) {
						Spacer(Modifier.width((4 * scale).dp))
						Text(
							text = "×${group.size}",
							style = typography.monoSmall.copy(fontSize = (9 * scale).sp, fontWeight = FontWeight.Bold),
							color = metaColor,
							maxLines = 1,
						)
					}
					if (isHead) {
						Spacer(Modifier.width((4 * scale).dp))
						Text(
							text = tr("history.head"),
							style = typography.monoSmall.copy(fontSize = (8.5 * scale).sp, fontWeight = FontWeight.Bold),
							color = colors.highlight,
						)
					}
				}
				Row(verticalAlignment = Alignment.CenterVertically) {
					ActorBadge(actor = node.actor, scale = scale, alpha = fade)
					Spacer(Modifier.width((5 * scale).dp))
					Text(
						text = shortTime(node.createdAt),
						style = typography.caption.copy(fontSize = (9.5 * scale).sp, lineHeight = (11 * scale).sp),
						color = metaColor,
						maxLines = 1,
						modifier = Modifier.weight(1f),
					)
					if (!annotation?.note.isNullOrBlank()) {
						IconNote(tint = metaColor, modifier = Modifier.size((10 * scale).dp))
						Spacer(Modifier.width((4 * scale).dp))
					}
					Text(
						text = "#${node.id.takeLast(6)}",
						style = typography.monoSmall.copy(fontSize = (9 * scale).sp, lineHeight = (11 * scale).sp),
						color = metaColor,
						maxLines = 1,
					)
				}
			}
		}
	}
}

/**
 * Everything a card or list row cuts short: the full title, the original summary behind a renamed
 * node, its note, who made it and when, and what clicking does.
 */
@Composable
private fun NodeTooltip(node: WorkspaceHistoryNodeSnapshot, annotation: HistoryAnnotation?, hint: String?) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val customTitle = annotation?.title?.takeIf { it.isNotBlank() }
	val note = annotation?.note?.takeIf { it.isNotBlank() }
	Surface(
		color = colors.panelElevated,
		shape = RoundedCornerShape(3.dp),
		border = BorderStroke(1.dp, colors.border),
		elevation = 4.dp,
	) {
		Column(
			modifier = Modifier.widthIn(max = 280.dp).padding(horizontal = 8.dp, vertical = 6.dp),
			verticalArrangement = Arrangement.spacedBy(3.dp),
		) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text(
					text = customTitle ?: node.summary,
					style = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
					color = colors.textPrimary,
					modifier = Modifier.weight(1f, fill = false),
				)
				if (node.isHead) {
					Spacer(Modifier.width(6.dp))
					Text(
						text = tr("history.head"),
						style = typography.monoSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
						color = colors.highlight,
					)
				}
			}
			if (customTitle != null) {
				Text(text = node.summary, style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
			}
			if (note != null) {
				Row(verticalAlignment = Alignment.Top) {
					IconNote(tint = colors.textMuted, modifier = Modifier.padding(top = 2.dp).size(10.dp))
					Spacer(Modifier.width(4.dp))
					Text(text = note, style = typography.caption.copy(fontSize = 10.sp), color = colors.textPrimary)
				}
			}
			Row(verticalAlignment = Alignment.CenterVertically) {
				ActorBadge(actor = node.actor)
				Spacer(Modifier.width(6.dp))
				Text(
					text = node.createdAt.take(19).replace('T', ' '),
					style = typography.caption.copy(fontSize = 10.sp),
					color = colors.textMuted,
				)
				Spacer(Modifier.width(6.dp))
				Text(
					text = "#${node.id.takeLast(10)}",
					style = typography.monoSmall.copy(fontSize = 9.5.sp),
					color = colors.textMuted,
				)
			}
			if (hint != null) {
				Text(text = hint, style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textDisabled)
			}
		}
	}
}

/** How many steps of a merged run its card tooltip lists before it cuts off. */
private const val GROUP_TOOLTIP_ROWS = 8

/**
 * The tooltip of a merged card. The card shows the end of its run, so the tooltip adds how many
 * steps the run holds, its time span and the first few steps themselves: the left operations list
 * is where a step inside the run is picked for checkout.
 */
@Composable
private fun GroupTooltip(group: HistoryNodeGroup, annotations: Map<String, HistoryAnnotation>, hint: String?) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val node = group.target
	val annotation = annotations[node.id]
	val customTitle = annotation?.title?.takeIf { it.isNotBlank() }
	val note = annotation?.note?.takeIf { it.isNotBlank() }
	Surface(
		color = colors.panelElevated,
		shape = RoundedCornerShape(3.dp),
		border = BorderStroke(1.dp, colors.border),
		elevation = 4.dp,
	) {
		Column(
			modifier = Modifier.widthIn(max = 300.dp).padding(horizontal = 8.dp, vertical = 6.dp),
			verticalArrangement = Arrangement.spacedBy(3.dp),
		) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text(
					text = customTitle ?: node.summary,
					style = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
					color = colors.textPrimary,
					modifier = Modifier.weight(1f, fill = false),
				)
				if (group.isHead) {
					Spacer(Modifier.width(6.dp))
					Text(
						text = tr("history.head"),
						style = typography.monoSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
						color = colors.highlight,
					)
				}
			}
			if (customTitle != null) {
				Text(text = node.summary, style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
			}
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text(
					text = tr("history.group.steps", group.size),
					style = typography.caption.copy(fontSize = 10.sp),
					color = colors.textMuted,
				)
				Spacer(Modifier.width(6.dp))
				Text(
					text = "${shortTime(group.first.createdAt)} → ${shortTime(node.createdAt)}",
					style = typography.caption.copy(fontSize = 10.sp),
					color = colors.textMuted,
					maxLines = 1,
				)
			}
			group.nodes.take(GROUP_TOOLTIP_ROWS).forEach { member ->
				Text(
					text = "· " + (annotations[member.id]?.title?.takeIf { it.isNotBlank() } ?: member.summary),
					style = typography.caption.copy(fontSize = 10.sp),
					color = if (member.isHead) colors.highlight else colors.textMuted,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
			if (group.size > GROUP_TOOLTIP_ROWS) {
				Text(text = "…", style = typography.caption.copy(fontSize = 10.sp), color = colors.textDisabled)
			}
			if (note != null) {
				Row(verticalAlignment = Alignment.Top) {
					IconNote(tint = colors.textMuted, modifier = Modifier.padding(top = 2.dp).size(10.dp))
					Spacer(Modifier.width(4.dp))
					Text(text = note, style = typography.caption.copy(fontSize = 10.sp), color = colors.textPrimary)
				}
			}
			Row(verticalAlignment = Alignment.CenterVertically) {
				ActorBadge(actor = node.actor)
				Spacer(Modifier.width(6.dp))
				Text(
					text = node.createdAt.take(19).replace('T', ' '),
					style = typography.caption.copy(fontSize = 10.sp),
					color = colors.textMuted,
				)
				Spacer(Modifier.width(6.dp))
				Text(
					text = "#${node.id.takeLast(10)}",
					style = typography.monoSmall.copy(fontSize = 9.5.sp),
					color = colors.textMuted,
				)
			}
			if (hint != null) {
				Text(text = hint, style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textDisabled)
			}
		}
	}
}

/** Node count and zoom controls on a frosted pill, as the edit canvas shows its stats. */
@Composable
private fun ZoomPill(
	nodeCount: Int,
	segmentCount: Int?,
	scale: Float,
	onZoomOut: () -> Unit,
	onZoomIn: () -> Unit,
	onReset: () -> Unit,
	onFit: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(
		modifier = modifier
			.frostedGlass(shape = RoundedCornerShape(6.dp), elevation = 2.dp, alpha = 0.78f)
			.padding(horizontal = 3.dp, vertical = 2.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = if (segmentCount == null) tr("history.nodeCount", nodeCount)
			else tr("history.nodeCount.compressed", nodeCount, segmentCount),
			style = typography.caption.copy(fontSize = 11.sp),
			color = colors.textMuted,
			modifier = Modifier.padding(horizontal = 5.dp),
		)
		PillSeparator()
		PillButton(tooltip = tr("history.zoomOut"), onClick = onZoomOut) { tint ->
			IconZoomStep(plus = false, tint = tint)
		}
		PillButton(tooltip = tr("history.resetView"), onClick = onReset, width = 40.dp) { tint ->
			Text(
				text = "${(scale * 100).roundToInt()}%",
				style = typography.caption.copy(fontSize = 11.sp),
				color = tint,
				textAlign = TextAlign.Center,
				maxLines = 1,
			)
		}
		PillButton(tooltip = tr("history.zoomIn"), onClick = onZoomIn) { tint ->
			IconZoomStep(plus = true, tint = tint)
		}
		PillSeparator()
		PillButton(tooltip = tr("history.fitView"), onClick = onFit) { tint -> IconFitView(tint = tint) }
	}
}

@Composable
private fun PillSeparator() {
	Box(
		Modifier
			.padding(horizontal = 2.dp)
			.width(1.dp)
			.height(12.dp)
			.background(LocalToolColors.current.divider),
	)
}

/** Borderless pill button; the tint brightens on hover like the log header icons. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PillButton(
	tooltip: String,
	onClick: () -> Unit,
	width: androidx.compose.ui.unit.Dp = 20.dp,
	content: @Composable (Color) -> Unit,
) {
	val colors = LocalToolColors.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	TooltipArea(tooltip = { ParameterTooltip(tooltip) }, delayMillis = Motion.TooltipDelay) {
		Box(
			modifier = Modifier
				.width(width)
				.height(20.dp)
				.background(if (hovered) colors.controlHover else Color.Transparent, RoundedCornerShape(3.dp))
				.hoverable(interaction)
				.clickable(interactionSource = interaction, indication = null, onClick = onClick)
				.handCursor(),
			contentAlignment = Alignment.Center,
		) {
			content(if (hovered) colors.textPrimary else colors.textMuted)
		}
	}
}

/**
 * The selected node's details, annotation and checkout, on a floating card over the tree's
 * bottom-right corner. Rows follow the inspector's label-then-field form.
 */
@Composable
private fun NodeInspector(
	node: WorkspaceHistoryNodeSnapshot,
	annotation: HistoryAnnotation,
	canCheckout: Boolean,
	onClose: () -> Unit,
	onApply: (title: String, note: String, hidden: Boolean) -> Unit,
	onCheckout: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var title by remember(node.id, annotation) { mutableStateOf(annotation.title) }
	var note by remember(node.id, annotation) { mutableStateOf(annotation.note) }
	var hidden by remember(node.id, annotation) { mutableStateOf(annotation.hidden) }
	val dirty = title != annotation.title || note != annotation.note || hidden != annotation.hidden
	val shape = RoundedCornerShape(4.dp)

	Column(
		modifier = modifier
			.width(248.dp)
			.heightIn(max = 400.dp)
			.background(colors.panelElevated, shape)
			.border(BorderStroke(1.dp, colors.border), shape),
	) {
		// Header: who made the node, what it did, and whether it is HEAD.
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.height(26.dp)
				.padding(start = 8.dp, end = 3.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			ActorBadge(actor = node.actor)
			Spacer(Modifier.width(6.dp))
			Text(
				text = node.summary,
				style = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
				color = colors.textPrimary,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			if (node.isHead) {
				Spacer(Modifier.width(4.dp))
				Text(
					text = tr("history.head"),
					style = typography.monoSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
					color = colors.highlight,
				)
			}
			Spacer(Modifier.width(2.dp))
			CompactIconButton(onClick = onClose, size = 20.dp, tooltip = tr("workspace.close")) {
				IconClose(modifier = Modifier.size(8.dp), tint = colors.textMuted)
			}
		}
		Divider(color = colors.divider)

		Column(
			modifier = Modifier
				.fillMaxWidth()
				.verticalScroll(rememberScrollState())
				.padding(8.dp),
			verticalArrangement = Arrangement.spacedBy(4.dp),
		) {
			DetailRow(label = tr("history.time"), value = node.createdAt.take(19).replace('T', ' '))
			DetailRow(label = tr("history.nodeId"), value = node.id) {
				val copy = StringSelection(node.id)
				Toolkit.getDefaultToolkit().systemClipboard.setContents(copy, copy)
			}
			DetailRow(label = tr("history.parentId"), value = node.parentId ?: "root")
			DetailRow(label = tr("history.revisionId"), value = node.revisionId.take(16))

			Divider(color = colors.divider, modifier = Modifier.padding(vertical = 2.dp))

			FormRow(tr("project.historyTitle")) {
				CompactTextField(
					value = title,
					onValueChange = { title = it },
					placeholder = node.summary,
					modifier = Modifier.fillMaxWidth(),
					height = 22.dp,
				)
			}
			FormRow(tr("project.historyNote")) {
				CompactTextField(
					value = note,
					onValueChange = { note = it },
					modifier = Modifier.fillMaxWidth(),
					height = 22.dp,
				)
			}
			CompactCheckbox(
				checked = hidden,
				onCheckedChange = { hidden = it },
				label = tr("project.historyHide"),
				modifier = Modifier.padding(start = FORM_LABEL_WIDTH),
			)
		}

		Divider(color = colors.divider)
		Row(
			modifier = Modifier.fillMaxWidth().padding(8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			CompactButton(
				text = tr("project.historyApply"),
				onClick = { onApply(title, note, hidden) },
				enabled = dirty,
				height = 24.dp,
				modifier = Modifier.weight(1f),
			)
			if (node.isHead) {
				Text(
					text = tr("history.current"),
					style = typography.caption.copy(fontSize = 11.sp),
					color = colors.highlight,
					textAlign = TextAlign.Center,
					maxLines = 1,
					modifier = Modifier.weight(1f),
				)
			} else {
				CompactButton(
					text = tr("history.checkout"),
					onClick = onCheckout,
					enabled = canCheckout,
					isPrimary = true,
					height = 24.dp,
					modifier = Modifier.weight(1f),
				)
			}
		}
	}
}

private val FORM_LABEL_WIDTH = 64.dp

/** The inspector's form row: an end-aligned label, then the control. */
@Composable
private fun FormRow(label: String, content: @Composable () -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Text(
			text = label,
			style = typography.caption.copy(fontSize = 11.sp),
			color = colors.textPrimary,
			textAlign = TextAlign.End,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.width(FORM_LABEL_WIDTH).padding(end = 8.dp),
		)
		Box(Modifier.weight(1f)) { content() }
	}
}

/**
 * Photoshop-style operation list: the chain that led to the current state, oldest first and the
 * current state last, scrolling to keep it in view. Clicking a row rewinds the workspace to it --
 * the same checkout the tree card's "restore" runs, without hunting for the card on the canvas.
 *
 * Rows past the current one are not listed: they belong to the redo side, which the tree's branches
 * and Ctrl+Y already cover, and mixing them in would make "before the current state" ambiguous.
 */
@Composable
private fun OperationListSidebar(
	chain: List<WorkspaceHistoryNodeSnapshot>,
	annotations: Map<String, HistoryAnnotation>,
	enabled: Boolean,
	onCheckout: (String) -> Unit,
	paintSession: PaintSession? = null,
	onJumpToPaintStroke: ((Int) -> Unit)? = null,
	compressed: Boolean = false,
	groupOfNode: Map<String, String> = emptyMap(),
	selectedNodeId: String? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val listState = rememberLazyListState()
	val headId = chain.lastOrNull()?.id
	val selectedIndex = remember(chain, selectedNodeId) { chain.indexOfFirst { it.id == selectedNodeId } }
	// The newest operation is the one you step back from, so it stays in sight as the chain grows;
	// in the compact view a selected card also scrolls the list to the run it belongs to.
	val scrollTarget = if (compressed) selectedIndex.takeIf { it >= 0 } ?: chain.lastIndex else chain.lastIndex
	LaunchedEffect(headId, scrollTarget) {
		if (chain.isNotEmpty() && scrollTarget >= 0) listState.animateScrollToItem(scrollTarget)
	}

	Row(Modifier.fillMaxHeight()) {
		Column(
			modifier = Modifier
				.fillMaxHeight()
				.width(208.dp)
				.background(colors.panelBackground),
		) {
			SidebarHeader(title = tr("history.operations"), count = chain.size)

			if (paintSession != null) {
				SidebarHeader(
					title = tr("editor.paint.session", paintSession.layerName),
					trailing = tr("editor.paint.strokeCount", paintSession.strokeCount),
					accent = true,
				)
				LazyColumn(
					modifier = Modifier
						.fillMaxWidth()
						.heightIn(max = 160.dp),
				) {
					itemsIndexed(paintSession.strokeRecords) { idx, record ->
						ListRow(
							tooltip = { ParameterTooltip(record.name) },
							index = if (idx == 0) "•" else "$idx",
							title = record.name,
							current = idx == paintSession.currentStrokeIndex,
							future = idx > paintSession.currentStrokeIndex,
							enabled = enabled,
							onClick = { onJumpToPaintStroke?.invoke(idx) },
						)
					}
				}
				Divider(color = colors.divider)
			}

			LazyColumn(
				state = listState,
				modifier = Modifier
					.weight(1f)
					.fillMaxWidth(),
			) {
				itemsIndexed(chain, key = { _, node -> node.id }) { index, node ->
					Column {
						// A ruled line marks where the tree's merged run ends and the next one begins.
						if (compressed && index > 0 && groupOfNode[chain[index - 1].id] != groupOfNode[node.id]) {
							Box(
								Modifier
									.fillMaxWidth()
									.padding(horizontal = 6.dp)
									.height(1.dp)
									.background(colors.divider),
							)
						}
						ListRow(
							tooltip = {
								NodeTooltip(node, annotations[node.id], hint = if (node.isHead) null else tr("history.hint.click"))
							},
							index = "${index + 1}",
							// The annotations are what the tree cards show, so a renamed node reads the same here.
							title = annotations[node.id]?.title?.takeIf { it.isNotBlank() } ?: node.summary,
							current = node.isHead,
							selected = node.id == selectedNodeId,
							enabled = enabled,
							onClick = { onCheckout(node.id) },
							trailing = { ActorBadge(actor = node.actor, scale = 0.85f) },
						)
					}
				}
			}
		}
		Box(Modifier.fillMaxHeight().width(1.dp).background(colors.divider))
	}
}

@Composable
private fun SidebarHeader(title: String, count: Int? = null, trailing: String? = count?.toString(), accent: Boolean = false) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.height(24.dp)
			.background(if (accent) colors.accent.copy(alpha = 0.12f) else colors.panelElevated.copy(alpha = 0.55f))
			.padding(horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = title,
			style = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
			color = if (accent) colors.accent else colors.textPrimary,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		if (trailing != null) {
			Text(text = trailing, style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
		}
	}
}

/** A row of the operation or stroke list, drawn like the skeleton tree's rows. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListRow(
	tooltip: @Composable () -> Unit,
	index: String,
	title: String,
	current: Boolean,
	enabled: Boolean,
	onClick: () -> Unit,
	future: Boolean = false,
	selected: Boolean = false,
	trailing: (@Composable () -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val canRewind = enabled && !current

	TooltipArea(tooltip = tooltip, delayMillis = Motion.TooltipDelay) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.height(22.dp)
				.background(
					when {
						current -> colors.selection
						selected -> colors.selection.copy(alpha = 0.42f)
						hovered && canRewind -> colors.controlHover.copy(alpha = 0.3f)
						else -> Color.Transparent
					}
				)
				.hoverable(interaction)
				.handCursor(canRewind)
				.clickable(enabled = canRewind, interactionSource = interaction, indication = null, onClick = onClick)
				.padding(start = 6.dp, end = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = index,
				style = typography.monoSmall.copy(fontSize = 9.5.sp),
				color = if (current) colors.selectionText else colors.textDisabled,
				textAlign = TextAlign.End,
				modifier = Modifier.width(20.dp).padding(end = 6.dp),
			)
			Text(
				text = title,
				style = typography.body.copy(
					fontSize = 11.sp,
					fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
				),
				color = when {
					current -> colors.selectionText
					future -> colors.textDisabled
					else -> colors.textPrimary
				},
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			if (trailing != null) {
				Spacer(Modifier.width(4.dp))
				trailing()
			}
		}
	}
}

/** Actor chip in the theme's tag colors, shared with the log's source chips. */
@Composable
private fun ActorBadge(actor: String, scale: Float = 1f, alpha: Float = 1f) {
	val colors = LocalToolColors.current
	val (bg, fg, label) = when (actor.lowercase()) {
		"agent" -> Triple(colors.tagAgent, colors.tagAgentText, "Agent")
		"user" -> Triple(colors.tagUser, colors.tagUserText, "User")
		else -> Triple(colors.tagSystem, colors.tagSystemText, "System")
	}
	Box(
		modifier = Modifier
			.background(bg.copy(alpha = bg.alpha * alpha), RoundedCornerShape((2 * scale).dp))
			.padding(horizontal = (4 * scale).dp),
	) {
		Text(
			text = label,
			style = LocalToolTypography.current.monoSmall.copy(
				fontSize = (8.5 * scale).sp,
				lineHeight = (12 * scale).sp,
				fontWeight = FontWeight.Bold,
			),
			color = fg.copy(alpha = alpha),
			maxLines = 1,
		)
	}
}

/** A label and a monospaced value; with [onCopy] the value copies on click. */
@Composable
private fun DetailRow(label: String, value: String, onCopy: (() -> Unit)? = null) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Text(
			text = label,
			style = typography.caption.copy(fontSize = 11.sp),
			color = colors.textMuted,
			textAlign = TextAlign.End,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.width(FORM_LABEL_WIDTH).padding(end = 8.dp),
		)
		Text(
			text = value,
			style = typography.monoSmall.copy(fontSize = 10.sp),
			color = if (onCopy != null && hovered) colors.accent else colors.textPrimary,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f).then(
				if (onCopy == null) Modifier else Modifier
					.hoverable(interaction)
					.handCursor()
					.clickable(interactionSource = interaction, indication = null, onClick = onCopy)
			),
		)
		if (onCopy != null) {
			CompactIconButton(onClick = onCopy, size = 18.dp, tooltip = tr("history.copyId")) {
				IconCopy(tint = colors.textMuted)
			}
		}
	}
}

/** "2026-09-27T12:34:56Z" as "09-27 12:34", the part that tells nearby nodes apart. */
private fun shortTime(createdAt: String): String =
	createdAt.take(16).replace('T', ' ').let { if (it.length > 5 && it[4] == '-') it.drop(5) else it }

/** Three list lines with bullets: the operation list. */
@Composable
private fun IconOperationList(tint: Color) = GridIcon(Modifier.size(12.dp), tint) {
	listOf(4f, 9f, 14f).forEach { y ->
		dot(2.8f, y, 1.4f)
		line(6.4f, y, 15.8f, y)
	}
}

/** Two ends pressed together with arrows between: the compact view merges the versions in between. */
@Composable
private fun IconCompactHistory(tint: Color) = GridIcon(Modifier.size(12.dp), tint) {
	line(3.4f, 4f, 14.6f, 4f)
	line(3.4f, 14f, 14.6f, 14f)
	chevron(9f, 8.4f, 0f, 1f, 2.6f)
	chevron(9f, 9.6f, 0f, -1f, 2.6f)
}

@Composable
private fun IconZoomStep(plus: Boolean, tint: Color) = GridIcon(Modifier.size(9.dp), tint) {
	line(1.6f, 9f, 16.4f, 9f, 1.6f)
	if (plus) line(9f, 1.6f, 9f, 16.4f, 1.6f)
}

/** Four corner brackets: fit the whole tree in view. */
@Composable
private fun IconFitView(tint: Color) = GridIcon(Modifier.size(11.dp), tint) { brackets(1.8f, 1.8f, 16.2f, 16.2f, arm = 5f) }

/** A page with a folded corner and two lines: the node carries a note. */
@Composable
private fun IconNote(tint: Color, modifier: Modifier = Modifier.size(10.dp)) = GridIcon(modifier, tint) { document() }

/** Two overlapping sheets, as the log's copy button draws them. */
@Composable
private fun IconCopy(tint: Color) = GridIcon(Modifier.size(10.dp), tint) { copySheets() }

/**
 * Hierarchical tree positioning algorithm for rooted append-only DAG.
 * Uses DP units to remain completely independent of display density.
 */
internal data class TreeCalculationResult(
	val roots: List<TreeNodeLayout>,
	val allNodes: List<TreeNodeLayout>,
	val width: Float,
	val height: Float,
)

internal fun calculateTreeLayout(nodes: List<WorkspaceHistoryNodeSnapshot>): TreeCalculationResult {
	if (nodes.isEmpty()) return TreeCalculationResult(emptyList(), emptyList(), 0f, 0f)

	val layoutNodeMap = nodes.associate { it.id to TreeNodeLayout(it) }
	val roots = mutableListOf<TreeNodeLayout>()

	for (node in nodes) {
		val layoutNode = layoutNodeMap.getValue(node.id)
		val parent = node.parentId?.let(layoutNodeMap::get)
		if (parent != null) {
			parent.children.add(layoutNode)
		} else {
			roots.add(layoutNode)
		}
	}

	// Two iterative passes keep long MCP histories stack-safe and linear in node count.
	val traversal = mutableListOf<TreeNodeLayout>()
	val pending = java.util.ArrayDeque<TreeNodeLayout>()
	val visited = mutableSetOf<String>()

	roots.forEach { pending.add(it) }
	while (pending.isNotEmpty()) {
		val node = pending.removeFirst()
		if (!visited.add(node.node.id)) continue
		traversal.add(node)
		node.children.forEach {
			it.y = node.y + NODE_HEIGHT_DP + VERTICAL_GAP_DP
			pending.add(it)
		}
	}

	// Handle any detached/cyclic nodes safely so they don't overlap at (0, 0)
	for (node in layoutNodeMap.values) {
		if (node.node.id !in visited) {
			roots.add(node)
			pending.add(node)
			while (pending.isNotEmpty()) {
				val n = pending.removeFirst()
				if (!visited.add(n.node.id)) continue
				traversal.add(n)
				n.children.forEach {
					it.y = n.y + NODE_HEIGHT_DP + VERTICAL_GAP_DP
					pending.add(it)
				}
			}
		}
	}

	val widths = mutableMapOf<String, Float>()
	traversal.asReversed().forEach { node ->
		val childrenWidthSum = node.children.sumOf { widths.getValue(it.node.id).toDouble() }.toFloat()
		val totalGaps = (node.children.size - 1).coerceAtLeast(0) * HORIZONTAL_GAP_DP
		widths[node.node.id] = max(NODE_WIDTH_DP, childrenWidthSum + totalGaps)
	}

	val starts = mutableMapOf<String, Float>()
	var currentRootX = 0f
	roots.forEach {
		starts[it.node.id] = currentRootX
		currentRootX += widths.getValue(it.node.id) + HORIZONTAL_GAP_DP * 2f
	}

	traversal.forEach { node ->
		val nodeWidth = widths.getValue(node.node.id)
		var start = starts.getValue(node.node.id)
		node.x = start + (nodeWidth - NODE_WIDTH_DP) / 2f
		node.children.forEach { child ->
			starts[child.node.id] = start
			start += widths.getValue(child.node.id) + HORIZONTAL_GAP_DP
		}
	}

	val allList = layoutNodeMap.values.toList()
	val maxX = allList.maxOfOrNull { it.x + NODE_WIDTH_DP } ?: 0f
	val maxY = allList.maxOfOrNull { it.y + NODE_HEIGHT_DP } ?: 0f
	return TreeCalculationResult(roots, allList, maxX, maxY)
}

/** Bounds clip without a graphics layer, so a dock resize cannot leave the graph behind. */
private fun Modifier.clipRectToBounds(): Modifier = drawWithContent {
	clipRect { this@drawWithContent.drawContent() }
}

/** Rounded clip without a graphics layer. Node cards pan inside the graph and must move with it. */
private fun Modifier.clipShape(shape: Shape): Modifier = drawWithContent {
	val outline = shape.createOutline(size, layoutDirection, this)
	clipPath(Path().apply { addOutline(outline) }) {
		this@drawWithContent.drawContent()
	}
}
