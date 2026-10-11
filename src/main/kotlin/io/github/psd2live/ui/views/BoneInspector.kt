package io.github.psd2live.ui.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.parameterKeyMarks
import io.github.psd2live.ui.state.CanvasMode
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import org.umamo.runtime.model.ParameterId
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.SkeletonBone
import io.github.psd2live.core.SkeletonRig
import io.github.psd2live.core.SkeletonSampling
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.core.SkeletonSpec
import io.github.psd2live.core.SkeletonWeights
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.SkeletonPalette
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactSlider
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.components.IconBone
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.visualKind
import kotlin.math.roundToInt

/**
 * The settings of the selected bone, in the inspector beside the tree. What can be shown on the canvas is
 * there instead - the angle limits and blend band are handles on the bone, the symmetry axis is a line,
 * IK targets are dragged in Pose - so this holds only what has no shape: names, links, toggles and
 * actions, with the few exact numbers folded away under Advanced.
 */
@Composable
internal fun BoneInspector(editor: CanvasEditor, viewModel: PSD2LiveViewModel, state: PSD2LiveState) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val spec = editor.currentSkeleton
	val draft = editor.skeletonDraft
	val bone = spec?.bone(editor.selectedBoneId ?: "")
	if (spec == null || bone == null) {
		Text(tr("skeleton.inspector.empty"), color = colors.textMuted, style = typography.caption.copy(fontSize = 10.5.sp),
			modifier = Modifier.padding(12.dp))
		return
	}
	Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		BoneHeader(spec, bone)
		Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			if (draft != null) {
				BoneBasics(editor, draft, bone)
				JointReadout(editor, draft, bone)
			}
			SkeletonIkControls(editor)
			if (draft != null && editor.skeletonEditSubTool.editsBones) SkeletonSelectionActions(editor)
			Advanced(tr("skeleton.inspector.advanced")) {
				SkeletonIkAdvanced(editor)
				if (draft != null) SkeletonNumericTransform(editor)
				if (draft == null) {
					val baked = state.previewModel?.config?.rigEdits?.skeleton
					val pointCounts = state.previewModel?.takeIf { spec.enabled && baked == spec }?.let { preview ->
						val marks = preview.rig.puppet.parameterKeyMarks()
						spec.bones.filterNot { it.role.anchor }.associate { it.parameterId to (marks[ParameterId(it.parameterId)]?.allKeys?.size ?: 0) }
					}
					SamplingSettings(spec, pointCounts, bone.id, viewModel)
				}
			}
		}
	}
}

/** A folded section for the exact numbers: closed until asked for, so a panel does not open as a wall of fields. */
@Composable
private fun Advanced(title: String, content: @Composable () -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var open by remember { mutableStateOf(false) }
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
			IconChevron(expanded = open, modifier = Modifier.size(9.dp), tint = colors.textMuted)
			Spacer(Modifier.width(4.dp))
			Text(title, color = colors.textMuted, style = typography.caption.copy(fontSize = 10.5.sp))
		}
		if (open) content()
	}
}

/** The selected bone's title bar: its kind's glyph in its color, the kind's name and its parameter. */
@Composable
private fun BoneHeader(spec: SkeletonSpec, bone: SkeletonBone) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(
		Modifier.fillMaxWidth().height(24.dp).background(colors.panelElevated)
			.border(BorderStroke(1.dp, colors.divider)).padding(horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		IconBone(tint = SkeletonPalette.color(spec, bone.id), modifier = Modifier.size(12.dp), kind = bone.visualKind())
		Spacer(Modifier.width(6.dp))
		Text(bone.name, modifier = Modifier.weight(1f), color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
			style = typography.header.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold))
		Text(tr("skeleton.kind." + bone.visualKind().name.lowercase()), color = colors.textMuted, style = typography.caption.copy(fontSize = 9.sp), maxLines = 1)
		Spacer(Modifier.width(6.dp))
		Text(bone.parameterId, color = colors.textMuted, style = typography.monoSmall.copy(fontSize = 9.sp), maxLines = 1)
	}
}

/** Name, parent and the two shape toggles: the bone's identity in the tree. */
@Composable
private fun BoneBasics(editor: CanvasEditor, spec: SkeletonSpec, bone: SkeletonBone) {
	Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
		var name by remember(bone.id, bone.name) { mutableStateOf(bone.name) }
		CompactTextField(value = name, onValueChange = { name = it }, placeholder = tr("skeleton.author.name"),
			modifier = Modifier.fillMaxWidth(), onCommit = { editor.renameBone(bone.id, name) },
			onFocusLost = { editor.renameBone(bone.id, name) })
		val excluded = spec.descendants(bone.id) + bone.id
		val parents = listOf("" to tr("skeleton.author.noParent")) + spec.bones.filterNot { it.id in excluded }.map { it.id to it.name }
		CompactDropdown(items = parents, selectedItem = parents.first { it.first == (bone.parentId ?: "") },
			onItemSelected = { editor.setSelectedBoneParent(it.first.takeIf(String::isNotEmpty)) }, itemLabel = { it.second },
			modifier = Modifier.fillMaxWidth(), height = 24.dp)
		Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
			CompactToggleChip(text = tr("skeleton.author.connected"), selected = spec.isConnected(bone.id), enabled = bone.parentId != null,
				onToggle = { editor.setSelectedBoneParent(bone.parentId, !spec.isConnected(bone.id)) }, height = 22.dp)
			if (bone.role.limb) {
				CompactToggleChip(text = tr("skeleton.author.flex"), selected = bone.flex,
					onToggle = { editor.setBonesFlex(!bone.flex) }, height = 22.dp)
			}
		}
		if (bone.role.limb && bone.flex) {
			val chain = generateSequence(bone) { b -> b.parentId?.let(spec::bone)?.takeIf { it.flex } }.last()
			val straight = (spec.descendants(chain.id) + chain.id).any { spec.bone(it)?.drawnBend?.let { d -> d != 0f } == true }
			CompactToggleChip(text = tr(if (straight) "skeleton.author.unstraighten" else "skeleton.author.straighten"), selected = straight,
				onToggle = { editor.straightenSelectedChain(!straight) }, showCheckWhenSelected = false, height = 22.dp)
		}
	}
}

/**
 * The limits and blend band, read out - they are set by the handles on the canvas. Only the way back to
 * the automatic band is a control here, as a handle cannot say "automatic".
 */
@Composable
private fun JointReadout(editor: CanvasEditor, spec: SkeletonSpec, bone: SkeletonBone) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	if (bone.role.anchor) return
	val hasBand = lineage(spec, bone).firstOrNull { !it.role.anchor }?.takeUnless { it.role.body } != null || bone.role.body
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		Text(tr("skeleton.inspector.range", bone.minAngle.roundToInt(), bone.maxAngle.roundToInt()), color = colors.textPrimary,
			style = typography.monoSmall.copy(fontSize = 10.sp), modifier = Modifier.weight(1f))
		if (hasBand && bone.blendWidth != null) {
			CompactToggleChip(text = tr("skeleton.panel.auto"), selected = false, onToggle = { editor.setBoneBlendWidth(null) },
				showCheckWhenSelected = false, height = 18.dp)
		}
	}
	Text(tr("skeleton.inspector.handlesHint"), color = colors.textMuted, style = typography.caption.copy(fontSize = 10.sp))
}

/** A property row: muted label, the control, and the value right-aligned in a fixed column. */
@Composable
internal fun SettingRow(label: String, value: String, control: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
		Text(label, color = colors.textMuted, style = typography.caption.copy(fontSize = 9.5.sp), maxLines = 1,
			overflow = TextOverflow.Ellipsis, modifier = Modifier.width(56.dp))
		control()
		Text(value, color = colors.textPrimary, style = typography.monoSmall.copy(fontSize = 10.sp), textAlign = TextAlign.End,
			maxLines = 1, modifier = Modifier.width(42.dp))
	}
}

/** The canvas editor that holds the skeleton's selection: the skeleton is drawn and edited on an edit canvas, and a focused preview canvas hands over to one. */
internal fun skeletonEditor(state: PSD2LiveState, viewModel: PSD2LiveViewModel): CanvasEditor {
	val canvas = state.activeCanvas.takeIf { it.mode == CanvasMode.EDIT }
		?: state.activeWorkspace.canvases.firstOrNull { it.mode == CanvasMode.EDIT }
		?: state.activeCanvas
	return viewModel.canvasEditorFor(canvas.id)
}

/** Project-wide skeleton key sampling, with counts read from the rebuilt rig. */
@Composable
internal fun SamplingSettings(
	spec: SkeletonSpec,
	pointCounts: Map<String, Int>?,
	selectedBoneId: String?,
	viewModel: PSD2LiveViewModel,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val sampling = spec.sampling
	fun change(next: SkeletonSampling) {
		val current = viewModel.state.value.rigEdits.skeleton ?: spec
		if (current.sampling != next) viewModel.setSkeleton(current.copy(sampling = next))
	}
	Row(
		Modifier.fillMaxWidth().height(24.dp).background(colors.panelElevated)
			.border(BorderStroke(1.dp, colors.divider)).padding(horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(tr("skeleton.sampling.title"), modifier = Modifier.weight(1f), color = colors.textPrimary,
			style = typography.header.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold))
		CompactButton(text = tr("skeleton.sampling.reset"), onClick = { change(SkeletonSampling()) },
			enabled = sampling != SkeletonSampling(), height = 18.dp)
	}
	Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
		SettingRow(tr("skeleton.sampling.jointMesh"), sampling.jointMeshSegments.toString()) {
			CompactSlider(value = sampling.jointMeshSegments.toFloat(), onValueChange = {
				change(sampling.copy(jointMeshSegments = ((it / 2f).roundToInt() * 2).coerceIn(4, 32)))
			}, onValueChangeStarted = { viewModel.beginEditorField("skeleton.sampling.jointMesh") },
				onValueChangeFinished = { viewModel.endEditorField("skeleton.sampling.jointMesh") },
				valueRange = 4f..32f, modifier = Modifier.weight(1f))
		}
		SettingRow(tr("skeleton.sampling.tolerance"), "%.2fpx".format(sampling.tolerancePx)) {
			CompactSlider(value = sampling.tolerancePx, onValueChange = {
				change(sampling.copy(tolerancePx = ((it * 20f).roundToInt() / 20f).coerceIn(SkeletonSampling.TOLERANCE_RANGE)))
			}, onValueChangeStarted = { viewModel.beginEditorField("skeleton.sampling.tolerance") },
				onValueChangeFinished = { viewModel.endEditorField("skeleton.sampling.tolerance") },
				valueRange = SkeletonSampling.TOLERANCE_RANGE, modifier = Modifier.weight(1f))
		}
		SettingRow(tr("skeleton.sampling.step"), "%.1f°".format(sampling.minimumStepDegrees)) {
			CompactSlider(value = sampling.minimumStepDegrees, onValueChange = {
				change(sampling.copy(minimumStepDegrees = ((it * 2f).roundToInt() / 2f).coerceIn(SkeletonSampling.STEP_RANGE)))
			}, onValueChangeStarted = { viewModel.beginEditorField("skeleton.sampling.step") },
				onValueChangeFinished = { viewModel.endEditorField("skeleton.sampling.step") },
				valueRange = SkeletonSampling.STEP_RANGE, modifier = Modifier.weight(1f))
		}
		SettingRow(tr("skeleton.sampling.max"), sampling.maxMeshKeyforms.toString()) {
			CompactSlider(value = sampling.maxMeshKeyforms.toFloat(), onValueChange = {
				change(sampling.copy(maxMeshKeyforms = ((it / 10f).roundToInt() * 10).coerceIn(SkeletonSampling.MESH_LIMIT_RANGE)))
			}, onValueChangeStarted = { viewModel.beginEditorField("skeleton.sampling.max") },
				onValueChangeFinished = { viewModel.endEditorField("skeleton.sampling.max") },
				valueRange = SkeletonSampling.MESH_LIMIT_RANGE.first.toFloat()..SkeletonSampling.MESH_LIMIT_RANGE.last.toFloat(),
				modifier = Modifier.weight(1f))
		}
		val countText = when {
			!spec.enabled -> tr("skeleton.sampling.disabled")
			pointCounts == null -> tr("skeleton.sampling.calculating")
			else -> {
				val total = pointCounts.values.sum()
				val selected = selectedBoneId?.let(spec::bone)?.let { pointCounts[it.parameterId] }
				if (selected == null) tr("skeleton.sampling.points", total)
				else tr("skeleton.sampling.pointsSelected", total, selected)
			}
		}
		Text(countText, color = colors.textMuted, style = typography.caption.copy(fontSize = 10.sp),
			modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp))
	}
}

