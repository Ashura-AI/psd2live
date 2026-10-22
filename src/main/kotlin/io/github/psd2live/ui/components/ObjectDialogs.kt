package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.psd2live.application.WorkspaceObjectEdits
import io.github.psd2live.core.RigIdConversion
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The label of an object kind as find and replace and ID conversion list them. */
private fun kindLabel(kind: String) = tr("objectOps.kind.$kind")

/** A bordered box holding a list, as the depth split dialog frames its preview. */
@Composable
private fun ListBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
	val colors = LocalToolColors.current
	Column(modifier.fillMaxWidth().background(colors.inputBackground, RoundedCornerShape(4.dp))
		.border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(4.dp)).padding(8.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
private fun Labeled(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
	val colors = LocalToolColors.current
	Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
		Text(label, style = LocalToolTypography.current.caption, color = colors.textMuted)
		content()
	}
}

/**
 * Find and replace object names or IDs, as Cubism's search and replace: the list shows what the replace makes of each
 * match before anything is written; Replace All commits one undoable edit through `object_rename`.
 */
@Composable
internal fun FindReplaceDialog(state: PSD2LiveState, viewModel: PSD2LiveViewModel) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var field by remember { mutableStateOf("name") }
	var kinds by remember { mutableStateOf(WorkspaceObjectEdits.FIND_KINDS.toSet()) }
	var find by remember { mutableStateOf("") }
	var replace by remember { mutableStateOf("") }
	var prefix by remember { mutableStateOf("") }
	var suffix by remember { mutableStateOf("") }
	var regex by remember { mutableStateOf(false) }
	var matchCase by remember { mutableStateOf(true) }
	var swapSides by remember { mutableStateOf(false) }
	var selectionOnly by remember { mutableStateOf(false) }
	val selection = remember { viewModel.selectedObjectTargets() }
	val request = buildJsonObject {
		put("field", field)
		put("kinds", JsonArray(WorkspaceObjectEdits.FIND_KINDS.filter { it in kinds }.map(::JsonPrimitive)))
		if (selectionOnly) put("targets", JsonArray(selection.map(::JsonPrimitive)))
		if (find.isNotEmpty()) put("find", find)
		if (find.isNotEmpty()) put("replace", replace)
		if (prefix.isNotEmpty()) put("prefix", prefix)
		if (suffix.isNotEmpty()) put("suffix", suffix)
		if (regex) put("regex", true)
		if (!matchCase) put("match_case", false)
		if (swapSides) put("swap_sides", true)
	}
	val replacing = find.isNotEmpty() || prefix.isNotEmpty() || suffix.isNotEmpty() || swapSides
	val found = remember(request, state.previewModel) { viewModel.findObjects(request) }
	val matches = found.getOrNull().orEmpty()
	val busy = state.canvasEditBusy || state.workspaceEditBusy
	val apply = { if (replacing && matches.isNotEmpty() && kinds.isNotEmpty() && !busy) viewModel.replaceObjects(request, matches.size) }
	ModalDialogFrame(
		title = tr("objectOps.find.title"),
		onDismiss = viewModel::dismissObjectDialog,
		width = 520.dp,
		onConfirm = apply,
		footer = {
			CompactButton(tr("action.cancel"), viewModel::dismissObjectDialog)
			CompactButton(tr("objectOps.find.apply"), apply, enabled = replacing && matches.isNotEmpty() && kinds.isNotEmpty() && !busy, isPrimary = true)
		},
	) {
		Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
			Text(tr("objectOps.find.field"), style = typography.caption, color = colors.textMuted)
			CompactRadioButton(field == "name", { field = "name" }, label = tr("objectOps.find.fieldName"))
			CompactRadioButton(field == "id", { field = "id" }, label = tr("objectOps.find.fieldId"))
		}
		Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			for (kind in WorkspaceObjectEdits.FIND_KINDS) CompactToggleChip(kindLabel(kind), kind in kinds,
				{ kinds = if (kind in kinds) kinds - kind else kinds + kind })
		}
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Labeled(tr("objectOps.find.find"), Modifier.weight(1f)) { CompactTextField(find, { find = it }, Modifier.fillMaxWidth(), isMono = field == "id") }
			Labeled(tr("objectOps.find.replace"), Modifier.weight(1f)) { CompactTextField(replace, { replace = it }, Modifier.fillMaxWidth(), isMono = field == "id",
				enabled = find.isNotEmpty()) }
		}
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Labeled(tr("objectOps.find.prefix"), Modifier.weight(1f)) { CompactTextField(prefix, { prefix = it }, Modifier.fillMaxWidth(), isMono = field == "id") }
			Labeled(tr("objectOps.find.suffix"), Modifier.weight(1f)) { CompactTextField(suffix, { suffix = it }, Modifier.fillMaxWidth(), isMono = field == "id") }
		}
		Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
			CompactCheckbox(regex, { regex = it }, label = tr("objectOps.find.regex"))
			CompactCheckbox(matchCase, { matchCase = it }, label = tr("objectOps.find.matchCase"))
			CompactCheckbox(selectionOnly, { selectionOnly = it }, enabled = selection.isNotEmpty(), label = tr("objectOps.find.selection", selection.size))
		}
		CompactCheckbox(swapSides, { swapSides = it }, label = tr("objectOps.find.swapSides"))
		val failure = found.exceptionOrNull()?.message
		Text(when {
			failure != null -> failure
			matches.isEmpty() -> tr("objectOps.find.none")
			else -> tr("objectOps.find.matches", matches.size)
		}, style = typography.caption, color = if (failure != null) colors.warning else colors.textMuted)
		ListBox(Modifier.height(220.dp)) {
			LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
				items(matches.take(500), key = { it.target }) { match ->
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
						Text(kindLabel(match.kind), style = typography.caption, color = colors.textMuted, modifier = Modifier.width(72.dp), maxLines = 1)
						Text(match.before, style = typography.body, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
							modifier = Modifier.weight(1f))
						if (replacing) {
							Text("→", style = typography.caption, color = colors.textMuted)
							Text(match.after, style = typography.body, color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
								modifier = Modifier.weight(1f))
						}
					}
				}
			}
		}
	}
}

/**
 * Cubism's ID conversion: each object of a kind with its ID and a field for the new one; what the fields hold converts
 * as one undoable edit through `object_convert_id`, every reference following. The standard button fills Cubism 3's
 * standard IDs in for Cubism 2 style ones.
 */
@Composable
internal fun ConvertIdsDialog(state: PSD2LiveState, viewModel: PSD2LiveViewModel) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val puppet = state.previewModel?.rig?.puppet ?: return
	val objects = remember(puppet) { WorkspaceObjectEdits.objects(puppet) }
	var kind by remember { mutableStateOf("parameter") }
	var filter by remember { mutableStateOf("") }
	val drafts = remember { mutableStateMapOf<String, String>() }
	val shown = objects.filter { it.first == kind && (filter.isBlank() || it.second.contains(filter, true) || it.third.contains(filter, true)) }
	val renames = drafts.filter { (target, id) -> id.isNotBlank() && id.trim() != target.substringAfter(':') }.mapValues { it.value.trim() }
	val problem = remember(renames, puppet) {
		if (renames.isEmpty()) null
		else RigIdConversion.problem(puppet, renames.map { (target, id) -> RigIdConversion.Rename(target.substringBefore(':'), target.substringAfter(':'), id) })
	}
	val busy = state.canvasEditBusy || state.workspaceEditBusy
	val apply = { if (renames.isNotEmpty() && problem == null && !busy) viewModel.convertObjectIds(renames) }
	ModalDialogFrame(
		title = tr("objectOps.convert.title"),
		onDismiss = viewModel::dismissObjectDialog,
		width = 560.dp,
		onConfirm = apply,
		footerStart = {
			CompactButton(tr("objectOps.convert.standard"), {
				for ((k, id, _) in objects) WorkspaceObjectEdits.standardId(k, id)?.let { drafts["$k:$id"] = it }
			}, enabled = !busy)
		},
		footer = {
			CompactButton(tr("action.cancel"), viewModel::dismissObjectDialog)
			CompactButton(tr("objectOps.convert.apply", renames.size), apply, enabled = renames.isNotEmpty() && problem == null && !busy, isPrimary = true)
		},
	) {
		ModalMessage(tr("objectOps.convert.body"))
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
			Labeled(tr("objectOps.find.kinds"), Modifier.width(150.dp)) {
				CompactDropdown(RigIdConversion.KINDS.filter { k -> objects.any { it.first == k } }.ifEmpty { listOf(kind) }, kind, { kind = it },
					Modifier.fillMaxWidth(), itemLabel = ::kindLabel)
			}
			Labeled(tr("objectOps.convert.filter"), Modifier.weight(1f)) { CompactTextField(filter, { filter = it }, Modifier.fillMaxWidth()) }
		}
		ListBox(Modifier.height(300.dp)) {
			LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
				items(shown, key = { "${it.first}:${it.second}" }) { (k, id, name) ->
					val target = "$k:$id"
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
						Column(Modifier.weight(1f)) {
							Text(id, style = typography.mono, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
							Text(name, style = typography.caption, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
						}
						Text("→", style = typography.caption, color = colors.textMuted)
						CompactTextField(drafts[target].orEmpty(), { drafts[target] = it }, Modifier.weight(1f), placeholder = id, isMono = true)
					}
				}
			}
		}
		Text(problem ?: tr("objectOps.convert.count", renames.size), style = typography.caption,
			color = if (problem != null) colors.warning else colors.textMuted)
	}
}
