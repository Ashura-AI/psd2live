package org.umamo.format.cmo3.io

import kotlin.test.Test
import kotlin.test.assertFailsWith

class BinaryReaderBoundsTest {
	@Test
	fun oversizedReadIsAParseError() {
		val reader = BinaryReader(byteArrayOf(1, 2, 3, 4))
		assertFailsWith<IllegalArgumentException> { reader.readBytes(8, 0) }
	}
}
