package io.github.psd2live.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.core.modifier.*
import io.github.psd2live.i18n.tr
import org.umamo.runtime.model.ParameterId

/**
 * The modifier tool: one modifier of the selected mesh's stack as canvas handles - a transform's pivot, move and
 * turn, a bend's axis and steering arm, a twist's centre, rim and turn, a wave's axis, lengths and amplitude, a
 * lattice's cage corners and control points ([ModifierHandles]). Settings are in canvas pixels, so the handles sit
 * where the values say on the canvas, at the pose shown.
 *
 * A drag edits a draft of the values only, previewed through the same `modifier_put` on a candidate
 * ([io.github.psd2live.ui.state.ModifierController.preview]); release commits it once - one history step. A setting
 * keyed on parameters takes the edit as its key at the current pose, as the panel's fields do ([ModifierEdits]).
 */
internal class ModifierCanvasTool(private val editor: CanvasEditor) {
	/** The modifier the tool works on, with its values at the pose shown. */
	class Scene(
		val mesh: String,
		val stack: List<RigModifier>,
		val modifier: RigModifier,
		val values: Map<String, FloatArray>,
		val pose: (String) -> Float,
	)

	var hover by mutableStateOf<ModifierHandle?>(null)
		private set

	/** The modifier picked from the panel or the options bar, by ID; the stack's first enabled one when it names none there. */
	var chosen by mutableStateOf<String?>(null)
		private set

	private class Drag(
		val handle: ModifierHandle,
		val modifier: RigModifier,
		val pose: (String) -> Float,
		val start: Map<String, FloatArray>,
		val grab: ModifierPoint,
	) {
		/** The draft the overlay and the status bar show. */
		var values by mutableStateOf(start)
		var previous = grab

		/** The settings the drag changed so far, each whole, as they are sent. */
		fun changed() = ModifierHandles.rounded(values.filter { (key, value) -> !value.contentEquals(start.getValue(key)) })
	}

	private var drag by mutableStateOf<Drag?>(null)

	/** What a release sent, shown until the committed stack replaces the one it was made on. */
	private var held: Triple<List<RigModifier>, String, Map<String, FloatArray>>? = null
	private var previewing = false

	val dragging: Boolean get() = drag != null

	/** The handle a drag holds, lit as the hovered one is. */
	val active: ModifierHandle? get() = drag?.handle

	/** Edits [id] on the canvas from now on: the panel's "edit on canvas". */
	fun choose(id: String?) {
		chosen = id
		hover = null
	}

	// ── The scene ──────────────────────────────────────────────────────────────

	private fun mesh(): CanvasTarget? = editor.target(layerId = editor.state.selectedLayerId, deformerId = null)?.takeIf { it.kind == "mesh" }

	fun scene(): Scene? {
		val state = editor.state
		val model = state.previewModel ?: return null
		val mesh = mesh() ?: return null
		val stack = state.rigEdits.modifiers.filter { mesh.id in it.target.resolve(model.rig.puppet) }
		val modifier = stack.firstOrNull { it.id == chosen } ?: stack.firstOrNull { it.enabled } ?: stack.firstOrNull() ?: return null
		val parameters = model.rig.puppet.parameters
		val pose: (String) -> Float = { p -> state.parameterValues[ParameterId(p)] ?: parameters.firstOrNull { it.id.raw == p }?.default ?: 0f }
		val d = drag
		val values = when {
			d != null && d.modifier.id == modifier.id -> d.values
			else -> held?.takeIf { (modifiers, id) -> modifiers === state.rigEdits.modifiers && id == modifier.id }?.third
				?: ModifierEdits.values(modifier, pose)
		}
		return Scene(mesh.id, stack, modifier, values, pose)
	}

	/** Why the tool has nothing to edit, for the status bar and the options bar. */
	fun missing(): String? {
		val mesh = mesh() ?: return tr("modifiers.canvas.selectMesh")
		return when {
			editor.state.rigEdits.modifiers.none { mesh.id in it.target.resolve(editor.state.previewModel?.rig?.puppet) } -> tr("modifiers.canvas.none")
			scene() == null -> tr("modifiers.canvas.unavailable")
			else -> null
		}
	}

	/** A modifier's name as its card titles it: the kind alone while it keeps its kind's name. */
	fun title(modifier: RigModifier): String {
		val kind = tr("modifiers.kind.${modifier.kind}")
		return if (modifier.name == modifier.kind.replaceFirstChar { it.uppercase() }) kind else "${modifier.name} · $kind"
	}

	// ── Canvas <-> screen ──────────────────────────────────────────────────────

	/** A handle's arm where the value has no length of its own, in canvas pixels: the same length on screen at any zoom. */
	fun reach(viewport: CanvasViewport) = (ARM / viewport.scale).toFloat()

	fun screen(p: ModifierPoint, viewport: CanvasViewport) = Offset(viewport.x(p.x).toFloat(), (viewport.offsetY + p.y * viewport.scale).toFloat())

	private fun canvas(pos: Offset, viewport: CanvasViewport) = ModifierPoint(viewport.canvasX(pos.x), viewport.canvasY(pos.y))

	fun handles(scene: Scene, viewport: CanvasViewport): Map<ModifierHandle, Offset> =
		ModifierHandles.handles(scene.modifier.kind, scene.values, reach(viewport)).mapValues { screen(it.value, viewport) }

	fun guides(scene: Scene, viewport: CanvasViewport): List<Pair<List<Offset>, Boolean>> =
		ModifierHandles.guides(scene.modifier.kind, scene.values, reach(viewport)).map { g -> g.points.map { screen(it, viewport) } to g.dashed }

	// ── Hit testing and gestures ───────────────────────────────────────────────

	private fun hit(scene: Scene, pos: Offset, viewport: CanvasViewport): ModifierHandle? =
		handles(scene, viewport).entries.filter { (it.value - pos).getDistance() <= HANDLE_REACH }.minByOrNull { (it.value - pos).getDistance() }?.key

	fun hover(pos: Offset, viewport: CanvasViewport) {
		if (drag != null) return
		hover = scene()?.let { hit(it, pos, viewport) }
	}

	/** Takes a press on a handle; false, leaving the press to the editor's object pick, when there is none under it. */
	fun press(pos: Offset, viewport: CanvasViewport): Boolean {
		val scene = scene() ?: return false
		val handle = hit(scene, pos, viewport) ?: return false
		if (!editor.editable) return true
		held = null
		drag = Drag(handle, scene.modifier, scene.pose, scene.values, canvas(pos, viewport))
		hover = handle
		return true
	}

	fun move(pos: Offset, viewport: CanvasViewport) {
		val d = drag ?: return
		val at = canvas(pos, viewport)
		d.values = d.values + ModifierHandles.drag(d.modifier.kind, d.handle, d.start, d.values, d.grab, d.previous, at, reach(viewport))
		d.previous = at
		val changed = d.changed()
		if (changed.isEmpty()) return
		previewing = true
		editor.viewModel.modifiers.preview(ModifierEdits.request(d.modifier, changed, d.pose))
	}

	/** Commits the drag as one `modifier_put`: one history step. */
	fun release() {
		val d = drag ?: return
		drag = null
		val changed = d.changed()
		if (changed.isEmpty()) return
		held = Triple(editor.state.rigEdits.modifiers, d.modifier.id, d.values + changed)
		editor.viewModel.modifiers.put(ModifierEdits.request(d.modifier, changed, d.pose))
	}

	/** Esc: a drag goes back to where it started and its preview is withdrawn. False when there was no drag. */
	fun cancel(): Boolean {
		drag ?: return false
		drag = null
		dropPreview()
		return true
	}

	/** The document or its model changed: the committed rig shows what the drag previewed. */
	fun documentChanged() {
		if (drag != null) return
		held = held?.takeIf { it.first === editor.state.rigEdits.modifiers }
		if (held == null) dropPreview()
	}

	private fun dropPreview() {
		if (!previewing) return
		previewing = false
		editor.viewModel.modifiers.dropPreview()
	}

	fun clearHover() { hover = null }

	/** The status bar's line: the modifier, and the handle under the pointer with its value, or how to start. */
	fun hint(): String {
		missing()?.let { return it }
		val scene = scene() ?: return tr("modifiers.canvas.hint", "")
		val handle = drag?.handle ?: hover ?: return tr("modifiers.canvas.hint", title(scene.modifier))
		return "${title(scene.modifier)}   ${tr(handle.labelKey)}  ${ModifierHandles.value(handle, scene.values)}"
	}

	fun cursorHand(): Boolean = drag != null || hover != null

	companion object {
		/** Screen pixels within which a handle answers a press. */
		const val HANDLE_REACH = 9f

		/** Screen pixels of an arm with no length of its own: a turn, a direction, a corner's tie. */
		const val ARM = 44.0
	}
}
