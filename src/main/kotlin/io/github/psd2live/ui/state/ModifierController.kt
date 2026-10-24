package io.github.psd2live.ui.state

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.ModifierAuthoring
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.ui.CanvasEditor
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.umamo.runtime.model.PuppetModel

/**
 * The modifier panel's commits and its preview while a field is dragged. Commits run one after another, each on the
 * document as the one before left it; a preview is the same `modifier_put` on a candidate, shown on the active canvas
 * until the committed model arrives. No document solver lives here.
 */
internal class ModifierController(
	private val state: () -> PSD2LiveState,
	private val backend: () -> WorkspaceBackend?,
	private val scope: CoroutineScope,
	private val editorFor: (String) -> CanvasEditor,
	/** The status bar's task indicator: on while an edit runs, off when the latest one ends. */
	private val progress: (Boolean) -> Unit = {},
	/** Where a failed edit or preview is reported, beside the indicator. */
	private val failed: (String) -> Unit = {},
) : AutoCloseable {
	fun put(request: JsonObject) = edit(ModifierAuthoring.PUT, request)
	fun delete(id: String) = edit(ModifierAuthoring.DELETE, buildJsonObject { put("id", id) })
	fun move(id: String, to: String) = edit(ModifierAuthoring.MOVE, buildJsonObject { put("id", id); put("to", to) })
	fun moveBefore(id: String, before: String) = edit(ModifierAuthoring.MOVE, buildJsonObject { put("id", id); put("before", before) })
	fun apply(id: String, mode: String, pose: Map<String, Float> = emptyMap()) = edit(ModifierAuthoring.APPLY, buildJsonObject {
		put("id", id); put("mode", mode)
		if (pose.isNotEmpty()) putJsonObject("pose") { pose.forEach { (k, v) -> put(k, v) } }
	})

	private var editJob: Job? = null
	private var pending = 0

	private fun edit(operation: String, request: JsonObject) {
		val workspace = backend() ?: return
		val previous = editJob
		pending++
		progress(true)
		editJob = scope.launch {
			try {
				// One after another: a move right after an add acts on the added modifier.
				previous?.join()
				val expected = workspace.snapshot()
				withContext(Dispatchers.Default + WorkspaceExecution(expected.projectId, expected.state, MutationAuthor.USER)) {
					workspace.applyDocumentEdits(expected.state, "Modifier", listOf(WorkspaceDocumentOperation(operation, request)), MutationAuthor.USER)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				dropPreview()
				failed(e.message ?: e.toString())
			} finally {
				if (--pending == 0) progress(false)
			}
		}
	}

	private var previewJob: Job? = null
	private var shown: Pair<CanvasEditor, PuppetModel>? = null

	/**
	 * Shows `modifier_put` [request] on the active canvas before it is committed: the candidate rig, sampled as the
	 * commit will sample it. Later requests replace earlier ones; the committed model takes over when it arrives.
	 */
	fun preview(request: JsonObject) {
		val captured = state()
		val preview = captured.previewModel ?: return
		val canvas = captured.activeCanvas.id
		previewJob?.cancel()
		previewJob = scope.launch {
			delay(40)
			try {
				val puppet = withContext(Dispatchers.Default) {
					runInterruptible {
						val overlay = ModifierAuthoring.put(preview, preview.config.rigEdits, request)
						preview.baseRig.withRigEdits(overlay, preview.config.layerVisibility, preview.config.drawOrderOverrides,
							SeeThroughLayers.Settings.of(preview.config)).puppet
					}
				}
				val current = state()
				if (current.previewModel !== preview || current.activeCanvas.id != canvas) return@launch
				val editor = editorFor(canvas)
				editor.preview = puppet
				shown = editor to puppet
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				failed(e.message ?: e.toString())
			}
		}
	}

	/** Withdraws the preview, if this controller's is still the one shown. */
	fun dropPreview() {
		previewJob?.cancel()
		previewJob = null
		shown?.let { (editor, puppet) -> if (editor.preview === puppet) editor.preview = null }
		shown = null
	}

	override fun close() {
		editJob?.cancel()
		dropPreview()
	}
}
