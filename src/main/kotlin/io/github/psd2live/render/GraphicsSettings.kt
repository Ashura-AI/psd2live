package io.github.psd2live.render

/** How the window and the canvases are drawn. */
enum class RenderPath(val labelKey: String, val descriptionKey: String) {
	/** The window draws with OpenGL and the canvases on the GPU in its context. */
	GPU("settings.graphics.path.gpu", "settings.graphics.path.gpu.desc"),
	/** The window keeps OpenGL; the editing canvas and atlas page paint in software. */
	SOFTWARE_CANVAS("settings.graphics.path.softwareCanvas", "settings.graphics.path.softwareCanvas.desc"),
	/** Everything in software: for drivers that cannot draw the window at all. */
	SOFTWARE("settings.graphics.path.software", "settings.graphics.path.software.desc"),
}

/** Which graphics card draws the window, where the system lets a program choose. */
sealed interface GpuChoice {
	/** The system's choice. */
	data object Auto : GpuChoice
	/** The discrete card of a laptop with two. */
	data object HighPerformance : GpuChoice
	/** The integrated card of a laptop with two. */
	data object PowerSaving : GpuChoice
	/** One card by its [GpuDevice.id] (Linux). */
	data class Device(val id: String) : GpuChoice

	val code: String get() = when (this) {
		Auto -> "auto"
		HighPerformance -> "high_performance"
		PowerSaving -> "power_saving"
		is Device -> "device:$id"
	}

	companion object {
		fun parse(code: String?): GpuChoice = when {
			code == null -> Auto
			code == "high_performance" -> HighPerformance
			code == "power_saving" -> PowerSaving
			code.startsWith("device:") && code.length > "device:".length -> Device(code.removePrefix("device:"))
			else -> Auto
		}
	}
}

/**
 * The graphics settings. [renderPath], [gpu] and [vsync] are read once, before the first window; [samples] (the
 * window's antialiasing) is followed from the next frame.
 */
data class GraphicsOptions(
	val renderPath: RenderPath = RenderPath.GPU,
	val gpu: GpuChoice = GpuChoice.Auto,
	val samples: Int = DEFAULT_SAMPLES,
	val vsync: Boolean = true,
) {
	/** Whether going from [startup] to these options waits for the next start. */
	fun needsRestartFrom(startup: GraphicsOptions): Boolean =
		softwareWindow != startup.softwareWindow || gpu != startup.gpu || vsync != startup.vsync

	/** Whether the window draws in software: the part of [renderPath] fixed at start. */
	val softwareWindow: Boolean get() = renderPath == RenderPath.SOFTWARE

	companion object {
		const val DEFAULT_SAMPLES = 4
		/** The antialiasing choices: off, then multisampling by the count. */
		val SAMPLE_CHOICES = listOf(0, 2, 4, 8)

		fun samplesOf(stored: Int): Int = SAMPLE_CHOICES.firstOrNull { it == stored } ?: DEFAULT_SAMPLES

		/** Stored settings; before render paths, only whether the canvas painted in software was kept. */
		fun stored(path: String?, legacySoftwareCanvas: Boolean, gpu: String?, samples: Int, vsync: Boolean) = GraphicsOptions(
			renderPath = RenderPath.entries.firstOrNull { it.name == path } ?: if (legacySoftwareCanvas) RenderPath.SOFTWARE_CANVAS else RenderPath.GPU,
			gpu = GpuChoice.parse(gpu),
			samples = samplesOf(samples),
			vsync = vsync,
		)
	}
}

/**
 * The graphics settings as the renderer follows them. [applyAtStartup] runs before the first window (the render
 * API, the card and vsync cannot change after); [samples] changes as the user picks.
 *
 * Development switches override the stored settings: `skiko.renderApi` / `SKIKO_RENDER_API`, `skiko.vsync.enabled`,
 * `psd2live.msaa` and `psd2live.compositor=false` (draws windows as Compose does, for comparison and in case of
 * trouble).
 */
object GraphicsSettings {
	private val os = System.getProperty("os.name").orEmpty()
	internal val isWindows = os.contains("windows", ignoreCase = true)
	internal val isLinux = os.contains("linux", ignoreCase = true)
	internal val isMac = os.contains("mac", ignoreCase = true)

	/** Whether windows draw through [WindowCompositor]. */
	val compositing: Boolean = System.getProperty("psd2live.compositor") != "false"

	private val samplesOverride: Int? = System.getProperty("psd2live.msaa")?.toIntOrNull()

	/** The window's multisampling: 0 (or 1) draws without it. */
	@Volatile var samples: Int = samplesOverride ?: GraphicsOptions.DEFAULT_SAMPLES
		private set

	/** The options this process started with; null before [applyAtStartup]. */
	@Volatile var startup: GraphicsOptions? = null
		private set

	/** Environment set for the chosen card on Linux, for the settings page and the log. */
	@Volatile var appliedEnvironment: Map<String, String> = emptyMap()
		private set

	fun setSamples(value: Int) {
		samples = samplesOverride ?: value
	}

	/** Applies [options] before any window exists. */
	fun applyAtStartup(options: GraphicsOptions) {
		startup = options
		setSamples(options.samples)
		val explicitApi = System.getProperty("skiko.renderApi") != null || System.getenv("SKIKO_RENDER_API") != null
		if (!explicitApi) when {
			options.renderPath == RenderPath.SOFTWARE -> System.setProperty("skiko.renderApi", "SOFTWARE")
			// Skiko would pick Direct3D on Windows; the canvases draw in an OpenGL context.
			isWindows -> System.setProperty("skiko.renderApi", "OPENGL")
		}
		if (!options.vsync && System.getProperty("skiko.vsync.enabled") == null) System.setProperty("skiko.vsync.enabled", "false")
		if (isLinux && options.renderPath != RenderPath.SOFTWARE && options.gpu != GpuChoice.Auto) {
			// Before Skiko or LWJGL loads the OpenGL library, which reads them.
			val devices = GraphicsDevices.linuxDevices(java.io.File("/sys/class/drm"), names = false)
			appliedEnvironment = GraphicsDevices.linuxEnvironment(options.gpu, devices).filter { (name, value) -> NativeEnvironment.set(name, value) }
		}
		// macOS draws with Metal, where Skiko picks the card itself.
		if (isMac && System.getProperty("skiko.metal.gpu.priority") == null) when (options.gpu) {
			GpuChoice.HighPerformance -> System.setProperty("skiko.metal.gpu.priority", "discrete")
			GpuChoice.PowerSaving -> System.setProperty("skiko.metal.gpu.priority", "integrated")
			else -> Unit
		}
	}
}

/** Sets variables in the process's own environment, which the OpenGL library reads when it loads; JNA's libc. */
internal object NativeEnvironment {
	private interface LibC : com.sun.jna.Library {
		fun setenv(name: String, value: String, overwrite: Int): Int
	}

	private val libc: LibC? by lazy { runCatching { com.sun.jna.Native.load("c", LibC::class.java) }.getOrNull() }

	/** True when [name] is [value] afterwards. A variable the user set is kept. */
	fun set(name: String, value: String): Boolean {
		if (System.getenv(name) != null) return false
		return libc?.setenv(name, value, 0) == 0
	}
}
