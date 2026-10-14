package io.github.psd2live.project

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Writes a ZIP archive to [channel] from entries whose bytes are ready (compressed or not) or streamed, so a
 * project save can compress many entries at once and write them in order. Names are UTF-8; every entry carries the
 * same DOS time (1980-01-01), so equal content makes equal archives. ZIP64 records are written where a size, an
 * offset or the entry count needs them; `java.util.zip.ZipFile` reads the result.
 */
internal class ZipWriter(private val channel: FileChannel) {
	private class Entry(val name: ByteArray, val method: Int, val crc: Long, val size: Long, val compressedSize: Long, val offset: Long)

	private val entries = ArrayList<Entry>()
	private var finished = false

	/** Adds an entry whose [data] are its bytes as stored ([method] [STORED] or [DEFLATED], raw DEFLATE without header). */
	fun add(name: String, method: Int, crc: Long, size: Long, data: ByteBuffer) {
		val offset = channel.position()
		val compressedSize = data.remaining().toLong()
		val zip64 = size >= ZIP64_THRESHOLD || compressedSize >= ZIP64_THRESHOLD
		writeFully(localHeader(name.encodeToByteArray(), method, crc, size, compressedSize, zip64))
		writeFully(data)
		entries += Entry(name.encodeToByteArray(), method, crc, size, compressedSize, offset)
	}

	/**
	 * Adds an entry of [size] bytes whose stored bytes [write] streams to the channel, returning their CRC-32; the
	 * local header is completed afterwards with the CRC and the stored length.
	 */
	fun stream(name: String, method: Int, size: Long, write: (FileChannel) -> Long) {
		val offset = channel.position()
		val bytes = name.encodeToByteArray()
		// A DEFLATE stream can come out a little longer than its input: decide ZIP64 with room to spare.
		val zip64 = size >= ZIP64_THRESHOLD / 2
		writeFully(localHeader(bytes, method, 0, size, 0, zip64))
		val start = channel.position()
		val crc = write(channel)
		val compressedSize = channel.position() - start
		require(zip64 || compressedSize < ZIP64_THRESHOLD) { "Entry grew past the ZIP64 limit: $name" }
		val end = channel.position()
		channel.position(offset)
		writeFully(localHeader(bytes, method, crc, size, compressedSize, zip64))
		channel.position(end)
		entries += Entry(bytes, method, crc, size, compressedSize, offset)
	}

	/** Writes the central directory and the end records. */
	fun finish() {
		check(!finished) { "Archive finished already" }
		finished = true
		val start = channel.position()
		for (entry in entries) writeFully(centralHeader(entry))
		val size = channel.position() - start
		val zip64 = entries.size >= 0xFFFF || start >= ZIP64_THRESHOLD || size >= ZIP64_THRESHOLD
		if (zip64) {
			val record = channel.position()
			writeFully(buffer(56).putInt(0x06064b50).putLong(44).putShort(45).putShort(45).putInt(0).putInt(0)
				.putLong(entries.size.toLong()).putLong(entries.size.toLong()).putLong(size).putLong(start).flip())
			writeFully(buffer(20).putInt(0x07064b50).putInt(0).putLong(record).putInt(1).flip())
		}
		val count = if (zip64) 0xFFFF else entries.size
		writeFully(buffer(22).putInt(0x06054b50).putShort(0).putShort(0).putShort(count.toShort()).putShort(count.toShort())
			.putInt(if (zip64) -1 else size.toInt()).putInt(if (zip64) -1 else start.toInt()).putShort(0).flip())
	}

	private fun localHeader(name: ByteArray, method: Int, crc: Long, size: Long, compressedSize: Long, zip64: Boolean): ByteBuffer {
		val header = buffer(30 + name.size + if (zip64) 20 else 0)
			.putInt(0x04034b50).putShort(if (zip64) 45 else 20).putShort(UTF8).putShort(method.toShort())
			.putShort(DOS_TIME).putShort(DOS_DATE).putInt(crc.toInt())
			.putInt(if (zip64) -1 else compressedSize.toInt()).putInt(if (zip64) -1 else size.toInt())
			.putShort(name.size.toShort()).putShort(if (zip64) 20 else 0).put(name)
		if (zip64) header.putShort(1).putShort(16).putLong(size).putLong(compressedSize)
		return header.flip()
	}

	private fun centralHeader(entry: Entry): ByteBuffer {
		val bigSize = entry.size >= ZIP64_THRESHOLD
		val bigCompressed = entry.compressedSize >= ZIP64_THRESHOLD
		val bigOffset = entry.offset >= ZIP64_THRESHOLD
		val extra = (if (bigSize) 8 else 0) + (if (bigCompressed) 8 else 0) + (if (bigOffset) 8 else 0)
		val zip64 = extra > 0
		val header = buffer(46 + entry.name.size + if (zip64) 4 + extra else 0)
			.putInt(0x02014b50).putShort(if (zip64) 45 else 20).putShort(if (zip64) 45 else 20).putShort(UTF8)
			.putShort(entry.method.toShort()).putShort(DOS_TIME).putShort(DOS_DATE).putInt(entry.crc.toInt())
			.putInt(if (bigCompressed) -1 else entry.compressedSize.toInt()).putInt(if (bigSize) -1 else entry.size.toInt())
			.putShort(entry.name.size.toShort()).putShort(if (zip64) (4 + extra).toShort() else 0).putShort(0).putShort(0).putShort(0)
			.putInt(0).putInt(if (bigOffset) -1 else entry.offset.toInt()).put(entry.name)
		if (zip64) {
			header.putShort(1).putShort(extra.toShort())
			if (bigSize) header.putLong(entry.size)
			if (bigCompressed) header.putLong(entry.compressedSize)
			if (bigOffset) header.putLong(entry.offset)
		}
		return header.flip()
	}

	private fun buffer(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

	private fun writeFully(buffer: ByteBuffer) {
		while (buffer.hasRemaining()) channel.write(buffer)
	}

	companion object {
		const val STORED = 0
		const val DEFLATED = 8
		private const val ZIP64_THRESHOLD = 0xFFFFFFFFL
		private const val UTF8: Short = 0x0800
		/** 00:00:00 and 1980-01-01, the earliest DOS time. */
		private const val DOS_TIME: Short = 0
		private const val DOS_DATE: Short = 0x21
	}
}
