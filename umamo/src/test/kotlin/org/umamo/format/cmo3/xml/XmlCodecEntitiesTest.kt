package org.umamo.format.cmo3.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class XmlCodecEntitiesTest {
	@Test
	fun plainElementParses() {
		assertEquals("ok", XmlCodec.parse("<root>ok</root>".toByteArray()).rootElement.text)
	}

	@Test
	fun doctypeIsRejected() {
		val xml = """<!DOCTYPE root [<!ENTITY xxe "no">]><root>&xxe;</root>"""
		assertFailsWith<Exception> { XmlCodec.parse(xml.toByteArray()) }
	}
}
