package io.github.psd2live.render

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The graphics settings outside a window: stored values read back (and the older software-canvas switch carried
 * over), which changes wait for a restart, and how each system is told which card draws.
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

	@Test fun storedSettingsReadBackAndTheOldSoftwareSwitchBecomesAPath() {
		assertEquals(RenderPath.SOFTWARE_CANVAS, GraphicsOptions.stored(null, legacySoftwareCanvas = true, null, 4, true).renderPath)
		assertEquals(RenderPath.GPU, GraphicsOptions.stored(null, legacySoftwareCanvas = false, null, 4, true).renderPath)
		// A stored path wins over the old switch.
		val stored = GraphicsOptions.stored("SOFTWARE", legacySoftwareCanvas = false, "high_performance", 8, false)
		assertEquals(GraphicsOptions(RenderPath.SOFTWARE, GpuChoice.HighPerformance, 8, vsync = false), stored)
		// Sample counts other than the offered ones read as the default.
		assertEquals(GraphicsOptions.DEFAULT_SAMPLES, GraphicsOptions.stored(null, false, null, 3, true).samples)
		assertEquals(0, GraphicsOptions.stored(null, false, null, 0, true).samples)
	}

	@Test fun theRenderApiCardAndVsyncWaitForARestartButAntialiasingAndTheCanvasDoNot() {
		val startup = GraphicsOptions()
		assertFalse(startup.copy(samples = 8).needsRestartFrom(startup))
		assertFalse(startup.copy(renderPath = RenderPath.SOFTWARE_CANVAS).needsRestartFrom(startup))
		assertTrue(startup.copy(renderPath = RenderPath.SOFTWARE).needsRestartFrom(startup))
		assertTrue(startup.copy(gpu = GpuChoice.PowerSaving).needsRestartFrom(startup))
		assertTrue(startup.copy(vsync = false).needsRestartFrom(startup))
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
