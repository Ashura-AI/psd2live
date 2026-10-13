package org.umamo.format

import org.umamo.format.psd.PsdReader
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PsdUserMaskTest {
	@Test fun absoluteAndLayerRelativeMasksCoverTheSamePixels() {
		for (flags in listOf(0, 1)) {
			val art = PsdReader.read(psd(flags))
			assertEquals(listOf(255, 0), alphas(art.layers.single().raster.rgba))
		}
	}

	@Test fun relativeMaskStillHonorsDisableAndInvertFlags() {
		assertEquals(listOf(255, 255), alphas(PsdReader.read(psd(3)).layers.single().raster.rgba))
		assertEquals(listOf(0, 255), alphas(PsdReader.read(psd(5)).layers.single().raster.rgba))
	}

	@Test fun maskDensityAndFeatherFollowTheBaseFlags() {
		val parameters = bytes { writeByte(3); writeByte(128); writeDouble(2.0) }
		val art = PsdReader.read(psd(16, parameters))
		assertEquals(listOf(255, 0), alphas(art.layers.single().raster.rgba))
		assertTrue(art.warnings.any { "Raster mask feather or density" in it })
	}

	@Test fun vectorParametersAreReadAfterRasterParameters() {
		val parameters = bytes {
			writeByte(15); writeByte(255); writeDouble(0.0); writeByte(128); writeDouble(1.0)
		}
		val art = PsdReader.read(psd(16, parameters))
		assertTrue(art.warnings.any { "Vector mask feather or density" in it })
		assertTrue(art.warnings.none { "Raster mask feather or density" in it })
	}

	@Test fun truncatedParametersCannotReadIntoTheNextLayerField() {
		assertFailsWith<IllegalArgumentException> {
			PsdReader.read(psd(16, byteArrayOf(2, 0), realMask = false))
		}
	}

	private fun alphas(rgba: ByteArray) = (3 until rgba.size step 4).map { rgba[it].toInt() and 255 }

	private fun bytes(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { stream ->
		DataOutputStream(stream).use(write)
	}.toByteArray()

	/** A two-pixel layer at (10,20), with a mask covering the first pixel. */
	private fun psd(flags: Int, parameters: ByteArray = byteArrayOf(), realMask: Boolean = true): ByteArray {
		val relative = flags and 1 != 0
		val channels = listOf(0 to byteArrayOf(-1, -1), 1 to byteArrayOf(0, 0),
			2 to byteArrayOf(0, 0), -2 to byteArrayOf(-1, 0))
		val mask = bytes {
			writeInt(if (relative) 0 else 20); writeInt(if (relative) 0 else 10)
			writeInt(if (relative) 1 else 21); writeInt(if (relative) 2 else 12)
			writeByte(0); writeByte(flags)
			if (flags and 16 != 0) {
				write(parameters)
				if (realMask) {
					writeByte(0); writeByte(0)
					writeInt(20); writeInt(10); writeInt(21); writeInt(12)
				}
			} else writeShort(0)
		}
		val extra = bytes {
			writeInt(mask.size); write(mask); writeInt(0)
			writeByte(1); writeByte('A'.code); writeShort(0)
		}
		val info = bytes {
			writeShort(1)
			writeInt(20); writeInt(10); writeInt(21); writeInt(12)
			writeShort(channels.size)
			channels.forEach { (id, data) -> writeShort(id); writeInt(2 + data.size) }
			writeBytes("8BIMnorm"); writeByte(255); writeByte(0); writeByte(0); writeByte(0)
			writeInt(extra.size); write(extra)
			channels.forEach { (_, data) -> writeShort(0); write(data) }
		}
		val section = bytes { writeInt(info.size); write(info); writeInt(0) }
		return bytes {
			writeBytes("8BPS"); writeShort(1); write(ByteArray(6)); writeShort(3)
			writeInt(32); writeInt(32); writeShort(8); writeShort(3)
			writeInt(0); writeInt(0); writeInt(section.size); write(section)
		}
	}
}
