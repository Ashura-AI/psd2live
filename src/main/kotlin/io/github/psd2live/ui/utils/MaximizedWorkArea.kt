package io.github.psd2live.ui.utils

import java.awt.EventQueue
import java.awt.Frame
import java.awt.Insets
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowStateListener

/**
 * Keeps an undecorated frame's maximized bounds at its screen's work area. Windows maximizes a borderless window over
 * the whole monitor, taskbar included, and a window that exactly covers the monitor while drawing with OpenGL is
 * treated as full screen: dialogs and file pickers open behind it, and leaving or entering it blanks the screen.
 */
object MaximizedWorkArea {

	private val isWindows = System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)

	/**
	 * The JetBrains Runtime (21) applies the display scale to maximized bounds twice on Windows, so a window
	 * maximizes larger than its screen; the bundled runtime and other OpenJDK builds take them as documented.
	 */
	private val scalesMaximizedBoundsTwice = isWindows &&
		System.getProperty("java.vendor").orEmpty().contains("JetBrains", ignoreCase = true)

	/**
	 * The bounds a maximized window takes on a screen of [screen] bounds with [insets] reserved (taskbar, docks).
	 * On Windows a screen with nothing reserved (an auto-hidden taskbar) gives up its bottom row, so the window
	 * never covers the whole monitor and the taskbar can still slide in.
	 */
	internal fun workArea(screen: Rectangle, insets: Insets, windows: Boolean = isWindows): Rectangle {
		val area = Rectangle(screen.x + insets.left, screen.y + insets.top,
			screen.width - insets.left - insets.right, screen.height - insets.top - insets.bottom)
		if (windows && area == screen && area.height > 1) area.height -= 1
		return area
	}

	/** Follows the screen [frame] is on; a maximized frame that moved to another screen maximizes again there. */
	fun install(frame: Frame): () -> Unit {
		if (!frame.isUndecorated || scalesMaximizedBoundsTwice) return {}
		fun update(): Boolean {
			val gc = frame.graphicsConfiguration ?: return false
			val area = workArea(gc.bounds, runCatching { Toolkit.getDefaultToolkit().getScreenInsets(gc) }.getOrDefault(Insets(0, 0, 0, 0)))
			if (frame.maximizedBounds == area) return false
			frame.maximizedBounds = area
			return true
		}
		// The new bounds take effect on the next maximize, so a frame maximized under the old ones maximizes again.
		fun refresh() {
			if (update() && frame.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH) EventQueue.invokeLater {
				val state = frame.extendedState
				frame.extendedState = state and Frame.MAXIMIZED_BOTH.inv()
				frame.extendedState = state
			}
		}
		val moves = object : ComponentAdapter() {
			override fun componentMoved(e: ComponentEvent) = refresh()
			override fun componentShown(e: ComponentEvent) = refresh()
		}
		val states = WindowStateListener { refresh() }
		frame.addComponentListener(moves)
		frame.addWindowStateListener(states)
		refresh()
		return {
			frame.removeComponentListener(moves)
			frame.removeWindowStateListener(states)
		}
	}
}
