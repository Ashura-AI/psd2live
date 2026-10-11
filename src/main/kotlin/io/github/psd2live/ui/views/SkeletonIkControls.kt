package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.theme.LocalToolColors

/**
 * What a user reaches for with IK: pin the bone's tip so it can be dragged in Pose, release it, say which
 * way the joint bends. The target itself is dragged on the canvas, so it has no coordinates here.
 */
@Composable
internal fun SkeletonIkControls(editor: CanvasEditor) {
    val spec = editor.currentSkeleton ?: return
    val bone = spec.bone(editor.selectedBoneId ?: "") ?: return
    val colors = LocalToolColors.current
    val settings = bone.ik
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tr("skeleton.ik.title"), color = colors.textPrimary, fontSize = 11.sp)
        val bends = listOf(0 to tr("skeleton.ik.bend.auto"), 1 to tr("skeleton.ik.bend.clockwise"), -1 to tr("skeleton.ik.bend.counterclockwise"))
        CompactDropdown(bends, bends.first { it.first == settings.bendDirection }, { editor.setSelectedBoneIk(settings.copy(bendDirection = it.first)) },
            itemLabel = { it.second }, modifier = Modifier.fillMaxWidth())
        if (editor.skeletonDraft == null) {
            val target = spec.ikTargets[bone.id]
            if (target == null) CompactButton(tr("skeleton.ik.pin"), { editor.pinSelectedBone() }, enabled = !bone.role.anchor, modifier = Modifier.fillMaxWidth())
            else Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CompactToggleChip(tr("skeleton.ik.enabled"), target.enabled, { editor.updateIkTarget(bone.id, target.copy(enabled = !target.enabled)) }, modifier = Modifier.weight(1f))
                CompactButton(tr("skeleton.ik.release"), { editor.updateIkTarget(bone.id, null) }, modifier = Modifier.weight(1f))
            }
        }
        Text(tr("skeleton.ik.hint"), color = colors.textMuted, fontSize = 10.sp)
    }
}

/** The solver's exact numbers, rarely worth touching. */
@Composable
internal fun SkeletonIkAdvanced(editor: CanvasEditor) {
    val spec = editor.currentSkeleton ?: return
    val bone = spec.bone(editor.selectedBoneId ?: "") ?: return
    val settings = bone.ik
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CompactNumberSpinner(settings.chainLength.toDouble(), { editor.setSelectedBoneIk(settings.copy(chainLength = it.toInt())) },
                min = 1.0, max = 32.0, unit = tr("skeleton.ik.chain"), modifier = Modifier.weight(1f))
            CompactNumberSpinner(settings.iterations.toDouble(), { editor.setSelectedBoneIk(settings.copy(iterations = it.toInt())) },
                min = 1.0, max = 256.0, unit = tr("skeleton.ik.iterations"), modifier = Modifier.weight(1f))
        }
        CompactNumberSpinner(settings.tolerancePx.toDouble(), { editor.setSelectedBoneIk(settings.copy(tolerancePx = it.toFloat())) },
            min = 0.001, max = 10.0, step = 0.01, decimals = 3, unit = tr("skeleton.ik.tolerance"), modifier = Modifier.fillMaxWidth())
    }
}
