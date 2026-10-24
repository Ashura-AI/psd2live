package io.github.psd2live.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.render.GpuChoice
import io.github.psd2live.render.GpuDevice
import io.github.psd2live.render.GraphicsDevices
import io.github.psd2live.render.GraphicsOptions
import io.github.psd2live.render.GraphicsSettings
import io.github.psd2live.render.RenderPath
import io.github.psd2live.render.SkiaGpu
import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.views.InspectorSectionBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The graphics settings: how the window and canvases draw, which card draws them, the window's antialiasing and
 * vsync. What applies only at the next start says so while it differs from what this process started with.
 */
@Composable
internal fun SettingsGraphicsSection() {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val graphics by AppSettings.graphicsFlow.collectAsState()
	val gpuStatus by SkiaGpu.status.collectAsState()
	val startup = GraphicsSettings.startup
	fun update(change: (GraphicsOptions) -> GraphicsOptions) { AppSettings.graphics = change(AppSettings.graphics) }

	SettingsSectionDescription(tr("settings.graphics.desc"))

	InspectorSectionBox(tr("settings.graphics.path")) {
		for (path in RenderPath.entries) {
			GraphicsChoice(path == graphics.renderPath, tr(path.labelKey), tr(path.descriptionKey)) { update { it.copy(renderPath = path) } }
		}
		val status = when {
			startup?.softwareWindow == true -> tr("settings.graphics.status.softwareWindow")
			graphics.renderPath != RenderPath.GPU -> tr("dialog.settings.canvas.rendererSoftware")
			else -> when (val current = gpuStatus) {
				is SkiaGpu.Status.Ready -> tr("dialog.settings.canvas.rendererGpu", current.description)
				is SkiaGpu.Status.Unavailable -> tr("dialog.settings.canvas.rendererUnavailable", current.reason)
				SkiaGpu.Status.Starting -> tr("dialog.settings.canvas.rendererStarting")
			}
		}
		Text(
			text = status,
			style = typography.caption.copy(fontSize = 10.5.sp),
			color = colors.textMuted,
			modifier = Modifier.padding(start = 4.dp, top = 2.dp),
		)
	}

	// Listing the cards asks lspci, which may take a moment: off the UI thread, once.
	val devices by produceState(emptyList<GpuDevice>()) { value = withContext(Dispatchers.IO) { GraphicsDevices.list() } }
	val gpuSelectable = graphics.renderPath != RenderPath.SOFTWARE
	InspectorSectionBox(tr("settings.graphics.gpu")) {
		val choices = listOf(GpuChoice.Auto, GpuChoice.HighPerformance, GpuChoice.PowerSaving) + devices.map { GpuChoice.Device(it.id) }
		// A card chosen before and gone now still shows, so the choice can be seen and undone.
		val shown = if (graphics.gpu in choices) choices else choices + graphics.gpu
		for (choice in shown) {
			val (label, description) = when (choice) {
				GpuChoice.Auto -> tr("settings.graphics.gpu.auto") to tr("settings.graphics.gpu.auto.desc")
				GpuChoice.HighPerformance -> tr("settings.graphics.gpu.highPerformance") to tr("settings.graphics.gpu.highPerformance.desc")
				GpuChoice.PowerSaving -> tr("settings.graphics.gpu.powerSaving") to tr("settings.graphics.gpu.powerSaving.desc")
				is GpuChoice.Device -> {
					val device = devices.firstOrNull { it.id == choice.id }
					(device?.name ?: choice.id) to when {
						device == null -> tr("settings.graphics.gpu.device.missing")
						device.primary -> tr("settings.graphics.gpu.device.primary", device.id)
						else -> tr("settings.graphics.gpu.device", device.id)
					}
				}
			}
			GraphicsChoice(choice == graphics.gpu, label, description, enabled = gpuSelectable) { update { it.copy(gpu = choice) } }
		}
		val note = when {
			GraphicsSettings.isWindows -> if (GraphicsDevices.windowsLauncher() != null) tr("settings.graphics.gpu.windows") else tr("settings.graphics.gpu.windowsUnpackaged")
			GraphicsSettings.isLinux -> GraphicsSettings.appliedEnvironment.takeIf { it.isNotEmpty() }
				?.let { environment -> tr("settings.graphics.gpu.linuxApplied", environment.entries.joinToString(" ") { "${it.key}=${it.value}" }) }
				?: tr("settings.graphics.gpu.linux")
			else -> tr("settings.graphics.gpu.mac")
		}
		Text(
			text = note,
			style = typography.caption.copy(fontSize = 10.5.sp),
			color = colors.textMuted,
			modifier = Modifier.padding(start = 4.dp, top = 2.dp),
		)
	}

	InspectorSectionBox(tr("settings.graphics.antialiasing")) {
		Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
			for (samples in GraphicsOptions.SAMPLE_CHOICES) {
				CompactRadioButton(
					selected = samples == graphics.samples,
					onClick = { update { it.copy(samples = samples) } },
					enabled = graphics.renderPath != RenderPath.SOFTWARE,
					label = if (samples == 0) tr("settings.graphics.antialiasing.off") else tr("settings.graphics.antialiasing.samples", samples),
				)
			}
		}
		Text(
			text = tr("settings.graphics.antialiasing.desc"),
			style = typography.caption.copy(fontSize = 10.5.sp),
			color = colors.textMuted,
			modifier = Modifier.padding(start = 4.dp, top = 2.dp),
		)
	}

	InspectorSectionBox(tr("settings.graphics.vsync")) {
		CompactCheckbox(checked = graphics.vsync, onCheckedChange = { on -> update { it.copy(vsync = on) } }, label = tr("settings.graphics.vsync.label"))
		GraphicsHint(tr("settings.graphics.vsync.desc"))
	}

	if (startup != null && graphics.needsRestartFrom(startup)) {
		Text(
			text = tr("settings.graphics.restart"),
			style = typography.caption.copy(fontSize = 11.sp),
			color = colors.warning,
			modifier = Modifier.padding(start = 4.dp),
		)
	}
}

@Composable
private fun GraphicsHint(text: String) = Text(
	text = text,
	style = LocalToolTypography.current.caption.copy(fontSize = 10.5.sp),
	color = LocalToolColors.current.textMuted,
	modifier = Modifier.padding(start = 24.dp),
)

@Composable
private fun GraphicsChoice(selected: Boolean, label: String, description: String?, enabled: Boolean = true, onClick: () -> Unit) {
	Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
		CompactRadioButton(selected = selected, onClick = onClick, enabled = enabled, label = label)
		if (description != null) GraphicsHint(description)
	}
}
