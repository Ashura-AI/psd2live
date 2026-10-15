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
	val logs: Path get() = home.resolve("logs")
	/** The `mcp` command's default store; a run with `--store` keeps its own elsewhere. */
	val headlessStore: Path get() = Path.of(System.getProperty("user.home"), "psd2live-mcp")
	val temp: Path get() = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()

	/**
	 * Where the settings live: Java Preferences, whose store depends on the system, under the `io/github/psd2live`
	 * and `io.github.psd2live.*` nodes. Not a folder to open, so the page shows it as text.
	 */
	val preferences: String get() {
		val os = System.getProperty("os.name").orEmpty().lowercase()
		return when {
			os.contains("win") -> "HKEY_CURRENT_USER\\Software\\JavaSoft\\Prefs"
			os.contains("mac") -> "~/Library/Preferences/com.apple.java.util.prefs.plist"
			else -> "~/.java/.userPrefs"
		}
	}

	/**
	 * What earlier runs left in the system's temporary folder: the preview, download, upscale and motion sample
	 * folders, which a run deletes when done or on exit and an abnormal exit leaves behind. Only entries older than
	 * this process count, so nothing this run is using is touched. Extracted projects (`psd2live-project-`) are
	 * left out: the workspace store keeps track of them, keeps one a recovery still needs, and reclaims the rest.
	 */
	fun tempLeftovers(): List<Path> = runCatching {
		val started = ProcessHandle.current().info().startInstant().orElse(null)?.toEpochMilli() ?: return emptyList()
		Files.list(temp).use { entries ->
			entries.filter { entry ->
				val name = entry.fileName.toString()
				TempPrefixes.any(name::startsWith) &&
					runCatching { Files.getLastModifiedTime(entry).toMillis() < started }.getOrDefault(false)
			}.toList()
		}
	}.getOrDefault(emptyList())

	private val TempPrefixes = listOf(
		"psd2live-preview-model-", "psd2live-cubism-", "psd2live-motion-sample-", "psd2live-upscale-", "psd2live-download-",
	)

	/**
	 * Where the app is installed: the packaged launcher's folder, which jpackage names in `jpackage.app-path`. A run
	 * from the sources has no launcher, so it is the folder the app was started in.
	 */
	val install: Path get() = System.getProperty("jpackage.app-path")?.takeIf(String::isNotBlank)
		?.let { runCatching { Path.of(it).toAbsolutePath().parent }.getOrNull() }
		?: Path.of(System.getProperty("user.dir")).toAbsolutePath()
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
		FolderRow(tr("settings.files.data.install"), AppDataFolders.install)
		FolderRow(tr("settings.files.data.workspaces"), AppDataFolders.workspaces)
		FolderRow(tr("settings.files.data.runtime"), AppDataFolders.runtime)
		FolderRow(tr("settings.files.data.upscaleCache"), AppDataFolders.upscaleCache, clearable = !state.isUpscaling)
		FolderRow(tr("settings.files.data.logs"), AppDataFolders.logs, clearable = true)
		FolderRow(tr("settings.files.data.headlessStore"), AppDataFolders.headlessStore)
		TempLeftoversRow()
		InspectorFormRow(tr("settings.files.data.preferences")) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				Value(AppDataFolders.preferences, Modifier.weight(1f), mono = true)
				CompactButton(tr("dialog.agent.copy"), onClick = { DesktopUtils.copyToClipboard(AppDataFolders.preferences) }, height = 22.dp)
			}
		}
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

/** The leftovers in the temporary folder: how many and how large, opened at the folder, cleared together. */
@Composable
private fun TempLeftoversRow() {
	val scope = rememberCoroutineScope()
	var found by remember { mutableStateOf<Pair<Int, Long>?>(null) }
	var generation by remember { mutableStateOf(0) }
	LaunchedEffect(generation) {
		found = withContext(Dispatchers.IO) {
			AppDataFolders.tempLeftovers().let { entries -> entries.size to entries.sumOf { maxOf(0L, folderSize(it)) } }
		}
	}
	InspectorFormRow(tr("settings.files.data.temp")) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			Value(AppDataFolders.temp.toString(), Modifier.weight(1f), mono = true)
			Value(found?.let { (count, bytes) ->
				if (count == 0) tr("settings.files.data.temp.none") else tr("settings.files.data.temp.found", count.toString(), ModelDownloader.formatBytes(bytes))
			} ?: "…", muted = true)
			CompactButton(tr("settings.files.open"), onClick = { DesktopUtils.openDirectory(AppDataFolders.temp) }, height = 22.dp)
			CompactButton(tr("settings.files.data.clear"), onClick = {
				scope.launch {
					withContext(Dispatchers.IO) { AppDataFolders.tempLeftovers().forEach(::deleteEntry) }
					generation++
				}
			}, enabled = (found?.first ?: 0) > 0, height = 22.dp)
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

/** The bytes of the files under [folder] (or of [folder] when it is a file), or -1 when it does not exist. */
private fun folderSize(folder: Path): Long = runCatching {
	if (Files.isRegularFile(folder)) return Files.size(folder)
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

/** Deletes a file, or a folder with everything in it. */
private fun deleteEntry(entry: Path) {
	if (!Files.exists(entry)) return
	Files.walk(entry).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } } }
}
