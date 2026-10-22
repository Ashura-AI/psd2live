package io.github.psd2live.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.core.textureLayer
import io.github.psd2live.core.interior.*
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.InteriorDraft
import org.umamo.runtime.model.DeformPathPoint

/**
 * The interior tool: an interior effect's virtual surface, propagation direction and guides as canvas handles on the
 * selected mesh. Handles edit the effect's draft - the one the Interior panel shows, so both stay in step - and a
 * gesture commits once on release as one `interior_put`, inside one editor gesture so it records one history step.
 * While the effect is auditioned the drag previews through the same command on a candidate
 * ([io.github.psd2live.ui.state.InteriorController.previewLater]).
 */
internal class InteriorCanvasTool(private val editor: CanvasEditor) {
	/** What a press lands on. */
	sealed interface Part {
		data class Handle(val handle: InteriorHandle) : Part
		data class Point(val guide: Int, val point: Int) : Part
		data class Segment(val guide: Int, val segment: Int) : Part
		data class Radius(val guide: Int) : Part
	}

	/** The effect the tool works on, with everything needed to place it on screen. */
	class Scene(
		val target: String,
		val effect: NativeInteriorEffect,
		val draft: InteriorDraft,
		val map: InteriorCanvasMap,
		val mesh: CanvasTarget,
		val surface: InteriorFrame?,
		val propagation: InteriorFrame?,
	)

	var hover by mutableStateOf<Part?>(null)
		private set

	/** The selected guide by ID - indices shift as guides come and go - and, when one is picked, its point. */
	var selected by mutableStateOf<Pair<String, Int?>?>(null)
		private set

	/** The fold line being drawn, in raster pixels; empty when none is. */
	var drawing by mutableStateOf<List<InteriorPoint>>(emptyList())
		private set

	/** The pointer while a line is drawn, for the segment that follows it. */
	var cursor by mutableStateOf<Offset?>(null)
		private set

	private class Drag(
		val part: Part,
		val scene: Scene,
		val grab: InteriorPoint,
		val shape: InteriorSurfaceSettings,
		val motion: InteriorMotionSettings,
		val guides: List<InteriorGuide>,
		val wasDirty: Boolean,
		/** The guides the drag edits from: [guides], or with the point a Ctrl+press inserted. */
		val base: List<InteriorGuide> = guides,
	) {
		var changed = false
	}

	private var drag: Drag? = null

	val dragging: Boolean get() = drag != null

	/** The part a drag holds, lit as the hovered one is. */
	val active: Part? get() = drag?.part

	// ── The scene ──────────────────────────────────────────────────────────────

	private var mapKey: Any? = null
	private var mapValue: InteriorCanvasMap? = null
	private var alphaKey: Any? = null
	private var alphaValue: InteriorFrame? = null

	private var sceneKey: List<Any?>? = null
	private var sceneValue: Scene? = null

	/**
	 * The effect on the selected mesh, kept while nothing it is built from changes: the overlay, the hover, the press and
	 * the status bar all ask for it every frame.
	 */
	fun scene(): Scene? {
		val state = editor.state
		// Everything [CanvasEditor.target] reads: hiding or locking the mesh takes its handles away.
		val key = listOf(state.previewModel, editor.preview, state.selectedLayerId, state.rigEdits.interiorEffects, state.parameterValues,
			state.projectOpenGeneration, state.activeWorkspace.id, state.effectiveVisibleLayerIds, state.deformerVisibility)
		if (key != sceneKey) {
			sceneKey = key
			sceneValue = buildScene()
		}
		return sceneValue
	}

	private fun buildScene(): Scene? {
		val state = editor.state
		val model = state.previewModel ?: return null
		val layerId = state.selectedLayerId ?: return null
		val mesh = editor.target(layerId = layerId, deformerId = null)?.takeIf { it.kind == "mesh" } ?: return null
		val effect = state.rigEdits.interiorEffects.singleOrNull { it.target == mesh.id }?.takeIf { it.enabled } ?: return null
		val drawable = model.rig.puppet.drawables.firstOrNull { it.id.raw == mesh.id } ?: return null
		if (drawable.mesh !== mapKey) {
			mapKey = drawable.mesh
			mapValue = InteriorCanvasMap.of(model.rig.puppet, drawable)
		}
		val map = mapValue?.takeIf { it.vertexCount == mesh.count } ?: return null
		val raster = model.analysis.layers.singleOrNull { it.source.id.raw == layerId }?.source?.textureLayer?.raster
		if (raster !== alphaKey) {
			alphaKey = raster
			alphaValue = raster?.let { InteriorFrame.ofAlpha(it.width, it.height, it.rgba) }
		}
		return Scene(mesh.id, effect, editor.viewModel.interior.draft(mesh.id, effect), map, mesh, alphaValue,
			InteriorFrame.ofPoints(map.raster))
	}

	/** Why the tool has nothing to edit, for the status bar and the options bar. */
	fun missing(): String? {
		val state = editor.state
		val mesh = editor.target(layerId = state.selectedLayerId, deformerId = null)?.takeIf { it.kind == "mesh" }
			?: return tr("interior.canvas.selectMesh")
		val effect = state.rigEdits.interiorEffects.singleOrNull { it.target == mesh.id }
		return when {
			effect == null -> tr("interior.canvas.noEffect")
			!effect.enabled -> tr("interior.canvas.disabled")
			scene() == null -> tr("interior.canvas.unavailable")
			else -> null
		}
	}

	/** Whether [enable] has a mesh to turn the effect on for. */
	fun canEnable(): Boolean {
		val mesh = editor.target(layerId = editor.state.selectedLayerId, deformerId = null)?.takeIf { it.kind == "mesh" } ?: return false
		return editor.state.rigEdits.interiorEffects.singleOrNull { it.target == mesh.id }?.enabled != true
	}

	fun selectedIsFold(): Boolean {
		val scene = scene() ?: return false
		return selectedGuide(scene)?.let { scene.draft.guides[it].purpose == InteriorGuidePurpose.FOLD } == true
	}

	/** Marks the selected guide a fold, or back to a topology line: only folds shape the surface. */
	fun markFold(fold: Boolean) {
		val scene = scene() ?: return
		val guide = selectedGuide(scene) ?: return
		val purpose = if (fold) InteriorGuidePurpose.FOLD else InteriorGuidePurpose.TOPOLOGY
		if (scene.draft.guides[guide].purpose == purpose) return
		commit(scene, scene.draft.guides.toMutableList().also { it[guide] = it[guide].copy(purpose = purpose) })
	}

	/** Turns the selected mesh's effect on, creating it with the defaults when it has none. */
	fun enable() {
		val mesh = editor.target(layerId = editor.state.selectedLayerId, deformerId = null)?.takeIf { it.kind == "mesh" } ?: return
		editor.viewModel.interior.put(kotlinx.serialization.json.buildJsonObject {
			put("target", kotlinx.serialization.json.JsonPrimitive(mesh.id))
			put("enabled", kotlinx.serialization.json.JsonPrimitive(true))
		})
	}

	// ── Raster <-> screen ──────────────────────────────────────────────────────

	private var bindingMap: InteriorCanvasMap? = null
	private val bindingCache = HashMap<InteriorPoint, DeformPathPoint?>()

	/**
	 * Where each raster point sits in the mesh. Binding is the costly step, so it is kept per point for as long as the
	 * mesh stays: a drag rebinds only the points it moved.
	 */
	private fun bindings(scene: Scene, points: Collection<InteriorPoint>): Map<InteriorPoint, DeformPathPoint?> {
		if (bindingMap !== scene.map || bindingCache.size > BINDING_CACHE) {
			bindingMap = scene.map
			bindingCache.clear()
		}
		for (p in points) bindingCache.getOrPut(p) { scene.map.bind(p) }
		return bindingCache
	}

	/** [points] on screen, as the mesh is drawn now; empty when the mesh has no area to carry them. */
	fun screen(scene: Scene, points: List<InteriorPoint>, viewport: CanvasViewport): List<Offset> {
		if (points.isEmpty()) return emptyList()
		val bound = bindings(scene, points)
		val local = FloatArray(points.size * 2)
		for ((i, p) in points.withIndex()) {
			val (x, y) = (bound[p] ?: return emptyList()).position(scene.mesh.geometry.points)
			local[i * 2] = x; local[i * 2 + 1] = y
		}
		return editor.screen(local, scene.mesh, viewport)
	}

	private var placedPoints: FloatArray? = null
	private var placedViewport: CanvasViewport? = null
	private var placedValue = FloatArray(0)

	/** The mesh's vertices on screen, kept for the pose and viewport a gesture is made in. */
	private fun placed(scene: Scene, viewport: CanvasViewport): FloatArray {
		if (placedPoints !== scene.mesh.geometry.points || placedViewport != viewport) {
			placedPoints = scene.mesh.geometry.points
			placedViewport = viewport
			val placed = editor.screen(scene.mesh.geometry.points, scene.mesh, viewport)
			placedValue = FloatArray(placed.size * 2) { if (it % 2 == 0) placed[it / 2].x else placed[it / 2].y }
		}
		return placedValue
	}

	/** The raster pixel under [pos]; null when the mesh is drawn with no area to find it in. */
	fun raster(scene: Scene, pos: Offset, viewport: CanvasViewport): InteriorPoint? =
		scene.map.toRaster(placed(scene, viewport), pos.x, pos.y)

	/** Every guide on screen; a guide the mesh cannot carry comes back empty. */
	fun guideLines(scene: Scene, viewport: CanvasViewport): List<List<Offset>> =
		scene.draft.guides.map { screen(scene, it.points, viewport) }

	/** Whether [guide]'s points are drawn, and so can be picked: drawn guides always, a found line once selected. */
	fun pointsShown(scene: Scene, guide: Int): Boolean = scene.draft.guides[guide].detected == null || guide == selectedGuide(scene)

	fun surfaceHandles(scene: Scene, viewport: CanvasViewport): Map<InteriorHandle, Offset> {
		val frame = scene.surface ?: return emptyMap()
		val points = InteriorHandles.surface(frame, scene.draft.shape)
		return points.keys.zip(screen(scene, points.values.toList(), viewport)).toMap()
	}

	fun propagationHandles(scene: Scene, viewport: CanvasViewport): Map<InteriorHandle, Offset> {
		val frame = scene.propagation ?: return emptyMap()
		if (!scene.draft.motion.enabled) return emptyMap()
		val points = InteriorHandles.propagation(frame, scene.draft.motion)
		return points.keys.zip(screen(scene, points.values.toList(), viewport)).toMap()
	}

	fun selectedGuide(scene: Scene): Int? = selected?.first?.let { id -> scene.draft.guides.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }

	fun radiusHandle(scene: Scene, viewport: CanvasViewport): Pair<Offset, Offset>? {
		val g = selectedGuide(scene)?.let { scene.draft.guides[it] }?.takeIf { it.purpose == InteriorGuidePurpose.FOLD } ?: return null
		val (mid, handle) = InteriorGuideEdits.radiusHandle(g)
		val placed = screen(scene, listOf(mid, handle), viewport)
		return if (placed.size == 2) placed[0] to placed[1] else null
	}

	// ── Hit testing ────────────────────────────────────────────────────────────

	private fun hit(scene: Scene, pos: Offset, viewport: CanvasViewport): Part? {
		val handles = propagationHandles(scene, viewport) + surfaceHandles(scene, viewport)
		handles.entries.filter { (it.value - pos).getDistance() <= HANDLE_REACH }
			.minByOrNull { (it.value - pos).getDistance() }?.let { return Part.Handle(it.key) }
		val guide = selectedGuide(scene)
		radiusHandle(scene, viewport)?.let { (_, h) -> if ((h - pos).getDistance() <= HANDLE_REACH) return Part.Radius(guide!!) }
		val lines = guideLines(scene, viewport).map { line -> FloatArray(line.size * 2) { if (it % 2 == 0) line[it / 2].x else line[it / 2].y } }
		val point = InteriorPolylines.hit(lines, pos.x, pos.y, POINT_REACH) { pointsShown(scene, it) }
		return when {
			point == null -> null
			point.segment -> if (point.distance <= LINE_REACH) Part.Segment(point.line, point.point) else null
			else -> Part.Point(point.line, point.point)
		}
	}

	fun hover(pos: Offset, viewport: CanvasViewport) {
		if (drag != null) return
		val scene = scene()
		cursor = pos.takeIf { drawing.isNotEmpty() }
		hover = if (scene == null || drawing.isNotEmpty()) null else hit(scene, pos, viewport)
	}

	// ── Gestures ───────────────────────────────────────────────────────────────

	/**
	 * Takes a press: a handle or guide under it, the next point of the line being drawn, or the first point of a new one.
	 * False, leaving the press to the editor, when there is no effect to edit or the press lands on another object
	 * than the effect's mesh - object mode picks that one instead.
	 */
	fun press(pos: Offset, viewport: CanvasViewport, ctrl: Boolean, alt: Boolean): Boolean {
		val scene = scene() ?: return false
		val part = if (drawing.isEmpty()) hit(scene, pos, viewport) else null
		if (drawing.isEmpty() && part == null) {
			// A fold starts on the mesh or on empty canvas beside it; another object under the press is picked.
			val under = editor.layerCandidates(pos, viewport)
			if (under.isNotEmpty() && editor.state.selectedLayerId !in under) return false
		}
		val at = raster(scene, pos, viewport) ?: return true
		if (drawing.isNotEmpty()) {
			// A click on the last point drawn ends the line, as Enter does.
			val last = screen(scene, listOf(drawing.last()), viewport).singleOrNull()
			if (last != null && (last - pos).getDistance() <= POINT_REACH) finish() else drawing = drawing + at
			return true
		}
		val draft = scene.draft
		when (part) {
			null -> {
				selected = null
				drawing = listOf(at)
				cursor = pos
			}
			is Part.Point -> {
				val id = draft.guides[part.guide].id
				if (alt) {
					// The point is gone either way: the guide stays selected without one, or goes with it.
					val next = InteriorGuideEdits.remove(draft.guides, part.guide, part.point)
					selected = if (next.size == draft.guides.size) id to null else null
					hover = null
					commit(scene, next)
				} else {
					selected = id to part.point
					begin(part, scene, at)
				}
			}
			is Part.Segment -> {
				selected = draft.guides[part.guide].id to null
				if (ctrl) {
					val inserted = InteriorGuideEdits.insert(draft.guides, part.guide, part.segment, at) ?: return true
					begin(Part.Point(part.guide, part.segment + 1), scene, at, inserted)
					draft.guides = inserted
					selected = inserted[part.guide].id to part.segment + 1
					drag?.changed = true
				} else begin(part, scene, at)
			}
			is Part.Handle, is Part.Radius -> begin(part, scene, at)
		}
		return true
	}

	private fun begin(part: Part, scene: Scene, at: InteriorPoint, base: List<InteriorGuide> = scene.draft.guides) {
		val draft = scene.draft
		drag = Drag(part, scene, at, draft.shape, draft.motion, draft.guides, draft.dirty, base)
		hover = part
	}

	fun move(pos: Offset, viewport: CanvasViewport) {
		val d = drag ?: return
		val scene = d.scene
		val draft = scene.draft
		val at = raster(scene, pos, viewport) ?: return
		when (val part = d.part) {
			is Part.Handle -> if (part.handle.surface) {
				scene.surface?.let { draft.shape = InteriorHandles.dragSurface(part.handle, it, draft.shape, at) }
			} else {
				val frame = scene.propagation ?: return
				InteriorHandles.dragPropagation(part.handle, frame, d.motion, d.grab, at)?.let { draft.motion = it }
			}
			is Part.Point -> {
				val from = d.base[part.guide].points[part.point]
				val target = InteriorPoint(from.x + at.x - d.grab.x, from.y + at.y - d.grab.y)
				InteriorGuideEdits.move(d.base, part.guide, part.point, target)?.let { draft.guides = it }
			}
			is Part.Segment -> {
				val g = d.base[part.guide]
				val moved = g.points.map { InteriorPoint(it.x + at.x - d.grab.x, it.y + at.y - d.grab.y) }
				draft.guides = d.base.toMutableList().also { it[part.guide] = g.copy(points = moved) }
			}
			is Part.Radius -> draft.guides = InteriorGuideEdits.dragRadius(d.base, part.guide, at)
		}
		d.changed = true
		editor.viewModel.interior.previewLater(scene.target, true, draft)
	}

	fun release() {
		val d = drag ?: return
		drag = null
		if (d.changed) commit(d.scene)
	}

	/** One `interior_put` of the whole draft: one history node, as the panel's commits are. */
	private fun commit(scene: Scene, guides: List<InteriorGuide>? = null) {
		guides?.let { scene.draft.guides = it }
		editor.viewModel.interior.put(scene.draft.request(scene.target, true))
	}

	/**
	 * Ends the line being drawn: two points or more become a fold. False, leaving the line as it is, when there is no
	 * line of two points to end or the document cannot take an edit now.
	 */
	fun finish(): Boolean {
		if (drawing.size < 2 || !editor.editable) return false
		val scene = scene() ?: return false
		val points = drawing
		drawing = emptyList()
		cursor = null
		val next = InteriorGuideEdits.add(scene.draft.guides, points) ?: run {
			editor.error = tr("interior.canvas.lineRejected")
			return true
		}
		selected = next.last().id to null
		commit(scene, next)
		return true
	}

	/** Takes back the last point drawn, or removes the selected point (or the whole selected guide). */
	fun deleteSelection() {
		if (drawing.isNotEmpty()) {
			drawing = drawing.dropLast(1)
			if (drawing.isEmpty()) cursor = null
			return
		}
		if (!editor.editable) return
		val scene = scene() ?: return
		val guide = selectedGuide(scene) ?: return
		val point = selected?.second
		val guides = scene.draft.guides
		val next = if (point == null) InteriorGuideEdits.removeGuide(guides, guide) else InteriorGuideEdits.remove(guides, guide, point)
		selected = if (point != null && next.size == guides.size) guides[guide].id to null else null
		commit(scene, next)
	}

	/**
	 * Esc: a drag goes back to where it started, a line in progress is dropped, then the selection clears. False when
	 * there was nothing of the tool's own to step back.
	 */
	fun cancel(): Boolean {
		val d = drag
		if (d != null) {
			drag = null
			val draft = d.scene.draft
			val saved = editor.state.rigEdits.interiorEffects.singleOrNull { it.target == d.scene.target }
			if (!d.wasDirty) draft.revert()
			else {
				draft.shape = d.shape; draft.motion = d.motion; draft.guides = d.guides
				// Back to values already saved (a commit that has landed since the press): nothing is unsaved.
				draft.settle(saved)
			}
			// The audition shows what the draft holds now: the saved recipe, or the values not yet committed it went back to.
			editor.viewModel.interior.dropDraftPreview(saved)
			if (draft.dirty) editor.viewModel.interior.previewLater(d.scene.target, true, draft)
			return true
		}
		if (drawing.isNotEmpty()) {
			drawing = emptyList(); cursor = null
			return true
		}
		if (selected == null) return false
		selected = null
		return true
	}

	fun clearHover() { hover = null; cursor = null }

	/** The status bar's line: what the pointer is over, with its value, or how to start. */
	fun hint(): String {
		missing()?.let { return it }
		val scene = scene() ?: return tr("interior.canvas.hint")
		if (drawing.isNotEmpty()) return tr("interior.canvas.drawingHint", drawing.size)
		return when (val part = drag?.part ?: hover) {
			is Part.Handle -> "${tr(part.handle.labelKey)}  ${InteriorHandles.value(part.handle, scene.draft.shape, scene.draft.motion)}"
			is Part.Radius -> "${tr("interior.radius")}  %.0f".format(java.util.Locale.US, scene.draft.guides.getOrNull(part.guide)?.radius ?: 0f)
			is Part.Point, is Part.Segment -> tr("interior.canvas.guideHint")
			null -> tr("interior.canvas.hint")
		}
	}

	fun cursorHand(): Boolean = drag != null || hover != null

	companion object {
		/** Screen pixels within which a handle, a point and a line answer a press. */
		const val HANDLE_REACH = 9f
		const val POINT_REACH = 7f
		const val LINE_REACH = 5f
		private const val BINDING_CACHE = 65_536
	}
}
