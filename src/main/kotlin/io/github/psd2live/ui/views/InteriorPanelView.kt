package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.interior.*
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.ParameterKeyMarks
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.*
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.views.physics.FieldLabel
import io.github.psd2live.ui.views.physics.PhysicsRowDivider
import io.github.psd2live.ui.views.physics.PhysicsSection
import java.util.Locale
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

private val INTERIOR_SECTIONS = setOf("audition", "shape", "motion", "more", "binding", "folds")
private val INTERIOR_OPEN = INTERIOR_SECTIONS - "more"

/** Transmission presets, as the direction row offers them: label key, from, to. */
private val INTERIOR_DIRECTIONS =
	listOf(
		Triple("interior.direction.down", InteriorPoint(.5f, .05f), InteriorPoint(.5f, .95f)),
		Triple("interior.direction.up", InteriorPoint(.5f, .95f), InteriorPoint(.5f, .05f)),
		Triple("interior.direction.right", InteriorPoint(.05f, .5f), InteriorPoint(.95f, .5f)),
		Triple("interior.direction.left", InteriorPoint(.95f, .5f), InteriorPoint(.05f, .5f)),
	)

/** Icon turn per preset: the icon points down. */
private val INTERIOR_DIRECTION_DEGREES = listOf(0f, 180f, -90f, 90f)

/** Native dock: authoring settings persist; audition and view isolation are temporary. */
@Composable
internal fun InteriorPanelView(vm: PSD2LiveViewModel, state: PSD2LiveState) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val preview = state.previewModel
	val target =
		preview
			?.rig
			?.layerIdByDrawableId
			?.entries
			?.firstOrNull { it.value == state.selectedLayerId }
			?.key ?: state.selectedLayerId
	val drawable = preview?.rig?.puppet?.drawables?.singleOrNull { it.id.raw == target }
	val effect = state.rigEdits.interiorEffects.singleOrNull { it.target == target }
	var open by remember { mutableStateOf(INTERIOR_OPEN) }
	Column(Modifier.fillMaxSize()) {
		InteriorToolbar(
			vm,
			state,
			drawable = drawable?.takeIf { it.mesh != null },
			effect = effect,
			onOpen = { open = it },
		)
		if (drawable?.mesh == null || target == null) {
			Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
				Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
					InteriorIconView(InteriorIcon.SHAPE, colors.textDisabled, size = 24.dp)
					Text(
						tr("interior.select"),
						style = typography.caption.copy(fontSize = 10.5.sp),
						color = colors.textMuted,
						textAlign = TextAlign.Center,
					)
				}
			}
			return@Column
		}
		InteriorBody(vm, state, preview, target, drawable, effect, open) { open = it }
	}
}

/** Enable, solo and delete, and the expand pair; edits report progress in the status bar, so nothing here moves. */
@Composable
private fun InteriorToolbar(
	vm: PSD2LiveViewModel,
	state: PSD2LiveState,
	drawable: Drawable?,
	effect: NativeInteriorEffect?,
	onOpen: (Set<String>) -> Unit,
) {
	val colors = LocalToolColors.current
	val solo = vm.interior.soloActiveFor(state)
	val labels = listOf(tr("interior.enabled"), tr(if (solo) "interior.restoreView" else "interior.solo"), tr("interior.delete"))
	PanelToolbar(
		labels = labels,
		iconCount = 5,
	) { labelsShown ->
		PanelToolSwitch(
			labels[0],
			showLabel = labelsShown > 0,
			checked = effect?.enabled == true,
			onCheckedChange = { value ->
				if (!value) vm.interior.stopTrial()
				vm.interior.put(
					buildJsonObject {
						put("target", drawable!!.id.raw)
						put("enabled", value)
					}
				)
			},
			enabled = drawable != null,
			tooltip = tr("interior.enabledTip"),
		)
		PanelToolButton(
			labels[1],
			showLabel = labelsShown > 1,
			onClick = { vm.interior.toggleSolo() },
			enabled = drawable != null,
			tooltip = tr("interior.soloTip"),
			active = solo,
		) {
			InteriorIconView(InteriorIcon.SOLO, if (solo) colors.accent else colors.textPrimary, size = 11.dp)
		}
		PanelToolbarSeparator()
		PanelToolButton(
			labels[2],
			showLabel = labelsShown > 2,
			onClick = { drawable?.let { vm.interior.delete(it.id.raw) } },
			enabled = drawable != null && effect != null,
			tooltip = tr("interior.delete"),
		) {
			IconTrash(modifier = Modifier.size(11.dp), tint = colors.textPrimary)
		}
		Spacer(Modifier.weight(1f))
		PanelExpandCollapseButtons(
			onExpandAll = { onOpen(INTERIOR_SECTIONS) },
			onCollapseAll = { onOpen(emptySet()) },
			enabled = effect?.enabled == true,
		)
	}
}

@Composable
private fun InteriorBody(
	vm: PSD2LiveViewModel,
	state: PSD2LiveState,
	preview: io.github.psd2live.core.RigPreviewModel,
	target: String,
	drawable: Drawable,
	effect: NativeInteriorEffect?,
	open: Set<String>,
	onOpen: (Set<String>) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val caption = typography.caption.copy(fontSize = 10.sp)
	val draft = vm.interior.draft(drawable.id.raw, effect)
	val issue = remember(effect, preview) { effect?.issue(preview.authored.rig.puppet) }
	fun request() = buildJsonObject {
		put("target", target)
		put("enabled", effect?.enabled ?: true)
		put("settings", NativeInteriorAuthoring.settingsJson(draft.shape))
		put("motion", draft.motion.toJson())
		put("parameters", JsonArray(draft.sources.map(::JsonPrimitive)))
		put(
			"guides",
			JsonArray(
				draft.guides.map { g ->
					buildJsonObject {
						put("id", g.id)
						put("purpose", g.purpose.name)
						put("radius", g.radius)
						put("amount", g.amount)
						put("pitchOnly", g.pitchOnly)
						put(
							"points",
							JsonArray(
								g.points.map {
									JsonArray(listOf(JsonPrimitive(it.x), JsonPrimitive(it.y)))
								}
							),
						)
					}
				}
			),
		)
	}
	fun save() = vm.interior.put(request())
	LaunchedEffect(target, draft.version, draft.dirty, vm.interior.poseTarget != null) {
		if (effect?.enabled == true && draft.dirty && vm.interior.poseTarget != null) {
			kotlinx.coroutines.delay(90)
			try {
				vm.interior.previewDraft(request(), draft, draft.version)
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Exception) {
				vm.interior.fail(e.message ?: e.toString())
			}
		}
	}
	DisposableEffect(
		target,
		state.projectOpenGeneration,
		state.activeWorkspace.id,
		state.activeCanvas.id,
		state.activeCanvas.mode,
	) {
		onDispose { vm.interior.stopTrial() }
	}
	LaunchedEffect(effect, preview) {
		if (effect?.enabled != true || issue != null || vm.interior.poseTarget == null) {
			vm.interior.stopTrial()
			return@LaunchedEffect
		}
		vm.interior.startTrial(effect)
	}
	LaunchedEffect(target, effect, vm.interior.poseTarget != null) {
		if (effect?.enabled != true || issue != null || vm.interior.poseTarget == null) {
			vm.interior.stopTrial()
			return@LaunchedEffect
		}
		vm.interior.startTrial(effect)
		var last = withFrameNanos { it }
		while (true) {
			val now = withFrameNanos { it }
			vm.interior.stepTrial((now - last) / 1_000_000_000f)
			last = now
		}
	}
	fun toggle(key: String) = onOpen(if (key in open) open - key else open + key)
	@Composable fun icon(icon: InteriorIcon): @Composable () -> Unit = { InteriorIconView(icon, colors.accent) }

	Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
		Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				Text(
					drawable.name,
					style = typography.body.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
					color = colors.textPrimary,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
				if (effect?.enabled == true)
					Text(
						effect.sources.joinToString(" · ") { it.name },
						style = typography.monoSmall,
						color = colors.textMuted,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
			}
			when {
				effect?.enabled != true -> Text(tr("interior.disabledHint"), style = caption, color = colors.textMuted)
				issue != null -> {
					Text(tr(issue), style = caption, color = colors.warning)
					CompactButton(tr("interior.regenerate"), ::save, isPrimary = true, height = 22.dp)
				}
			}
		}
		PhysicsRowDivider()
		if (effect?.enabled != true) return@Column
		val editing = state.activeCanvas.mode == CanvasMode.EDIT

		val auditionTools: @Composable RowScope.() -> Unit = {
			val mouse = vm.interior.mouse
			CompactIconButton(
				onClick = {
					vm.interior.mouse = !mouse
					if (!mouse) {
						vm.interior.poseTarget = 0f to 0f
						vm.interior.startTrial(effect)
					}
				},
				enabled = editing && issue == null,
				tooltip = tr("interior.mouse"),
				size = 18.dp,
			) {
				IconMouse(mouse, modifier = Modifier.size(12.dp), tint = if (mouse) colors.accent else colors.textMuted)
			}
			CompactIconButton(
				onClick = { vm.interior.stopTrial() },
				enabled = editing && vm.interior.poseTarget != null,
				tooltip = tr("interior.stop"),
				size = 18.dp,
			) {
				IconPause(modifier = Modifier.size(10.dp), tint = colors.textMuted)
			}
		}
		PhysicsSection(
			tr("interior.audition"),
			"audition" in open,
			{ toggle("audition") },
			icon = icon(InteriorIcon.AUDITION),
			trailing = auditionTools,
		) {
			if (!editing) {
				Text(tr("interior.auditionEditOnly"), style = caption, color = colors.textMuted)
				CompactButton(
					tr("interior.editAudition"),
					{ vm.setCanvasMode(state.activeCanvas.id, CanvasMode.EDIT) },
					height = 22.dp,
				)
			} else {
				// The audition pose is the pair the effect reads, posed here as the parameter panel poses it.
				val axes = effect.sources
				fun pose() = vm.interior.poseTarget ?: (0f to 0f)
				fun parameterValue(p: Parameter, v: Float) =
					p.default + v * (if (v < 0) p.default - p.min else p.max - p.default)
				fun normalized(p: Parameter, v: Float) =
					(v - p.default) / (if (v < p.default) p.default - p.min else p.max - p.default)
				ParameterPairPad(
					axes[0],
					axes[1],
					{ parameterValue(axes[0], pose().first) },
					{ parameterValue(axes[1], pose().second) },
					enabled = issue == null,
					horizontalKeys = ParameterKeyMarks(listOf(axes[0].min, axes[0].default, axes[0].max), emptyList()),
					verticalKeys = ParameterKeyMarks(listOf(axes[1].min, axes[1].default, axes[1].max), emptyList()),
					onChange = { x, y ->
						vm.interior.mouse = false
						vm.interior.poseTarget = normalized(axes[0], x) to normalized(axes[1], y)
						vm.interior.startTrial(effect)
					},
					reverseY = true,
				)
			}
		}

		val shapeReset: @Composable RowScope.() -> Unit = {
			CompactIconButton(
				onClick = {
					draft.shape = InteriorSurfaceSettings()
					save()
				},
				tooltip = tr("interior.shapeReset"),
				size = 18.dp,
			) {
				IconReset(modifier = Modifier.size(11.dp), tint = colors.textMuted)
			}
		}
		PhysicsSection(
			tr("interior.shape"),
			"shape" in open,
			{ toggle("shape") },
			icon = icon(InteriorIcon.SHAPE),
			trailing = shapeReset.takeIf { draft.shape != InteriorSurfaceSettings() },
		) {
			InteriorSlider("interior.horizontal", draft.shape.horizontal, 0f..2f, { draft.shape = draft.shape.copy(horizontal = it) }, ::save)
			InteriorSlider("interior.vertical", draft.shape.vertical, 0f..2f, { draft.shape = draft.shape.copy(vertical = it) }, ::save)
			InteriorSlider("interior.bulge", draft.shape.bulge, 0f..2f, { draft.shape = draft.shape.copy(bulge = it) }, ::save)
			InteriorSlider("interior.press", draft.shape.press, 0f..2f, { draft.shape = draft.shape.copy(press = it) }, ::save)
			InteriorSlider("interior.folds", draft.shape.folds, 0f..2f, { draft.shape = draft.shape.copy(folds = it) }, ::save)
			InteriorSlider("interior.contour", draft.shape.contour, 0f..1f, { draft.shape = draft.shape.copy(contour = it) }, ::save)
		}

		val motionOn = draft.motion.enabled
		val motionSwitch: @Composable RowScope.() -> Unit = {
			PanelToolSwitch(
				tr("interior.dynamics"),
				showLabel = false,
				checked = motionOn,
				onCheckedChange = {
					draft.motion = draft.motion.copy(enabled = it)
					save()
				},
				enabled = true,
				tooltip = tr("interior.dynamicsTip"),
			)
		}
		PhysicsSection(tr("interior.dynamics"), "motion" in open, { toggle("motion") }, icon = icon(InteriorIcon.MOTION), trailing = motionSwitch) {
			InteriorSlider("interior.response", draft.motion.responseSeconds, 0f..0.6f, { draft.motion = draft.motion.copy(responseSeconds = it) }, ::save, enabled = motionOn)
			InteriorSlider("interior.transmission", draft.motion.transmissionSeconds, 0f..0.5f, { draft.motion = draft.motion.copy(transmissionSeconds = it) }, ::save, enabled = motionOn)
			InteriorSlider("interior.strength", draft.motion.transmissionStrength, 0f..1f, { draft.motion = draft.motion.copy(transmissionStrength = it) }, ::save, enabled = motionOn)
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				FieldLabel(tr("interior.direction"))
				INTERIOR_DIRECTIONS.forEachIndexed { i, (key, from, to) ->
					val selected = draft.motion.from == from && draft.motion.to == to
					CompactToggleChip(
						"",
						selected,
						{
							draft.motion = draft.motion.copy(from = from, to = to)
							save()
						},
						enabled = motionOn,
						leadingIcon = {
							InteriorIconView(
								InteriorIcon.DIRECTION,
								if (!motionOn) colors.textDisabled else if (selected) colors.accent else colors.textMuted,
								size = 13.dp,
								degrees = INTERIOR_DIRECTION_DEGREES[i],
							)
						},
						showCheckWhenSelected = false,
						tooltip = tr(key),
					)
				}
			}
		}

		PhysicsSection(tr("interior.more"), "more" in open, { toggle("more") }, icon = icon(InteriorIcon.MOTION)) {
			InteriorSlider("interior.sensitivityX", draft.motion.sensitivityX, .1f..1f, { draft.motion = draft.motion.copy(sensitivityX = it) }, ::save)
			InteriorSlider("interior.sensitivityY", draft.motion.sensitivityY, .1f..1f, { draft.motion = draft.motion.copy(sensitivityY = it) }, ::save)
			for (end in 0..1) {
				val p = if (end == 0) draft.motion.from else draft.motion.to
				val other = if (end == 0) draft.motion.to else draft.motion.from
				fun set(next: InteriorPoint) {
					// The two ends must stay apart, or the transmission has no direction.
					if (kotlin.math.hypot((next.x - other.x).toDouble(), (next.y - other.y).toDouble()) >= .05)
						draft.motion = if (end == 0) draft.motion.copy(from = next) else draft.motion.copy(to = next)
				}
				InteriorPointRow(
					tr(if (end == 0) "interior.from" else "interior.to"),
					p,
					1f,
					1f,
					decimals = 2,
					step = .05,
					onChange = ::set,
					onFinish = ::save,
				)
			}
		}

		PhysicsSection(tr("interior.binding"), "binding" in open, { toggle("binding") }, icon = icon(InteriorIcon.BINDING)) {
			val candidates =
				remember(preview) {
					preview.rig.puppet.parameters.filter {
						it.kind == ParameterKind.NORMAL && !it.repeat && it.min < it.default && it.default < it.max
					}
				}
			val names = remember(preview) { preview.rig.puppet.parameters.associate { it.id.raw to it.name } }
			for (axis in 0..1) {
				val selected = draft.sources.getOrElse(axis) { "" }
				Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					FieldLabel(tr(if (axis == 0) "interior.axisX" else "interior.axisY"))
					CompactDropdown(
						(listOf("") + candidates.map { it.id.raw } + selected).distinct(),
						selected,
						{ id ->
							draft.sources = draft.sources.toMutableList().also { it[axis] = id }
							save()
						},
						Modifier.weight(1f),
						itemLabel = { if (it.isEmpty()) tr("interior.newParameter") else names[it] ?: it },
						height = 22.dp,
					)
				}
			}
		}

		val raster =
			preview.analysis.layers
				.singleOrNull { it.source.id.raw == state.selectedLayerId }
				?.source
				?.raster
		val addFold: @Composable RowScope.() -> Unit = {
			CompactIconButton(
				onClick = {
					if (raster != null) {
						draft.guides =
							draft.guides +
								InteriorGuide(
									nextInteriorFoldId(draft.guides),
									listOf(
										InteriorPoint(raster.width * .5f, raster.height * .2f),
										InteriorPoint(raster.width * .5f, raster.height * .8f),
									),
									InteriorGuidePurpose.FOLD,
								)
						save()
					}
				},
				enabled = raster != null,
				tooltip = tr("interior.addFold"),
				size = 18.dp,
			) {
				IconAdd(modifier = Modifier.size(10.dp), tint = colors.textPrimary)
			}
		}
		PhysicsSection(
			tr("interior.guides"),
			"folds" in open,
			{ toggle("folds") },
			count = draft.guides.size,
			icon = icon(InteriorIcon.FOLDS),
			trailing = addFold,
		) {
			if (draft.guides.isEmpty()) Text(tr("interior.noFolds"), style = caption, color = colors.textMuted)
			draft.guides.forEachIndexed { i, g ->
				fun replace(next: InteriorGuide) {
					draft.guides = draft.guides.toMutableList().also { it[i] = next }
				}
				InspectorSectionBox("${tr("interior.fold")} ${i + 1}") {
					Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						CompactToggleChip(
							tr("interior.pitchOnly"),
							g.pitchOnly,
							{
								replace(g.copy(pitchOnly = !g.pitchOnly))
								save()
							},
							tooltip = tr("interior.pitchOnlyTip"),
						)
						Spacer(Modifier.weight(1f))
						CompactIconButton(
							onClick = {
								draft.guides = draft.guides.filterIndexed { n, _ -> n != i }
								save()
							},
							tooltip = tr("interior.removeFold"),
							size = 18.dp,
						) {
							IconTrash(modifier = Modifier.size(11.dp))
						}
					}
					InteriorSlider("interior.foldAmount", g.amount, 0f..2f, { replace(g.copy(amount = it)) }, ::save)
					InteriorSlider(
						"interior.radius",
						g.radius,
						1f..maxOf(2f, (raster?.width ?: 128).toFloat()),
						{ replace(g.copy(radius = it)) },
						::save,
						format = "%.0f",
					)
					g.points.forEachIndexed { j, p ->
						InteriorPointRow(
							tr("interior.point", j + 1),
							p,
							maxOf(1f, raster?.width?.toFloat() ?: 1f),
							maxOf(1f, raster?.height?.toFloat() ?: 1f),
							decimals = 0,
							step = 1.0,
							onChange = { next ->
								val pts = g.points.toMutableList().also { it[j] = next }
								if (pts.zipWithNext().all { (a, b) -> a != b }) replace(g.copy(points = pts))
							},
							onFinish = ::save,
						)
					}
				}
			}
		}
	}
}

/** A labelled slider that edits the draft while dragged and submits on release. */
@Composable
private fun InteriorSlider(
	key: String,
	value: Float,
	range: ClosedFloatingPointRange<Float>,
	change: (Float) -> Unit,
	finish: () -> Unit,
	enabled: Boolean = true,
	format: String = "%.2f",
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(tr(key))
		CompactSlider(
			value,
			{ change(((it * 100f).toInt() / 100f).coerceIn(range)) },
			onValueChangeFinished = finish,
			modifier = Modifier.weight(1f),
			valueRange = range,
			enabled = enabled,
		)
		Text(
			String.format(Locale.US, format, value),
			style = typography.monoSmall,
			color = if (enabled) colors.textPrimary else colors.textDisabled,
			modifier = Modifier.width(34.dp),
		)
	}
}

/** A point as two number fields; [onFinish] submits once a field's edit ends. */
@Composable
private fun InteriorPointRow(
	label: String,
	point: InteriorPoint,
	maxX: Float,
	maxY: Float,
	decimals: Int,
	step: Double,
	onChange: (InteriorPoint) -> Unit,
	onFinish: () -> Unit,
) {
	val colors = LocalToolColors.current
	val axis = LocalToolTypography.current.caption.copy(fontSize = 9.5.sp)
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		FieldLabel(label)
		Text("X", style = axis, color = colors.textMuted)
		CompactNumberSpinner(
			point.x.toDouble(),
			{ onChange(point.copy(x = it.toFloat())) },
			Modifier.weight(1f),
			min = 0.0,
			max = maxX.toDouble(),
			step = step,
			decimals = decimals,
			height = 22.dp,
			onEditEnd = onFinish,
		)
		Text("Y", style = axis, color = colors.textMuted)
		CompactNumberSpinner(
			point.y.toDouble(),
			{ onChange(point.copy(y = it.toFloat())) },
			Modifier.weight(1f),
			min = 0.0,
			max = maxY.toDouble(),
			step = step,
			decimals = decimals,
			height = 22.dp,
			onEditEnd = onFinish,
		)
	}
}
