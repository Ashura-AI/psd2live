package io.github.psd2live.ui.state

import io.github.psd2live.application.ImageLayerInsertion
import io.github.psd2live.core.LayerImport
import io.github.psd2live.ui.utils.DesktopDropTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Files carried from the system over the window, sorted once by what each kind of target takes. */
class CarriedFiles(val files: List<File>) {
	/** Images that import as layers. */
	val rasters: List<File> by lazy { LayerImport.transparentRasterFiles(files) }

	/** What the files open as a project; null when they open nothing. */
	val opens: DesktopDropTarget.DroppedAction? by lazy {
		DesktopDropTarget.resolveDropAction(files).takeUnless { it is DesktopDropTarget.DroppedAction.Unsupported }
	}

	/** Whether any file is one the material library reads. */
	val materials: Boolean by lazy { files.any { it.isDirectory || it.extension.lowercase() in MATERIAL_EXTENSIONS } }

	/** Unknown until the drop: the system does not always hand the files over while they are dragged. */
	val unknown: Boolean get() = files.isEmpty()

	companion object {
		val MATERIAL_EXTENSIONS = setOf("png", "webp", "tif", "tiff", "bmp", "psd", "cmo3", "svg", "json", "zip")
	}
}

/** What dropping the carried files where the pointer is would do, and the words the pointer shows for it. */
sealed interface FileDropIntent {
	val label: String

	data class Open(val action: DesktopDropTarget.DroppedAction, override val label: String) : FileDropIntent
	data class ImportMaterials(val folderId: String?, override val label: String) : FileDropIntent
	/** Into the hierarchy; the tree marks the row from [rowTop] to [rowBottom] (its own coordinates) as [mark]. */
	data class InsertLayers(val insertion: ImageLayerInsertion, override val label: String, val mark: TreeDropMark,
	                        val rowTop: Float = 0f, val rowBottom: Float = 0f) : FileDropIntent
	data class PlaceOnCanvas(val centre: Pair<Float, Float>, override val label: String) : FileDropIntent
	data class SetReference(override val label: String) : FileDropIntent
	data class ReplaceImage(val layerId: String, override val label: String) : FileDropIntent
	/** Dropping here does nothing; [label] says why. */
	data class Refuse(override val label: String) : FileDropIntent
}

/** How the hierarchy marks where dropped images go: into a row, just above or below it, or at the root. */
enum class TreeDropMark { INTO, ABOVE, BELOW, ROOT }

/** A target for system files: what dropping [carried] at window pixel ([x], [y]) does there, or null off it. */
fun interface FileDropZone {
	fun resolve(carried: CarriedFiles, x: Float, y: Float): FileDropIntent?
}

/**
 * Files being dragged over the window: where, what dropping them there does (null: nothing, with [hint]) and the
 * zone that said so (null: the fallback).
 */
data class FileDrag(val carried: CarriedFiles, val x: Float, val y: Float, val intent: FileDropIntent?, val hint: String?, val zone: String?)

/**
 * Routes system file drags to the target under the pointer. Panels register zones while they show; while files are
 * carried the router asks them, highest [priority][register] first, what a drop would do, and publishes the answer
 * ([drag]) for the pointer label and the targets' own marks. Where no zone answers, [fallback] decides.
 */
class FileDropRouter(private val fallback: (CarriedFiles) -> FileDropIntent?, private val hint: (CarriedFiles) -> String?) {
	private class Entry(val priority: Int, val zone: FileDropZone)

	private val zones = java.util.concurrent.ConcurrentHashMap<String, Entry>()
	private val state = MutableStateFlow<FileDrag?>(null)
	val drag: StateFlow<FileDrag?> = state

	fun register(id: String, priority: Int = 0, zone: FileDropZone) { zones[id] = Entry(priority, zone) }
	fun unregister(id: String) { zones.remove(id) }

	/** What dropping [carried] at ([x], [y]) does, and the zone that decides it (null: the fallback). */
	fun resolve(carried: CarriedFiles, x: Float, y: Float): Pair<FileDropIntent?, String?> {
		for ((id, entry) in zones.entries.sortedByDescending { it.value.priority }) {
			entry.zone.resolve(carried, x, y)?.let { return it to id }
		}
		return fallback(carried) to null
	}

	/** The carried files moved to ([x], [y]); whether a drop there would do anything. */
	fun move(files: List<File>, x: Float, y: Float): Boolean {
		val carried = state.value?.carried?.takeIf { it.files == files } ?: CarriedFiles(files)
		val (intent, zone) = resolve(carried, x, y)
		state.value = FileDrag(carried, x, y, intent, if (intent == null) hint(carried) else null, zone)
		return carried.unknown || intent != null && intent !is FileDropIntent.Refuse
	}

	fun end() { state.value = null }

	/** The files dropped at ([x], [y]): what to do with them, or null for nothing. */
	fun drop(files: List<File>, x: Float?, y: Float?): FileDropIntent? {
		end()
		val carried = CarriedFiles(files)
		return (if (x != null && y != null) resolve(carried, x, y).first else fallback(carried)).takeUnless { it is FileDropIntent.Refuse }
	}
}
