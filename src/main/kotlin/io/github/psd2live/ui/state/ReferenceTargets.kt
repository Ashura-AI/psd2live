package io.github.psd2live.ui.state

import io.github.psd2live.project.WorkspaceLayout
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel

/**
 * One reference as the hierarchy and the menus act on it, wherever it shows: a reference image behind or in front of
 * the model ([Image]), or a reference layer in the layer stack ([Layer]).
 */
sealed interface ReferenceTarget {
	data class Image(val id: String) : ReferenceTarget
	data class Layer(val layerId: String) : ReferenceTarget

	/** The keyform [layout] pins it to: parameter ID to value, empty at every pose. */
	fun pin(layout: WorkspaceLayout): Map<String, Float> = when (this) {
		is Image -> layout.reference(id)?.pins.orEmpty()
		is Layer -> layout.referencePins[layerId].orEmpty()
	}
}

/** Where a reference shows, as `reference_set_mode` names it. */
enum class ReferenceMode(val key: String, val labelKey: String) {
	BACKGROUND("background", "references.mode.background"),
	LAYER("layer", "references.mode.layer"),
	FOREGROUND("foreground", "references.mode.foreground");
}

/**
 * The parameters a reference's pin menu shows, in the parameter panel's shapes: a 2D pad for a linked pair, a track
 * for any other. Listed are those it is pinned to, those the edit pose has moved off their default, and their 2D
 * partners - the 2D pads' parameters when nothing has moved - at most [MAX_ROWS] parameters.
 */
object ReferencePinRows {
	sealed interface Row {
		/** The parameters the row pins together. */
		val parameters: List<Parameter>
	}

	data class Axis(val parameter: Parameter) : Row {
		override val parameters get() = listOf(parameter)
	}

	data class Pad(val horizontal: Parameter, val vertical: Parameter) : Row {
		override val parameters get() = listOf(horizontal, vertical)
	}

	const val MAX_ROWS = 12

	fun of(puppet: PuppetModel, pin: Map<String, Float>, values: Map<ParameterId, Float>): List<Row> {
		fun current(parameter: Parameter) = values[parameter.id] ?: parameter.default
		val moved = puppet.parameters.filter { it.id.raw in pin || kotlin.math.abs(current(it) - it.default) > 1e-4f }.mapTo(LinkedHashSet()) { it.id }
		val partners = puppet.parameterLinks.filter { it.horizontal in moved || it.vertical in moved }.flatMap { listOf(it.horizontal, it.vertical) }
		val listed = (moved + partners).ifEmpty { puppet.parameterLinks.flatMap { listOf(it.horizontal, it.vertical) }.toSet() }
		val parameters = puppet.parameters.filter { it.id in listed }.take(MAX_ROWS)
		val byId = parameters.associateBy { it.id }
		val paired = HashSet<ParameterId>()
		val rows = ArrayList<Row>()
		for (parameter in parameters) {
			if (parameter.id in paired) continue
			val link = puppet.parameterLinks.firstOrNull { it.horizontal == parameter.id || it.vertical == parameter.id }
			val horizontal = link?.horizontal?.let(byId::get); val vertical = link?.vertical?.let(byId::get)
			if (horizontal != null && vertical != null) {
				paired += horizontal.id; paired += vertical.id
				rows += Pad(horizontal, vertical)
			} else rows += Axis(parameter)
		}
		return rows
	}
}
