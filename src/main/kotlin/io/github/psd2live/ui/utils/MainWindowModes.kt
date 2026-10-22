package io.github.psd2live.ui.utils

import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.state.WindowMode
import java.awt.EventQueue
import java.awt.Frame
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.WindowStateListener
import java.lang.ref.WeakReference

/**
 * Places the main window for its [WindowMode]. The window is undecorated, and every mode is bounds set here: the
 * operating system's maximize covers the taskbar of a borderless window, and the JetBrains Runtime scales maximized
 * bounds twice, so the frame itself always stays in its normal state. A maximize from outside (Win+Up) is taken
 * back and becomes [WindowMode.MAXIMIZED].
 */
object MainWindowModes {

	private val isWindows = System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)

	/**
	 * The bounds a maximized window takes on a screen of [screen] bounds with [insets] reserved (taskbar, docks).
	 * On Windows a screen with nothing reserved (an auto-hidden taskbar) gives up its bottom row: a window covering
	 * the whole monitor while drawing with OpenGL is treated as full screen, and the taskbar could not slide in.
	 */
	internal fun workArea(screen: Rectangle, insets: Insets, windows: Boolean = isWindows): Rectangle {
		val area = Rectangle(screen.x + insets.left, screen.y + insets.top,
			screen.width - insets.left - insets.right, screen.height - insets.top - insets.bottom)
		if (windows && area == screen && area.height > 1) area.height -= 1
		return area
	}

	/** Where [mode] puts a window on a screen of [screen] bounds and [insets]; null for windowed, which is free. */
	internal fun boundsFor(mode: WindowMode, screen: Rectangle, insets: Insets, windows: Boolean = isWindows): Rectangle? = when (mode) {
		WindowMode.WINDOWED -> null
		WindowMode.MAXIMIZED -> workArea(screen, insets, windows)
		WindowMode.FULLSCREEN -> Rectangle(screen)
	}

	/**
	 * Windowed bounds of [size] on a screen whose work area is [area]: at [preferred] when it still fits there,
	 * otherwise centred; never larger than the work area, so the title bar cannot open above the screen.
	 */
	internal fun windowedBounds(size: java.awt.Dimension, preferred: Point?, area: Rectangle): Rectangle {
		val width = minOf(size.width, area.width).coerceAtLeast(1)
		val height = minOf(size.height, area.height).coerceAtLeast(1)
		val fits = preferred != null && preferred.x >= area.x && preferred.y >= area.y &&
			preferred.x + width <= area.x + area.width && preferred.y + height <= area.y + area.height
		return if (fits) Rectangle(preferred!!.x, preferred.y, width, height)
		else Rectangle(area.x + (area.width - width) / 2, area.y + (area.height - height) / 2, width, height)
	}

	private fun insetsOf(gc: GraphicsConfiguration): Insets =
		runCatching { Toolkit.getDefaultToolkit().getScreenInsets(gc) }.getOrDefault(Insets(0, 0, 0, 0))

	private fun defaultScreen(): GraphicsConfiguration? =
		runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration }.getOrNull()

	/** The bounds the window opens with: its mode's on the default screen, or the saved windowed size centred. */
	fun initialBounds(mode: WindowMode, width: Float, height: Float): Rectangle? {
		val gc = defaultScreen() ?: return null
		val insets = insetsOf(gc)
		return boundsFor(mode, gc.bounds, insets)
			?: windowedBounds(java.awt.Dimension(width.toInt(), height.toInt()), null, workArea(gc.bounds, insets))
	}

	/** The windowed bounds last held, across window replacements (a language or transparency switch). */
	@Volatile private var windowed: Rectangle? = null

	/** The window and the mode last applied to it. */
	private var applied: Pair<WeakReference<Frame>, WindowMode>? = null

	/** The windowed size to save on exit: the current one when windowed, else the one the window returns to. */
	fun windowedSize(frame: Frame): java.awt.Dimension =
		if (AppSettings.windowMode == WindowMode.WINDOWED) frame.size
		else windowed?.size ?: java.awt.Dimension(AppSettings.windowWidth.toInt(), AppSettings.windowHeight.toInt())

	/** Puts [frame] where [mode] says, on the screen it is on; runs on the event thread. */
	fun apply(frame: Frame, mode: WindowMode = AppSettings.windowMode) {
		if (!EventQueue.isDispatchThread()) { EventQueue.invokeLater { apply(frame, mode) }; return }
		val gc = frame.graphicsConfiguration ?: return
		val previous = applied?.takeIf { it.first.get() === frame }?.second
		if (frame.extendedState and Frame.MAXIMIZED_BOTH != 0) frame.extendedState = frame.extendedState and Frame.MAXIMIZED_BOTH.inv()
		if (previous == WindowMode.WINDOWED && mode != WindowMode.WINDOWED) windowed = frame.bounds
		applied = WeakReference(frame) to mode
		val insets = insetsOf(gc)
		val target = boundsFor(mode, gc.bounds, insets) ?: when {
			// Windowed is free: only coming back from another mode moves the window.
			previous == WindowMode.WINDOWED -> return
			else -> windowedBounds(windowed?.size ?: java.awt.Dimension(AppSettings.windowWidth.toInt(), AppSettings.windowHeight.toInt()),
				windowed?.location, workArea(gc.bounds, insets))
		}
		if (frame.bounds != target) frame.bounds = target
	}

	/**
	 * A drag on the title bar of a maximized window: the window becomes windowed at its windowed size, under the
	 * pointer at [pointer] as far across it as the pointer was across the maximized one. Returns the new location.
	 */
	fun restoreForDrag(frame: Frame, pointer: Point): Point {
		val before = frame.bounds
		val size = windowed?.size ?: java.awt.Dimension(AppSettings.windowWidth.toInt(), AppSettings.windowHeight.toInt())
		val across = if (before.width > 0) (pointer.x - before.x).toFloat() / before.width else 0.5f
		val location = Point(pointer.x - (size.width * across).toInt(), pointer.y - (pointer.y - before.y).coerceAtMost(size.height - 1))
		applied = WeakReference(frame) to WindowMode.WINDOWED
		AppSettings.windowMode = WindowMode.WINDOWED
		frame.bounds = Rectangle(location, size)
		return location
	}

	/**
	 * Keeps [frame] in its mode: a maximize from the system (Win+Up) is turned into [WindowMode.MAXIMIZED], and the
	 * window is fitted again when it is activated, after a taskbar or display change.
	 */
	fun install(frame: Frame): () -> Unit {
		val states = WindowStateListener { event ->
			if (event.newState and Frame.MAXIMIZED_BOTH != 0) EventQueue.invokeLater {
				if (AppSettings.windowMode == WindowMode.WINDOWED) AppSettings.windowMode = WindowMode.MAXIMIZED
				else apply(frame)
			}
		}
		val activation = object : WindowAdapter() {
			override fun windowActivated(e: WindowEvent) { if (AppSettings.windowMode != WindowMode.WINDOWED) apply(frame) }
		}
		// Moving between screens of different scales replaces the configuration: fit the new screen.
		val moves = object : ComponentAdapter() {
			private var screen = frame.graphicsConfiguration?.device
			override fun componentMoved(e: ComponentEvent) {
				val now = frame.graphicsConfiguration?.device
				if (now !== screen) { screen = now; if (AppSettings.windowMode != WindowMode.WINDOWED) apply(frame) }
			}
		}
		frame.addWindowStateListener(states)
		frame.addWindowListener(activation)
		frame.addComponentListener(moves)
		return {
			frame.removeWindowStateListener(states)
			frame.removeWindowListener(activation)
			frame.removeComponentListener(moves)
		}
	}
}
