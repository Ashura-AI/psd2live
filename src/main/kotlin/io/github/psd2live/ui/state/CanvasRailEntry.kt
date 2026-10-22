package io.github.psd2live.ui.state

/**
 * A display toggle the canvas's display rail can carry. Every one is also in the canvas's View menu; the rail shows
 * the ones the user pinned there ([AppSettings.canvasRail]), each only while the canvas has something for it to show.
 */
enum class CanvasRailEntry(val id: String, val pinnedByDefault: Boolean) {
	TEXTURE("texture", true),
	SOURCE_PIXELS("sourcePixels", false),
	REFERENCE("reference", true),
	REFERENCE_LAYERS_OVER("referenceLayersOver", false),
	DENSITY("density", false),
	MESH("mesh", true),
	WARP("warp", true),
	ROTATION("rotation", true),
	PATHS("paths", true),
	PATH_WIDTH("pathWidth", false),
	PATH_HARDNESS("pathHardness", false),
	SKELETON("skeleton", true),
	SKIN_WEIGHTS("skinWeights", false),
	SELECTED_ONLY("selectedOnly", true),
	GRID("grid", true),
	GUIDES("guides", true),
	;

	companion object {
		val Defaults: Set<CanvasRailEntry> = entries.filterTo(LinkedHashSet()) { it.pinnedByDefault }

		fun parse(text: String): Set<CanvasRailEntry> =
			text.split(',').mapNotNullTo(LinkedHashSet()) { id -> entries.firstOrNull { it.id == id.trim() } }

		fun encode(pins: Set<CanvasRailEntry>): String = pins.joinToString(",") { it.id }
	}
}

/**
 * What a canvas has for its display toggles to show: a toggle with nothing to show stays off the rail, and the View
 * menu keeps it within reach.
 */
data class CanvasViewContent(
	val edit: Boolean,
	val textures: Boolean,
	val reference: Boolean,
	val referenceLayers: Boolean,
	val warps: Boolean,
	val rotations: Boolean,
	val paths: Boolean,
	val skeleton: Boolean,
) {
	/** Whether [entry] has anything to show here. */
	fun shows(entry: CanvasRailEntry): Boolean = when (entry) {
		CanvasRailEntry.TEXTURE, CanvasRailEntry.MESH -> textures
		CanvasRailEntry.SOURCE_PIXELS, CanvasRailEntry.DENSITY -> edit && textures
		CanvasRailEntry.REFERENCE -> edit && reference
		CanvasRailEntry.REFERENCE_LAYERS_OVER -> edit && referenceLayers
		CanvasRailEntry.WARP -> warps
		CanvasRailEntry.ROTATION -> rotations
		CanvasRailEntry.PATHS, CanvasRailEntry.PATH_WIDTH, CanvasRailEntry.PATH_HARDNESS -> edit && paths
		CanvasRailEntry.SKELETON, CanvasRailEntry.SKIN_WEIGHTS -> edit && skeleton
		CanvasRailEntry.SELECTED_ONLY -> edit && textures
		CanvasRailEntry.GRID, CanvasRailEntry.GUIDES -> edit
	}

	companion object {
		/** What [state]'s model has for a canvas in [mode]; [skeleton] is the edit canvas's committed armature. */
		fun of(state: PSD2LiveState, mode: CanvasMode, skeleton: Boolean): CanvasViewContent {
			val model = state.previewModel
			val puppet = model?.rig?.puppet
			val edit = mode == CanvasMode.EDIT
			return CanvasViewContent(
				edit = edit,
				textures = puppet?.drawables?.any { it.mesh != null } == true,
				reference = state.layout.references.isNotEmpty(),
				referenceLayers = model?.referenceLayerIds?.isNotEmpty() == true,
				warps = puppet?.deformers?.any { it is org.umamo.runtime.model.Deformer.Warp } == true,
				rotations = puppet?.deformers?.any { it is org.umamo.runtime.model.Deformer.Rotation } == true,
				paths = puppet?.deformPaths?.isNotEmpty() == true,
				skeleton = skeleton,
			)
		}
	}
}
