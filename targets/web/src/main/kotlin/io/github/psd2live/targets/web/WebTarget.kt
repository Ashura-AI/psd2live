package io.github.psd2live.targets.web

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.runtime.P2lrt

/**
 * A folder that plays the rig in a browser: `index.html`, the player script `p2l.js`, the PSD2Live runtime
 * as WebAssembly and the compiled `.p2lrt`. Served over http, it draws with WebGL (every color and alpha blend
 * mode, masks and isolated groups), plays clips, blinks, breathes, follows the pointer and opens the mouth with a
 * slider. Setting `title` names the page.
 */
public object WebTarget : ExportTarget {
	override val id: String = "web"
	override val family: TargetFamily = TargetFamily.RIG
	override val description: String = "Web player (HTML, WebAssembly runtime, WebGL)"
	override val capabilities: CapabilityProfile = CapabilityProfile(
		warpLattice = true, parameterGrid = 8, blendShapes = true, timeline = true, physics = PhysicsSupport.PARAMETER_PENDULUM,
		blendModes = ColorBlend.entries.toSet(),
		masks = MaskSupport.TEXTURE_ALPHA, keyedDrawOrder = true, glue = true, hitAreas = true,
	)

	private fun resource(name: String): ByteArray =
		checkNotNull(WebTarget::class.java.getResourceAsStream(name)) { "The web player is missing $name" }.use { it.readBytes() }

	private fun html(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

	/** A JavaScript string literal body. `<` is escaped so the value cannot close the surrounding script. */
	private fun js(text: String) = buildString {
		for (ch in text) when (ch) {
			'\\' -> append("\\\\")
			'"' -> append("\\\"")
			'\n' -> append("\\n")
			'\r' -> append("\\r")
			'<' -> append("\\u003c")
			else -> append(ch)
		}
	}

	override fun plan(ir: RigIR, options: ExportOptions): LoweredExport {
		val rig = P2lrt.write(ir)
		val rigName = "${options.baseName}.p2lrt"
		val page = resource("index.html").decodeToString()
			.replace("{{TITLE}}", html(options.settings["title"] ?: options.baseName))
			// The file is written under its real name. The script asks for the encoded name, so a space,
			// ampersand or non-ASCII character still resolves when the page is opened.
			.replace("{{RIG}}", js(java.net.URLEncoder.encode(rigName, Charsets.UTF_8).replace("+", "%20")))
		return object : LoweredExport {
			override val losses: List<LossEntry> = CapabilityScan.scan(ir, capabilities, options)
			override fun write(sink: OutputSink) {
				sink.write("index.html", page.encodeToByteArray())
				sink.write("p2l.js", resource("p2l.js"))
				sink.write("p2l_runtime.wasm", resource("p2l_runtime.wasm"))
				sink.write(rigName, rig)
			}
		}
	}
}
