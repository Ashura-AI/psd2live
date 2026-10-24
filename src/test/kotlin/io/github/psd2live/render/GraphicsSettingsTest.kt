package io.github.psd2live.render

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The graphics settings outside a window: stored values read back (and older ones carried over), which changes wait
 * for a restart, presets, frame rate caps, refused antialiasing targets, and how each system is told which card
 * draws and whether its driver threads.
 */
class GraphicsSettingsTest {
	private val intel = GpuDevice("0000:00:02.0", "Intel UHD", GpuDevice.INTEL, "i915", primary = true)
	private val nvidia = GpuDevice("0000:01:00.0", "NVIDIA RTX", GpuDevice.NVIDIA, "nvidia", primary = false)
	private val amd = GpuDevice("0000:03:00.0", "AMD Radeon", GpuDevice.AMD, "amdgpu", primary = false)

	@Test fun choicesReadBackAndUnknownOnesFallBackToTheSystem() {
		for (choice in listOf(GpuChoice.Auto, GpuChoice.HighPerformance, GpuChoice.PowerSaving, GpuChoice.Device("0000:01:00.0")))
			assertEquals(choice, GpuChoice.parse(choice.code))
		assertEquals(GpuChoice.Auto, GpuChoice.parse(null))
		assertEquals(GpuChoice.Auto, GpuChoice.parse("device:"))
		assertEquals(GpuChoice.Auto, GpuChoice.parse("quantum"))
	}

	@Test fun storedSettingsReadBackAndOlderOnesCarryOver() {
		fun stored(vararg pairs: Pair<String, String>) = GraphicsOptions.stored(mapOf(*pairs)::get)
		assertEquals(GraphicsOptions(), stored())
		// Before render paths only the software canvas switch was kept, and vsync was a switch.
		assertEquals(RenderPath.SOFTWARE_CANVAS, stored("software_canvas" to "true").renderPath)
		assertEquals(VsyncMode.OFF, stored("vsync" to "false").vsync)
		// The preview menu's frame rate is the same setting, under its old key.
		assertEquals(FrameRateLimit.FPS_60, stored("preview_frame_rate" to "60").frameRateLimit)
		// A stored path or mode wins over the old switch.
		assertEquals(RenderPath.SOFTWARE, stored("render_path" to "SOFTWARE", "software_canvas" to "true").renderPath)
		assertEquals(VsyncMode.ADAPTIVE, stored("vsync_mode" to "ADAPTIVE", "vsync" to "false").vsync)
		// Window antialiasing keeps its sample count; counts not offered, and unknown names, read as the default.
		assertEquals(WindowAntialiasing.COVERAGE, stored("antialiasing_samples" to "0").windowAntialiasing)
		assertEquals(GraphicsOptions().windowAntialiasing, stored("antialiasing_samples" to "3").windowAntialiasing)
		assertEquals(GraphicsOptions().canvasAntialiasing, stored("canvas_antialiasing" to "FXAA").canvasAntialiasing)
		// Everything written reads back.
		val everything = GraphicsOptions(RenderPath.SOFTWARE_CANVAS, GpuChoice.Device("0000:01:00.0"), VsyncMode.OFF, FrameRateLimit.FPS_60,
			BackgroundFrameRate.FPS_10, WindowAntialiasing.MSAA_16, CanvasAntialiasing.SSAA_2X, TextureFiltering.ANISOTROPIC_8X,
			windowCache = false, GpuCacheSize.GB_2, ThreadedGl.ON)
		assertEquals(everything, GraphicsOptions.stored(everything.toStored()::get))
	}

	@Test fun theRenderApiCardVsyncOffAndDriverThreadingWaitForARestartButTheRestDoesNot() {
		val startup = GraphicsOptions()
		for (live in listOf(startup.copy(windowAntialiasing = WindowAntialiasing.MSAA_8), startup.copy(renderPath = RenderPath.SOFTWARE_CANVAS),
			startup.copy(canvasAntialiasing = CanvasAntialiasing.SSAA_2X), startup.copy(textureFiltering = TextureFiltering.BILINEAR),
			startup.copy(frameRateLimit = FrameRateLimit.FPS_30), startup.copy(windowCache = false), startup.copy(gpuCache = GpuCacheSize.GB_1),
			// Adaptive lifts and restores the wait vsync already has.
			startup.copy(vsync = VsyncMode.ADAPTIVE)))
			assertFalse(live.needsRestartFrom(startup), "$live")
		for (restart in listOf(startup.copy(renderPath = RenderPath.SOFTWARE), startup.copy(gpu = GpuChoice.PowerSaving),
			startup.copy(vsync = VsyncMode.OFF), startup.copy(threadedGl = ThreadedGl.OFF)))
			assertTrue(restart.needsRestartFrom(startup), "$restart")
	}

	@Test fun presetsSetTheQualitySettingsAndAnyOtherMixIsCustom() {
		for (preset in QualityPreset.entries - QualityPreset.CUSTOM) assertEquals(preset, QualityPreset.of(preset.applyTo(GraphicsOptions())))
		// The defaults are the high preset; the display settings are not part of any.
		assertEquals(QualityPreset.HIGH, QualityPreset.of(GraphicsOptions(vsync = VsyncMode.OFF, frameRateLimit = FrameRateLimit.FPS_30)))
		val custom = QualityPreset.HIGH.applyTo(GraphicsOptions()).copy(textureFiltering = TextureFiltering.ANISOTROPIC_4X)
		assertEquals(QualityPreset.CUSTOM, QualityPreset.of(custom))
		assertEquals(custom, QualityPreset.CUSTOM.applyTo(custom))
	}

	@Test fun previewsSlowDownInTheBackgroundButNeverAboveTheLimit() {
		try {
			GraphicsSettings.update(GraphicsOptions(frameRateLimit = FrameRateLimit.DISPLAY, backgroundFrameRate = BackgroundFrameRate.FPS_30))
			assertEquals(0, GraphicsSettings.frameRateCap(active = true))
			assertEquals(30, GraphicsSettings.frameRateCap(active = false))
			GraphicsSettings.update(GraphicsOptions(frameRateLimit = FrameRateLimit.FPS_30, backgroundFrameRate = BackgroundFrameRate.SAME))
			assertEquals(30, GraphicsSettings.frameRateCap(active = false))
			GraphicsSettings.update(GraphicsOptions(frameRateLimit = FrameRateLimit.FPS_60, backgroundFrameRate = BackgroundFrameRate.FPS_10))
			assertEquals(60, GraphicsSettings.frameRateCap(active = true))
			assertEquals(10, GraphicsSettings.frameRateCap(active = false))
		} finally {
			GraphicsSettings.update(GraphicsOptions())
		}
	}

	@Test fun aRefusedTargetIsNotTriedAgainAtThatSizeOrAbove() {
		val refused = RefusedQuality()
		assertEquals(TargetQuality(8, 1), refused.limit(TargetQuality(8, 1)))
		refused.refuse(TargetQuality(8, 1))
		assertEquals(TargetQuality.NONE, refused.limit(TargetQuality(8, 1)))
		assertEquals(TargetQuality(4, 1), refused.limit(TargetQuality(4, 1)))
		// Supersampling is refused apart from multisampling.
		assertEquals(TargetQuality(0, 2), refused.limit(TargetQuality(0, 2)))
		refused.refuse(TargetQuality(0, 2))
		assertEquals(TargetQuality.NONE, refused.limit(TargetQuality(0, 2)))
	}

	@Test fun linuxDriversAreToldToThreadOrNot() {
		assertEquals(emptyMap(), GraphicsSettings.threadingEnvironment(ThreadedGl.DEFAULT))
		assertEquals(mapOf("mesa_glthread" to "true", "__GL_THREADED_OPTIMIZATIONS" to "1"), GraphicsSettings.threadingEnvironment(ThreadedGl.ON))
		assertEquals(mapOf("mesa_glthread" to "false", "__GL_THREADED_OPTIMIZATIONS" to "0"), GraphicsSettings.threadingEnvironment(ThreadedGl.OFF))
	}

	@Test fun linuxSendsOpenGlToTheChosenCardAndLeavesTheDefaultOneAlone() {
		val devices = listOf(intel, nvidia, amd)
		assertEquals(emptyMap(), GraphicsDevices.linuxEnvironment(GpuChoice.Auto, devices))
		// The card the system boots on needs nothing set.
		assertEquals(emptyMap(), GraphicsDevices.linuxEnvironment(GpuChoice.Device(intel.id), devices))
		assertEquals(emptyMap(), GraphicsDevices.linuxEnvironment(GpuChoice.PowerSaving, devices))
		// NVIDIA's own driver takes its render offload; Mesa's drivers take DRI_PRIME.
		val offload = mapOf("__NV_PRIME_RENDER_OFFLOAD" to "1", "__GLX_VENDOR_LIBRARY_NAME" to "nvidia")
		assertEquals(offload, GraphicsDevices.linuxEnvironment(GpuChoice.HighPerformance, devices))
		assertEquals(offload, GraphicsDevices.linuxEnvironment(GpuChoice.Device(nvidia.id), devices))
		assertEquals(mapOf("DRI_PRIME" to "pci-0000_03_00_0"), GraphicsDevices.linuxEnvironment(GpuChoice.Device(amd.id), devices))
		// Nouveau is a Mesa driver.
		assertEquals(mapOf("DRI_PRIME" to "pci-0000_01_00_0"), GraphicsDevices.linuxEnvironment(GpuChoice.HighPerformance, listOf(intel, nvidia.copy(driver = "nouveau"))))
		// A card no longer there, or no second card, leaves the system's choice.
		assertEquals(emptyMap(), GraphicsDevices.linuxEnvironment(GpuChoice.Device("0000:09:00.0"), devices))
		assertEquals(emptyMap(), GraphicsDevices.linuxEnvironment(GpuChoice.HighPerformance, listOf(intel)))
	}

	@Test fun windowsTakesItsOwnPreferenceValues() {
		assertEquals("GpuPreference=2;", GraphicsDevices.windowsPreference(GpuChoice.HighPerformance))
		assertEquals("GpuPreference=1;", GraphicsDevices.windowsPreference(GpuChoice.PowerSaving))
		assertNull(GraphicsDevices.windowsPreference(GpuChoice.Auto))
	}

	@Test fun lspciNamesCardsByTheirMarketingName() {
		assertEquals("NVIDIA GeForce RTX 4070",
			GraphicsDevices.lspciDeviceName("01:00.0 \"VGA compatible controller\" \"NVIDIA Corporation\" \"AD104 [GeForce RTX 4070]\" -ra1 \"Micro-Star\" \"Device 5136\""))
		assertEquals("Intel UHD Graphics 620", GraphicsDevices.lspciDeviceName("00:02.0 \"VGA compatible controller\" \"Intel Corporation\" \"UHD Graphics 620\""))
		assertNull(GraphicsDevices.lspciDeviceName(""))
	}

	@Test fun linuxCardsAreReadFromSysfs() {
		val root = Files.createTempDirectory("psd2live-graphics-settings")
		try {
			fun pci(address: String, vendor: String, driver: String, bootVga: String?) = root.resolve("devices/$address").also { device ->
				Files.createDirectories(device)
				Files.writeString(device.resolve("vendor"), "$vendor\n")
				bootVga?.let { Files.writeString(device.resolve("boot_vga"), "$it\n") }
				Files.createDirectories(root.resolve("drivers/$driver"))
				Files.createSymbolicLink(device.resolve("driver"), root.resolve("drivers/$driver"))
			}
			val drm = Files.createDirectories(root.resolve("drm"))
			try {
				Files.createDirectories(drm.resolve("card1"))
				Files.createSymbolicLink(drm.resolve("card1/device"), pci("0000:01:00.0", GpuDevice.NVIDIA, "nvidia", "0"))
			} catch (unsupported: Exception) {
				// Symbolic links need a privilege on Windows; the walk itself only runs on Linux.
				return
			}
			Files.createDirectories(drm.resolve("card0"))
			Files.createSymbolicLink(drm.resolve("card0/device"), pci("0000:00:02.0", GpuDevice.INTEL, "i915", "1"))
			// Connectors are not cards.
			Files.createDirectories(drm.resolve("card0-HDMI-A-1"))
			val devices = GraphicsDevices.linuxDevices(drm.toFile(), names = false)
			assertEquals(listOf("0000:00:02.0", "0000:01:00.0"), devices.map { it.id })
			assertEquals(listOf(true, false), devices.map { it.primary })
			assertEquals(listOf("i915", "nvidia"), devices.map { it.driver })
			assertEquals("NVIDIA (0000:01:00.0)", devices[1].name)
		} finally {
			root.toFile().deleteRecursively()
		}
	}
}
