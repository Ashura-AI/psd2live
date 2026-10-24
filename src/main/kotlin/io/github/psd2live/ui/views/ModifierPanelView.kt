package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.DocumentGenerators
import io.github.psd2live.core.modifier.*
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.*
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.views.physics.FieldLabel
import io.github.psd2live.ui.views.physics.PhysicsSection
import io.github.psd2live.ui.views.texture.FloatingMenuRow
import io.github.psd2live.ui.views.texture.FloatingMenuSection
import androidx.compose.ui.input.pointer.isAltPressed
import io.github.psd2live.ui.views.tooloptions.GlidingColumn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.PuppetModel

/** How a setting's fields step and show: step, decimals. */
private val MODIFIER_FIELDS = mapOf("offset" to (1.0 to 1), "rotation" to (1.0 to 1), "scale" to (0.05 to 2), "pivot" to (1.0 to 1),
	"angle" to (1.0 to 1), "origin" to (1.0 to 1), "direction" to (1.0 to 1), "length" to (1.0 to 1), "center" to (1.0 to 1), "radius" to (1.0 to 1),
	"amplitude" to (1.0 to 1), "wavelength" to (1.0 to 1), "phase" to (5.0 to 1), "ramp" to (1.0 to 1), "bounds" to (1.0 to 1))

/**
 * The selected mesh's (or warp's) stack: one card per entry in the order they run, the skeleton's skin locked first. A
 * modifier's card holds its settings, keyed on parameters by the key button; a swing's, simulation's or interior
 * effect's holds that generator's settings, its own panel keeping what it bakes, previews and checks.
 */
@Composable
internal fun ModifierPanelView(vm: PSD2LiveViewModel, state: PSD2LiveState) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val preview = state.previewModel
	val target = preview?.rig?.layerIdByDrawableId?.entries?.firstOrNull { it.value == state.selectedLayerId }?.key ?: state.selectedLayerId
	val puppet = preview?.rig?.puppet
	val mesh = puppet?.drawables?.singleOrNull { it.id.raw == target }?.takeIf { it.mesh != null }
	// A warp picked on the canvas shows its own stack; otherwise the selected layer's mesh.
	val warp = vm.canvasEditorFor(state.activeCanvas.id).selectionTarget()?.takeIf { it.kind == "warp" }?.id
	val key = warp?.let { ModifierTarget.WARP + it } ?: mesh?.id?.raw
	val stack = state.rigEdits.modifiers.filter { key != null && key in it.target.keys(puppet) }
	// The committed model shows what was previewed: the preview steps aside.
	LaunchedEffect(preview) { vm.modifiers.dropPreview() }
	DisposableEffect(key, state.activeCanvas.id) { onDispose { vm.modifiers.dropPreview() } }
	// Cards start open, a new one too: the panel keeps the ones closed.
	var closed by remember(key) { mutableStateOf(emptySet<String>()) }
	val rows = remember(state.rigEdits, puppet, key) { if (key == null || puppet == null) emptyList() else stackRows(state.rigEdits, puppet, key) }
	Column(Modifier.fillMaxSize()) {
		val labels = listOf(tr("modifiers.add"))
		var adding by remember { mutableStateOf(false) }
		PanelToolbar(labels = labels, iconCount = 2) { shown ->
			Box {
				PanelToolButton(labels[0], showLabel = shown > 0, onClick = { adding = true }, enabled = key != null, tooltip = tr("modifiers.addTip")) {
					IconAdd(modifier = Modifier.size(10.dp), tint = colors.textPrimary)
				}
				if (key != null && puppet != null) PressMenu(expanded = adding, press = Offset(0f, 0f), onDismissRequest = { adding = false }) {
					ModifierAddMenu(vm, state, puppet, if (warp != null) listOf(key) else selectedMeshes(state, puppet, key), stack) { adding = false }
				}
			}
			Spacer(Modifier.weight(1f))
			PanelExpandCollapseButtons(onExpandAll = { closed = emptySet() }, onCollapseAll = { closed = rows.map { it.first }.toSet() }, enabled = rows.isNotEmpty())
		}
		if (key == null || puppet == null) {
			Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
				Text(tr("modifiers.select"), style = typography.caption.copy(fontSize = 10.5.sp), color = colors.textMuted, textAlign = TextAlign.Center)
			}
			return@Column
		}
		val issues by produceState(emptyList<ModifierSampler.Issue>(), preview, state.rigEdits.modifiers) {
			value = withContext(Dispatchers.Default) {
				runCatching {
					val overlay = preview.config.rigEdits
					if (overlay.modifiers.isEmpty()) emptyList()
					else ModifierSampler.issues(DocumentGenerators.generate(preview.authoredPuppet(overlay), overlay.copy(modifiers = emptyList())), overlay.modifiers)
				}.getOrDefault(emptyList())
			}
		}
		val skin = remember(state.rigEdits, key) { key in runCatching { io.github.psd2live.core.SkeletonCanvasSkin.placed(state.rigEdits) }.getOrDefault(emptySet()) }
		val ids = rows.map { it.first }
		Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
			// The skeleton's skin is baked before every other entry; the rest run in the stack's order.
			if (skin) SkinCard()
			if (rows.isEmpty() && !skin) Text(tr("modifiers.empty"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted,
				modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
			rows.forEachIndexed { index, (_, row) ->
				when (row) {
					is RigModifier -> ModifierCard(vm, state, puppet, key, ids, index, row, issues.filter { it.modifier == row.id && it.target == key },
						row.id !in closed) { closed = if (row.id in closed) closed - row.id else closed + row.id }
					is SystemModifier -> GeneratorCard(vm, state, puppet, row, ids, index, row.id !in closed) {
						closed = if (row.id in closed) closed - row.id else closed + row.id
					}
				}
			}
		}
	}
}

@Composable
private fun ModifierCard(
	vm: PSD2LiveViewModel,
	state: PSD2LiveState,
	puppet: PuppetModel,
	mesh: String,
	ids: List<String>,
	index: Int,
	modifier: RigModifier,
	issues: List<ModifierSampler.Issue>,
	open: Boolean,
	onToggle: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var menu by remember { mutableStateOf(false) }
	val trailing: @Composable RowScope.() -> Unit = {
		PanelToolSwitch(tr("modifiers.enabled"), showLabel = false, checked = modifier.enabled, onCheckedChange = { value ->
			vm.modifiers.put(buildJsonObject { put("id", modifier.id); put("enabled", value) })
		}, enabled = true, tooltip = tr("modifiers.enabled"))
		Box {
			CompactIconButton(onClick = { menu = true }, tooltip = tr("modifiers.menu"), size = 18.dp) {
				GridIcon(Modifier.size(11.dp), colors.textMuted) { dot(4.6f, 9f, 1.3f); dot(9f, 9f, 1.3f); dot(13.4f, 9f, 1.3f) }
			}
			PressMenu(expanded = menu, press = Offset(0f, 0f), onDismissRequest = { menu = false }) {
				ModifierMenu(vm, state, puppet, mesh, ids, index, modifier) { menu = false }
			}
		}
	}
	// A modifier left with its default name is titled by its kind alone.
	val kind = tr("modifiers.kind.${modifier.kind}")
	val title = if (modifier.name == modifier.kind.replaceFirstChar { it.uppercase() }) kind else "${modifier.name} · $kind"
	PhysicsSection(title, open, onToggle, trailing = trailing) {
		for (issue in issues) Text(tr("modifiers.issue.${issue.kind.code}", issue.detail.ifEmpty { issue.error?.let { "%.1f".format(it) } ?: "" }),
			style = typography.caption.copy(fontSize = 10.sp), color = colors.warning)
		val pose: (String) -> Float = { p -> state.parameterValues[ParameterId(p)] ?: puppet.parameters.firstOrNull { it.id.raw == p }?.default ?: 0f }
		for (setting in modifier.definition.settings.filter { it.field }) ModifierField(vm, state, puppet, mesh, modifier, setting, pose)
	}
}

/** One setting: its components at the current pose, and the key button that keys it on the parameters there. */
@Composable
private fun ModifierField(vm: PSD2LiveViewModel, state: PSD2LiveState, puppet: PuppetModel, mesh: String, modifier: RigModifier, setting: ModifierSetting,
                          pose: (String) -> Float) {
	val colors = LocalToolColors.current
	val window = androidx.compose.ui.platform.LocalWindowInfo.current
	val stored = modifier.value(setting.key)
	val shown = stored.at(pose)
	// What a drag or typed value holds until the commit it sends comes back as the stored value.
	var draft by remember(modifier, setting.key) { mutableStateOf<FloatArray?>(null) }
	val value = draft ?: shown
	val keyed = stored as? ModifierValue.Keyed
	val at = keyed?.axes?.associate { it.parameter to pose(it.parameter) }.orEmpty()
	fun request(next: FloatArray) = ModifierEdits.request(modifier, mapOf(setting.key to next), pose)
	val onKey = keyed != null && keyed.axes.all { a -> a.keys.any { kotlin.math.abs(it - pose(a.parameter)) <= ModifierValue.EPS } }
	val (step, decimals) = MODIFIER_FIELDS[setting.key] ?: (0.1 to 2)
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		FieldLabel(tr("modifiers.setting.${setting.key}"), tooltip = setting.description)
		for (c in 0 until setting.size) CompactNumberSpinner(
			value[c].toDouble(),
			{ v ->
				val next = value.copyOf().also { it[c] = v.toFloat() }
				draft = next
				vm.modifiers.preview(request(next))
			},
			Modifier.weight(1f),
			min = setting.min.toDouble().coerceAtLeast(-100_000.0), max = setting.max.toDouble().coerceAtMost(100_000.0),
			step = step, decimals = decimals, height = 22.dp,
			onEditEnd = {
				draft?.let { next ->
					// With Alt held, the same value goes to the first modifier of this kind on every other selected mesh.
					@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
					val alt = window.keyboardModifiers.isAltPressed
					val others = if (!alt) emptyList() else {
						val stacks = ModifierSampler.stacks(state.rigEdits.modifiers, puppet)
						selectedMeshes(state, puppet, mesh).filter { it != mesh }.mapNotNull { m ->
							stacks[m].orEmpty().firstOrNull { it.kind == modifier.kind && it.id != modifier.id }
						}.distinctBy { it.id }
					}
					if (others.isEmpty()) vm.modifiers.put(request(next))
					else vm.modifiers.batch((listOf(modifier) + others).map { m ->
						ModifierAuthoring.PUT to JsonObject(request(next) + ("id" to JsonPrimitive(m.id)))
					})
				}
			},
		)
		val tip = tr(when { onKey -> "modifiers.key.remove"; keyed != null -> "modifiers.key.add"; else -> "modifiers.key.start" })
		CompactIconButton(onClick = {
			when {
				onKey -> vm.modifiers.put(buildJsonObject {
					put("id", modifier.id)
					putJsonArray("keys") { addJsonObject { put("setting", setting.key); putJsonObject("at") { at.forEach { (k, v) -> put(k, v) } }; put("delete", true) } }
				})
				keyed != null -> vm.modifiers.put(request(value))
				else -> {
					// A first key goes on the parameters the modifier is keyed on, else on those moved off their defaults.
					val parameters = modifier.drivers.ifEmpty {
						puppet.parameters.filter { p -> p.kind == ParameterKind.NORMAL && kotlin.math.abs(pose(p.id.raw) - p.default) > ModifierValue.EPS }.map { it.id.raw }
					}
					if (parameters.isEmpty()) vm.setStatusText(tr("modifiers.key.noParameter"))
					else vm.modifiers.put(buildJsonObject {
						put("id", modifier.id)
						putJsonArray("keys") { addJsonObject {
							put("setting", setting.key); putJsonObject("at") { parameters.forEach { put(it, pose(it)) } }; put("value", JsonArray(value.map(::JsonPrimitive)))
						} }
					})
				}
			}
		}, tooltip = tip, size = 18.dp) {
			GridIcon(Modifier.size(11.dp), if (keyed != null) colors.accent else colors.textMuted) { keyDiamond(if (onKey) 2 else if (keyed != null) 1 else 0) }
		}
	}
}

@Composable
private fun ModifierMenu(vm: PSD2LiveViewModel, state: PSD2LiveState, puppet: PuppetModel, mesh: String, ids: List<String>, index: Int, modifier: RigModifier,
                         close: () -> Unit) {
	val rows = mutableListOf<Triple<Boolean, Boolean, @Composable () -> Unit>>()
	fun add(enabled: Boolean, danger: Boolean = false, label: String, hint: String? = null, action: () -> Unit) {
		rows += Triple(enabled, danger, @Composable { FloatingMenuRow(label, { close(); action() }, enabled = enabled, hint = hint, danger = danger) })
	}
	// Its handles on the canvas, in object mode, with the keyboard back on the canvas.
	rows += Triple(true, false, @Composable {
		FloatingMenuRow(tr("modifiers.editOnCanvas"), {
			close()
			val canvas = state.activeCanvas.id
			if (state.activeCanvas.mode != CanvasMode.EDIT) vm.setCanvasMode(canvas, CanvasMode.EDIT)
			vm.canvasEditorFor(canvas).let { it.modifierTool.choose(modifier.id); it.activateTool(io.github.psd2live.ui.CanvasTool.MODIFIER) }
			vm.requestCanvasFocus(canvas)
		}, hint = tr("modifiers.editOnCanvasTip"), trailing = state.keymap.labelFor(ShortcutAction.TOOL_MODIFIER))
	})
	val stacks = ModifierSampler.stacks(state.rigEdits.modifiers, puppet)
	val meshes = modifier.target.keys(puppet)
	val alone = meshes.all { stacks[it].orEmpty().firstOrNull()?.id == modifier.id }
	val pose = modifier.drivers.associateWith { p -> state.parameterValues[ParameterId(p)] ?: puppet.parameters.firstOrNull { it.id.raw == p }?.default ?: 0f }
	add(alone && modifier.drivers.isNotEmpty(), label = tr("modifiers.applyKeyforms"), hint = tr(if (alone) "modifiers.applyKeyformsTip" else "modifiers.applyAlone")) {
		vm.modifiers.apply(modifier.id, "keyforms")
	}
	add(true, label = tr("modifiers.applyRest"), hint = tr("modifiers.applyRestTip")) { vm.modifiers.apply(modifier.id, "rest", pose) }
	if (meshes.size > 1) {
		val here = stacks[mesh].orEmpty().firstOrNull()?.id == modifier.id
		add(true, label = tr("modifiers.applyMesh"), hint = tr("modifiers.applyMeshTip")) {
			vm.modifiers.applyTo(modifier.id, mesh, if (here && modifier.drivers.isNotEmpty()) "keyforms" else "rest", pose)
		}
		add(true, label = tr("modifiers.split"), hint = tr("modifiers.splitTip")) { vm.modifiers.split(modifier.id, mesh) }
	}
	val others = selectedMeshes(state, puppet, mesh).filter { it !in meshes }
	add(others.isNotEmpty(), label = tr("modifiers.copyToSelected"), hint = tr("modifiers.copyToSelectedTip", others.size)) { vm.modifiers.copy(modifier.id, others) }
	addMoves(vm.modifiers, ids, index) { label, enabled, action -> add(enabled, label = label, action = action) }
	add(true, danger = true, label = tr("modifiers.delete")) { vm.modifiers.delete(modifier.id) }
	GlidingColumn(Modifier.width(236.dp), rows.size, { rows[it].first }, dangers = { rows[it].second }) { rows[it].third() }
}

/** The meshes of the selected layers, [current] first: what a copy or a batch edit goes to. */
internal fun selectedMeshes(state: PSD2LiveState, puppet: PuppetModel, current: String): List<String> {
	val byLayer = state.previewModel?.rig?.layerIdByDrawableId.orEmpty()
	val selected = (state.selectedLayerIds + listOfNotNull(state.selectedLayerId)).toSet()
	val meshes = puppet.drawables.filter { it.mesh != null && (byLayer[it.id.raw] ?: it.id.raw) in selected }.map { it.id.raw }
	return listOf(current) + (meshes - current)
}

/**
 * What can go on [meshes]: the kinds, those suiting the layer's role first, then the saved presets, and saving
 * [stack] as one.
 */
@Composable
private fun ModifierAddMenu(vm: PSD2LiveViewModel, state: PSD2LiveState, puppet: PuppetModel, meshes: List<String>, stack: List<RigModifier>, close: () -> Unit) {
	val colors = LocalToolColors.current
	val rows = mutableListOf<Pair<Boolean, @Composable () -> Unit>>()
	val role = state.previewModel?.analysis?.layers?.firstOrNull { it.source.id.raw == state.selectedLayerId }?.semantic?.tag
	val kinds = ModifierKinds.forRole(role)
	val suited = kinds.filter { role != null && role in it.suits }
	if (suited.isNotEmpty()) rows += false to @Composable { FloatingMenuSection(tr("modifiers.suggested")) }
	for ((i, kind) in kinds.withIndex()) {
		if (i == suited.size && suited.isNotEmpty()) rows += false to @Composable { FloatingMenuSection(tr("modifiers.allKinds")) }
		rows += true to @Composable { FloatingMenuRow(tr("modifiers.kind.${kind.id}"), { close(); vm.modifiers.add(kind.id, meshes) }, hint = tr("modifiers.kindTip.${kind.id}")) }
	}
	rows += false to @Composable { FloatingMenuSection(tr("modifiers.presets")) }
	val parameters = puppet.parameters.mapTo(HashSet()) { it.id.raw } + state.rigEdits.modifiers.flatMap { m -> m.created.map { it.id.raw } }
	for (preset in vm.modifiers.presets) rows += true to @Composable {
		FloatingMenuRow(preset.name, { close(); vm.modifiers.applyPreset(preset, meshes, parameters) }, hint = tr("modifiers.presetCount", preset.modifiers.size),
			control = {
				CompactIconButton(onClick = { vm.modifiers.deletePreset(preset.name) }, tooltip = tr("modifiers.presetDelete"), size = 18.dp) {
					IconTrash(modifier = Modifier.size(10.dp), tint = colors.textMuted)
				}
			})
	}
	rows += stack.isNotEmpty() to @Composable {
		FloatingMenuRow(tr("modifiers.presetSave"), { close(); vm.modifiers.presetDialog = meshes.first() }, enabled = stack.isNotEmpty(), hint = tr("modifiers.presetSaveTip"))
	}
	GlidingColumn(Modifier.width(236.dp), rows.size, { rows[it].first }) { rows[it].second() }
}

/** Names the selected mesh's stack and saves it as a preset. */
@Composable
internal fun ModifierPresetDialog(vm: PSD2LiveViewModel, state: PSD2LiveState) {
	val mesh = vm.modifiers.presetDialog ?: return
	val puppet = state.previewModel?.rig?.puppet
	val stack = state.rigEdits.modifiers.filter { mesh in it.target.resolve(puppet) }
	val default = puppet?.drawables?.firstOrNull { it.id.raw == mesh }?.name ?: mesh
	var name by remember(mesh) { mutableStateOf(default) }
	val taken = vm.modifiers.presets.any { it.name == name.trim() }
	val save = { if (name.isNotBlank() && stack.isNotEmpty()) { vm.modifiers.savePreset(name.trim(), stack); vm.modifiers.presetDialog = null } }
	ModalDialogFrame(
		title = tr("modifiers.presetSave"),
		onDismiss = { vm.modifiers.presetDialog = null },
		width = 360.dp,
		onConfirm = save,
		footer = {
			CompactButton(tr("action.cancel"), { vm.modifiers.presetDialog = null })
			CompactButton(tr(if (taken) "modifiers.presetReplace" else "modifiers.presetSaveAction"), save, enabled = name.isNotBlank() && stack.isNotEmpty(), isPrimary = true)
		},
	) {
		CompactTextField(name, { name = it }, Modifier.fillMaxWidth())
		Text(tr("modifiers.presetSaveHint", stack.size), style = LocalToolTypography.current.caption, color = LocalToolColors.current.textMuted)
	}
}

/** Moving entry [index] of [ids] (the stack as one mesh shows it) up or down, through [vm]. */
private fun addMoves(vm: io.github.psd2live.ui.state.ModifierController, ids: List<String>, index: Int, add: (String, Boolean, () -> Unit) -> Unit) {
	add(tr("modifiers.moveUp"), index > 0) { vm.moveBefore(ids[index], ids[index - 1]) }
	add(tr("modifiers.moveDown"), index in 0 until ids.lastIndex) {
		if (index + 2 <= ids.lastIndex) vm.moveBefore(ids[index], ids[index + 2]) else vm.move(ids[index], "last")
	}
}

/**
 * A swing, simulation or interior effect as an entry of a mesh's stack: what it is, the document command that switches
 * it, applies it and deletes it, and the panel its settings are edited in.
 */
internal class SystemModifier(
	val kind: String, val id: String, val name: String, val module: String?, val apply: Pair<String, JsonObject>?, val note: String? = null,
	val enabled: Boolean? = null, val toggle: ((Boolean) -> Pair<String, JsonObject>)? = null, val delete: Pair<String, JsonObject>? = null,
	/** The swing, simulation or interior effect its card edits. */
	val source: Any? = null,
	/** Why it cannot be applied, when [apply] is null. */
	val applyNote: String? = null,
	/** [note] needs attention: a stale or missing bake. */
	val warn: Boolean = false,
)

/**
 * The entries of the stack that act on [mesh], in the order they run (after the skeleton's skin): its modifiers, the
 * unbaked swings on its parents, its interior effect and the simulations baked onto it. Each is its ID and the
 * modifier or the generator's [SystemModifier].
 */
internal fun stackRows(overlay: io.github.psd2live.core.RigEditOverlay, puppet: PuppetModel, mesh: String): List<Pair<String, Any>> {
	val deformers = puppet.deformers.associateBy { it.id.raw }
	// A warp's own stack holds what moves it; a mesh's what moves it or its parents.
	val chain = if (mesh.startsWith(ModifierTarget.WARP)) setOf(mesh.removePrefix(ModifierTarget.WARP))
		else generateSequence(puppet.drawables.firstOrNull { it.id.raw == mesh }?.parentDeformerId?.raw) { deformers[it]?.parent?.raw }.toSet()
	return ModifierStack.entries(overlay).mapNotNull { entry ->
		when (entry) {
			is ModifierStack.Entry.Modifier -> entry.modifier.takeIf { mesh in it.target.keys(puppet) }?.let { entry.id to it }
			is ModifierStack.Entry.Swing -> entry.swing.takeIf { !it.baked && it.targets.any { t -> t in chain } }?.let { swing ->
				entry.id to SystemModifier("swing", entry.id, swing.name, "physics", "swing_delete" to buildJsonObject { put("id", swing.id); put("bake", true) },
					delete = "swing_delete" to buildJsonObject { put("id", swing.id) }, source = swing)
			}
			is ModifierStack.Entry.Interior -> entry.effect.takeIf { it.target == mesh }?.let { effect ->
				entry.id to SystemModifier("interior", entry.id, effect.name, "interior", null, enabled = effect.enabled,
					toggle = { on -> "interior_put" to buildJsonObject { put("target", effect.target); put("enabled", on) } },
					delete = "interior_delete" to buildJsonObject { put("target", effect.target) }, source = effect, applyNote = tr("modifiers.system.applyNone"))
			}
			is ModifierStack.Entry.Simulation -> entry.simulation.takeIf { mesh in it.targets || it.bake?.vertexCounts?.containsKey(mesh) == true }?.let { sim ->
				val baked = sim.bake != null && sim.enabled
				// Modes written as blend shapes have no keyform axes to apply onto.
				val shapes = baked && io.github.psd2live.core.sim.SimGenerator.usesBlendShapes(puppet, sim)
				val stale = baked && runCatching { io.github.psd2live.core.sim.SimBake.stale(puppet, sim, overlay) }.getOrDefault(false)
				entry.id to SystemModifier("simulation", entry.id, sim.name, "simulation",
					if (baked && !shapes) io.github.psd2live.core.sim.SimApply.OP to buildJsonObject { put("id", sim.id) } else null,
					note = tr(when { sim.bake == null -> "modifiers.system.unbaked"; stale -> "modifiers.system.stale"; else -> "modifiers.system.baked" }),
					enabled = sim.enabled,
					toggle = { on -> "simulation_put" to buildJsonObject { put("id", sim.id); put("enabled", on) } },
					delete = "simulation_delete" to buildJsonObject { put("id", sim.id) }, source = sim,
					applyNote = tr(if (shapes) "modifiers.system.applyBlendShapes" else "modifiers.system.applyUnbaked"), warn = sim.enabled && (sim.bake == null || stale))
			}
		}
	}
}

/** The skeleton's skin, locked before every other entry: it has nothing to set here and runs first. */
@Composable
private fun SkinCard() {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		GridIcon(Modifier.size(11.dp), colors.textMuted) { lockGlyph() }
		Text(tr("modifiers.system.skin"), style = typography.caption.copy(fontSize = 10.5.sp), color = colors.textPrimary, maxLines = 1,
			modifier = Modifier.weight(1f))
	}
	io.github.psd2live.ui.views.physics.PhysicsRowDivider()
}

/**
 * A swing's, simulation's or interior effect's card: opened, its settings as that generator's panel had them; its
 * header the switch and a menu to bake, apply, move or delete it, or show it in its own panel.
 */
@Composable
private fun GeneratorCard(vm: PSD2LiveViewModel, state: PSD2LiveState, puppet: PuppetModel, system: SystemModifier, ids: List<String>, index: Int,
                          open: Boolean, onToggle: () -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var menu by remember { mutableStateOf(false) }
	val trailing: @Composable RowScope.() -> Unit = {
		system.note?.let { Text(it, style = typography.caption.copy(fontSize = 9.5.sp), color = if (system.warn) colors.warning else colors.textMuted, maxLines = 1) }
		val toggle = system.toggle
		if (system.enabled != null && toggle != null) PanelToolSwitch(tr("modifiers.enabled"), showLabel = false, checked = system.enabled,
			onCheckedChange = { vm.modifiers.batch(listOf(toggle(it))) }, enabled = true, tooltip = tr("modifiers.enabled"))
		Box {
			CompactIconButton(onClick = { menu = true }, tooltip = tr("modifiers.menu"), size = 18.dp) {
				GridIcon(Modifier.size(11.dp), colors.textMuted) { dot(4.6f, 9f, 1.3f); dot(9f, 9f, 1.3f); dot(13.4f, 9f, 1.3f) }
			}
			PressMenu(expanded = menu, press = Offset(0f, 0f), onDismissRequest = { menu = false }) {
				GeneratorMenu(vm, system, ids, index) { menu = false }
			}
		}
	}
	PhysicsSection("${tr("modifiers.system.${system.kind}")} · ${system.name}", open, onToggle, trailing = trailing) {
		when (val source = system.source) {
			is io.github.psd2live.core.RigSwingEdit -> SwingSettings(vm, state, puppet, source)
			is io.github.psd2live.core.sim.RigSimEdit -> io.github.psd2live.ui.views.simulation.SimulationSettings(vm, state, puppet, source)
			is io.github.psd2live.core.interior.NativeInteriorEffect -> {
				val preview = state.previewModel
				if (!source.enabled) Text(tr("interior.disabledHint"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
				else if (preview != null) InteriorSettings(vm, state, preview, source.target, source)
			}
		}
	}
}

@Composable
private fun GeneratorMenu(vm: PSD2LiveViewModel, system: SystemModifier, ids: List<String>, index: Int, close: () -> Unit) {
	val rows = mutableListOf<Triple<Boolean, Boolean, @Composable () -> Unit>>()
	fun add(enabled: Boolean, danger: Boolean = false, label: String, hint: String? = null, action: () -> Unit) {
		rows += Triple(enabled, danger, @Composable { FloatingMenuRow(label, { close(); action() }, enabled = enabled, hint = hint, danger = danger) })
	}
	when (val source = system.source) {
		is io.github.psd2live.core.sim.RigSimEdit -> add(source.enabled, label = tr(if (source.bake == null) "sim.bakeAction" else "sim.rebake")) { vm.bakeSimulation(source.id) }
		is io.github.psd2live.core.RigSwingEdit -> add(true, label = tr("modifiers.editOnCanvas"), hint = tr("swing.editOnCanvasTip")) { vm.beginSwing(source.targets) }
	}
	val apply = system.apply
	add(apply != null, label = tr("modifiers.applyKeyforms"), hint = if (apply != null) tr("modifiers.system.applyTip") else system.applyNote ?: tr("modifiers.system.applyNone")) {
		apply?.let { vm.modifiers.batch(listOf(it)) }
	}
	system.module?.let { module -> add(true, label = tr("modifiers.system.open.${system.kind}")) { vm.requestSelectDockModule(module) } }
	addMoves(vm.modifiers, ids, index) { label, enabled, action -> add(enabled, label = label, action = action) }
	system.delete?.let { op -> add(true, danger = true, label = tr("modifiers.delete")) { vm.modifiers.batch(listOf(op)) } }
	GlidingColumn(Modifier.width(236.dp), rows.size, { rows[it].first }, dangers = { rows[it].second }) { rows[it].third() }
}
