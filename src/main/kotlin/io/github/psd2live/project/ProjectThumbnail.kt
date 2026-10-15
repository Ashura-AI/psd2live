package io.github.psd2live.project

import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * The small picture a save keeps at the archive root, for the start page's recent projects. It is not authoritative:
 * opening never reads it, a project without one opens the same, and each save writes it afresh.
 */
object ProjectThumbnail {
	const val ENTRY = "thumbnail.png"

	/** The longer side, in pixels. */
	const val LONG_EDGE = 256

	private const val MAX_BYTES = 4L shl 20

	/** The PNG kept in the project at [file], read without extracting the rest; null when it has none. */
	fun read(file: Path): ByteArray? = runCatching {
		ZipFile(file.toFile()).use { zip ->
			val entry = zip.getEntry(ENTRY) ?: return null
			if (entry.size !in 1..MAX_BYTES) return null
			zip.getInputStream(entry).use { it.readBytes() }
		}
	}.getOrNull()
}
