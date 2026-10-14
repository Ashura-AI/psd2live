package io.github.psd2live.project

import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Archives [ZipWriter] writes read back with `java.util.zip`, past the classic entry-count limit too. */
class ZipWriterTest {
	@TempDir lateinit var temp: Path

	private fun crc(bytes: ByteArray) = CRC32().apply { update(bytes) }.value

	@Test fun readyAndStreamedEntriesReadBack() {
		val file = temp.resolve("a.zip")
		val text = "héllo ".repeat(5000).encodeToByteArray()
		val raw = ByteArray(70_000) { (it * 31).toByte() }
		FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
			val zip = ZipWriter(channel)
			val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
			deflater.setInput(text); deflater.finish()
			val packed = java.io.ByteArrayOutputStream().also { out -> val buffer = ByteArray(4096); while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer)) }.toByteArray()
			deflater.end()
			zip.add("dir/ready.txt", ZipWriter.DEFLATED, crc(text), text.size.toLong(), ByteBuffer.wrap(packed))
			zip.stream("stored.bin", ZipWriter.STORED, raw.size.toLong()) { out -> Channels.newOutputStream(out).write(raw); crc(raw) }
			zip.stream("名前/streamed.txt", ZipWriter.DEFLATED, text.size.toLong()) { out ->
				val stream = DeflaterOutputStream(Channels.newOutputStream(out), Deflater(Deflater.BEST_SPEED, true))
				stream.write(text); stream.finish(); crc(text)
			}
			zip.finish()
		}
		ZipFile(file.toFile()).use { zip ->
			assertEquals(listOf("dir/ready.txt", "stored.bin", "名前/streamed.txt"), zip.entries().toList().map { it.name })
			assertContentEquals(text, zip.getInputStream(zip.getEntry("dir/ready.txt")).readBytes())
			assertContentEquals(raw, zip.getInputStream(zip.getEntry("stored.bin")).readBytes())
			assertContentEquals(text, zip.getInputStream(zip.getEntry("名前/streamed.txt")).readBytes())
		}
	}

	@Test fun moreEntriesThanTheClassicLimitReadBack() {
		val file = temp.resolve("many.zip")
		val count = 70_000
		FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
			val zip = ZipWriter(channel)
			repeat(count) { i ->
				val bytes = "$i".encodeToByteArray()
				zip.add("e/$i", ZipWriter.STORED, crc(bytes), bytes.size.toLong(), ByteBuffer.wrap(bytes))
			}
			zip.finish()
		}
		ZipFile(file.toFile()).use { zip ->
			assertEquals(count, zip.size())
			assertEquals("69999", zip.getInputStream(zip.getEntry("e/69999")).readBytes().decodeToString())
		}
	}
}
