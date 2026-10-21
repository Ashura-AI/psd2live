package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.IconAdd
import io.github.psd2live.ui.components.IconMouse
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.views.texture.FloatingMenuRow
import org.umamo.runtime.model.Drawable

/** What the inspector's hit area list offers: no area, one of the document's, a new one, or the Head and Body presets. */
private sealed interface HitAreaChoice {
	data object None : HitAreaChoice
	data class Area(val id: String, val label: String) : HitAreaChoice
	data object New : HitAreaChoice
	data object Preset : HitAreaChoice
}

/** The inspector rows of a mesh's hit area: the area it is in, if any, and that area's name and ID. */
@Composable
internal fun HitAreaInspectorRows(drawable: Drawable, state: PSD2LiveState, viewModel: PSD2LiveViewModel) {
	if (drawable.mesh == null) return
	val areas = state.rigEdits.hitAreas
	val meshId = drawable.id.raw
	val current = areas.firstOrNull { meshId in it.meshes }
	val editable = !state.isBusy && !state.workspaceEditBusy && !state.editorDraftBusy
	InspectorFormRow(label = tr("inspector.hitArea")) {
		val choices = buildList {
			add(HitAreaChoice.None)
			areas.forEach { add(HitAreaChoice.Area(it.id, "${it.name} (${it.id})")) }
			add(HitAreaChoice.New)
			if (areas.isEmpty()) add(HitAreaChoice.Preset)
		}
		CompactDropdown(
			items = choices,
			selectedItem = choices.firstOrNull { it is HitAreaChoice.Area && it.id == current?.id } ?: HitAreaChoice.None,
			onItemSelected = { choice ->
				when (choice) {
					HitAreaChoice.None -> viewModel.assignMeshHitArea(meshId, null)
					is HitAreaChoice.Area -> viewModel.assignMeshHitArea(meshId, choice.id)
					HitAreaChoice.New -> viewModel.createHitArea(listOf(meshId))
					HitAreaChoice.Preset -> viewModel.presetHitAreas()
				}
			},
			itemLabel = { choice ->
				when (choice) {
					HitAreaChoice.None -> tr("inspector.none")
					is HitAreaChoice.Area -> choice.label
					HitAreaChoice.New -> tr("hitArea.new")
					HitAreaChoice.Preset -> tr("hitArea.preset")
				}
			},
			enabled = editable,
			modifier = Modifier.fillMaxWidth(),
			height = 23.dp,
		)
	}
	if (current == null) return
	// Typed into locally and committed when the field is left, so one rename is one history edit.
	var name by remember(current.id, current.name) { mutableStateOf(current.name) }
	InspectorFormRow(label = tr("inspector.hitAreaName")) {
		CompactTextField(
			value = name,
			onValueChange = { name = it },
			onCommit = { viewModel.renameHitArea(current.id, name) },
			onEditEnd = { viewModel.renameHitArea(current.id, name) },
			enabled = editable,
			modifier = Modifier.fillMaxWidth(),
			height = 23.dp,
		)
	}
	var id by remember(current.id) { mutableStateOf(current.id) }
	InspectorFormRow(label = tr("inspector.hitAreaId")) {
		CompactTextField(
			value = id,
			onValueChange = { id = it },
			onCommit = { viewModel.changeHitAreaId(current.id, id) },
			onEditEnd = { viewModel.changeHitAreaId(current.id, id) },
			enabled = editable,
			isMono = true,
			modifier = Modifier.fillMaxWidth(),
			height = 23.dp,
		)
	}
}

/**
 * The hierarchy's ArtMesh menu rows for hit areas: each area as a toggle of this mesh, then a new area of it, and the
 * Head and Body presets while the document has no area. Added through [add] as [ArtMeshMenu]'s other rows are.
 */
internal fun hitAreaMenuRows(
	drawable: Drawable,
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	editable: Boolean,
	close: () -> Unit,
	add: (highlight: Boolean, content: @Composable () -> Unit) -> Unit,
) {
	val meshId = drawable.id.raw
	val areas = state.rigEdits.hitAreas
	for (area in areas) {
		val member = meshId in area.meshes
		add(editable) {
			FloatingMenuRow(tr("hitArea.menuArea", area.name), { close(); viewModel.toggleMeshHitArea(meshId, area.id) },
				selected = member, enabled = editable, trailing = area.id, icon = { IconMouse(member, Modifier.size(12.dp), it) })
		}
	}
	add(editable) {
		FloatingMenuRow(tr("hitArea.menuNew"), { close(); viewModel.createHitArea(listOf(meshId)) }, enabled = editable,
			icon = { IconAdd(Modifier.size(12.dp), it) })
	}
	if (areas.isEmpty()) add(editable) {
		FloatingMenuRow(tr("hitArea.preset"), { close(); viewModel.presetHitAreas() }, enabled = editable,
			icon = { IconMouse(false, Modifier.size(12.dp), it) })
	}
}
