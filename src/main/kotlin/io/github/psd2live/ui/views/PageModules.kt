package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.GridIcon
import io.github.psd2live.ui.components.HelpPanel
import io.github.psd2live.ui.components.IconInfo
import io.github.psd2live.ui.components.HelpNav
import io.github.psd2live.ui.components.SettingsNav
import io.github.psd2live.ui.components.SettingsPanel
import io.github.psd2live.ui.components.resetAllSettings
import io.github.psd2live.ui.state.*
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.tutorial.TutorialId
import io.github.psd2live.ui.tutorial.TutorialPath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What the app's pages do outside themselves: files to pick, tutorials to start, folders to reveal. */
@Stable
internal class PageActions(
	val onOpenTutorialCatalog: (() -> Unit)?,
	val onStartTutorial: ((TutorialId) -> Unit)?,
	val onStartTutorialPath: ((TutorialId, TutorialPath) -> Unit)?,
	val onOpenProject: (() -> Unit)?,
	val onOpenPsd: (() -> Unit)?,
	val onOpenCmo3: (() -> Unit)?,
	val onReplaceCmo3: (() -> Unit)?,
	val onRevealFile: (String) -> Unit,
	/** A file is being dragged over the window; the start page shows where it will land. */
	val filesDragging: Boolean,
	val start: StartPageModel,
)

/** The content of one panel of an app page. */
@Composable
internal fun PageModuleContent(id: String, state: PSD2LiveState, viewModel: PSD2LiveViewModel, actions: PageActions) {
	when (id) {
		START_NAV -> StartNavPanel(state, viewModel, actions)
		START_MAIN -> StartMainPanel(state, viewModel, actions)
		START_DETAILS -> StartDetailsPanel(state, viewModel, actions)
		HELP_NAV -> HelpNav(viewModel.helpTab, viewModel::setHelpTab)
		HELP_CONTENT -> HelpPanel(viewModel.helpTab, state.keymap, onStartInteractiveTutorial = actions.onStartTutorialPath)
		SETTINGS_NAV -> SettingsNav(viewModel.settingsTopic, viewModel::setSettingsTopic, onResetAll = { resetAllSettings(viewModel) })
		SETTINGS_CONTENT -> SettingsPanel(viewModel.settingsTopic, state, viewModel)
	}
}

/** A dock panel's title on an app page: a topic panel is named after the topic it shows, a contents panel after its page. */
internal fun pageModuleTitle(id: String, viewModel: PSD2LiveViewModel): String = when (id) {
	START_NAV -> pageTitle(AppPage.START)
	START_MAIN -> startSectionTitle(viewModel.startSection)
	START_DETAILS -> startDetailsTitle(viewModel.startSection)
	HELP_NAV -> pageTitle(AppPage.HELP)
	HELP_CONTENT -> tr(viewModel.helpTab.labelKey)
	SETTINGS_NAV -> pageTitle(AppPage.SETTINGS)
	SETTINGS_CONTENT -> tr(viewModel.settingsTopic.labelKey)
	else -> moduleTitle(id)
}

/** A page's name, for its tab. */
internal fun pageTitle(page: AppPage): String = tr(when (page) {
	AppPage.START -> "start.page.tab"
	AppPage.HELP -> "page.help"
	AppPage.SETTINGS -> "page.settings"
})

/** A page's icon, for its tab and the links to it. */
@Composable
internal fun PageIcon(page: AppPage, tint: Color, modifier: Modifier = Modifier.size(12.dp)) {
	when (page) {
		AppPage.START -> StartIcon(modifier, tint)
		AppPage.HELP -> IconInfo(modifier = modifier, tint = tint)
		AppPage.SETTINGS -> GridIcon(modifier, tint) {
			// A cog: eight teeth round a ring, the axle hole open.
			for (i in 0 until 8) {
				val a = i * PI / 4
				line(9f + 5.2f * cos(a).toFloat(), 9f + 5.2f * sin(a).toFloat(), 9f + 7.4f * cos(a).toFloat(), 9f + 7.4f * sin(a).toFloat(), width = 2.2f)
			}
			ring(9f, 9f, 5f)
			ring(9f, 9f, 2f)
		}
	}
}
