package io.github.psd2live.format.compile

import io.github.psd2live.format.model.Floats
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Float arrays keep their exact bits in the planar encoding, and objects written before it still read. */
class RigIrBinaryFloatsTest {
	private val values = floatArrayOf(0f, -0f, 1.5f, -2.25e-9f, Float.NaN, Float.fromBits(0x7fc00123), Float.MAX_VALUE, 3f)

	@Test fun planarFloatsKeepTheirBits() {
		val bytes = ByteArrayOutputStream().also { DataOutputStream(it).use { out -> RigIrBinary.Writer(out).floats(Floats.wrap(values)) } }.toByteArray()
		assertEquals(4 + values.size * 4, bytes.size)
		// The first plane holds each value's high byte.
		assertEquals((values[2].toRawBits() ushr 24).toByte(), bytes[4 + 2])
		val read = RigIrBinary.Reader(DataInputStream(bytes.inputStream())).floats().shared()
		assertContentEquals(values.map { it.toRawBits() }, read.map { it.toRawBits() })
	}

	@Test fun interleavedFloatsOfEarlierVersionsStillRead() {
		val bytes = ByteArrayOutputStream().also { output ->
			DataOutputStream(output).use { out -> out.writeInt(values.size); values.forEach { out.writeInt(it.toRawBits()) } }
		}.toByteArray()
		val read = RigIrBinary.Reader(DataInputStream(bytes.inputStream()), RigIrBinary.PLANAR_FLOATS - 1).floats().shared()
		assertContentEquals(values.map { it.toRawBits() }, read.map { it.toRawBits() })
	}
}
