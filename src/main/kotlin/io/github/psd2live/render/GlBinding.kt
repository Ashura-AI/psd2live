package io.github.psd2live.render

import org.jetbrains.skiko.SkiaLayer
import java.lang.reflect.Method

/**
 * How one window's Skia OpenGL context is found current and made current on the calling thread.
 *
 * Skiko renders a frame in two steps: Compose records into a picture (`update`, where canvases draw on the GPU),
 * then the redrawer makes its context current and replays the picture. While Compose records, the context current
 * is the one of the window Skiko drew last, which with several windows (floating panels) need not be this one, so
 * the GPU renderer makes the window's own context current itself before Compose renders ([makeCurrent]).
 */
internal sealed class GlBinding(
	/** The `HGLRC` or `GLXContext` Skia draws with. */
	val context: Long,
) {
	abstract fun isCurrent(): Boolean

	/** Makes [context] current on the calling thread; true when it is afterwards. */
	abstract fun makeCurrent(): Boolean

	/** WGL: the window's `HDC` and its context; Skiko's own `makeCurrent` switches them. */
	class Wgl(context: Long, private val device: Long) : GlBinding(context) {
		override fun isCurrent() = org.lwjgl.opengl.WGL.wglGetCurrentContext(null) == context

		override fun makeCurrent(): Boolean {
			if (isCurrent()) return true
			if (device == 0L) return false
			runCatching { wglMakeCurrent?.invoke(null, device, context) }
			return isCurrent()
		}
	}

	/**
	 * GLX. Skiko's redrawer does not hold the `GLXContext` itself but the address of a native variable holding it
	 * ([handle]); [context] is the context read from there, as `glXGetCurrentContext` reports it. The window's drawable
	 * is reached through Skiko's lock on the AWT drawing surface, which also serializes the X calls with AWT; the lock
	 * is reentrant, so this works inside Skiko's `renderImmediately`, which holds it.
	 */
	class Glx(private val handle: Long, private val layer: SkiaLayer) : GlBinding(org.lwjgl.system.MemoryUtil.memGetAddress(handle)) {
		// The address itself counts too, should a later Skiko hold the context directly; reading through it stays safe
		// then, as it points at the context's own allocation.
		override fun isCurrent() = org.lwjgl.opengl.GLX.glXGetCurrentContext().let { it == context || it == handle }

		override fun makeCurrent(): Boolean {
			if (isCurrent()) return true
			val lock = linuxLock ?: return failed("Skiko's drawing surface lock is unavailable")
			val unlock = linuxUnlock ?: return failed("Skiko's drawing surface unlock is unavailable")
			val switch = glxMakeCurrent ?: return failed("Skiko's makeCurrent is unavailable")
			val hardware = runCatching { backedLayer?.invoke(layer) }.getOrNull() ?: return failed("the window has no hardware layer")
			val surface = try {
				lock.invoke(null, hardware)
			} catch (failure: Exception) {
				return failed("locking the drawing surface failed: ${failure.cause ?: failure}")
			} ?: return failed("the drawing surface is unavailable")
			try {
				switch.invoke(null, surface, handle)
			} catch (failure: Exception) {
				return failed("glXMakeCurrent failed: ${failure.cause ?: failure}")
			} finally {
				runCatching { unlock.invoke(null, surface) }
			}
			return isCurrent() || failed("the context is still not current")
		}

		private var reported = false

		/** Says once why the context could not be made current: the canvases then paint nothing on the GPU. */
		private fun failed(reason: String): Boolean {
			if (!reported) System.err.println("GPU canvas: cannot make the window's GLX context current, $reason")
			reported = true
			return false
		}
	}

	companion object {
		/** Skiko's `makeCurrent(HDC, HGLRC)`. */
		private val wglMakeCurrent: Method? by lazy {
			skikoMethod("org.jetbrains.skiko.redrawer.WindowsOpenGLRedrawerKt", "access\$makeCurrent", Long::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!)
		}
		private val linuxSurface: Class<*>? by lazy { runCatching { Class.forName("org.jetbrains.skiko.LinuxDrawingSurface") }.getOrNull() }
		private val hardwareLayer: Class<*>? by lazy { runCatching { Class.forName("org.jetbrains.skiko.HardwareLayer") }.getOrNull() }
		private val backedLayer: Method? by lazy { runCatching { SkiaLayer::class.java.getMethod("getBackedLayer\$skiko") }.getOrNull() }
		private val linuxLock: Method? by lazy {
			hardwareLayer?.let { skikoMethod("org.jetbrains.skiko.AWTLinuxDrawingSurfaceKt", "lockLinuxDrawingSurface", it) }
		}
		private val linuxUnlock: Method? by lazy {
			linuxSurface?.let { skikoMethod("org.jetbrains.skiko.AWTLinuxDrawingSurfaceKt", "unlockLinuxDrawingSurface", it) }
		}
		/** Skiko's `makeCurrent(LinuxDrawingSurface, GLXContext)`. */
		private val glxMakeCurrent: Method? by lazy {
			linuxSurface?.let { skikoMethod("org.jetbrains.skiko.redrawer.LinuxOpenGLRedrawerKt", "access\$makeCurrent", it, Long::class.javaPrimitiveType!!) }
		}

		private fun skikoMethod(owner: String, name: String, vararg parameters: Class<*>): Method? = runCatching {
			Class.forName(owner).getDeclaredMethod(name, *parameters).apply { isAccessible = true }
		}.onFailure { System.err.println("Skiko method $owner.$name unavailable: $it") }.getOrNull()

		/** The binding of [redrawer], an OpenGL redrawer of [layer]; null for any other redrawer or before its context exists. */
		fun of(layer: SkiaLayer, redrawer: Any): GlBinding? {
			val type = redrawer.javaClass
			val windows = type.simpleName == "WindowsOpenGLRedrawer"
			if (!windows && type.simpleName != "LinuxOpenGLRedrawer") return null
			val context = type.getDeclaredField("context").apply { isAccessible = true }.getLong(redrawer)
			if (context == 0L) return null
			if (!windows) return Glx(context, layer)
			val device = runCatching { type.getDeclaredField("device").apply { isAccessible = true }.getLong(redrawer) }.getOrDefault(0L)
			return Wgl(context, device)
		}
	}
}
