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

/** The selected mesh's modifier stack: one card per modifier in stack order, its settings keyed on parameters by the key button. */
@Composable
internal fun ModifierPanelView(vm: PSD2LiveViewModel, state: PSD2LiveState) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val preview = state.previewModel
	val target = preview?.rig?.layerIdByDrawableId?.entries?.firstOrNull { it.value == state.selectedLayerId }?.key ?: state.selectedLayerId
	val puppet = preview?.rig?.puppet
	val mesh = puppet?.drawables?.singleOrNull { it.id.raw == target }?.takeIf { it.mesh != null }
	val stack = state.rigEdits.modifiers.filter { target != null && target in it.target.meshes }
	// The committed model shows what was previewed: the preview steps aside.
	LaunchedEffect(preview) { vm.modifiers.dropPreview() }
	DisposableEffect(target, state.activeCanvas.id) { onDispose { vm.modifiers.dropPreview() } }
	// Cards start open, a new one too: the panel keeps the ones closed.
	var closed by remember(target) { mutableStateOf(emptySet<String>()) }
	Column(Modifier.fillMaxSize()) {
		val labels = listOf(tr("modifiers.add"))
		PanelToolbar(labels = labels, iconCount = 2) { shown ->
			PanelToolButton(labels[0], showLabel = shown > 0, onClick = {
				vm.modifiers.put(buildJsonObject { put("kind", ModifierKinds.Transform.id); putJsonArray("targets") { add(mesh!!.id.raw) } })
			}, enabled = mesh != null, tooltip = tr("modifiers.addTip")) {
				IconAdd(modifier = Modifier.size(10.dp), tint = colors.textPrimary)
			}
			Spacer(Modifier.weight(1f))
			PanelExpandCollapseButtons(onExpandAll = { closed = emptySet() }, onCollapseAll = { closed = stack.map { it.id }.toSet() }, enabled = stack.isNotEmpty())
		}
		if (mesh == null || puppet == null) {
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
		Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
			if (stack.isEmpty()) Text(tr("modifiers.empty"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted,
				modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
			stack.forEachIndexed { index, modifier ->
				ModifierCard(vm, state, puppet, stack, index, modifier, issues.filter { it.modifier == modifier.id && it.target == mesh.id.raw },
					modifier.id !in closed) { closed = if (modifier.id in closed) closed - modifier.id else closed + modifier.id }
			}
		}
	}
}

@Composable
private fun ModifierCard(
	vm: PSD2LiveViewModel,
	state: PSD2LiveState,
	puppet: PuppetModel,
	stack: List<RigModifier>,
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
				ModifierMenu(vm, state, puppet, stack, index, modifier) { menu = false }
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
		for (setting in modifier.definition.settings.filter { it.field }) ModifierField(vm, state, puppet, modifier, setting, pose)
	}
}

/** One setting: its components at the current pose, and the key button that keys it on the parameters there. */
@Composable
private fun ModifierField(vm: PSD2LiveViewModel, state: PSD2LiveState, puppet: PuppetModel, modifier: RigModifier, setting: ModifierSetting, pose: (String) -> Float) {
	val colors = LocalToolColors.current
	val stored = modifier.value(setting.key)
	val shown = stored.at(pose)
	// What a drag or typed value holds until the commit it sends comes back as the stored value.
	var draft by remember(modifier, setting.key) { mutableStateOf<FloatArray?>(null) }
	val value = draft ?: shown
	val keyed = stored as? ModifierValue.Keyed
	val at = keyed?.axes?.associate { it.parameter to pose(it.parameter) }.orEmpty()
	fun request(next: FloatArray) = buildJsonObject {
		put("id", modifier.id)
		if (keyed == null) putJsonObject("settings") { put(setting.key, JsonArray(next.map(::JsonPrimitive))) }
		else putJsonArray("keys") { addJsonObject {
			put("setting", setting.key); putJsonObject("at") { at.forEach { (k, v) -> put(k, v) } }; put("value", JsonArray(next.map(::JsonPrimitive)))
		} }
	}
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
			onEditEnd = { draft?.let { vm.modifiers.put(request(it)) } },
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
private fun ModifierMenu(vm: PSD2LiveViewModel, state: PSD2LiveState, puppet: PuppetModel, stack: List<RigModifier>, index: Int, modifier: RigModifier, close: () -> Unit) {
	val rows = mutableListOf<Triple<Boolean, Boolean, @Composable () -> Unit>>()
	fun add(enabled: Boolean, danger: Boolean = false, label: String, hint: String? = null, action: () -> Unit) {
		rows += Triple(enabled, danger, @Composable { FloatingMenuRow(label, { close(); action() }, enabled = enabled, hint = hint, danger = danger) })
	}
	val stacks = ModifierSampler.stacks(state.rigEdits.modifiers)
	val alone = modifier.target.meshes.all { stacks[it].orEmpty().singleOrNull()?.id == modifier.id }
	add(alone && modifier.drivers.isNotEmpty(), label = tr("modifiers.applyKeyforms"), hint = tr(if (alone) "modifiers.applyKeyformsTip" else "modifiers.applyAlone")) {
		vm.modifiers.apply(modifier.id, "keyforms")
	}
	add(true, label = tr("modifiers.applyRest"), hint = tr("modifiers.applyRestTip")) {
		val pose = modifier.drivers.associateWith { p -> state.parameterValues[ParameterId(p)] ?: puppet.parameters.firstOrNull { it.id.raw == p }?.default ?: 0f }
		vm.modifiers.apply(modifier.id, "rest", pose)
	}
	add(index > 0, label = tr("modifiers.moveUp")) { vm.modifiers.moveBefore(modifier.id, stack[index - 1].id) }
	add(index < stack.lastIndex, label = tr("modifiers.moveDown")) {
		if (index + 2 <= stack.lastIndex) vm.modifiers.moveBefore(modifier.id, stack[index + 2].id) else vm.modifiers.move(modifier.id, "last")
	}
	add(true, danger = true, label = tr("modifiers.delete")) { vm.modifiers.delete(modifier.id) }
	GlidingColumn(Modifier.width(236.dp), rows.size, { rows[it].first }, dangers = { rows[it].second }) { rows[it].third() }
}
