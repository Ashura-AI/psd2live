package org.umamo.format

import org.umamo.format.psd.PsdReader
import org.umamo.format.tiff.TiffReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuditImportTest {
	@Test
	fun sixteenBitLayersStoredInLr16AreRead() {
		val art = PsdReader.read(psd16WithLr16())
		assertEquals(1, art.layers.size)
		assertEquals("Hi", art.layers.single().name)
		val red = art.layers.single().raster.rgba[0].toInt() and 0xFF
		assertTrue(red > 200, "red $red")
	}

	@Test
	fun rasterMaskHidesPixelsAndVectorMasksAreReported() {
		val art = PsdReader.read(psdWithMask())
		val rgba = art.layers.single().raster.rgba
		assertEquals(0xFF, rgba[3].toInt() and 0xFF)
		assertEquals(0, rgba[7].toInt() and 0xFF)
		assertTrue(art.warnings.any { "Vector mask" in it })
		assertTrue(art.warnings.any { "feather" in it || "density" in it })
	}

	@Test
	fun tiffOrientationFlipsTheFirstRow() {
		val image = TiffReader.read(tiff(orientation = 2, pixels = byteArrayOf(
			255.toByte(), 0, 0,
			0, 0, 255.toByte(),
		)))
		assertEquals(2, image.width)
		assertEquals(0, image.rgba[0].toInt() and 0xFF)
		assertEquals(255, image.rgba[2].toInt() and 0xFF)
		assertEquals(255, image.rgba[4].toInt() and 0xFF)
	}

	private fun psd16WithLr16(): ByteArray {
		val payload = layerInfo(
			depthBytes = 2,
			channels = listOf(0 to byteArrayOf(0xFF.toByte(), 0xFF.toByte()), 1 to byteArrayOf(0, 0), 2 to byteArrayOf(0, 0)),
			width = 1, height = 1, name = "Hi", mask = null, vector = false, feather = false,
		)
		val section = ByteBuffer.allocate(4 + 4 + 12 + payload.size).order(ByteOrder.BIG_ENDIAN)
		section.putInt(0) // empty standard layer info
		section.putInt(0) // empty global mask
		section.put("8BIM".toByteArray()); section.put("Lr16".toByteArray()); section.putInt(payload.size)
		section.put(payload)
		return psdFile(depth = 16, width = 1, height = 1, section = section.array())
	}

	private fun psdWithMask(): ByteArray {
		val info = layerInfo(
			depthBytes = 1,
			channels = listOf(
				0 to byteArrayOf(255.toByte(), 255.toByte()),
				1 to byteArrayOf(0, 0),
				2 to byteArrayOf(0, 0),
				-2 to byteArrayOf(255.toByte(), 0),
				-3 to byteArrayOf(),
			),
			width = 2, height = 1, name = "A",
			mask = intArrayOf(0, 0, 1, 2), vector = true, feather = true,
		)
		val section = ByteBuffer.allocate(4 + info.size + 4).order(ByteOrder.BIG_ENDIAN)
		section.putInt(info.size); section.put(info); section.putInt(0)
		return psdFile(depth = 8, width = 2, height = 1, section = section.array())
	}

	private fun psdFile(depth: Int, width: Int, height: Int, section: ByteArray): ByteArray {
		val header = ByteBuffer.allocate(26 + 8 + 4 + section.size).order(ByteOrder.BIG_ENDIAN)
		header.put("8BPS".toByteArray()); header.putShort(1); header.put(ByteArray(6))
		header.putShort(3); header.putInt(height); header.putInt(width); header.putShort(depth.toShort()); header.putShort(3)
		header.putInt(0); header.putInt(0); header.putInt(section.size); header.put(section)
		return header.array()
	}

	private fun layerInfo(
		depthBytes: Int, channels: List<Pair<Int, ByteArray>>, width: Int, height: Int, name: String,
		mask: IntArray?, vector: Boolean, feather: Boolean,
	): ByteArray {
		val channelBlocks = channels.map { (_, samples) ->
			ByteBuffer.allocate(2 + samples.size).order(ByteOrder.BIG_ENDIAN).putShort(0).put(samples).array()
		}
		val maskBytes = if (mask == null) byteArrayOf() else {
			// 18-byte base, then mask parameters, then the optional 18-byte real-mask fields.
			val maskBlock = ByteBuffer.allocate(if (feather) 38 else 20).order(ByteOrder.BIG_ENDIAN)
			maskBlock.putInt(mask[0]); maskBlock.putInt(mask[1]); maskBlock.putInt(mask[2]); maskBlock.putInt(mask[3])
			maskBlock.put(0); maskBlock.put(if (feather) 0x10 else 0)
			if (feather) {
				maskBlock.put(0x01); maskBlock.put(0)
				maskBlock.put(ByteArray(18))
			}
			maskBlock.array()
		}
		val maskField = ByteBuffer.allocate(4 + maskBytes.size).order(ByteOrder.BIG_ENDIAN).putInt(maskBytes.size).put(maskBytes).array()
		val ranges = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(0).array()
		val nameBytes = byteArrayOf(name.length.toByte()) + name.toByteArray()
		val extra = maskField + ranges + nameBytes
		val record = ByteBuffer.allocate(16 + 2 + channels.size * 6 + 8 + 4 + 4 + extra.size).order(ByteOrder.BIG_ENDIAN)
		record.putInt(0); record.putInt(0); record.putInt(height); record.putInt(width)
		record.putShort(channels.size.toShort())
		channels.forEachIndexed { index, (id, _) ->
			record.putShort(id.toShort()); record.putInt(channelBlocks[index].size)
		}
		record.put("8BIM".toByteArray()); record.put("norm".toByteArray())
		record.put(255.toByte()); record.put(0); record.put(0); record.put(0)
		record.putInt(extra.size); record.put(extra)
		val body = record.array() + channelBlocks.fold(byteArrayOf()) { acc, block -> acc + block }
		return ByteBuffer.allocate(2 + body.size).order(ByteOrder.BIG_ENDIAN).putShort(1).put(body).array().also {
			if (vector && channels.none { pair -> pair.first == -3 }) error("vector channel missing")
		}
	}

	private fun tiff(orientation: Int, pixels: ByteArray): ByteArray {
		val ifdCount = 11
		val pixelAt = 8 + 2 + ifdCount * 12 + 4
		val out = ByteBuffer.allocate(pixelAt + pixels.size).order(ByteOrder.LITTLE_ENDIAN)
		out.put("II".toByteArray()); out.putShort(42); out.putInt(8)
		out.putShort(ifdCount.toShort())
		fun tag(id: Int, type: Int, count: Int, value: Int) {
			out.putShort(id.toShort()); out.putShort(type.toShort()); out.putInt(count)
			if (type == 3) { out.putShort(value.toShort()); out.putShort(0) } else out.putInt(value)
		}
		tag(256, 3, 1, 2)
		tag(257, 3, 1, 1)
		tag(258, 3, 1, 8)
		tag(259, 3, 1, 1)
		tag(262, 3, 1, 2)
		tag(273, 4, 1, pixelAt)
		tag(274, 3, 1, orientation)
		tag(277, 3, 1, 3)
		tag(278, 3, 1, 1)
		tag(279, 4, 1, pixels.size)
		tag(284, 3, 1, 1)
		out.putInt(0)
		out.put(pixels)
		return out.array()
	}
}
