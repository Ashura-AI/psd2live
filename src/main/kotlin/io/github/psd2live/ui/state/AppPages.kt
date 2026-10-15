package io.github.psd2live.ui.state

/**
 * Pages of the app rather than of a project: each is a tab at the head of the workspace strip that covers the dock
 * with its own dock of [modules]. Their panels move, tab, float and hide like the editor's, and their arrangement is
 * kept in the preferences, never in a project.
 */
internal enum class AppPage(val modules: List<String>) {
	START(listOf(START_NAV, START_MAIN, START_DETAILS, START_RECENT_PSDS, START_UPDATES)),
	HELP(listOf(HELP_TUTORIALS, HELP_PSD_SPEC, HELP_SHORTCUTS, HELP_LINKS, HELP_ABOUT)),
	SETTINGS(listOf(
		SETTINGS_SCALE, SETTINGS_THEME, SETTINGS_LANGUAGE, SETTINGS_CANVAS, SETTINGS_PROMPTS, SETTINGS_SHORTCUTS, SETTINGS_ENVIRONMENT,
	)),
}

internal const val START_NAV = "start.nav"
internal const val START_MAIN = "start.main"
internal const val START_DETAILS = "start.details"
internal const val START_RECENT_PSDS = "start.recentPsds"
internal const val START_UPDATES = "start.updates"

internal const val HELP_TUTORIALS = "help.tutorials"
internal const val HELP_PSD_SPEC = "help.psdSpec"
internal const val HELP_SHORTCUTS = "help.shortcuts"
internal const val HELP_LINKS = "help.links"
internal const val HELP_ABOUT = "help.about"

internal const val SETTINGS_SCALE = "settings.scale"
internal const val SETTINGS_THEME = "settings.theme"
internal const val SETTINGS_LANGUAGE = "settings.language"
internal const val SETTINGS_CANVAS = "settings.canvas"
internal const val SETTINGS_PROMPTS = "settings.prompts"
internal const val SETTINGS_SHORTCUTS = "settings.shortcuts"
internal const val SETTINGS_ENVIRONMENT = "settings.environment"

/** The page whose dock holds [module], or null for an editor module. */
internal fun pageOfModule(module: String): AppPage? = AppPage.entries.firstOrNull { module in it.modules }

/** A page's arrangement as last left: its dock tree as JSON (null for the default) and the panels closed in it. */
internal data class PageArrangement(val layoutJson: String? = null, val hiddenModules: Set<String> = emptySet())
