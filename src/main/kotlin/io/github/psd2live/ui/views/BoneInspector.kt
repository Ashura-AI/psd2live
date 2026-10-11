package io.github.psd2live.ui.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

/**
 * Everything about the selected bone in one place, in the skeleton dock under the tree: its title, its joint
 * and structure while the armature is being edited, its IK, and the numeric transforms of the selection.
 * The tool details panel keeps only what belongs to the active tool.
 */
@Composable
internal fun BoneInspector(editor: CanvasEditor, modifier: Modifier = Modifier) {
	val spec = editor.currentSkeleton ?: return
	val bone = spec.bone(editor.selectedBoneId ?: "") ?: return
	val draft = editor.skeletonDraft
	Column(modifier) {
		BoneHeader(spec, bone)
		if (draft != null) BoneSettings(editor, draft, bone)
		Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			if (draft != null) io.github.psd2live.ui.components.CompactNumberSpinner((draft.symmetryAxisX ?: editor.model.canvasWidth / 2f).toDouble(),
				{ editor.setBoneSymmetryAxis(it.toFloat()) }, min = -100000.0, decimals = 1, unit = tr("skeleton.structure.axis"), modifier = Modifier.fillMaxWidth())
			SkeletonIkControls(editor)
			if (draft != null && editor.skeletonEditSubTool.editsBones) SkeletonTransformControls(editor)
		}
	}
}

/**
 * The selected bone's joint: how wide the blend band around its head is, and how far it may turn each
 * way. The band is automatic until moved; the limits are the range of the bone's parameter.
 */
@Composable
private fun BoneSettings(editor: CanvasEditor, spec: SkeletonSpec, bone: SkeletonBone) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	// A limb skins apart from the body bone it hangs from, so only a joint inside the limb has a blend band.
	val parent = lineage(spec, bone).firstOrNull { !it.role.anchor }?.takeUnless { it.role.body }
	Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
		var name by remember(bone.id, bone.name) { mutableStateOf(bone.name) }
		CompactTextField(value = name, onValueChange = { name = it }, placeholder = tr("skeleton.author.name"),
			modifier = Modifier.fillMaxWidth(), onCommit = { editor.renameBone(bone.id, name) },
			onFocusLost = { editor.renameBone(bone.id, name) })
		val excluded = spec.descendants(bone.id) + bone.id
		val parents = listOf("" to tr("skeleton.author.noParent")) + spec.bones.filterNot { it.id in excluded }.map { it.id to it.name }
		CompactDropdown(items = parents, selectedItem = parents.first { it.first == (bone.parentId ?: "") },
			onItemSelected = { editor.setSelectedBoneParent(it.first.takeIf(String::isNotEmpty)) }, itemLabel = { it.second },
			modifier = Modifier.fillMaxWidth(), height = 24.dp)
		CompactToggleChip(text = tr("skeleton.author.connected"), selected = spec.isConnected(bone.id), enabled = bone.parentId != null,
			onToggle = { editor.setSelectedBoneParent(bone.parentId, !spec.isConnected(bone.id)) }, height = 22.dp)
		if (bone.role.limb) {
			Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
				CompactToggleChip(text = tr("skeleton.author.flex"), selected = bone.flex,
					onToggle = { editor.setBonesFlex(!bone.flex) }, height = 22.dp)
				if (bone.flex) {
					val chain = generateSequence(bone) { b -> b.parentId?.let(spec::bone)?.takeIf { it.flex } }.last()
					val straight = (spec.descendants(chain.id) + chain.id).any { spec.bone(it)?.drawnBend?.let { d -> d != 0f } == true }
					CompactToggleChip(text = tr(if (straight) "skeleton.author.unstraighten" else "skeleton.author.straighten"), selected = straight,
						onToggle = { editor.straightenSelectedChain(!straight) }, showCheckWhenSelected = false, height = 22.dp)
				}
			}
		}
		// A body half bends about the waist across a band of its own.
		if (parent != null || bone.role.body) {
			val limit = ((if (parent != null) minOf(bone.length, parent.length) else bone.length) * 0.45f).coerceAtLeast(1f)
			val automatic = bone.blendWidth == null
			val shown = if (parent != null) bone.blendWidth ?: SkeletonWeights.blendHalfWidth(bone, parent.length).toFloat()
				else SkeletonRig.waistBand(bone)
			SettingRow(tr("skeleton.panel.blend"), "${shown.roundToInt()}px") {
				CompactSlider(value = shown.coerceIn(0f, limit), onValueChange = { editor.setBoneBlendWidth(it) },
					valueRange = 0f..limit, modifier = Modifier.weight(1f))
				Spacer(Modifier.width(4.dp))
				CompactToggleChip(text = tr("skeleton.panel.auto"), selected = automatic, onToggle = { editor.setBoneBlendWidth(null) },
					showCheckWhenSelected = false, height = 18.dp)
			}
		}
		SettingRow(tr("skeleton.panel.min"), "${bone.minAngle.roundToInt()}°") {
			CompactSlider(value = bone.minAngle, onValueChange = { editor.setBoneLimits(it, bone.maxAngle) },
				valueRange = -180f..0f, modifier = Modifier.weight(1f))
		}
		SettingRow(tr("skeleton.panel.max"), "${bone.maxAngle.roundToInt()}°") {
			CompactSlider(value = bone.maxAngle, onValueChange = { editor.setBoneLimits(bone.minAngle, it) },
				valueRange = 0f..180f, modifier = Modifier.weight(1f))
		}
	}
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
