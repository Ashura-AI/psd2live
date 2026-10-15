package io.github.psd2live

import io.github.psd2live.agent.AgentMcpConfig
import io.github.psd2live.agent.AgentMcpCredentials
import io.github.psd2live.agent.AgentMcpService
import io.github.psd2live.agent.AgentToolProfile
import io.github.psd2live.agent.DEFAULT_MCP_PORT
import io.github.psd2live.application.WorkspaceOperations
import io.github.psd2live.ui.state.DesktopWorkspace
import io.github.psd2live.ui.state.PSD2LiveViewModel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

/**
 * `psd2live mcp`: the MCP endpoint without a window, for an agent working alone. It serves the same operations as
 * the editor, on its own workspace store (`--store`, default a `psd2live-mcp` folder in the user's home) so it never
 * shares the editor's history or instance lock. Prints the endpoint and token, then serves until stopped.
 */
internal object HeadlessMcp {
	fun run(arguments: List<String>): Int {
		val options = arguments.drop(1).chunked(2).associate { pair ->
			require(pair.size == 2 && pair[0] in setOf("--port", "--token", "--store", "--profile")) { "Usage: mcp [--port N] [--token T] [--store DIR] [--profile core|full]" }
			pair[0] to pair[1]
		}
		val store = Path.of(options["--store"] ?: Path.of(System.getProperty("user.home"), "psd2live-mcp").toString()).toAbsolutePath()
		Files.createDirectories(store)
		val profile = options["--profile"]?.let { requireNotNull(AgentToolProfile.fromKey(it)) { "Unknown profile: $it" } } ?: AgentToolProfile.CORE
		val viewModel = PSD2LiveViewModel()
		val workspace = DesktopWorkspace(viewModel, store)
		viewModel.attachWorkspace(workspace)
		val operations = WorkspaceOperations(workspace)
		val service = AgentMcpService(workspace, AgentMcpConfig(port = options["--port"]?.toInt() ?: DEFAULT_MCP_PORT,
			token = options["--token"] ?: AgentMcpCredentials.generateToken(), profile = profile), operations)
		val connection = service.start()
		println("endpoint ${connection.endpoint}")
		println("token ${connection.token}")
		println("profile ${profile.key}")
		println("store $store")
		System.out.flush()
		val stopped = CountDownLatch(1)
		Runtime.getRuntime().addShutdownHook(Thread({
			runCatching { service.close() }; runCatching { operations.close() }; runCatching { viewModel.close() }
			stopped.countDown()
		}, "psd2live-mcp-shutdown"))
		stopped.await()
		return 0
	}
}
