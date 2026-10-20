package io.github.psd2live.ui.state

import io.github.psd2live.project.PinPoint
import io.github.psd2live.project.WorkspaceLayout
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.RigStructureEdits
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A continuous opacity preview; the document changes only when the slider is released. */
data class ReferenceOpacityDraft(val target: ReferenceTarget, val opacity: Float) {
	fun preview(model: RigPreviewModel): RigPreviewModel {
		val layer = target as? ReferenceTarget.Layer ?: return model
		val edits = model.rig.puppet.drawables.filter { model.rig.layerIdByDrawableId[it.id.raw] == layer.layerId }.map {
			buildJsonObject { put("action", "static"); put("kind", "mesh"); put("id", it.id.raw); put("opacity", opacity) }
		}
		if (edits.isEmpty()) return model
		return model.copy(rig = model.rig.copy(puppet = RigStructureEdits.apply(model.rig.puppet, edits)))
	}
}

/**
 * One reference as the hierarchy and the menus act on it, wherever it shows: a reference image behind or in front of
 * the model ([Image]), or a reference layer in the layer stack ([Layer]).
 */
sealed interface ReferenceTarget {
	data class Image(val id: String) : ReferenceTarget
	data class Layer(val layerId: String) : ReferenceTarget

	/** The keyform points [layout] pins it to (parameter ID to value each); none shows it at every pose. */
	fun pin(layout: WorkspaceLayout): List<PinPoint> = when (this) {
		is Image -> layout.reference(id)?.pins.orEmpty()
		is Layer -> layout.referencePins[layerId].orEmpty()
	}
}

/** Where a reference shows, as `reference_set_mode` names it. */
enum class ReferenceMode(val key: String, val labelKey: String, val descriptionKey: String, val inModel: Boolean) {
	/** Behind the whole model, in the editor only. */
	BACKGROUND("background", "references.mode.background", "references.mode.background.desc", false),
	/** In the layer stack: an ArtMesh of the model in its guide-image part. */
	LAYER("layer", "references.mode.layer", "references.mode.layer.desc", true),
	/** In front of the whole model, in the editor only. */
	FOREGROUND("foreground", "references.mode.foreground", "references.mode.foreground.desc", false);
}

/**
 * The parameters a reference's pin menu offers, in the parameter panel's shapes: a 2D pad for a linked pair, a track
 * for any other - every parameter keyed somewhere in the rig, and those a point pins. A point is pinned on a row's
 * keys: one parameter's key on a track, a crossing of both parameters' keys on a pad.
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

	fun of(puppet: PuppetModel, keyed: Set<ParameterId>, points: List<PinPoint>): List<Row> {
		val pinned = points.flatMapTo(HashSet()) { it.keys }
		val parameters = puppet.parameters.filter { it.id in keyed || it.id.raw in pinned }
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

	/** Whether [point] is [other] - the same parameters at the same values, as a key read back may round. */
	fun same(point: PinPoint, other: PinPoint): Boolean =
		point.keys == other.keys && point.all { (id, value) -> kotlin.math.abs(value - other.getValue(id)) <= 1e-4f }

	/** The points no row of [rows] pins on its own keys: pinned some other way, listed apart. */
	fun loose(rows: List<Row>, points: List<PinPoint>): List<PinPoint> {
		val shapes = rows.map { row -> row.parameters.mapTo(HashSet()) { it.id.raw } }
		return points.filter { point -> shapes.none { it == point.keys } }
	}
}
