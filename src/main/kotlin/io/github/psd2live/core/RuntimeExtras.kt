package io.github.psd2live.core

import io.github.psd2live.format.model.ExpressionIR
import io.github.psd2live.format.model.ExpressionParameter
import io.github.psd2live.format.model.HitAreaIR
import io.github.psd2live.format.model.PoseIR
import io.github.psd2live.i18n.tr
import io.github.psd2live.format.model.ExpressionBlend as IrBlend
import io.github.psd2live.format.model.PoseEntry as IrPoseEntry
import org.umamo.runtime.model.PuppetModel

/**
 * What a runtime host uses to make a rig interactive: the document's expressions and pose ([ModelExpressions],
 * [ModelPoses]) in IR form, and hit areas on the head and the body derived from the rig.
 */
internal object RuntimeExtras {
	/** The expressions an export writes ([ModelExpressions.exported]). */
	fun expressions(overlay: RigEditOverlay, parameterIds: Set<String>): List<ExpressionIR> =
		ModelExpressions.exported(overlay, parameterIds).map { expression ->
			ExpressionIR(expression.id, expression.name, expression.fadeIn, expression.fadeOut, expression.entries.map {
				ExpressionParameter(it.parameterId, IrBlend.valueOf(it.blend.name), it.value)
			})
		}

	/** The pose an export writes ([ModelPoses.exported]), or none. */
	fun pose(overlay: RigEditOverlay, partIds: Set<String>): PoseIR? = ModelPoses.exported(overlay.pose, partIds)?.let { pose ->
		PoseIR(pose.fadeIn, pose.groups.map { group -> group.parts.map { IrPoseEntry(it.partId, it.links) } })
	}

	/** Head: the face. Body: the top, else the neck or the bottom. Each only where the rig draws such a mesh. */
	fun hitAreas(model: PuppetModel, analysis: PipelineAnalysis, layerIdByDrawable: Map<String, String>): List<HitAreaIR> {
		val layers = analysis.layers.associateBy { it.source.id.raw }
		fun meshes(vararg tags: SemanticTag): List<String> {
			for (tag in tags) {
				val found = model.drawables.filter { d ->
					d.mesh != null && d.isVisible && layers[layerIdByDrawable[d.id.raw] ?: d.id.raw]?.semantic?.tag == tag
				}.map { it.id.raw }
				if (found.isNotEmpty()) return found
			}
			return emptyList()
		}
		return listOfNotNull(
			meshes(SemanticTag.FACE).takeIf { it.isNotEmpty() }?.let { HitAreaIR("HitAreaHead", tr("model.hitArea.head"), it) },
			meshes(SemanticTag.TOPWEAR, SemanticTag.NECK, SemanticTag.BOTTOMWEAR).takeIf { it.isNotEmpty() }?.let { HitAreaIR("HitAreaBody", tr("model.hitArea.body"), it) },
		)
	}
}
