package io.github.psd2live.scenario

import io.github.psd2live.project.ProjectThumbnail
import io.github.psd2live.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/** The start page: it opens the app, a project opened or started from it takes its place, and it comes back on request. */
class StartPageScenario {
	@TempDir lateinit var temp: Path

	/** The start page's presets are the user's preferences; each test puts back what it found. */
	private fun editor(body: suspend (PSD2LiveViewModel) -> Unit) = runBlocking {
		val choices = AppSettings.startChoices
		try {
			PSD2LiveViewModel().use { vm ->
				vm.setStateForTest(vm.state.value.copy(atlasSize = 512, exportMoc3 = false, generatePhysics = false))
				DesktopWorkspace(vm, temp.resolve("store")).use { workspace ->
					vm.attachWorkspace(workspace)
					body(vm)
				}
			}
		} finally {
			AppSettings.startChoices = choices
		}
	}

	private suspend fun until(what: String, condition: () -> Boolean) {
		withTimeoutOrNull(120_000) { while (!condition()) delay(20) } ?: fail("Timed out waiting for $what")
	}

	private suspend fun settle(vm: PSD2LiveViewModel) {
		withTimeout(60_000) { vm.state.first { !it.workspaceEditBusy && !it.editorDraftBusy && !it.isAnalyzing && !it.isGenerating } }
	}

	/** Imports the figure with [choices] picked, and waits until the model has them. */
	private suspend fun importFigure(vm: PSD2LiveViewModel, choices: StartPresetChoices) {
		vm.setStartChoices(choices)
		vm.setInputPath(Characters.figurePsd(temp).toString())
		vm.analyze(discardUnsaved = true)
		until("the import") { vm.state.value.previewModel != null && !vm.startPageShown }
		val parts = PresetParts.of(vm.state.value.analysis)
		until("the presets") { StartPresetChoices.of(vm.state.value).within(parts) == choices.within(parts) }
		settle(vm)
	}

	@Test fun aPsdImportAppliesTheStartPresetsAndOpensTheEditWorkspace() = editor { vm ->
		assertTrue(vm.startPageShown, "The app opens on the start page")
		assertEquals(StartSection.RECENT, vm.startSection, "It opens on the recent files")
		importFigure(vm, StartQuickPreset.MINIMAL.choices)
		assertEquals(WorkspacePreset.EDIT, vm.state.value.activeWorkspace.preset)
		assertFalse(vm.state.value.mouthOutlineEnabled)
		assertNull(vm.pendingMeshSplitBatch, "Splits wait for the Tools menu")
		assertNull(vm.pendingMeshSplit)
	}

	@Test fun theStartPageComesBackOnRequestAndGoesWithAnyWorkspace() = editor { vm ->
		vm.leaveStartPage()
		vm.showStartPage(StartSection.LAYOUT)
		assertTrue(vm.startPageShown)
		assertEquals(StartSection.LAYOUT, vm.startSection)
		// The workspace already active counts too: its tab is how the user goes back to it.
		vm.setActiveWorkspace(vm.state.value.activeWorkspace.id)
		assertFalse(vm.startPageShown)
		vm.showStartPage()
		vm.addCanvas(CanvasMode.PREVIEW)
		assertFalse(vm.startPageShown)
	}

	@Test fun helpAndSettingsStayOpenAsTabsUntilClosed() = editor { vm ->
		vm.showPage(AppPage.HELP, HELP_SHORTCUTS)
		vm.showPage(AppPage.SETTINGS)
		assertEquals(listOf(AppPage.START, AppPage.HELP, AppPage.SETTINGS), vm.openPages, "Pages keep the strip's order")
		assertEquals(AppPage.SETTINGS, vm.activePage)
		// Going to a workspace folds the start page away; help and settings keep their tabs.
		vm.setActiveWorkspace(vm.state.value.activeWorkspace.id)
		assertNull(vm.activePage)
		assertEquals(listOf(AppPage.HELP, AppPage.SETTINGS), vm.openPages)
		// A page's shortcut shows it, and pressed again goes back to the workspace.
		vm.togglePage(AppPage.HELP)
		assertEquals(AppPage.HELP, vm.activePage)
		vm.togglePage(AppPage.HELP)
		assertNull(vm.activePage)
		vm.showPage(AppPage.SETTINGS)
		vm.closePage(AppPage.SETTINGS)
		assertNull(vm.activePage)
		assertEquals(listOf(AppPage.HELP), vm.openPages)
	}

	@Test fun aLayoutProjectLeavesTheStartPageForTheLayoutWorkspace() = editor { vm ->
		vm.showStartPage(StartSection.LAYOUT)
		vm.createLayoutProject(1024, 768)
		until("the layout project") { vm.state.value.previewModel != null && !vm.startPageShown }
		settle(vm)
		assertEquals(WorkspacePreset.LAYOUT, vm.state.value.activeWorkspace.preset)
	}

	@Test fun aSavedProjectKeepsItsThumbnailAndOpeningItLeavesTheStartPage() = editor { vm ->
		importFigure(vm, StartQuickPreset.MINIMAL.choices)
		val project = temp.resolve("start.psd2live")
		// The presets land over several steps after the import; a save racing one of them is turned back.
		var saved = false
		repeat(20) {
			if (saved) return@repeat
			saved = runCatching { vm.saveProjectNow(project) }.onFailure { if (it !is io.github.psd2live.application.WorkspaceConflict) throw it }.isSuccess
			if (!saved) { delay(250); settle(vm) }
		}
		assertTrue(saved, "The project saves once the presets have landed")
		val thumbnail = assertNotNull(ProjectThumbnail.read(project), "A save keeps a thumbnail")
		val image = assertNotNull(ImageIO.read(thumbnail.inputStream()))
		assertTrue(maxOf(image.width, image.height) <= ProjectThumbnail.LONG_EDGE)
		vm.showStartPage(StartSection.RECENT)
		val generation = vm.state.value.projectOpenGeneration
		vm.openProject(project)
		until("the project") { vm.state.value.projectOpenGeneration != generation && !vm.startPageShown }
	}
}
