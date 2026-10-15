package io.github.psd2live.project

import java.io.InputStream
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * The small picture a save keeps at the archive root, for the start page's recent projects. It is not authoritative:
 * opening never reads it, a project without one opens the same, and each save writes it afresh.
 */
object ProjectThumbnail {
	const val ENTRY = "thumbnail.png"

	/** Where a project generated from a PSD, or laid out on a blank canvas, keeps its source. */
	private const val SOURCE_PSD = "source/original.psd"

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

	/**
	 * Runs [read] on the source PSD kept in the project at [file], streamed from the archive without extracting it, for
	 * projects saved before they carried a thumbnail; null when the project keeps none (one opened from a CMO3).
	 */
	fun <T> readSourcePsd(file: Path, read: (InputStream) -> T?): T? = runCatching {
		ZipFile(file.toFile()).use { zip ->
			val entry = zip.getEntry(SOURCE_PSD) ?: return null
			zip.getInputStream(entry).buffered(1 shl 16).use(read)
		}
	}.getOrNull()
}
