package io.github.psd2live.ui.state

import androidx.compose.runtime.*
import io.github.psd2live.application.*
import io.github.psd2live.core.mesh.structure.StructureKind
import io.github.psd2live.project.MutationAuthor
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/**
 * Remesh along texture structure from the Mesh and Interior panels: the options both share, the lines the canvas
 * shows while they are tuned, and the edit. The lines come from the captured version through the same query MCP
 * uses (`layer_mesh_structure_preview`); nothing is computed in Compose and nothing here is saved.
 */
internal class StructureMeshController(
	private val backend: () -> WorkspaceBackend?,
	private val scope: CoroutineScope,
	private val progress: (Boolean) -> Unit = {},
	private val failed: (String) -> Unit = {},
) : AutoCloseable {
	var sensitivity by mutableFloatStateOf(.5f)
	var near by mutableFloatStateOf(1f)
	var far by mutableFloatStateOf(1f)
	/** Vertices between the lines; off, only the edge rows and the lines carry vertices. */
	var fill by mutableStateOf(false)
	/** Rows either side of drawn creases. */
	var bands by mutableStateOf(false)
	var kinds by mutableStateOf(StructureKind.entries.toSet())
	/** Whether the canvas shows what the analysis finds on the selected mesh. */
	var showLines by mutableStateOf(false)
	var busy by mutableStateOf(false)
		private set
	var preview by mutableStateOf<Preview?>(null)
		private set

	/** One captured analysis: [lines] and [wires] in canvas units. */
	class Preview(val state: String, val target: String, val options: JsonObject, val lines: List<Line>, val wires: FloatArray,
	              val verticesBefore: Int, val verticesAfter: Int)

	class Line(val kind: StructureKind, val points: FloatArray, val followed: Boolean, val banded: Boolean)

	fun options(): JsonObject = buildJsonObject {
		put("sensitivity", sensitivity.toDouble())
		put("near_density", near.toDouble())
		put("far_density", far.toDouble())
		put("fill", fill)
		put("bands", bands)
		put("kinds", JsonArray(StructureKind.entries.filter { it in kinds }.map { JsonPrimitive(it.name) }))
	}

	private var previewJob: Job? = null
	private var editJob: Job? = null

	/** Analyses [target] at the current options for the overlay, replacing any analysis still running. */
	fun refresh(target: String?) {
		previewJob?.cancel()
		val workspace = backend()
		if (!showLines || target == null || workspace == null) { preview = null; return }
		val options = options()
		val state = workspace.snapshot().state
		val current = preview
		if (current != null && current.state == state && current.target == target && current.options == options) return
		previewJob = scope.launch {
			delay(250)
			try {
				val result = withContext(Dispatchers.Default) {
					val queries = workspace.captureQueries()
					runInterruptible { queries.structureMeshPreview(buildJsonObject { put("target", target); put("settings", options) }) }
				}
				preview = decode(result, target, options)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				preview = null
				failed(e.message ?: e.toString())
			}
		}
	}

	private fun decode(result: JsonObject, target: String, options: JsonObject): Preview {
		fun points(array: JsonArray) = FloatArray(array.size * 2) { array[it / 2].jsonArray[it % 2].jsonPrimitive.float }
		val canvas = points(result.getValue("canvas_points").jsonArray)
		val triangles = result.getValue("triangle_indices").jsonArray.map { it.jsonPrimitive.int }
		val edges = LinkedHashSet<Long>()
		for (t in 0 until triangles.size / 3) for (k in 0..2) {
			val a = triangles[t * 3 + k]; val b = triangles[t * 3 + (k + 1) % 3]
			edges += (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()
		}
		val wires = FloatArray(edges.size * 4)
		edges.forEachIndexed { i, e ->
			val a = (e ushr 32).toInt(); val b = e.toInt()
			wires[i * 4] = canvas[a * 2]; wires[i * 4 + 1] = canvas[a * 2 + 1]; wires[i * 4 + 2] = canvas[b * 2]; wires[i * 4 + 3] = canvas[b * 2 + 1]
		}
		val lines = result.getValue("lines").jsonArray.map { it.jsonObject }.map { line ->
			Line(StructureKind.valueOf(line.getValue("kind").jsonPrimitive.content), points(line.getValue("canvas").jsonArray),
				line.getValue("followed").jsonPrimitive.boolean, line.getValue("banded").jsonPrimitive.boolean)
		}
		return Preview(result.getValue("state").jsonPrimitive.content, target, options, lines, wires,
			result.getValue("vertices_before").jsonPrimitive.int, result.getValue("vertices_after").jsonPrimitive.int)
	}

	/** Rebuilds [target]; with [rebake] its interior effect is baked again in the same history edit. */
	fun remesh(target: String, rebake: Boolean) {
		val workspace = backend() ?: return
		val expected = workspace.snapshot()
		editJob?.cancel()
		busy = true
		progress(true)
		val edits = listOf(WorkspaceDocumentOperation(WorkspaceStructureMeshEdits.OP, buildJsonObject { put("target", target); put("settings", options()) })) +
			if (rebake) listOf(WorkspaceDocumentOperation("interior_put", buildJsonObject { put("target", target) })) else emptyList()
		editJob = scope.launch {
			try {
				withContext(Dispatchers.Default + WorkspaceExecution(expected.projectId, expected.state, MutationAuthor.USER)) {
					workspace.applyDocumentEdits(expected.state, "Remesh along texture", edits, MutationAuthor.USER)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				failed(e.message ?: e.toString())
			} finally {
				busy = false
				progress(false)
			}
		}
	}

	override fun close() {
		previewJob?.cancel()
		editJob?.cancel()
		preview = null
	}
}
