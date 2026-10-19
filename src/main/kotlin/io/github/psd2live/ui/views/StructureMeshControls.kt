package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.mesh.structure.StructureKind
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.views.physics.FieldLabel

/**
 * Options and the rebuild for remeshing [target] along its texture structure, as the Mesh and Interior panels show
 * them. While shown, the canvas draws the lines found and the mesh they would give ([drawStructurePreview]).
 */
@Composable
internal fun StructureMeshControls(vm: PSD2LiveViewModel, state: PSD2LiveState, target: String, enabled: Boolean = true) {
	val colors = LocalToolColors.current
	val caption = LocalToolTypography.current.caption.copy(fontSize = 10.sp)
	val structure = vm.structureMesh
	LaunchedEffect(target, state.previewModel, structure.showLines, structure.sensitivity, structure.near, structure.far, structure.kinds, structure.fill, structure.bands) {
		structure.refresh(target)
	}
	DisposableEffect(target) { onDispose { structure.refresh(null) } }
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		InteriorSlider("mesh.structure.sensitivity", structure.sensitivity, 0f..1f, { structure.sensitivity = it }, {})
		InteriorSlider("mesh.structure.near", structure.near, .25f..4f, { structure.near = it }, {})
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			FieldLabel(tr("mesh.structure.layout"))
			CompactToggleChip(tr("mesh.structure.fill"), structure.fill, { structure.fill = !structure.fill },
				tooltip = tr("mesh.structure.fillTip"))
			CompactToggleChip(tr("mesh.structure.bands"), structure.bands, { structure.bands = !structure.bands },
				tooltip = tr("mesh.structure.bandsTip"))
		}
		if (structure.fill) InteriorSlider("mesh.structure.far", structure.far, .5f..3f, { structure.far = it }, {})
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			FieldLabel(tr("mesh.structure.kinds"))
			for (kind in StructureKind.entries) {
				CompactToggleChip(
					tr("mesh.structure.kind.${kind.name.lowercase()}"),
					kind in structure.kinds,
					{ structure.kinds = if (kind in structure.kinds) structure.kinds - kind else structure.kinds + kind },
					tooltip = tr("mesh.structure.kind.${kind.name.lowercase()}Tip"),
				)
			}
		}
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			CompactToggleChip(
				tr("mesh.structure.show"),
				structure.showLines,
				{ structure.showLines = !structure.showLines },
				tooltip = tr("mesh.structure.showTip"),
			)
			Spacer(Modifier.weight(1f))
			CompactButton(
				tr("mesh.structure.apply"),
				{ structure.remesh(target) },
				enabled = enabled && !structure.busy,
				isPrimary = true,
				height = 22.dp,
			)
		}
		structure.preview?.takeIf { it.target == target && structure.showLines }?.let { preview ->
			fun count(kind: StructureKind) = preview.lines.count { it.kind == kind }.toString()
			Text(
				tr("mesh.structure.summary", count(StructureKind.FOLD), count(StructureKind.SHADE), count(StructureKind.PATTERN),
					preview.verticesBefore.toString(), preview.verticesAfter.toString()),
				style = caption,
				color = colors.textMuted,
			)
		}
	}
}
