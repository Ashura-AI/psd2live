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
import org.umamo.runtime.model.visibleDrawableIds
import org.umamo.runtime.model.withDerivedRenderRoot

/**
 * Anime-style features showing through the bangs: a copy of each eyebrow (and, when asked, each eye line) drawn just
 * above the front hair at partial opacity and clipped to it, so the feature reads through the hair while the original
 * keeps its place under it. The usual Live2D setup for this: duplicate the art mesh, raise its draw order above the
 * bangs, clip it with the bang meshes' ids and lower its opacity.
 *
 * Two stages. The base rig records the [Candidate]s - each brow or eye line whose bangs cover it at rest, with those
 * bangs - in [BuiltRig.seeThrough], whatever the settings say. The finished rig ([apply]), after the journal, the
 * generators, the generated overrides, layer visibility and draw-order overrides, makes the copies the [Settings] ask
 * for out of the originals as they are then: so a copy follows every deformer, keyform, edit and override of its
 * original, takes its masks from the bangs still drawn above the original and its draw order from where those bangs
 * are now, and the settings change it without regenerating. The copies are not in the authored rig: nothing edits them
 * ([WorkspaceGeneratedCopyEdit]); [BuiltRig.seeThroughCopies] names them, never their ids alone.
 */
object SeeThroughLayers {
	/** Appended to the original's id: the copy's id, fixed for every build. */
	const val SUFFIX = "_SeeThrough"
	const val DEFAULT_OPACITY = 0.5f
	val OPACITY_RANGE = 0f..1f

	fun copyId(original: DrawableId) = DrawableId(original.raw + SUFFIX)

	/** A feature the bangs cover at rest, as the base rig found it: [eyes] for an eye line, else a brow. */
	data class Candidate(val original: String, val eyes: Boolean, val bangs: List<String>)

	/** Why a recorded candidate made no copy although the settings ask for it. */
	enum class DropReason(val wire: String) {
		/** The original is gone: deleted, split or replaced since the base found it. */
		ORIGINAL_MISSING("original_missing"),
		/** Every bang mesh the base found over it is gone. */
		MASKS_MISSING("masks_missing"),
		/** Another mesh already has the copy's id. */
		ID_TAKEN("id_taken"),
	}

	data class Note(val original: String, val reason: DropReason)

	/** What the document asks for: brows and eye lines, their opacity, and the gap between generated draw orders. */
	data class Settings(val brows: Boolean, val eyes: Boolean, val opacity: Float, val drawOrderStep: Int) {
		val enabled get() = brows || eyes

		companion object {
			val OFF = Settings(brows = false, eyes = false, opacity = DEFAULT_OPACITY, drawOrderStep = DRAW_ORDER_STEP)

			/** [config]'s settings; an imported or mesh-only model has no copies. */
			fun of(config: PipelineConfig): Settings =
				if (config.rigEdits.importedCmo3 != null || config.meshOnly) OFF
				else Settings(config.seeThroughBrows, config.seeThroughEyes, config.seeThroughOpacity.coerceIn(OPACITY_RANGE), config.drawOrderStep)
		}
	}

	/** The finished model, the copies it holds (copy id to original id) and the candidates that made none. */
	class Result(val model: PuppetModel, val copies: Map<String, String>, val notes: List<Note>)

	private val BROWS = setOf(SemanticTag.EYEBROW)
	private val EYES = setOf(SemanticTag.EYELASH, SemanticTag.EYE_CLOSE)

	/**
	 * Every brow and eye line of [model] that front hair art reaches over at rest, with those bangs: [layerOf] gives
	 * each head drawable's layer (null for any other). Draw order is not looked at here: it is read when the copies are
	 * made, after the user's edits of it.
	 */
	fun candidates(model: PuppetModel, alphaThreshold: Int, layerOf: (DrawableId) -> ClassifiedLayer?): List<Candidate> {
		val bangs = model.drawables.mapNotNull { drawable -> layerOf(drawable.id)?.takeIf { it.semantic.tag == SemanticTag.FRONT_HAIR }?.let { drawable.id to it } }
		if (bangs.isEmpty()) return emptyList()
		return model.drawables.mapNotNull { drawable ->
			val layer = layerOf(drawable.id)?.takeIf { (it.semantic.tag in BROWS || it.semantic.tag in EYES) && it.opaquePixels > 0 } ?: return@mapNotNull null
			val reach = layer.bounds.let { it.copy(top = it.top - it.height * 0.5f, bottom = it.bottom + it.height * 0.5f) }
			val covering = bangs.filter { (_, hair) -> covers(hair, reach, alphaThreshold) }.map { it.first.raw }
			if (covering.isEmpty()) null else Candidate(drawable.id.raw, layer.semantic.tag in EYES, covering)
		}
	}

	/**
	 * [model] with a copy of each of [candidates] [settings] asks for. A copy's masks are its bangs still in the model,
	 * shown, and drawn above the original at every keyform; with none it is not made. It draws just above the highest
	 * of them and below the next mesh, at its original's opacity (static and keyed) times [Settings.opacity], and lists
	 * just in front of its original in the original's part, so hiding or fading that part hides it too. Its parent,
	 * mesh, keyforms, blend shapes, colors and visibility are the original's; it cannot be picked on the canvas.
	 */
	fun apply(model: PuppetModel, candidates: List<Candidate>, settings: Settings): Result {
		if (!settings.enabled || candidates.isEmpty()) return Result(model, emptyMap(), emptyList())
		val byId = model.drawables.associateBy { it.id.raw }
		val shown by lazy { model.visibleDrawableIds() }
		val notes = ArrayList<Note>()
		val made = ArrayList<Pair<Drawable, Drawable>>()
		val halfStep = (settings.drawOrderStep / 2f).coerceAtLeast(0.5f)
		for (candidate in candidates) {
			if (if (candidate.eyes) !settings.eyes else !settings.brows) continue
			val original = byId[candidate.original] ?: run { notes += Note(candidate.original, DropReason.ORIGINAL_MISSING); continue }
			val id = copyId(original.id)
			if (id.raw in byId) { notes += Note(candidate.original, DropReason.ID_TAKEN); continue }
			val present = candidate.bangs.mapNotNull(byId::get)
			if (present.isEmpty()) { notes += Note(candidate.original, DropReason.MASKS_MISSING); continue }
			val above = highestOrder(original)
			val masks = present.filter { it.id in shown && lowestOrder(it) > above }
			if (masks.isEmpty()) continue
			val top = masks.maxOf(::highestOrder)
			val skipped = masks.mapTo(HashSet()) { it.id } + original.id
			val next = model.drawables.asSequence().filter { it.id !in skipped }.map(::lowestOrder).filter { it > top }.minOrNull()
			val order = (top + if (next == null) halfStep else minOf(halfStep, (next - top) / 2f)).coerceIn(0f, 1000f)
			made += copyOf(original, id, masks.map { it.id }, order, settings.opacity) to original
		}
		if (made.isEmpty()) return Result(model, emptyMap(), notes)
		val before = made.groupBy({ it.second.id }, { it.first.id })
		fun placed(children: List<OrgChild>) = children.flatMap { child ->
			if (child is OrgChild.Drawable) before[child.id].orEmpty().map { OrgChild.Drawable(it) } + child else listOf(child)
		}
		val finished = model.copy(drawables = model.drawables + made.map { it.first },
			parts = model.parts.map { part -> if (part.children.none { it is OrgChild.Drawable && it.id in before }) part else part.copy(children = placed(part.children)) },
			rootChildren = placed(model.rootChildren)).withDerivedRenderRoot()
		return Result(finished, made.associate { (copy, original) -> copy.id.raw to original.id.raw }, notes)
	}

	/**
	 * [model] with each of [copies] (copy id to original id) taking its original's parent, mesh and geometry keyforms
	 * again: what a canvas drag shows before the edit commits and the copies are made anew.
	 */
	fun refresh(model: PuppetModel, copies: Map<String, String>): PuppetModel {
		if (copies.isEmpty()) return model
		val byId = model.drawables.associateBy { it.id.raw }
		var changed = false
		val drawables = model.drawables.map { drawable ->
			val original = copies[drawable.id.raw]?.let(byId::get) ?: return@map drawable
			if (drawable.mesh === original.mesh && drawable.geometryGrid === original.geometryGrid &&
				drawable.parentDeformerId == original.parentDeformerId) return@map drawable
			changed = true
			drawable.copy(parentDeformerId = original.parentDeformerId, mesh = original.mesh, geometryGrid = original.geometryGrid)
		}
		return if (changed) model.copy(drawables = drawables) else model
	}

	private fun copyOf(original: Drawable, id: DrawableId, masks: List<DrawableId>, order: Float, alpha: Float): Drawable {
		val tracks = LinkedHashMap(original.channelGrids.gridsByChannel)
		tracks.remove(FormChannel.DRAW_ORDER)
		tracks[FormChannel.OPACITY]?.let { tracks[FormChannel.OPACITY] = scaled(it, alpha) }
		return original.copy(id = id, name = tr("model.drawable.seeThrough", original.name), maskedBy = masks, invertMask = false,
			channelGrids = if (tracks.isEmpty()) ChannelGrids.Empty else ChannelGrids(tracks), drawOrder = order,
			opacity = original.opacity * alpha, isSelectable = false, textureSourceId = original.textureSourceId ?: original.id,
			blendShapes = shapes(original, order, alpha), userData = "")
	}

	private fun shapes(original: Drawable, order: Float, alpha: Float) = original.blendShapes.map { binding ->
		binding.copy(forms = binding.forms.map { form -> form?.let { MeshForm(it.positionDeltas, order, it.opacity * alpha, it.multiplyColor, it.screenColor) } })
	}

	private fun scaled(track: KeyformGrid<ChannelValue>, alpha: Float): KeyformGrid<ChannelValue> = KeyformGrid(track.axes,
		track.cells.map { cell -> KeyformCell(cell.coordinate, (cell.form as? ChannelValue.Scalar)?.let { ChannelValue.Scalar(it.value * alpha) } ?: cell.form) })

	private fun orders(drawable: Drawable): List<Float> =
		drawable.channelGrids[FormChannel.DRAW_ORDER]?.cells?.mapNotNull { (it.form as? ChannelValue.Scalar)?.value }?.takeIf { it.isNotEmpty() }
			?: listOf(drawable.drawOrder)

	/** The highest draw order [drawable] takes at any keyform. */
	private fun highestOrder(drawable: Drawable) = orders(drawable).max()

	/** The lowest draw order [drawable] takes at any keyform. */
	private fun lowestOrder(drawable: Drawable) = orders(drawable).min()

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

/**
 * [this] with the copies over the bangs [settings] asks for made from its finished puppet ([SeeThroughLayers.apply]), on
 * their originals' pages. A rig whose base recorded no candidates (an imported one) is returned as it is.
 */
internal fun BuiltRig.withSeeThrough(settings: SeeThroughLayers.Settings): BuiltRig {
	val candidates = seeThrough.orEmpty()
	if (candidates.isEmpty() || !settings.enabled) return if (seeThroughCopies.isEmpty() && seeThroughNotes.isEmpty()) this
		else copy(seeThroughCopies = emptyMap(), seeThroughNotes = emptyList())
	val result = SeeThroughLayers.apply(puppet, candidates, settings)
	val pages = if (result.copies.isEmpty()) pageByDrawableId
		else pageByDrawableId + result.copies.mapNotNull { (copy, original) -> pageByDrawableId[original]?.let { copy to it } }
	return copy(puppet = result.model, pageByDrawableId = pages, seeThroughCopies = result.copies, seeThroughNotes = result.notes)
}
