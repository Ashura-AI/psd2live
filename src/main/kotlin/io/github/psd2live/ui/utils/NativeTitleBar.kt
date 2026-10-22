package io.github.psd2live.ui.utils

import androidx.compose.runtime.staticCompositionLocalOf
import com.jetbrains.JBR
import com.jetbrains.WindowDecorations
import java.awt.Frame

/**
 * The main window's native frame with the title bar drawn by the app: the JetBrains Runtime's custom title bar keeps
 * the system window (maximize to the work area, Snap, resize edges, shadow, the window buttons) and lets the app draw
 * in the title bar, saying per mouse event whether the pointer is on a control of its own or on the bar itself.
 * Other runtimes, and a transparent window (which the system only allows undecorated), draw the whole bar themselves.
 *
 * The runtime's objects are proxies without a usable equality, so the bar is held here, compared by identity, before
 * it keys anything in a composition.
 */
class NativeTitleBar private constructor(private val bar: WindowDecorations.CustomTitleBar) {

	/** The bar's height in the window's units (pixels over the display scale). */
	var height: Float
		get() = bar.height
		set(value) { if (value > 0f && value != bar.height) bar.height = value }

	/** Room the system's own controls take at each end, in the window's units. */
	val leftInset: Float get() = bar.leftInset
	val rightInset: Float get() = bar.rightInset

	/** Window buttons drawn for a dark bar (light glyphs) or a light one. */
	fun setDarkControls(dark: Boolean) = bar.putProperty("controls.dark", dark)

	/** Whether the pointer of the event being handled is on the app's own controls rather than the bar itself. */
	fun forceHitTest(client: Boolean) = bar.forceHitTest(client)

	companion object {
		/** Whether this runtime gives a frame a custom title bar. */
		val supported: Boolean by lazy { runCatching { JBR.isWindowDecorationsSupported() }.getOrDefault(false) }

		/**
		 * Gives [frame] a custom title bar; null when the runtime cannot. The runtime wants a height first: the bar's
		 * nominal one, until the bar is laid out and sets its own.
		 */
		fun install(frame: Frame): NativeTitleBar? = runCatching {
			val decorations = JBR.getWindowDecorations()
			val bar = decorations.createCustomTitleBar().also { it.height = 32f; decorations.setCustomTitleBar(frame, it) }
			NativeTitleBar(bar)
		}.onFailure { System.err.println("Custom title bar unavailable: $it") }.getOrNull()
	}
}

/** The main window's custom title bar, or null when the app draws the whole bar (another runtime, a transparent window). */
val LocalNativeTitleBar = staticCompositionLocalOf<NativeTitleBar?> { null }
