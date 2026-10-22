package org.umamo.format.cmo3.xml

import kotlin.test.Test
import kotlin.test.assertFailsWith

class XmlCodecEntitiesTest {

	@Test
	fun doctypeIsRejected() {
		val xml = """<!DOCTYPE root [<!ENTITY xxe "no">]><root>&xxe;</root>"""
		assertFailsWith<Exception> { XmlCodec.parse(xml.toByteArray()) }
	}
}
