package io.github.psd2live.render

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A graphics card the system shows (Linux: a DRM card on a PCI bus).
 *
 * @property id      The PCI address, as `0000:01:00.0`.
 * @property name    What `lspci` calls it, or the vendor and address when it cannot say.
 * @property vendor  The PCI vendor ID, as `0x10de`.
 * @property driver  The kernel driver bound to it (`i915`, `amdgpu`, `nvidia`, …), empty when unknown.
 * @property primary Whether the firmware booted on it: the card programs draw on unless told otherwise.
 */
data class GpuDevice(val id: String, val name: String, val vendor: String, val driver: String, val primary: Boolean) {
	/** Mesa's name for the card in `DRI_PRIME`. */
	val primeTag: String get() = "pci-" + id.replace(':', '_').replace('.', '_')

	val integrated: Boolean get() = vendor == INTEL

	companion object {
		const val INTEL = "0x8086"
		const val NVIDIA = "0x10de"
		const val AMD = "0x1002"
	}
}

/** The cards a program can be sent to, and how each system is told which one draws PSD2Live. */
object GraphicsDevices {
	private val listed: List<GpuDevice> by lazy { if (GraphicsSettings.isLinux) linuxDevices(File("/sys/class/drm"), names = true) else emptyList() }

	/** The cards to choose from: Linux only, where a card is picked per program by environment variables. */
	fun list(): List<GpuDevice> = listed

	/** Linux cards under [drm] (`/sys/class/drm`), named by `lspci` when [names]. */
	internal fun linuxDevices(drm: File, names: Boolean): List<GpuDevice> {
		val cards = drm.listFiles { file -> CARD.matches(file.name) }.orEmpty().sortedBy { it.name.removePrefix("card").toIntOrNull() ?: 0 }
		return cards.mapNotNull { card ->
			val device = File(card, "device")
			val address = runCatching { device.canonicalFile.name }.getOrNull()?.takeIf { PCI_ADDRESS.matches(it) } ?: return@mapNotNull null
			val vendor = read(File(device, "vendor")) ?: ""
			val driver = runCatching { File(device, "driver").canonicalFile.name }.getOrNull()?.takeIf { File(device, "driver").exists() } ?: ""
			val primary = read(File(device, "boot_vga")) == "1"
			val name = (if (names) lspciName(address) else null) ?: "${vendorName(vendor)} ($address)"
			GpuDevice(address, name, vendor, driver, primary)
		}.distinctBy { it.id }
	}

	/**
	 * The variables that send OpenGL to the card [choice] names among [devices]: NVIDIA's PRIME render offload for its
	 * own driver, Mesa's `DRI_PRIME` for the others. Empty when the system's card is the one chosen.
	 */
	fun linuxEnvironment(choice: GpuChoice, devices: List<GpuDevice>): Map<String, String> {
		val target = when (choice) {
			GpuChoice.Auto -> null
			is GpuChoice.Device -> devices.firstOrNull { it.id == choice.id }
			GpuChoice.HighPerformance -> devices.filter { !it.primary && !it.integrated }.minByOrNull { if (it.vendor == GpuDevice.NVIDIA || it.vendor == GpuDevice.AMD) 0 else 1 }
			GpuChoice.PowerSaving -> devices.firstOrNull { it.integrated } ?: devices.firstOrNull { it.primary }
		}
		if (target == null || target.primary) return emptyMap()
		return if (target.driver == "nvidia") mapOf("__NV_PRIME_RENDER_OFFLOAD" to "1", "__GLX_VENDOR_LIBRARY_NAME" to "nvidia")
		else mapOf("DRI_PRIME" to target.primeTag)
	}

	/** The value of Windows' per-program graphics preference for [choice]; null leaves the choice to the system. */
	fun windowsPreference(choice: GpuChoice): String? = when (choice) {
		GpuChoice.PowerSaving -> "GpuPreference=1;"
		GpuChoice.HighPerformance -> "GpuPreference=2;"
		else -> null
	}

	/** The packaged launcher's executable, which Windows keys its graphics preference by; null when run otherwise. */
	fun windowsLauncher(): String? = ProcessHandle.current().info().command().orElse(null)
		?.takeIf { File(it).name.let { name -> name.startsWith("PSD2Live", ignoreCase = true) && name.endsWith(".exe", ignoreCase = true) } }

	/**
	 * Writes [choice] as Windows' graphics preference for PSD2Live (the same setting as Settings > Display > Graphics),
	 * read by the driver when the next process starts. False when this is not the packaged launcher or `reg` failed.
	 */
	fun applyWindowsPreference(choice: GpuChoice): Boolean {
		val launcher = windowsLauncher() ?: return false
		val key = "HKCU\\Software\\Microsoft\\DirectX\\UserGpuPreferences"
		val value = windowsPreference(choice)
		val command = if (value != null) listOf("reg", "add", key, "/v", launcher, "/t", "REG_SZ", "/d", value, "/f")
			else listOf("reg", "delete", key, "/v", launcher, "/f")
		return runCatching {
			val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
			if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroy(); false }
			// Deleting a value that is not there fails, and leaves the system's choice as wanted.
			else process.exitValue() == 0 || value == null
		}.getOrDefault(false)
	}

	private val CARD = Regex("card\\d+")
	private val PCI_ADDRESS = Regex("[0-9a-fA-F]{4}:[0-9a-fA-F]{2}:[0-9a-fA-F]{2}\\.[0-9a-fA-F]")

	private fun read(file: File): String? = runCatching { file.readText().trim() }.getOrNull()

	private fun vendorName(vendor: String) = when (vendor) {
		GpuDevice.INTEL -> "Intel"
		GpuDevice.NVIDIA -> "NVIDIA"
		GpuDevice.AMD -> "AMD"
		else -> vendor.ifEmpty { "GPU" }
	}

	/** `lspci -mm`'s vendor and device names for [address]; null without `lspci`. */
	private fun lspciName(address: String): String? = runCatching {
		val process = ProcessBuilder("lspci", "-mm", "-s", address).redirectErrorStream(true).start()
		val output = process.inputStream.bufferedReader().readText()
		if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroy(); return null }
		lspciDeviceName(output)
	}.getOrNull()

	/** The vendor and device of one `lspci -mm` line: `01:00.0 "VGA compatible controller" "NVIDIA Corporation" "AD104 [GeForce RTX 4070]" …`. */
	internal fun lspciDeviceName(line: String): String? {
		val fields = Regex("\"([^\"]*)\"").findAll(line).map { it.groupValues[1] }.toList()
		if (fields.size < 3) return null
		val vendor = fields[1].removeSuffix(" Corporation").removeSuffix(", Inc.").removeSuffix(" Inc.")
		// Most names carry the marketing name in brackets after the chip's.
		val device = Regex("\\[([^\\]]+)]").find(fields[2])?.groupValues?.get(1) ?: fields[2]
		return "$vendor $device".trim()
	}
}
