package io.github.psd2live.ui.views

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.MaterialFolder
import io.github.psd2live.project.MaterialItem
import io.github.psd2live.project.MaterialKind
import io.github.psd2live.project.MaterialLibrary
import io.github.psd2live.ui.components.CompactSlider
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.IconAdd
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.IconFolder
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.utils.NativeFilePicker
import io.github.psd2live.ui.utils.rgbaImageBitmap
import io.github.psd2live.ui.views.texture.FloatingMenu
import io.github.psd2live.ui.views.texture.FloatingMenuDivider
import io.github.psd2live.ui.views.texture.FloatingMenuRow
import io.github.psd2live.ui.views.texture.FloatingMenuSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.umamo.format.art.LayerRaster

/**
 * Thumbnails of materials, made on demand off the UI thread and kept by pixel identity: a library of thousands
 * scrolls without decoding anything twice. Three sizes, by the longest side asked for; a wide clear margin is cut away.
 */
internal object MaterialThumbnails {
	private val tiers = intArrayOf(128, 512, 2048)
	private val cache = object : LinkedHashMap<Pair<Int, Int>, ImageBitmap>(64, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int, Int>, ImageBitmap>?) = size > 512
	}

	fun tier(px: Int): Int = tiers.firstOrNull { it >= px } ?: tiers.last()

	fun cached(raster: LayerRaster, px: Int): ImageBitmap? = synchronized(cache) { cache[System.identityHashCode(raster.rgba) to tier(px)] }

	fun make(raster: LayerRaster, px: Int): ImageBitmap {
		val tier = tier(px)
		val key = System.identityHashCode(raster.rgba) to tier
		synchronized(cache) { cache[key] }?.let { return it }
		return scaled(trimmed(raster) ?: raster, tier).also { synchronized(cache) { cache[key] = it } }
	}

	private fun scaled(raster: LayerRaster, tier: Int): ImageBitmap {
		val scale = minOf(1.0, tier.toDouble() / maxOf(raster.width, raster.height))
		val width = (raster.width * scale).toInt().coerceAtLeast(1); val height = (raster.height * scale).toInt().coerceAtLeast(1)
		val rgba = if (scale == 1.0) raster.rgba else ByteArray(width * height * 4).also { out ->
			// Box filter over each output pixel's footprint: small thumbnails of fine line art stay legible.
			for (y in 0 until height) {
				val y0 = (y / scale).toInt(); val y1 = (((y + 1) / scale).toInt()).coerceIn(y0 + 1, raster.height)
				for (x in 0 until width) {
					val x0 = (x / scale).toInt(); val x1 = (((x + 1) / scale).toInt()).coerceIn(x0 + 1, raster.width)
					var r = 0L; var g = 0L; var b = 0L; var a = 0L; var n = 0
					for (sy in y0 until y1) for (sx in x0 until x1) {
						val o = (sy * raster.width + sx) * 4
						val alpha = raster.rgba[o + 3].toInt() and 255
						r += (raster.rgba[o].toInt() and 255) * alpha; g += (raster.rgba[o + 1].toInt() and 255) * alpha
						b += (raster.rgba[o + 2].toInt() and 255) * alpha; a += alpha; n++
					}
					val o = (y * width + x) * 4
					if (a > 0) { out[o] = (r / a).toByte(); out[o + 1] = (g / a).toByte(); out[o + 2] = (b / a).toByte() }
					out[o + 3] = (a / n).toByte()
				}
			}
		}
		return rgbaImageBitmap(width, height, rgba)
	}

	/**
	 * [raster] cut to its visible pixels when a clear margin takes much of it - a PSD layer kept at the size of its
	 * canvas - so the thumbnail shows the drawing rather than empty space; null when there is little to cut.
	 */
	private fun trimmed(raster: LayerRaster): LayerRaster? {
		var left = raster.width; var top = raster.height; var right = -1; var bottom = -1
		for (y in 0 until raster.height) for (x in 0 until raster.width) if (raster.rgba[(y * raster.width + x) * 4 + 3].toInt() != 0) {
			if (x < left) left = x; if (x > right) right = x; if (y < top) top = y; if (y > bottom) bottom = y
		}
		if (right < 0) return null
		val width = right - left + 1; val height = bottom - top + 1
		if (width.toLong() * height * 10 > raster.width.toLong() * raster.height * 8) return null
		val rgba = ByteArray(width * height * 4)
		for (y in 0 until height) System.arraycopy(raster.rgba, ((top + y) * raster.width + left) * 4, rgba, y * width * 4, width * 4)
		return LayerRaster(width, height, rgba)
	}
}

/** A material's thumbnail at [px] pixels, a placeholder until it is made. */
@Composable
private fun MaterialThumbnail(raster: LayerRaster, px: Int, modifier: Modifier) {
	val image by produceState(MaterialThumbnails.cached(raster, px), raster, MaterialThumbnails.tier(px)) {
		if (value == null) value = withContext(Dispatchers.Default) { MaterialThumbnails.make(raster, px) }
	}
	val colors = LocalToolColors.current
	Box(modifier.background(colors.inputBackground).checkerboard(), contentAlignment = Alignment.Center) {
		image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(2.dp)) }
	}
}

/** A light checkerboard behind transparent pixels. */
private fun Modifier.checkerboard(cell: Dp = 6.dp): Modifier = this.then(Modifier.drawBehindChecker(cell))

private fun Modifier.drawBehindChecker(cell: Dp): Modifier = this.drawBehind { checker(cell.toPx()) }

private fun DrawScope.checker(cell: Float) {
	val dark = Color(0x14000000)
	var y = 0f; var row = 0
	while (y < size.height) {
		var x = if (row % 2 == 0) 0f else cell
		while (x < size.width) {
			drawRect(dark, Offset(x, y), androidx.compose.ui.geometry.Size(minOf(cell, size.width - x), minOf(cell, size.height - y)))
			x += cell * 2
		}
		y += cell; row++
	}
}

/** Where the panel's first entry and the generation button sit, in root pixels, for the window tools that drive them. */
internal object MaterialsPanelProbe {
	@Volatile var firstEntry: androidx.compose.ui.geometry.Rect? = null
	@Volatile var generateButton: androidx.compose.ui.geometry.Rect? = null
}

private enum class MaterialView { GRID, LIST }

/** What a material panel row or tile stands for. */
private sealed interface MaterialEntry {
	val id: String
	val name: String
	data class Folder(val folder: MaterialFolder) : MaterialEntry { override val id get() = folder.id; override val name get() = folder.name }
	data class Item(val item: MaterialItem) : MaterialEntry { override val id get() = item.id; override val name get() = item.name }
}

/**
 * The material browser: one view of the open folder - its folders, then its items, as a grid or a list - under a path
 * bar that walks back up. Double-click opens a folder, or an item in the material window to look at and paint; an item
 * or folder dragged onto an edit canvas is placed where it lands, onto a folder tile or a path segment it moves there.
 * Files dropped from the system import into the folder under the pointer. Every change is one undoable document edit.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun MaterialsPanel(state: PSD2LiveState, vm: PSD2LiveViewModel) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val window = LocalAwtWindow.current
	val library = state.layout.materials
	val references = remember(state.layout.layerMaterials, state.analysis?.source) {
		val present = state.analysis?.source?.layers?.mapTo(HashSet()) { it.id.raw }.orEmpty() - state.deletedLayerIds
		state.layout.layerMaterials.filterKeys { it in present }.entries.groupBy({ it.value }, { it.key })
	}
	var folderId by remember { mutableStateOf<String?>(null) }
	if (folderId != null && library.folder(folderId!!) == null) folderId = null
	var view by remember { mutableStateOf(MaterialView.GRID) }
	var query by remember { mutableStateOf("") }
	var thumb by remember { mutableStateOf(88f) }
	var selected by remember { mutableStateOf<String?>(null) }
	var menu by remember { mutableStateOf<String?>(null) }
	var renaming by remember { mutableStateOf<String?>(null) }
	var resolution by remember { mutableStateOf<String?>(null) }
	val loaded = state.analysis != null

	// Where each folder target sits in the window: folder tiles and rows, and the path segments (ROOT for the top).
	val targets = remember { mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
	fun targetAt(at: Offset): String? = targets.entries.firstOrNull { at in it.value }?.key
	var panelCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
	DisposableEffect(panelCoordinates) {
		vm.materialsImportHitTest = hit@{ windowX, windowY ->
			val panel = panelCoordinates?.takeIf { it.isAttached } ?: return@hit null
			val origin = panel.positionInWindow()
			val x = windowX - origin.x; val y = windowY - origin.y
			if (x < 0 || y < 0 || x > panel.size.width || y > panel.size.height) return@hit null
			val folder = targetAt(Offset(windowX.toFloat(), windowY.toFloat()))
			PSD2LiveViewModel.MaterialsDropTarget(if (folder == null) folderId else folder.takeUnless { it == ROOT })
		}
		onDispose { if (vm.materialsImportHitTest != null) vm.materialsImportHitTest = null }
	}
	// A folder cannot move into itself or below itself.
	fun accepts(carried: String, target: String): Boolean {
		if (target == ROOT) return true
		if (target == carried) return false
		return library.folder(carried) == null || library.ancestry(target).none { it.id == carried }
	}
	// The folder a carried material would move into, lit while the pointer is over it.
	val drag by vm.materialDrag.collectAsState()
	val dropFolder = drag?.let { carried -> targetAt(carried.position)?.takeIf { accepts(carried.id, it) } }
	fun drop(): Boolean {
		val carried = vm.materialDrag.value ?: return false
		val target = targetAt(carried.position) ?: return false
		vm.cancelMaterialDrag()
		val into = target.takeUnless { it == ROOT }
		val from = library.item(carried.id)?.folderId ?: library.folder(carried.id)?.parentId
		if (accepts(carried.id, target) && into != from) vm.moveMaterials(listOf(carried.id), into)
		return true
	}
	fun track(id: String) = Modifier.onGloballyPositioned { c ->
		if (c.isAttached) targets[id] = androidx.compose.ui.geometry.Rect(c.positionInWindow(),
			androidx.compose.ui.geometry.Size(c.size.width.toFloat(), c.size.height.toFloat()))
	}

	fun entries(): List<MaterialEntry> {
		val needle = query.trim()
		val folders = library.folders.filter { if (needle.isEmpty()) it.parentId == folderId else it.name.contains(needle, true) || it.tags.any { t -> t.equals(needle, true) } }
		val items = library.items.filter {
			if (needle.isEmpty()) it.folderId == folderId
			else it.name.contains(needle, true) || (it.tags + library.ancestry(it.folderId).flatMap { f -> f.tags }).any { t -> t.equals(needle, true) }
		}
		return folders.map { MaterialEntry.Folder(it) } + items.map { MaterialEntry.Item(it) }
	}
	val shown = entries()
	// Targets of another folder's tiles do not linger.
	val live = remember(shown, folderId) { shown.mapTo(HashSet()) { it.id } + ROOT + library.ancestry(folderId).map { it.id } }
	SideEffect { targets.keys.retainAll { it in live } }

	Column(Modifier.fillMaxSize().onGloballyPositioned { panelCoordinates = it }) {
		val labels = listOf(tr("materials.import"), tr("materials.folder.new"))
		PanelToolbar(labels = labels, iconCount = 3, search = PanelSearch(query, { query = it }, tr("materials.search")), reservedWidth = 40.dp) { labelsShown ->
			PanelToolButton(labels[0], showLabel = labelsShown > 0, enabled = loaded, tooltip = tr("materials.import.tip"), onClick = {
				val files = NativeFilePicker.chooseMaterialFiles(window)
				if (files.isNotEmpty()) vm.importMaterials(files.map { it.toPath() }, folderId)
			}) { IconAdd(Modifier.size(11.dp), colors.textPrimary) }
			PanelToolButton(labels[1], showLabel = labelsShown > 1, enabled = loaded, tooltip = tr("materials.folder.new"), onClick = {
				vm.createMaterialFolder(uniqueName(library, folderId, tr("materials.folder.default")), folderId)
			}) { IconFolder(Modifier.size(12.dp), colors.textPrimary) }
			Spacer(Modifier.weight(1f))
			PanelToolButton(tr("materials.view.grid"), showLabel = false, enabled = true, tooltip = tr("materials.view.grid"), active = view == MaterialView.GRID,
				onClick = { view = MaterialView.GRID }) { GridGlyph(colors.textPrimary) }
			PanelToolButton(tr("materials.view.list"), showLabel = false, enabled = true, tooltip = tr("materials.view.list"), active = view == MaterialView.LIST,
				onClick = { view = MaterialView.LIST }) { ListGlyph(colors.textPrimary) }
		}
		// The path to the open folder: back up a level, or to any folder above; each segment takes a dragged material.
		Row(Modifier.fillMaxWidth().height(24.dp).background(colors.windowBackground).padding(horizontal = 4.dp),
			verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
			val parent = folderId?.let { library.folder(it)?.parentId }
			val up = folderId != null && query.isBlank()
			Box(Modifier.size(20.dp).clip(RoundedCornerShape(3.dp)).clickable(enabled = up) { folderId = parent }, contentAlignment = Alignment.Center) {
				Text("↑", color = if (up) colors.textPrimary else colors.textMuted.copy(alpha = 0.4f), fontSize = 12.sp)
			}
			if (query.isNotBlank()) Text(tr("materials.searching", query.trim()), color = colors.textMuted, fontSize = 10.5.sp, maxLines = 1,
				overflow = TextOverflow.Ellipsis)
			else {
				Crumb(tr("materials.root"), folderId == null, dropFolder == ROOT, track(ROOT)) { folderId = null }
				for (folder in library.ancestry(folderId).reversed()) {
					Text("›", color = colors.textMuted, fontSize = 10.sp)
					Crumb(folder.name, folder.id == folderId, dropFolder == folder.id, track(folder.id)) { folderId = folder.id }
				}
			}
		}
		Divider(color = colors.divider)
		if (!loaded) {
			Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				Text(tr("canvas.preview.empty"), style = typography.caption.copy(fontSize = 11.sp), color = colors.textMuted)
			}
			return@Column
		}
		Box(Modifier.weight(1f).fillMaxWidth()) {
			if (shown.isEmpty()) Text(if (query.isBlank()) tr("materials.empty") else tr("materials.noMatch"), style = typography.caption.copy(fontSize = 11.sp),
				color = colors.textMuted, modifier = Modifier.align(Alignment.Center).padding(12.dp))
			val select: (MaterialEntry) -> Unit = { entry -> selected = entry.id; if (entry is MaterialEntry.Item) vm.showInMaterialEditor(entry.id) }
			val open: (MaterialEntry) -> Unit = { entry -> if (entry is MaterialEntry.Folder) { folderId = entry.id; query = "" } else vm.openMaterialEditor(entry.id) }
			when (view) {
				MaterialView.GRID -> LazyVerticalGrid(GridCells.Adaptive(thumb.dp + 14.dp), Modifier.fillMaxSize().padding(4.dp)) {
					items(shown, key = { it.id }) { entry ->
						MaterialTile(entry, thumb.dp, entry.id == selected, entry.id == dropFolder, references[entry.id].orEmpty().size, vm,
							if (entry is MaterialEntry.Folder) track(entry.id) else Modifier,
							{ select(entry) }, { open(entry) }, { select(entry); menu = entry.id }, ::drop, first = entry == shown.first())
					}
				}
				MaterialView.LIST -> LazyColumn(Modifier.fillMaxSize()) {
					items(shown, key = { it.id }) { entry ->
						MaterialRow(entry, entry.id == selected, entry.id == dropFolder, references[entry.id].orEmpty().size, vm,
							if (entry is MaterialEntry.Folder) track(entry.id) else Modifier,
							{ select(entry) }, { open(entry) }, { select(entry); menu = entry.id }, ::drop)
					}
				}
			}
			// The context menu of the entry right-clicked, dropping from the panel's top right.
			val target = menu
			FloatingMenu(expanded = target != null, onDismiss = { menu = null }, width = 230.dp, alignment = Alignment.TopEnd) {
				val item = target?.let(library::item); val folder = target?.let(library::folder)
				if (item != null || folder != null) FloatingMenuSection(item?.name ?: folder!!.name)
				if (item != null) FloatingMenuRow(tr("materials.menu.edit"), { menu = null; vm.openMaterialEditor(item.id) }, hint = tr("materials.menu.edit.hint"))
				if (folder != null) FloatingMenuRow(tr("materials.menu.open"), { menu = null; folderId = folder.id; query = "" })
				FloatingMenuRow(tr("materials.menu.place"), { menu = null; target?.let { vm.placeMaterial(it) } }, hint = tr("materials.menu.place.hint"))
				val layer = state.selectedLayerId
				if (item != null && layer != null) FloatingMenuRow(tr("materials.menu.replace"), { menu = null; vm.replaceLayerWithMaterial(layer, item.id) },
					hint = tr("materials.menu.replace.hint"))
				if (item?.kind == MaterialKind.VECTOR) FloatingMenuRow(tr("materials.menu.resolution"), { menu = null; resolution = item.id },
					trailing = "${item.raster.width}×${item.raster.height}")
				val users = target?.let { references[it] }.orEmpty()
				if (users.isNotEmpty()) FloatingMenuRow(tr("materials.menu.locate", users.size), { menu = null; vm.selectLayer(users.first()) })
				FloatingMenuDivider()
				FloatingMenuRow(tr("materials.menu.rename"), { menu = null; renaming = target })
				if (folder != null) {
					FloatingMenuRow(tr("materials.folder.new"), { menu = null; vm.createMaterialFolder(uniqueName(library, folder.id, tr("materials.folder.default")), folder.id) })
					FloatingMenuRow(tr("materials.menu.exportPsd"), {
						menu = null
						NativeFilePicker.chooseSavePsdFile(window, folder.name)?.let { vm.exportMaterialFolder(folder.id, java.nio.file.Path.of(it)) }
					})
				}
				if (folderId != null) FloatingMenuRow(tr("materials.menu.moveUp"), { menu = null; target?.let { vm.moveMaterials(listOf(it), library.folder(folderId!!)?.parentId) } })
				FloatingMenuRow(tr("materials.menu.delete"), { menu = null; target?.let { vm.deleteMaterials(listOf(it)) } },
					enabled = users.isEmpty(), hint = if (users.isEmpty()) null else tr("materials.menu.delete.used", users.size))
			}
		}
		Divider(color = colors.divider)
		Row(Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			val chosen = selected?.let(library::item)
			Text(chosen?.let { "${it.name} · ${it.raster.width}×${it.raster.height}" } ?: tr("materials.count", library.items.size, library.folders.size),
				style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted,
				modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
			if (view == MaterialView.GRID) CompactSlider(thumb, { thumb = it }, valueRange = 48f..200f, modifier = Modifier.width(90.dp), height = 12.dp)
		}
	}

	renaming?.let { id ->
		val current = library.item(id)?.name ?: library.folder(id)?.name ?: run { renaming = null; return@let }
		NameDialog(tr("materials.menu.rename"), current, onDismiss = { renaming = null }) { name -> renaming = null; vm.renameMaterial(id, name) }
	}
	resolution?.let { id ->
		val item = library.item(id) ?: run { resolution = null; return@let }
		NameDialog(tr("materials.menu.resolution"), "${item.raster.width}x${item.raster.height}", onDismiss = { resolution = null }) { text ->
			resolution = null
			val parts = text.lowercase().split('x', '×', ' ', ',').mapNotNull { it.trim().toIntOrNull() }
			if (parts.size == 2) vm.setMaterialResolution(id, parts[0], parts[1])
			else vm.setErrorMessage(tr("materials.resolution.invalid"))
		}
	}
}

private const val ROOT = "#root"

private fun uniqueName(library: MaterialLibrary, parent: String?, base: String): String {
	val names = library.folders.filter { it.parentId == parent }.mapTo(HashSet()) { it.name }
	return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it !in names }
}

@Composable
private fun Crumb(label: String, current: Boolean, dropping: Boolean, modifier: Modifier, onClick: () -> Unit) {
	val colors = LocalToolColors.current
	Text(label, color = if (current) colors.textPrimary else colors.textMuted, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
		modifier = modifier.widthIn(max = 140.dp).clip(RoundedCornerShape(3.dp)).background(if (dropping) colors.accent.copy(alpha = 0.25f) else Color.Transparent)
			.clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp))
}

/**
 * Press, drag to an edit canvas to place or onto a folder to move, click to select, double-click to open, right-click
 * for the menu. [onDrop] takes a drop the panel handles itself (onto a folder) and says so.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.materialGestures(entry: MaterialEntry, vm: PSD2LiveViewModel, onSelect: () -> Unit, onOpen: () -> Unit,
                                      onMenu: () -> Unit, onDrop: () -> Boolean, origin: () -> Offset): Modifier = this
	.onPointerEvent(PointerEventType.Press) { event -> if (event.button == PointerButton.Secondary) onMenu() }
	.pointerInput(entry.id) { detectTapGestures(onTap = { onSelect() }, onDoubleTap = { onOpen() }) }
	.pointerInput(entry.id) {
		detectDragGestures(
			onDragStart = { at -> onSelect(); vm.startMaterialDrag(entry.id, entry.name, origin() + at) },
			onDrag = { change, _ -> change.consume(); vm.moveMaterialDrag(origin() + change.position) },
			onDragEnd = { if (!onDrop()) vm.endMaterialDrag() },
			onDragCancel = { vm.cancelMaterialDrag() },
		)
	}

@Composable
private fun MaterialTile(entry: MaterialEntry, size: Dp, selected: Boolean, dropping: Boolean, references: Int, vm: PSD2LiveViewModel, target: Modifier,
                         onSelect: () -> Unit, onOpen: () -> Unit, onMenu: () -> Unit, onDrop: () -> Boolean, first: Boolean = false) {
	val colors = LocalToolColors.current
	var origin by remember { mutableStateOf(Offset.Zero) }
	val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
	val shape = RoundedCornerShape(5.dp)
	Column(target.padding(3.dp).clip(shape)
		.background(when { dropping -> colors.accent.copy(alpha = 0.25f); selected -> colors.selection; else -> Color.Transparent })
		.border(if (dropping) 1.dp else 0.5.dp, if (dropping || selected) colors.accent.copy(alpha = 0.7f) else Color.Transparent, shape)
		.onGloballyPositioned {
			origin = it.positionInRoot()
			if (first) MaterialsPanelProbe.firstEntry = androidx.compose.ui.geometry.Rect(origin, androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat()))
		}
		.materialGestures(entry, vm, onSelect, onOpen, onMenu, onDrop) { origin }
		.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
		Box(Modifier.size(size)) {
			when (entry) {
				is MaterialEntry.Item -> MaterialThumbnail(entry.item.raster, px, Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp)))
				is MaterialEntry.Folder -> Box(Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp)).background(colors.panelElevated.copy(alpha = 0.5f)),
					contentAlignment = Alignment.Center) { IconFolder(Modifier.size(size * 0.45f), if (dropping) colors.accent else colors.textMuted) }
			}
			if (references > 0) Text("$references", color = colors.selectionText, fontSize = 9.sp,
				modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).clip(RoundedCornerShape(6.dp)).background(colors.accent).padding(horizontal = 4.dp))
			if (entry is MaterialEntry.Item && entry.item.kind == MaterialKind.VECTOR) Text("SVG", color = colors.textMuted, fontSize = 8.sp,
				modifier = Modifier.align(Alignment.BottomStart).padding(2.dp).background(colors.panelElevated.copy(alpha = 0.8f)).padding(horizontal = 2.dp))
		}
		Text(entry.name, color = if (selected) colors.selectionText else colors.textPrimary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
			textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.width(size).padding(top = 3.dp))
	}
}

@Composable
private fun MaterialRow(entry: MaterialEntry, selected: Boolean, dropping: Boolean, references: Int, vm: PSD2LiveViewModel, target: Modifier,
                        onSelect: () -> Unit, onOpen: () -> Unit, onMenu: () -> Unit, onDrop: () -> Boolean) {
	val colors = LocalToolColors.current
	var origin by remember { mutableStateOf(Offset.Zero) }
	Row(target.fillMaxWidth().height(30.dp)
		.background(when { dropping -> colors.accent.copy(alpha = 0.25f); selected -> colors.selection; else -> Color.Transparent })
		.onGloballyPositioned { origin = it.positionInRoot() }
		.materialGestures(entry, vm, onSelect, onOpen, onMenu, onDrop) { origin }
		.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		when (entry) {
			is MaterialEntry.Item -> MaterialThumbnail(entry.item.raster, 48, Modifier.size(24.dp).clip(RoundedCornerShape(2.dp)))
			is MaterialEntry.Folder -> Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { IconFolder(Modifier.size(16.dp), colors.textMuted) }
		}
		val text = if (selected) colors.selectionText else colors.textPrimary
		Text(entry.name, color = text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
		when (entry) {
			is MaterialEntry.Item -> {
				Text(if (entry.item.kind == MaterialKind.VECTOR) "SVG" else tr("materials.kind.bitmap"), color = colors.textMuted, fontSize = 10.sp)
				Text("${entry.item.raster.width}×${entry.item.raster.height}", color = colors.textMuted, fontSize = 10.sp)
				Text(if (references > 0) tr("materials.references", references) else tr("materials.unused"), color = colors.textMuted, fontSize = 10.sp)
			}
			is MaterialEntry.Folder -> Text("›", color = colors.textMuted, fontSize = 12.sp)
		}
	}
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
	val colors = LocalToolColors.current
	var text by remember { mutableStateOf(initial) }
	androidx.compose.ui.window.Popup(alignment = Alignment.Center, onDismissRequest = onDismiss,
		properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
		Column(Modifier.width(240.dp).clip(RoundedCornerShape(6.dp)).background(colors.panelElevated)
			.border(0.5.dp, colors.border, RoundedCornerShape(6.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(title, color = colors.textPrimary, fontSize = 12.sp)
			CompactTextField(text, { text = it }, selectAllOnFocus = true, onCommit = { if (text.isNotBlank()) onConfirm(text.trim()) },
				modifier = Modifier.fillMaxWidth())
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
				Text(tr("action.cancel"), color = colors.textMuted, fontSize = 11.sp,
					modifier = Modifier.clip(RoundedCornerShape(3.dp)).clickable(onClick = onDismiss).padding(horizontal = 8.dp, vertical = 3.dp))
				Text(tr("dialog.ok"), color = colors.accent, fontSize = 11.sp,
					modifier = Modifier.clip(RoundedCornerShape(3.dp)).clickable { if (text.isNotBlank()) onConfirm(text.trim()) }.padding(horizontal = 8.dp, vertical = 3.dp))
			}
		}
	}
}

/** The material carried to a canvas, following the pointer over the window. */
@Composable
internal fun MaterialDragGhost(vm: PSD2LiveViewModel) {
	val drag by vm.materialDrag.collectAsState()
	val current = drag ?: return
	val colors = LocalToolColors.current
	androidx.compose.ui.window.Popup(offset = androidx.compose.ui.unit.IntOffset(current.position.x.toInt() + 12, current.position.y.toInt() + 12)) {
		Text(current.label, color = colors.textPrimary, fontSize = 11.sp, maxLines = 1,
			modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(colors.panelElevated.copy(alpha = 0.92f))
				.border(0.5.dp, colors.accent, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp))
	}
}

@Composable private fun GridGlyph(tint: Color) = io.github.psd2live.ui.components.GridIcon(Modifier.size(12.dp), tint) {
	listOf(3f to 3f, 10f to 3f, 3f to 10f, 10f to 10f).forEach { (x, y) -> box(x, y, 5f, 5f, 1f) }
}
@Composable private fun ListGlyph(tint: Color) = io.github.psd2live.ui.components.GridIcon(Modifier.size(12.dp), tint) {
	listOf(4f, 9f, 14f).forEach { y -> line(2.5f, y, 4f, y); line(6.5f, y, 15.5f, y) }
}
