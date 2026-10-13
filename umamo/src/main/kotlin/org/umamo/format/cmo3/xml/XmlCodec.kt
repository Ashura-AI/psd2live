package org.umamo.format.cmo3.xml

import org.jdom.Document
import org.jdom.input.SAXBuilder
import org.jdom.output.Format
import org.jdom.output.XMLOutputter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Parses and emits the serialized model XML (main.xml) using the editor's exact JDOM backend.
 *
 * Matches the editor's writer: XMLOutputter + Format.getPrettyFormat() with indent "" and
 * TextMode TRIM (line separator "\r\n", UTF-8). Using JDOM 1.1.3 with the same Format reproduces
 * main.xml byte-for-byte across the whole corpus; this class centralises that contract so every
 * emit path (generic round-trip and the typed model) shares one formatter.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Payload: main.xml</a>
 */
public object XmlCodec {
	/**
	 * The editor's output format: pretty layout, empty indent, TRIM text mode.
	 *
	 * @return Format A fresh Format instance (XMLOutputter mutates none of ours).
	 */
	private fun editorFormat(): Format =
		Format.getPrettyFormat().apply {
			setIndent("")
			textMode = Format.TextMode.TRIM // Normalize would collapse spaces in user intended input so use trim instead.
		}

	/**
	 * Parses model XML bytes into a JDOM document, preserving element/attribute/PI order.
	 *
	 * @param ByteArray bytes The decompressed main.xml bytes.
	 * @return Document The parsed JDOM document.
	 */
	public fun parse(bytes: ByteArray): Document =
		saxBuilder().build(ByteArrayInputStream(bytes))

	/**
	 * A fresh builder that refuses DOCTYPE declarations and external entities. JDOM 1.1.3's default
	 * SAXBuilder follows the JDK parser, which allows both, so a CMO3 main.xml could otherwise read
	 * local files while it is imported.
	 */
	private fun saxBuilder(): SAXBuilder = SAXBuilder().apply {
		setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
		setFeature("http://xml.org/sax/features/external-general-entities", false)
		setFeature("http://xml.org/sax/features/external-parameter-entities", false)
		setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
	}

	/**
	 * Serializes a JDOM document back to bytes using the editor's exact format.
	 *
	 * @param Document document The document to emit.
	 * @return ByteArray The UTF-8 encoded XML, byte-compatible with the editor.
	 */
	public fun write(document: Document): ByteArray {
		val sink = ByteArrayOutputStream()
		XMLOutputter(editorFormat()).output(document, sink)
		return sink.toByteArray()
	}
}