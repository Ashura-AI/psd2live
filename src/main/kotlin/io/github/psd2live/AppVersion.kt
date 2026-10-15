package io.github.psd2live

import java.util.Properties

/** This build's version, as Gradle stamped it into the resources; "dev" when run from sources it never processed. */
object AppVersion {
	val value: String by lazy {
		runCatching {
			AppVersion::class.java.getResourceAsStream("/psd2live/version.properties")?.use { stream ->
				Properties().apply { load(stream) }.getProperty("version")
			}
		}.getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("\${") } ?: "dev"
	}
}
