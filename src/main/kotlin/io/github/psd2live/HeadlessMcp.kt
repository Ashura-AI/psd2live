package io.github.psd2live

import io.github.psd2live.agent.AgentMcpConfig
import io.github.psd2live.agent.AgentMcpCredentials
import io.github.psd2live.agent.AgentMcpService
import io.github.psd2live.agent.AgentToolProfile
import io.github.psd2live.agent.DEFAULT_MCP_PORT
import io.github.psd2live.application.HeadlessPresentation
import io.github.psd2live.application.WorkspaceHost
import io.github.psd2live.application.WorkspaceOperations
import io.github.psd2live.core.CubismSdkPreviewSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

/**
 * `psd2live mcp`: the MCP endpoint without a window, for an agent working alone. It serves the same operations as the
 * editor from the same application workspace host ([WorkspaceHost]), shown through a presentation that keeps nothing
 * on screen ([HeadlessPresentation]); no view model, Compose state or window is created. It has its own workspace
 * store (`--store`, default a `psd2live-mcp` folder in the user's home), so it never shares the editor's history or
 * instance lock. Prints the endpoint, the token and what it can do, then serves until stopped.
 */
internal object HeadlessMcp {
	fun run(arguments: List<String>): Int {
		// Views render with AWT images on the CPU; nothing needs a display.
		System.setProperty("java.awt.headless", "true")
		val options = arguments.drop(1).chunked(2).associate { pair ->
			require(pair.size == 2 && pair[0] in setOf("--port", "--token", "--store", "--profile")) { "Usage: mcp [--port N] [--token T] [--store DIR] [--profile core|full]" }
			pair[0] to pair[1]
		}
		val store = Path.of(options["--store"] ?: Path.of(System.getProperty("user.home"), "psd2live-mcp").toString()).toAbsolutePath()
		Files.createDirectories(store)
		val profile = options["--profile"]?.let { requireNotNull(AgentToolProfile.fromKey(it)) { "Unknown profile: $it" } } ?: AgentToolProfile.CORE
		val presentation = HeadlessPresentation()
		val host = WorkspaceHost(presentation, store)
		presentation.attach(host)
		val operations = WorkspaceOperations(host)
		val service = AgentMcpService(host, AgentMcpConfig(port = options["--port"]?.toInt() ?: DEFAULT_MCP_PORT,
			token = options["--token"] ?: AgentMcpCredentials.generateToken(), profile = profile), operations)
		val connection = service.start()
		println("endpoint ${connection.endpoint}")
		println("token ${connection.token}")
		println("profile ${profile.key}")
		println("store $store")
		// Motion sampling runs the Cubism Core, which a build may leave out; every other operation needs nothing more.
		println("capabilities view_sample_motion=${if (CubismSdkPreviewSession.coreAvailable()) "yes" else "no"}")
		System.out.flush()
		val stopped = CountDownLatch(1)
		Runtime.getRuntime().addShutdownHook(Thread({
			runCatching { service.close() }; runCatching { operations.close() }; runCatching { host.close() }; runCatching { presentation.close() }
			stopped.countDown()
		}, "psd2live-mcp-shutdown"))
		stopped.await()
		return 0
	}
}
