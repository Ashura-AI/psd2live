package io.github.psd2live.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.render.BackgroundFrameRate
import io.github.psd2live.render.CanvasAntialiasing
import io.github.psd2live.render.FrameRateLimit
import io.github.psd2live.render.GpuCacheSize
import io.github.psd2live.render.GpuChoice
import io.github.psd2live.render.GpuDevice
import io.github.psd2live.render.GraphicsChoice
import io.github.psd2live.render.GraphicsDevices
import io.github.psd2live.render.GraphicsOptions
import io.github.psd2live.render.GraphicsSettings
import io.github.psd2live.render.QualityPreset
import io.github.psd2live.render.RenderPath
import io.github.psd2live.render.SkiaGpu
import io.github.psd2live.render.TextureFiltering
import io.github.psd2live.render.ThreadedGl
import io.github.psd2live.render.VsyncMode
import io.github.psd2live.render.WindowAntialiasing
import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.views.InspectorFormRow
import io.github.psd2live.ui.views.InspectorSectionBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The graphics settings, laid out as a game's: a quality preset over the display, antialiasing and texture, and
 * advanced settings, each a labelled choice with what the chosen value does under it. What applies only at the
 * next start says so while it differs from what this process started with.
 */
@Composable
internal fun SettingsGraphicsSection() {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val graphics by AppSettings.graphicsFlow.collectAsState()
	val gpuStatus by SkiaGpu.status.collectAsState()
	val startup = GraphicsSettings.startup
	fun update(change: (GraphicsOptions) -> GraphicsOptions) { AppSettings.graphics = change(AppSettings.graphics) }
	// The window and the canvases draw on the GPU: what only they use is moot in software.
	val gpuWindow = graphics.renderPath != RenderPath.SOFTWARE
	val gpuCanvas = graphics.renderPath == RenderPath.GPU

	SettingsSectionDescription(tr("settings.graphics.desc"))

	InspectorSectionBox(tr("settings.graphics.quality")) {
		val preset = QualityPreset.of(graphics)
		GraphicsRow(tr("settings.graphics.preset"), QualityPreset.entries, preset, enabled = gpuWindow,
			optionEnabled = { it != QualityPreset.CUSTOM }) { chosen -> update { chosen.applyTo(it) } }
		GraphicsHint(tr("settings.graphics.preset.desc"))
	}

	InspectorSectionBox(tr("settings.graphics.display")) {
		GraphicsRow(tr("settings.graphics.path"), RenderPath.entries, graphics.renderPath) { path -> update { it.copy(renderPath = path) } }
		GraphicsHint(tr(graphics.renderPath.descriptionKey))
		val status = when {
			startup?.softwareWindow == true -> tr("settings.graphics.status.softwareWindow")
			graphics.renderPath != RenderPath.GPU -> tr("dialog.settings.canvas.rendererSoftware")
			else -> when (val current = gpuStatus) {
				is SkiaGpu.Status.Ready -> tr("dialog.settings.canvas.rendererGpu", current.description)
				is SkiaGpu.Status.Unavailable -> tr("dialog.settings.canvas.rendererUnavailable", current.reason)
				SkiaGpu.Status.Starting -> tr("dialog.settings.canvas.rendererStarting")
			}
		}
		GraphicsHint(status)

		// Listing the cards asks lspci, which may take a moment: off the UI thread, once.
		val devices by produceState(emptyList<GpuDevice>()) { value = withContext(Dispatchers.IO) { GraphicsDevices.list() } }
		val cards = listOf(GpuChoice.Auto, GpuChoice.HighPerformance, GpuChoice.PowerSaving) + devices.map { GpuChoice.Device(it.id) }
		// A card chosen before and gone now still shows, so the choice can be seen and undone.
		val shownCards = if (graphics.gpu in cards) cards else cards + graphics.gpu
		fun cardLabel(choice: GpuChoice): String = when (choice) {
			GpuChoice.Auto -> tr("settings.graphics.gpu.auto")
			GpuChoice.HighPerformance -> tr("settings.graphics.gpu.highPerformance")
			GpuChoice.PowerSaving -> tr("settings.graphics.gpu.powerSaving")
			is GpuChoice.Device -> devices.firstOrNull { it.id == choice.id }?.name ?: choice.id
		}
		SettingsChoiceRow(tr("settings.graphics.gpu"), shownCards, graphics.gpu, ::cardLabel, enabled = gpuWindow) { card -> update { it.copy(gpu = card) } }
		GraphicsHint(when (val card = graphics.gpu) {
			GpuChoice.Auto -> tr("settings.graphics.gpu.auto.desc")
			GpuChoice.HighPerformance -> tr("settings.graphics.gpu.highPerformance.desc")
			GpuChoice.PowerSaving -> tr("settings.graphics.gpu.powerSaving.desc")
			is GpuChoice.Device -> {
				val device = devices.firstOrNull { it.id == card.id }
				when {
					device == null -> tr("settings.graphics.gpu.device.missing")
					device.primary -> tr("settings.graphics.gpu.device.primary", device.id)
					else -> tr("settings.graphics.gpu.device", device.id)
				}
			}
		})
		GraphicsHint(when {
			GraphicsSettings.isWindows -> if (GraphicsDevices.windowsLauncher() != null) tr("settings.graphics.gpu.windows") else tr("settings.graphics.gpu.windowsUnpackaged")
			GraphicsSettings.isLinux -> GraphicsSettings.appliedEnvironment.takeIf { it.isNotEmpty() }
				?.let { environment -> tr("settings.graphics.gpu.linuxApplied", environment.entries.joinToString(" ") { "${it.key}=${it.value}" }) }
				?: tr("settings.graphics.gpu.linux")
			else -> tr("settings.graphics.gpu.mac")
		})

		val vsyncModes = VsyncMode.offered(GraphicsSettings.isWindows)
		// Adaptive stored on Windows and read elsewhere (a shared home) acts as on.
		val vsync = graphics.vsync.takeIf { it in vsyncModes } ?: VsyncMode.ON
		GraphicsRow(tr("settings.graphics.vsync"), vsyncModes, vsync, enabled = gpuWindow) { mode -> update { it.copy(vsync = mode) } }
		GraphicsHint(tr(if (vsync == VsyncMode.OFF && GraphicsSettings.isLinux) "settings.graphics.vsync.off.linux.desc" else vsync.descriptionKey))

		GraphicsRow(tr("settings.graphics.frameRate"), FrameRateLimit.entries, graphics.frameRateLimit) { limit -> update { it.copy(frameRateLimit = limit) } }
		GraphicsRow(tr("settings.graphics.backgroundFrameRate"), BackgroundFrameRate.entries, graphics.backgroundFrameRate) { limit -> update { it.copy(backgroundFrameRate = limit) } }
		GraphicsHint(tr("settings.graphics.frameRate.desc"))

		if (GraphicsSettings.isLinux) {
			GraphicsRow(tr("settings.graphics.threadedGl"), ThreadedGl.entries, graphics.threadedGl, enabled = gpuWindow) { threaded -> update { it.copy(threadedGl = threaded) } }
			GraphicsHint(tr("settings.graphics.threadedGl.desc"))
		}
	}

	InspectorSectionBox(tr("settings.graphics.antialiasing")) {
		GraphicsRow(tr("settings.graphics.windowAa"), WindowAntialiasing.entries, graphics.windowAntialiasing, enabled = gpuWindow) { aa -> update { it.copy(windowAntialiasing = aa) } }
		GraphicsHint(tr(graphics.windowAntialiasing.descriptionKey))
		GraphicsRow(tr("settings.graphics.canvasAa"), CanvasAntialiasing.entries, graphics.canvasAntialiasing, enabled = gpuCanvas) { aa -> update { it.copy(canvasAntialiasing = aa) } }
		GraphicsHint(tr(graphics.canvasAntialiasing.descriptionKey))
		GraphicsRow(tr("settings.graphics.texture"), TextureFiltering.entries, graphics.textureFiltering, enabled = gpuCanvas) { filtering -> update { it.copy(textureFiltering = filtering) } }
		GraphicsHint(tr("settings.graphics.texture.desc"))
	}

	InspectorSectionBox(tr("settings.graphics.advanced")) {
		InspectorFormRow(tr("settings.graphics.windowCache"), labelWidth = LabelWidth) {
			CompactCheckbox(checked = graphics.windowCache, onCheckedChange = { on -> update { it.copy(windowCache = on) } },
				enabled = gpuWindow, label = tr("settings.graphics.windowCache.label"))
		}
		GraphicsHint(tr("settings.graphics.windowCache.desc"))
		GraphicsRow(tr("settings.graphics.cache"), GpuCacheSize.entries, graphics.gpuCache, enabled = gpuWindow) { size -> update { it.copy(gpuCache = size) } }
		GraphicsHint(tr("settings.graphics.cache.desc"))
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

/** Wide enough for the longest label in the four languages ("Background frame rate"). */
private val LabelWidth = 132.dp

/** The name of [choice] as the settings page shows it. */
private fun choiceLabel(choice: GraphicsChoice): String = choice.labelArg?.let { tr(choice.labelKey, it) } ?: tr(choice.labelKey)

@Composable
private fun <T : GraphicsChoice> GraphicsRow(
	label: String,
	options: List<T>,
	selected: T,
	enabled: Boolean = true,
	optionEnabled: (T) -> Boolean = { true },
	onSelect: (T) -> Unit,
) = SettingsChoiceRow(label, options, selected, ::choiceLabel, enabled, optionEnabled, onSelect)

@Composable
private fun <T> SettingsChoiceRow(
	label: String,
	options: List<T>,
	selected: T,
	optionLabel: (T) -> String,
	enabled: Boolean = true,
	optionEnabled: (T) -> Boolean = { true },
	onSelect: (T) -> Unit,
) {
	InspectorFormRow(label, labelWidth = LabelWidth) {
		CompactSelect(options, selected, optionLabel, onSelect, Modifier.widthIn(max = 260.dp).fillMaxWidth(), enabled, optionEnabled)
	}
}

@Composable
private fun GraphicsHint(text: String) = Column(Modifier.fillMaxWidth().padding(start = LabelWidth, bottom = 2.dp)) {
	Text(
		text = text,
		style = LocalToolTypography.current.caption.copy(fontSize = 10.5.sp),
		color = LocalToolColors.current.textMuted,
	)
}
