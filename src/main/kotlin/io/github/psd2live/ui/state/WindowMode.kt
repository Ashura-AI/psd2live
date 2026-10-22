package io.github.psd2live.ui.state

/**
 * How the main window sits on its screen. The window draws its own title bar, so every mode is bounds the program
 * sets itself: the operating system's maximize is never used, since it differs between runtimes and covers the
 * taskbar of a borderless window.
 */
enum class WindowMode(val labelKey: String, val descriptionKey: String) {
	/** Free size and position, restored to what it last had. */
	WINDOWED("settings.window.mode.windowed", "settings.window.mode.windowed.desc"),
	/** The screen's work area: the taskbar stays visible. */
	MAXIMIZED("settings.window.mode.maximized", "settings.window.mode.maximized.desc"),
	/** The whole screen, taskbar included, with the title bar and its menus. */
	FULLSCREEN("settings.window.mode.fullscreen", "settings.window.mode.fullscreen.desc"),
}
