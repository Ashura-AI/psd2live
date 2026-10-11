package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.theme.LocalToolColors

/** Moving, turning and scaling the selection by exact amounts; dragging on the canvas moves it by eye. */
@Composable
internal fun SkeletonNumericTransform(editor: CanvasEditor) {
    val colors = LocalToolColors.current
    var dx by remember(editor.selectedBoneIds) { mutableStateOf(0.0) }
    var dy by remember(editor.selectedBoneIds) { mutableStateOf(0.0) }
    var angle by remember(editor.selectedBoneIds) { mutableStateOf(0.0) }
    var scale by remember(editor.selectedBoneIds) { mutableStateOf(1.0) }
    var segments by remember { mutableStateOf(2.0) }
    val bone = editor.currentSkeleton?.bone(editor.selectedBoneId ?: "")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tr("skeleton.transform.hint", editor.selectedBoneIds.size), color = colors.textMuted, fontSize = 10.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CompactNumberSpinner(dx, { dx = it }, min = -100000.0, unit = "X px", decimals = 1, modifier = Modifier.weight(1f))
            CompactNumberSpinner(dy, { dy = it }, min = -100000.0, unit = "Y px", decimals = 1, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CompactNumberSpinner(angle, { angle = it }, min = -360.0, max = 360.0, unit = "°", decimals = 1, modifier = Modifier.weight(1f))
            CompactNumberSpinner(scale, { scale = it }, min = 0.01, max = 100.0, step = 0.1, decimals = 2, unit = "×", modifier = Modifier.weight(1f))
        }
        CompactButton(tr("skeleton.transform.apply"), enabled = editor.selectedBoneIds.isNotEmpty(), modifier = Modifier.fillMaxWidth(),
            onClick = { editor.transformSelectedBones(dx.toFloat(), dy.toFloat(), angle.toFloat(), scale.toFloat()); dx = 0.0; dy = 0.0; angle = 0.0; scale = 1.0 })
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CompactNumberSpinner(segments, { segments = it }, min = 2.0, max = 16.0, modifier = Modifier.weight(1f))
            CompactButton(tr("skeleton.structure.subdivide"), onClick = { editor.subdivideSelectedBone(segments.toInt()) }, modifier = Modifier.weight(1f),
                enabled = bone != null && bone.role.limb && bone.length / segments >= 1f)
        }
    }
}

/**
 * What can be done to the selection: pick more of the chain, copy it, mirror it, merge a bone away.
 * Where the mirror axis lies is a line on the canvas, not a field here.
 */
@Composable
internal fun SkeletonSelectionActions(editor: CanvasEditor) {
    val colors = LocalToolColors.current
    val spec = editor.skeletonDraft ?: return
    val bone = spec.bone(editor.selectedBoneId ?: "")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CompactButton(tr("skeleton.transform.all"), onClick = { editor.selectBones(spec.bones.mapTo(linkedSetOf()) { it.id }) }, modifier = Modifier.weight(1f))
            CompactButton(tr("skeleton.transform.chain"), onClick = {
                val ids = editor.selectedBoneIds
                editor.selectBones(ids + ids.flatMap { spec.descendants(it) })
            }, enabled = editor.selectedBoneIds.isNotEmpty(), modifier = Modifier.weight(1f))
        }
        CompactToggleChip(tr("skeleton.transform.children"), selected = editor.transformBoneDescendants,
            onToggle = { editor.transformBoneDescendants = !editor.transformBoneDescendants })
        CompactToggleChip(tr("skeleton.structure.symmetric"), selected = editor.editBonesSymmetrically,
            onToggle = { editor.editBonesSymmetrically = !editor.editBonesSymmetrically })
        CompactToggleChip(tr("skeleton.structure.transfer"), selected = editor.transferCopiedBoneBindings,
            onToggle = { editor.transferCopiedBoneBindings = !editor.transferCopiedBoneBindings })
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CompactButton(tr("skeleton.structure.copy"), onClick = { editor.duplicateSelectedBones() }, enabled = editor.selectedBoneIds.isNotEmpty(), modifier = Modifier.weight(1f))
            CompactButton(tr("skeleton.structure.mirror"), onClick = { editor.duplicateSelectedBones(mirror = true) },
                enabled = editor.selectedBoneIds.isNotEmpty(), modifier = Modifier.weight(1f))
        }
        val mirrors = editor.boneMirrorDrawables()
        val selected = editor.selectedBoneIds + if (editor.transformBoneDescendants) editor.selectedBoneIds.flatMap(spec::descendants) else emptyList()
        val matched = spec.bones.filter { it.id in selected }.flatMap { it.drawableIds }.count { it in mirrors }
        Text(tr("skeleton.structure.mirrorHint", matched), color = colors.textMuted, fontSize = 10.sp)
        CompactButton(tr("skeleton.structure.dissolve"), onClick = { editor.dissolveSelectedBone() }, modifier = Modifier.fillMaxWidth(),
            enabled = bone != null && io.github.psd2live.core.SkeletonAuthoring.canDissolve(spec, bone.id))
        Text(tr("skeleton.structure.hint"), color = colors.textMuted, fontSize = 10.sp)
    }
}
