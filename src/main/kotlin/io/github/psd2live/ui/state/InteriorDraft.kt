package io.github.psd2live.ui.state

import androidx.compose.runtime.*
import io.github.psd2live.core.interior.*

/** Per-layer unsaved authoring state survives selection changes and rejected generation. */
internal class InteriorDraft(effect: NativeInteriorEffect?) {
	private var recipe = effect
	var dirty by mutableStateOf(false)
	var version by mutableStateOf(0L)
	private var shapeValue by mutableStateOf(effect?.settings ?: InteriorSurfaceSettings())
	private var motionValue by mutableStateOf(effect?.motion ?: InteriorMotionSettings())
	private var guideValue by mutableStateOf(effect?.guides.orEmpty())
	private var sourceValue by mutableStateOf(effect?.sources?.map { it.id.raw } ?: listOf("", ""))
	var shape: InteriorSurfaceSettings
		get() = shapeValue
		set(value) {
			shapeValue = value
			changed()
		}

	var motion: InteriorMotionSettings
		get() = motionValue
		set(value) {
			motionValue = value
			changed()
		}

	var guides: List<InteriorGuide>
		get() = guideValue
		set(value) {
			guideValue = value
			changed()
		}

	var sources: List<String>
		get() = sourceValue
		set(value) {
			sourceValue = value
			changed()
		}

	private fun changed() {
		dirty = true
		version++
	}

	/** The `interior_put` request for these values on [target]: what the panel and the canvas handles commit. */
	fun request(target: String, enabled: Boolean) =
		NativeInteriorAuthoring.request(target, enabled, shape, motion, sources, guides)

	/** Back to the saved recipe: a canvas drag cancelled before release leaves nothing to commit. */
	fun revert() {
		val effect = recipe
		shapeValue = effect?.settings ?: InteriorSurfaceSettings()
		motionValue = effect?.motion ?: InteriorMotionSettings()
		guideValue = effect?.guides.orEmpty()
		sourceValue = effect?.sources?.map { it.id.raw } ?: listOf("", "")
		dirty = false
		version++
	}

	/** Whether these values are [effect]'s; a blank source asks for a private input, which any saved one answers. */
	private fun matches(effect: NativeInteriorEffect) =
		shape == effect.settings && motion == effect.motion && guides == effect.guides &&
			sources.size == effect.sources.size &&
			sources.zip(effect.sources).all { (draft, saved) -> draft.isBlank() || draft == saved.id.raw }

	/**
	 * Clean again once the values are the saved recipe's: a commit that landed after the draft moved on, or a drag put
	 * back to where it started, leaves nothing unsaved, and the draft follows undo and MCP edits again.
	 */
	fun settle(effect: NativeInteriorEffect? = recipe) {
		if (dirty && effect != null && matches(effect)) {
			recipe = effect
			dirty = false
		}
	}

	fun sync(effect: NativeInteriorEffect?) {
		settle(effect)
		if (dirty || recipe === effect) return
		recipe = effect
		shapeValue = effect?.settings ?: InteriorSurfaceSettings()
		motionValue = effect?.motion ?: InteriorMotionSettings()
		guideValue = effect?.guides.orEmpty()
		sourceValue = effect?.sources?.map { it.id.raw } ?: listOf("", "")
	}
}
