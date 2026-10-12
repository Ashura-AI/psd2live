package io.github.psd2live.core

import io.github.psd2live.agent.bearerMatches
import io.github.psd2live.targets.raster.requireFfmpegExecutable
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewFixTest {
	@Test
	fun executablesMustUseTheirOwnNames() {
		assertEquals("ffmpeg", requireFfmpegExecutable("ffmpeg"))
		assertEquals("/usr/bin/ffmpeg", requireFfmpegExecutable("/usr/bin/ffmpeg"))
		assertFailsWith<IllegalArgumentException> { requireFfmpegExecutable("/tmp/not-ffmpeg") }
		assertEquals("python3", requirePythonExecutable("python3"))
		assertEquals("/opt/python/bin/python3.12", requirePythonExecutable("/opt/python/bin/python3.12"))
		assertFailsWith<IllegalArgumentException> { requirePythonExecutable("/tmp/worker.sh") }
	}

	@Test
	fun zipEntriesCannotEscapeTheDestination() {
		val root = Files.createTempDirectory("psd2live-zip")
		assertFailsWith<IllegalArgumentException> { ModelDownloader.zipEntryDestination(root, "../outside.txt") }
		val zip = root.resolve("sample.zip")
		ZipOutputStream(Files.newOutputStream(zip)).use { out ->
			out.putNextEntry(ZipEntry("nunif-root/../../outside.txt"))
			out.write(byteArrayOf(1))
			out.closeEntry()
		}
		assertFailsWith<IllegalArgumentException> { ModelDownloader.extractNunifZip(zip, root.resolve("out")) }
		assertFalse(Files.exists(root.resolve("outside.txt")))
	}

	@Test
	fun bearerCompareIgnoresAPrefixMatch() {
		assertTrue(bearerMatches("same-token", "same-token"))
		assertFalse(bearerMatches("same-token", "same-token-longer"))
		assertFalse(bearerMatches("aaaa", "aaab"))
	}
}
