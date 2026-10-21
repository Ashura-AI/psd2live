package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.withDerivedRenderRoot

/**
 * Anime-style features showing through the bangs: a copy of each eyebrow (and, when asked, each eye line) drawn just
 * above the front hair at partial opacity and clipped to it, so the feature reads through the hair while the
 * original keeps its place under it. The usual Live2D setup for this: duplicate the art mesh, raise its draw order
 * above the bangs, clip it with the bang meshes' ids and lower its opacity.
 *
 * The base rig adds the copies ([add]) and owns their own properties - masks, draw order, opacity - which edit like
 * any generated object's. Everything else the copy takes from its original after every replay ([follow]): parent
 * deformer, mesh, geometry keyforms, blend shapes, colors and visibility, with the original's opacity keyforms scaled
 * by the copy's opacity. So the copy moves with every deformer, keyform, journal edit and generated override of its
 * original and needs no keyforms of its own. A copy pairs with its original by id ([copyId]); one whose original or
 * every mask is gone is dropped.
 */
internal object SeeThroughLayers {
	/** Appended to the original's id: the copy's id, fixed for every build. */
	const val SUFFIX = "_SeeThrough"
	const val DEFAULT_OPACITY = 0.5f
	val OPACITY_RANGE = 0f..1f

	fun copyId(original: DrawableId) = DrawableId(original.raw + SUFFIX)

	/** The original [id] copies, or null when it is no copy. */
	fun originalOf(id: DrawableId): DrawableId? =
		if (id.raw.length > SUFFIX.length && id.raw.endsWith(SUFFIX)) DrawableId(id.raw.removeSuffix(SUFFIX)) else null

	fun isCopy(id: DrawableId) = originalOf(id) != null

	/** The roles [config] copies over the bangs. */
	fun copiedTags(config: PipelineConfig): Set<SemanticTag> = buildSet {
		if (config.meshOnly) return@buildSet
		if (config.seeThroughBrows) add(SemanticTag.EYEBROW)
		if (config.seeThroughEyes) { add(SemanticTag.EYELASH); add(SemanticTag.EYE_CLOSE) }
	}

	/**
	 * [model] with a copy of each drawable of a copied role ([copiedTags]) that the bangs cover: [layerOf] gives each
	 * head drawable's layer (null for any other). A feature's masks are the front hair drawables whose art reaches over
	 * it at rest; its copy draws just above the highest of them, below whatever came next, and is not selectable on the
	 * canvas - the original is the one to edit. A feature already drawn above its bangs gets none.
	 */
	fun add(model: PuppetModel, config: PipelineConfig, layerOf: (DrawableId) -> ClassifiedLayer?): PuppetModel {
		val tags = copiedTags(config)
		if (tags.isEmpty()) return model
		val bangs = model.drawables.filter { !isCopy(it.id) && layerOf(it.id)?.semantic?.tag == SemanticTag.FRONT_HAIR }
		if (bangs.isEmpty()) return model
		val present = model.drawables.mapTo(HashSet()) { it.id }
		val opacity = config.seeThroughOpacity.coerceIn(OPACITY_RANGE)
		val halfStep = (config.drawOrderStep / 2f).coerceAtLeast(0.5f)
		val copies = ArrayList<Pair<Drawable, DrawableId>>()
		for (drawable in model.drawables) {
			if (isCopy(drawable.id) || copyId(drawable.id) in present) continue
			val layer = layerOf(drawable.id)?.takeIf { it.semantic.tag in tags && it.opaquePixels > 0 } ?: continue
			val reach = layer.bounds.let { it.copy(top = it.top - it.height * 0.5f, bottom = it.bottom + it.height * 0.5f) }
			val masks = bangs.filter { mask -> layerOf(mask.id)?.let { covers(it, reach, config.alphaThreshold) } == true }
			if (masks.isEmpty()) continue
			val top = masks.maxOf(::highestOrder)
			if (highestOrder(drawable) >= top) continue
			val maskIds = masks.mapTo(HashSet()) { it.id }
			val next = model.drawables.filter { it.id != drawable.id && !isCopy(it.id) && it.id !in maskIds }
				.map { it.drawOrder }.filter { it > top }.minOrNull()
			val order = (top + if (next == null) halfStep else minOf(halfStep, (next - top) / 2f)).coerceIn(0f, 1000f)
			val copy = drawable.copy(id = copyId(drawable.id), name = tr("model.drawable.seeThrough", drawable.name),
				maskedBy = masks.map { it.id }, invertMask = false, drawOrder = order, opacity = opacity, isSelectable = false,
				userData = "")
			copies += sync(copy, drawable, copy.maskedBy) to masks.maxBy(::highestOrder).id
		}
		if (copies.isEmpty()) return model
		// Each copy lists first (front) in the part of the bangs it draws above.
		val parts = model.parts.map { part ->
			val joining = copies.filter { (_, mask) -> part.children.any { it is OrgChild.Drawable && it.id == mask } }
			if (joining.isEmpty()) part else part.copy(children = joining.map { OrgChild.Drawable(it.first.id) } + part.children)
		}
		val placed = parts.flatMap { it.children }.filterIsInstance<OrgChild.Drawable>().mapTo(HashSet()) { it.id }
		val loose = copies.map { it.first.id }.filter { it !in placed }.map { OrgChild.Drawable(it) }
		return model.copy(drawables = model.drawables + copies.map { it.first }, parts = parts,
			rootChildren = loose + model.rootChildren).withDerivedRenderRoot()
	}

	/**
	 * [model] with every copy following its original as it is now; a copy without its original or without any of its
	 * masks left is removed. The same model when it holds no copies.
	 */
	fun follow(model: PuppetModel): PuppetModel {
		if (model.drawables.none { isCopy(it.id) }) return model
		val byId = model.drawables.associateBy { it.id }
		val dropped = HashSet<DrawableId>()
		var changed = false
		val drawables = model.drawables.mapNotNull { drawable ->
			val original = originalOf(drawable.id)?.let(byId::get)
			if (originalOf(drawable.id) == null) return@mapNotNull drawable
			val masks = drawable.maskedBy.filter { it in byId && !isCopy(it) }
			if (original == null || masks.isEmpty()) { dropped += drawable.id; changed = true; return@mapNotNull null }
			sync(drawable, original, masks).also { if (it != drawable) changed = true }
		}
		if (!changed) return model
		if (dropped.isEmpty()) return model.copy(drawables = drawables)
		fun kept(children: List<OrgChild>) = children.filterNot { it is OrgChild.Drawable && it.id in dropped }
		return model.copy(drawables = drawables, parts = model.parts.map { it.copy(children = kept(it.children)) },
			rootChildren = kept(model.rootChildren)).withDerivedRenderRoot()
	}

	/** [copy] taking from [original] everything but its id, name, masks, draw order, opacity and selectability. */
	private fun sync(copy: Drawable, original: Drawable, masks: List<DrawableId>): Drawable {
		val alpha = copy.opacity
		val channels = original.channelGrids.let { grids ->
			if (grids.isEmpty) grids else {
				val tracks = LinkedHashMap(grids.gridsByChannel)
				val changed = tracks.remove(FormChannel.DRAW_ORDER) != null
				val opacity = tracks[FormChannel.OPACITY]
				if (opacity != null) tracks[FormChannel.OPACITY] = scaled(opacity, alpha)
				if (!changed && opacity == null) grids else ChannelGrids(tracks)
			}
		}
		val shapes = if (original.blendShapes.isEmpty()) original.blendShapes else original.blendShapes.map { binding ->
			binding.copy(forms = binding.forms.map { form ->
				form?.let { MeshForm(it.positionDeltas, copy.drawOrder, it.opacity * alpha, it.multiplyColor, it.screenColor) }
			})
		}
		val synced = copy.copy(parentDeformerId = original.parentDeformerId, blendMode = original.blendMode,
			maskedBy = masks, mesh = original.mesh, geometryGrid = original.geometryGrid,
			channelGrids = if (sameTracks(copy.channelGrids, channels)) copy.channelGrids else channels,
			multiplyColor = original.multiplyColor, screenColor = original.screenColor, alphaBlendMode = original.alphaBlendMode,
			culling = original.culling, isVisible = original.isVisible, textureSourceId = original.textureSourceId ?: original.id,
			texturePage = original.texturePage, atlasTileId = original.atlasTileId,
			blendShapes = if (copy.blendShapes == shapes) copy.blendShapes else shapes)
		return if (synced == copy) copy else synced
	}

	private fun scaled(track: KeyformGrid<ChannelValue>, alpha: Float): KeyformGrid<ChannelValue> = KeyformGrid(track.axes,
		track.cells.map { cell -> KeyformCell(cell.coordinate, (cell.form as? ChannelValue.Scalar)?.let { ChannelValue.Scalar(it.value * alpha) } ?: cell.form) })

	/** Whether two channel sets hold the same tracks: the same instances, or the same axes and cell values. */
	private fun sameTracks(a: ChannelGrids, b: ChannelGrids): Boolean {
		if (a === b) return true
		if (a.gridsByChannel.keys != b.gridsByChannel.keys) return false
		return a.gridsByChannel.all { (channel, x) ->
			val y = b.gridsByChannel.getValue(channel)
			x === y || (x.axes == y.axes && x.cells.size == y.cells.size && x.cells.indices.all { i ->
				x.cells[i].coordinate.contentEquals(y.cells[i].coordinate) && x.cells[i].form == y.cells[i].form
			})
		}
	}

	/** The highest draw order [drawable] takes at any keyform. */
	private fun highestOrder(drawable: Drawable): Float {
		val track = drawable.channelGrids[FormChannel.DRAW_ORDER] ?: return drawable.drawOrder
		return track.cells.maxOfOrNull { (it.form as? ChannelValue.Scalar)?.value ?: drawable.drawOrder } ?: drawable.drawOrder
	}

	/**
	 * Whether [hair]'s art covers any of [reach] at rest: some of its pixels there are above [threshold]. Its box alone
	 * would say yes for bangs swept aside, whose copies would only cost a mask.
	 */
	private fun covers(hair: ClassifiedLayer, reach: Bounds, threshold: Int): Boolean {
		val box = hair.bounds
		if (box.left >= reach.right || reach.left >= box.right || box.top >= reach.bottom || reach.top >= box.bottom) return false
		val layer = hair.source
		val raster = layer.raster
		if (raster.width <= 0 || raster.height <= 0 || layer.bounds.width <= 0 || layer.bounds.height <= 0) return false
		// The raster spans the layer's rectangle at its own resolution.
		val sx = raster.width / layer.bounds.width.toFloat(); val sy = raster.height / layer.bounds.height.toFloat()
		val x0 = ((reach.left - layer.bounds.left) * sx).toInt().coerceIn(0, raster.width)
		val x1 = kotlin.math.ceil((reach.right - layer.bounds.left) * sx).toInt().coerceIn(0, raster.width)
		val y0 = ((reach.top - layer.bounds.top) * sy).toInt().coerceIn(0, raster.height)
		val y1 = kotlin.math.ceil((reach.bottom - layer.bounds.top) * sy).toInt().coerceIn(0, raster.height)
		for (y in y0 until y1) for (x in x0 until x1)
			if ((raster.rgba[(y * raster.width + x) * 4 + 3].toInt() and 0xff) > threshold) return true
		return false
	}
}
