package io.github.psd2live.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.ModelDownloader
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.WorkspaceStore
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.utils.DesktopUtils
import io.github.psd2live.ui.views.InspectorFormRow
import io.github.psd2live.ui.views.InspectorSectionBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/** Where the app keeps things outside projects: the folders the files topic lists. */
internal object AppDataFolders {
	val workspaces: Path get() = WorkspaceStore.defaultRoot()
	val runtime: Path get() = ModelDownloader.defaultRuntimeDir()
	val upscaleCache: Path get() = Path.of(System.getProperty("user.home"), ".psd2live", "cache", "upscale")
	val home: Path get() = Path.of(System.getProperty("user.home"), ".psd2live")
}

/**
 * The files and data topic: the recent files and the last folder exported to, and the folders it keeps
 * outside projects with their sizes. Sizes are walked off the GUI thread and read again after a clear.
 */
@Composable
internal fun SettingsFilesSection(state: PSD2LiveState, viewModel: PSD2LiveViewModel) {
	val colors = LocalToolColors.current
	SettingsSectionDescription(tr("settings.files.desc"))

	InspectorSectionBox(tr("settings.files.recent")) {
		val psds = state.recentFiles.count { it.endsWith(".psd", ignoreCase = true) }
		InspectorFormRow(tr("settings.files.recent.list")) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Value(tr("settings.files.recent.count", (state.recentFiles.size - psds).toString(), psds.toString()), Modifier.weight(1f))
				CompactButton(tr("menu.file.recent.clear"), onClick = viewModel::clearRecentFiles, enabled = state.recentFiles.isNotEmpty(), height = 22.dp)
			}
		}
	}

	InspectorSectionBox(tr("settings.files.export")) {
		val lastExport = remember { viewModel.lastExportDirectory }
		InspectorFormRow(tr("settings.files.export.last")) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				val folder = lastExport
				if (folder == null) Value(tr("settings.files.export.none"), Modifier.weight(1f), muted = true)
				else Value(folder, Modifier.weight(1f), mono = true)
				CompactButton(tr("settings.files.open"), onClick = { folder?.let(DesktopUtils::openDirectory) }, enabled = folder != null, height = 22.dp)
			}
		}
	}

	InspectorSectionBox(tr("settings.files.data")) {
		FolderRow(tr("settings.files.data.workspaces"), AppDataFolders.workspaces)
		FolderRow(tr("settings.files.data.runtime"), AppDataFolders.runtime)
		FolderRow(tr("settings.files.data.upscaleCache"), AppDataFolders.upscaleCache, clearable = !state.isUpscaling)
		Text(
			tr("settings.files.data.clearHint"),
			style = LocalToolTypography.current.caption.copy(fontSize = 10.5.sp, lineHeight = 14.sp),
			color = colors.textMuted,
		)
	}
}

/** A folder with its size, opened in the file manager; [clearable] (null for never) offers deleting what is in it. */
@Composable
private fun FolderRow(label: String, folder: Path, clearable: Boolean? = null) {
	val scope = rememberCoroutineScope()
	var size by remember(folder) { mutableStateOf<Long?>(null) }
	var generation by remember { mutableStateOf(0) }
	LaunchedEffect(folder, generation) { size = withContext(Dispatchers.IO) { folderSize(folder) } }
	val exists = size != null && size!! >= 0
	InspectorFormRow(label) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			Value(folder.toString(), Modifier.weight(1f), mono = true)
			Value(when (val bytes = size) {
				null -> "…"
				-1L -> tr("settings.files.data.missing")
				else -> ModelDownloader.formatBytes(bytes)
			}, muted = true)
			CompactButton(tr("settings.files.open"), onClick = { DesktopUtils.openDirectory(folder) }, enabled = exists, height = 22.dp)
			if (clearable != null) CompactButton(tr("settings.files.data.clear"), onClick = {
				scope.launch {
					withContext(Dispatchers.IO) { clearFolder(folder) }
					generation++
				}
			}, enabled = clearable && exists && size!! > 0, height = 22.dp)
		}
	}
}

@Composable
private fun Value(text: String, modifier: Modifier = Modifier, mono: Boolean = false, muted: Boolean = false) {
	val typography = LocalToolTypography.current
	Text(
		text,
		style = if (mono) typography.monoSmall.copy(fontSize = 11.sp) else typography.caption.copy(fontSize = 11.sp),
		color = if (muted) LocalToolColors.current.textMuted else LocalToolColors.current.textPrimary,
		maxLines = 1,
		overflow = TextOverflow.Ellipsis,
		modifier = modifier,
	)
}

/** The bytes of the files under [folder], or -1 when it does not exist. */
private fun folderSize(folder: Path): Long = runCatching {
	if (!Files.isDirectory(folder)) return -1L
	Files.walk(folder).use { paths -> paths.filter { it.isRegularFile() }.mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum() }
}.getOrDefault(-1L)

/** Deletes what is inside [folder], keeping the folder itself. */
private fun clearFolder(folder: Path) {
	if (!Files.isDirectory(folder)) return
	Files.walk(folder).use { paths ->
		paths.sorted(Comparator.reverseOrder()).filter { it != folder }.forEach { runCatching { Files.deleteIfExists(it) } }
	}
}
