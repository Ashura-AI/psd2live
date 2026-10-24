package io.github.psd2live.render

/**
 * One value of a graphics setting as the settings page names it: [labelKey] (with [labelArg] for the numbered
 * ones, "MSAA {0}×") and, where the values differ in more than a number, [descriptionKey].
 */
interface GraphicsChoice {
	val labelKey: String
	val labelArg: Any? get() = null
	val descriptionKey: String? get() = null
}

/** How the window and the canvases are drawn. */
enum class RenderPath(override val labelKey: String, override val descriptionKey: String) : GraphicsChoice {
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

/** When a frame is shown. */
enum class VsyncMode : GraphicsChoice {
	/** At the display's refresh: no tearing, at most one frame per refresh. */
	ON,
	/** As [ON] while frames fit a refresh; while they miss it, each frame shows as soon as it is drawn ([AdaptiveVsync]). Windows. */
	ADAPTIVE,
	/**
	 * As soon as drawn. On Linux Skiko then paces frames to the display by a timer instead of waiting in the buffer
	 * swap, which the UI thread otherwise does every frame; on Windows frames are not paced.
	 */
	OFF;

	override val labelKey get() = "settings.graphics.vsync.${name.lowercase()}"
	override val descriptionKey get() = "$labelKey.desc"

	companion object {
		/** The modes Windows offers, and the others ([ADAPTIVE] is Windows only). */
		fun offered(windows: Boolean): List<VsyncMode> = if (windows) entries else listOf(ON, OFF)
	}
}

/**
 * The most frames per second a playing preview asks for; [DISPLAY] follows the display. The preview's own menu
 * sets the same value.
 */
enum class FrameRateLimit(val fps: Int) : GraphicsChoice {
	DISPLAY(0), FPS_30(30), FPS_60(60), FPS_120(120);

	override val labelKey get() = if (fps == 0) "settings.graphics.frameRate.display" else "settings.graphics.fps"
	override val labelArg get() = fps.takeIf { it > 0 }

	companion object {
		fun ofFps(fps: Int): FrameRateLimit = entries.firstOrNull { it.fps == fps } ?: DISPLAY
	}
}

/** The most frames per second a playing preview asks for while its window is not the active one; [SAME] keeps [FrameRateLimit]. */
enum class BackgroundFrameRate(val fps: Int) : GraphicsChoice {
	SAME(0), FPS_30(30), FPS_10(10);

	override val labelKey get() = if (fps == 0) "settings.graphics.backgroundFrameRate.same" else "settings.graphics.fps"
	override val labelArg get() = fps.takeIf { it > 0 }
}

/**
 * How the window's Compose picture antialiases. [COVERAGE] is Skia's own: it works out each shape's edge coverage on
 * the CPU. MSAA draws through a multisampled target ([MultisampledWindow]), where the GPU's rasterizer does it.
 */
enum class WindowAntialiasing(val samples: Int) : GraphicsChoice {
	COVERAGE(0), MSAA_2(2), MSAA_4(4), MSAA_8(8), MSAA_16(16);

	override val labelKey get() = if (samples == 0) "settings.graphics.windowAa.coverage" else "settings.graphics.msaa"
	override val labelArg get() = samples.takeIf { it > 0 }
	override val descriptionKey get() = if (samples == 0) "settings.graphics.windowAa.coverage.desc" else "settings.graphics.windowAa.msaa.desc"

	companion object {
		fun ofSamples(samples: Int): WindowAntialiasing? = entries.firstOrNull { it.samples == samples }
	}
}

/**
 * How the GPU canvases (editing canvas, atlas page) and the p2lrt preview antialias the meshes, masks and guides
 * they draw themselves ([GpuTarget]). Their lines and points fade their own edges either way.
 */
enum class CanvasAntialiasing(val samples: Int, val scale: Int) : GraphicsChoice {
	OFF(0, 1),
	MSAA_2(2, 1), MSAA_4(4, 1), MSAA_8(8, 1),
	/** Drawn at twice the width and height, then averaged down: four samples of everything, texture detail too. */
	SSAA_2X(0, 2);

	override val labelKey get() = when {
		this == OFF -> "settings.graphics.canvasAa.off"
		scale > 1 -> "settings.graphics.canvasAa.ssaa"
		else -> "settings.graphics.msaa"
	}
	override val labelArg get() = samples.takeIf { it > 0 }
	override val descriptionKey get() = when {
		this == OFF -> "settings.graphics.canvasAa.off.desc"
		scale > 1 -> "settings.graphics.canvasAa.ssaa.desc"
		else -> "settings.graphics.canvasAa.msaa.desc"
	}
}

/** How the canvases sample the artwork's textures when they are not drawn pixel for pixel. */
enum class TextureFiltering(val mipmaps: Boolean, val anisotropy: Int) : GraphicsChoice {
	/** The full-size texture, four texels: a zoomed-out canvas skips texels and its edges shimmer. */
	BILINEAR(false, 1),
	/** Between the two nearest mipmap levels. */
	TRILINEAR(true, 1),
	/** As [TRILINEAR], with more samples along the direction a stretched mesh squeezes the texture. */
	ANISOTROPIC_4X(true, 4), ANISOTROPIC_8X(true, 8), ANISOTROPIC_16X(true, 16);

	override val labelKey get() = when (this) {
		BILINEAR -> "settings.graphics.texture.bilinear"
		TRILINEAR -> "settings.graphics.texture.trilinear"
		else -> "settings.graphics.texture.anisotropic"
	}
	override val labelArg get() = anisotropy.takeIf { it > 1 }
}

/** How much GPU memory Skia keeps textures and paths it may draw again in; [AUTO] leaves Skia's own budget. */
enum class GpuCacheSize(val megabytes: Int) : GraphicsChoice {
	AUTO(0), MB_256(256), MB_512(512), GB_1(1024), GB_2(2048);

	override val labelKey get() = if (megabytes == 0) "settings.graphics.cache.auto" else "settings.graphics.cache.size"
	override val labelArg get() = when {
		megabytes == 0 -> null
		megabytes >= 1024 -> "${megabytes / 1024} GB"
		else -> "$megabytes MB"
	}
}

/**
 * Whether the OpenGL driver hands calls to a thread of its own (Mesa's glthread, NVIDIA's threaded optimization):
 * Skia issues thousands of calls a frame, which the UI thread then only queues. Linux; read when the driver loads.
 */
enum class ThreadedGl : GraphicsChoice {
	DEFAULT, ON, OFF;

	override val labelKey get() = "settings.graphics.threadedGl.${name.lowercase()}"
}

/** Named sets of the quality settings; [CUSTOM] is any other mix. */
enum class QualityPreset(
	val window: WindowAntialiasing?,
	val canvas: CanvasAntialiasing?,
	val textures: TextureFiltering?,
	val cache: GpuCacheSize?,
) : GraphicsChoice {
	LOW(WindowAntialiasing.COVERAGE, CanvasAntialiasing.OFF, TextureFiltering.BILINEAR, GpuCacheSize.MB_256),
	MEDIUM(WindowAntialiasing.MSAA_2, CanvasAntialiasing.OFF, TextureFiltering.TRILINEAR, GpuCacheSize.AUTO),
	HIGH(WindowAntialiasing.MSAA_4, CanvasAntialiasing.MSAA_4, TextureFiltering.TRILINEAR, GpuCacheSize.AUTO),
	ULTRA(WindowAntialiasing.MSAA_8, CanvasAntialiasing.SSAA_2X, TextureFiltering.ANISOTROPIC_16X, GpuCacheSize.GB_1),
	CUSTOM(null, null, null, null);

	override val labelKey get() = "settings.graphics.preset.${name.lowercase()}"

	/** [options] with this preset's quality settings; the others as they were. */
	fun applyTo(options: GraphicsOptions): GraphicsOptions = if (this == CUSTOM) options else options.copy(
		windowAntialiasing = window!!, canvasAntialiasing = canvas!!, textureFiltering = textures!!, gpuCache = cache!!,
	)

	companion object {
		fun of(options: GraphicsOptions): QualityPreset = entries.firstOrNull { it != CUSTOM && it.applyTo(options) == options } ?: CUSTOM
	}
}

/**
 * The graphics settings. [renderPath]'s software window, [gpu], [vsync] off and [threadedGl] are read once,
 * before the first window ([needsRestartFrom]); the others are followed from the next frame.
 */
data class GraphicsOptions(
	val renderPath: RenderPath = RenderPath.GPU,
	val gpu: GpuChoice = GpuChoice.Auto,
	val vsync: VsyncMode = VsyncMode.ON,
	val frameRateLimit: FrameRateLimit = FrameRateLimit.DISPLAY,
	val backgroundFrameRate: BackgroundFrameRate = BackgroundFrameRate.SAME,
	val windowAntialiasing: WindowAntialiasing = WindowAntialiasing.MSAA_4,
	val canvasAntialiasing: CanvasAntialiasing = CanvasAntialiasing.MSAA_4,
	val textureFiltering: TextureFiltering = TextureFiltering.TRILINEAR,
	/** Whether the window keeps its Compose picture on the GPU while only previews change ([WindowCompositor]). */
	val windowCache: Boolean = true,
	val gpuCache: GpuCacheSize = GpuCacheSize.AUTO,
	val threadedGl: ThreadedGl = ThreadedGl.DEFAULT,
	/** Whether the window shows its frame rate and frame times over everything ([PerformanceOverlay]). */
	val performanceOverlay: Boolean = false,
) {
	/** Whether going from [startup] to these options waits for the next start. */
	fun needsRestartFrom(startup: GraphicsOptions): Boolean =
		softwareWindow != startup.softwareWindow || gpu != startup.gpu ||
			(vsync == VsyncMode.OFF) != (startup.vsync == VsyncMode.OFF) || threadedGl != startup.threadedGl

	/** Whether the window draws in software: the part of [renderPath] fixed at start. */
	val softwareWindow: Boolean get() = renderPath == RenderPath.SOFTWARE

	companion object {
		/**
		 * Stored settings as [read] gives them by key, unknown values read as the default. Before render paths only
		 * whether the canvas painted in software was kept (`software_canvas`), and vsync was a switch.
		 */
		fun stored(read: (String) -> String?): GraphicsOptions {
			val defaults = GraphicsOptions()
			fun <T : Enum<T>> choice(key: String, entries: List<T>, default: T): T = read(key)?.let { value -> entries.firstOrNull { it.name == value } } ?: default
			return GraphicsOptions(
				renderPath = choice("render_path", RenderPath.entries, if (read("software_canvas") == "true") RenderPath.SOFTWARE_CANVAS else RenderPath.GPU),
				gpu = GpuChoice.parse(read("gpu")),
				vsync = choice("vsync_mode", VsyncMode.entries, if (read("vsync") == "false") VsyncMode.OFF else VsyncMode.ON),
				frameRateLimit = read("preview_frame_rate")?.toIntOrNull()?.let(FrameRateLimit::ofFps) ?: defaults.frameRateLimit,
				backgroundFrameRate = choice("background_frame_rate", BackgroundFrameRate.entries, defaults.backgroundFrameRate),
				windowAntialiasing = read("antialiasing_samples")?.toIntOrNull()?.let(WindowAntialiasing::ofSamples) ?: defaults.windowAntialiasing,
				canvasAntialiasing = choice("canvas_antialiasing", CanvasAntialiasing.entries, defaults.canvasAntialiasing),
				textureFiltering = choice("texture_filtering", TextureFiltering.entries, defaults.textureFiltering),
				windowCache = read("window_cache") != "false",
				gpuCache = choice("gpu_cache", GpuCacheSize.entries, defaults.gpuCache),
				threadedGl = choice("threaded_gl", ThreadedGl.entries, defaults.threadedGl),
				performanceOverlay = read("performance_overlay") == "true",
			)
		}
	}

	/** These options by key, as [stored] reads them. */
	fun toStored(): Map<String, String> = mapOf(
		"render_path" to renderPath.name,
		"gpu" to gpu.code,
		"vsync_mode" to vsync.name,
		"preview_frame_rate" to frameRateLimit.fps.toString(),
		"background_frame_rate" to backgroundFrameRate.name,
		"antialiasing_samples" to windowAntialiasing.samples.toString(),
		"canvas_antialiasing" to canvasAntialiasing.name,
		"texture_filtering" to textureFiltering.name,
		"window_cache" to windowCache.toString(),
		"gpu_cache" to gpuCache.name,
		"threaded_gl" to threadedGl.name,
		"performance_overlay" to performanceOverlay.toString(),
	)
}

/**
 * The graphics settings as the renderer follows them. [applyAtStartup] runs before the first window (the render
 * API, the card, vsync off and the driver's threading cannot change after); [update] passes on each change, and
 * the renderer reads [current] from its next frame.
 *
 * Development switches override the stored settings: `skiko.renderApi` / `SKIKO_RENDER_API`, `skiko.vsync.enabled`,
 * `psd2live.msaa`, `psd2live.adaptiveVsync=true` and `psd2live.compositor=false` (draws windows as Compose does,
 * for comparison and in case of trouble).
 */
object GraphicsSettings {
	private val os = System.getProperty("os.name").orEmpty()
	internal val isWindows = os.contains("windows", ignoreCase = true)
	internal val isLinux = os.contains("linux", ignoreCase = true)
	internal val isMac = os.contains("mac", ignoreCase = true)

	private val compositorOff = System.getProperty("psd2live.compositor") == "false"
	private val samplesOverride: Int? = System.getProperty("psd2live.msaa")?.toIntOrNull()
	private val adaptiveOverride = System.getProperty("psd2live.adaptiveVsync") == "true"

	/** The settings the renderer follows now. */
	@Volatile var current: GraphicsOptions = GraphicsOptions()
		private set

	/** The options this process started with; null before [applyAtStartup]. */
	@Volatile var startup: GraphicsOptions? = null
		private set

	/** Environment set for the chosen card and the driver's threading on Linux, for the settings page and the log. */
	@Volatile var appliedEnvironment: Map<String, String> = emptyMap()
		private set

	/** Whether windows draw through [WindowCompositor]. */
	val compositing: Boolean get() = !compositorOff && current.windowCache

	/** The window's multisampling: 0 draws with Skia's coverage antialiasing. */
	val samples: Int get() = samplesOverride ?: current.windowAntialiasing.samples

	/** Whether vsync stops waiting while frames miss the refresh ([AdaptiveVsync]); Windows only. */
	val adaptiveVsync: Boolean get() = adaptiveOverride || (isWindows && current.vsync == VsyncMode.ADAPTIVE)

	/** The most frames a second a preview asks for, 0 for the display's rate; [active] is whether its window is. */
	fun frameRateCap(active: Boolean): Int {
		val foreground = current.frameRateLimit.fps
		val background = current.backgroundFrameRate.fps
		if (active || background == 0) return foreground
		return if (foreground == 0) background else minOf(foreground, background)
	}

	fun update(options: GraphicsOptions) {
		current = options
	}

	/** Applies [options] before any window exists. */
	fun applyAtStartup(options: GraphicsOptions) {
		startup = options
		current = options
		val explicitApi = System.getProperty("skiko.renderApi") != null || System.getenv("SKIKO_RENDER_API") != null
		if (!explicitApi) when {
			options.renderPath == RenderPath.SOFTWARE -> System.setProperty("skiko.renderApi", "SOFTWARE")
			// Skiko would pick Direct3D on Windows; the canvases draw in an OpenGL context.
			isWindows -> System.setProperty("skiko.renderApi", "OPENGL")
		}
		if (options.vsync == VsyncMode.OFF && System.getProperty("skiko.vsync.enabled") == null) System.setProperty("skiko.vsync.enabled", "false")
		if (isLinux && options.renderPath != RenderPath.SOFTWARE) {
			// Before Skiko or LWJGL loads the OpenGL library, which reads them.
			val wanted = LinkedHashMap<String, String>()
			if (options.gpu != GpuChoice.Auto) {
				val devices = GraphicsDevices.linuxDevices(java.io.File("/sys/class/drm"), names = false)
				wanted += GraphicsDevices.linuxEnvironment(options.gpu, devices)
			}
			wanted += threadingEnvironment(options.threadedGl)
			appliedEnvironment = wanted.filter { (name, value) -> NativeEnvironment.set(name, value) }
		}
		// macOS draws with Metal, where Skiko picks the card itself.
		if (isMac && System.getProperty("skiko.metal.gpu.priority") == null) when (options.gpu) {
			GpuChoice.HighPerformance -> System.setProperty("skiko.metal.gpu.priority", "discrete")
			GpuChoice.PowerSaving -> System.setProperty("skiko.metal.gpu.priority", "integrated")
			else -> Unit
		}
	}

	/** What tells Mesa's and NVIDIA's drivers to thread, or not; nothing for the driver's own default. */
	internal fun threadingEnvironment(threaded: ThreadedGl): Map<String, String> = when (threaded) {
		ThreadedGl.DEFAULT -> emptyMap()
		ThreadedGl.ON -> mapOf("mesa_glthread" to "true", "__GL_THREADED_OPTIMIZATIONS" to "1")
		ThreadedGl.OFF -> mapOf("mesa_glthread" to "false", "__GL_THREADED_OPTIMIZATIONS" to "0")
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
