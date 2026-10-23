package io.github.psd2live.scenario

import io.github.psd2live.agent.AgentToolProfile
import io.github.psd2live.agent.createAgentMcpServer
import io.github.psd2live.application.HeadlessPresentation
import io.github.psd2live.application.WorkspaceBackend
import io.github.psd2live.application.WorkspaceHost
import io.github.psd2live.application.WorkspaceOperations
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.runtime.model.ParameterId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * What an agent writes it can read back through MCP: a parameter's repeat flag, the editor data that names a converted
 * parameter (saved poses, the authored pose, timeline bookmarks - forwards on the conversion and back on its undo) and
 * the mesh topology canvas_topology names by index, with the vertices add_points creates.
 */
class AgentReadBackScenario {
	@TempDir lateinit var temp: Path

	private val client = java.lang.reflect.Proxy.newProxyInstance(ClientConnection::class.java.classLoader, arrayOf(ClientConnection::class.java)) { _, method, _ ->
		if (method.name == "getSessionId") "test" else null
	} as ClientConnection

	private class Agent(val host: WorkspaceBackend, val server: Server)

	private suspend fun Agent.call(operation: String, request: JsonObject): JsonObject {
		val result = server.tools.getValue("workspace_call").handler.invoke(client,
			CallToolRequest(CallToolRequestParams("workspace_call", buildJsonObject { put("operation", operation); put("request", request) })))
		val content = result.structuredContent ?: error("No structured result from $operation")
		if (content["ok"]?.jsonPrimitive?.boolean != true) fail("$operation failed: $content")
		return content["data"]?.jsonObject?.let { it["result"]?.jsonObject ?: it } ?: fail("$operation gave no data: $content")
	}

	private suspend fun Agent.state(): String = call("workspace_inspect", req("scope" to "project")).getValue("state").jsonPrimitive.content
	private suspend fun Agent.edit(operation: String, vararg fields: Pair<String, Any?>) = call(operation, req("state" to state(), *fields))

	private fun agent(test: suspend Agent.() -> Unit) = runBlocking {
		HeadlessPresentation().use { presentation ->
			WorkspaceHost(presentation, temp.resolve("store")).use { host ->
				presentation.attach(host)
				WorkspaceOperations(host).use { operations ->
					val agent = Agent(host, createAgentMcpServer(host, operations, AgentToolProfile.CORE))
					agent.edit("project_create", "width" to 64, "height" to 64, "discard_unsaved" to true)
					agent.edit("material_import", "paths" to listOf(Characters.disc(Files.createDirectories(temp.resolve("files")), size = 32).toString()))
					val material = agent.call("material_list", req()).getValue("items").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content
					agent.edit("layer_place", "material_id" to material, "layer_id" to "disc")
					agent.test()
				}
			}
		}
	}

	@Test fun aRepeatingParameterReadsBackAsRepeating() = agent {
		edit("parameter_create", "parameter_id" to "ParamWheel", "name" to "Wheel", "min" to 0, "max" to 360, "default" to 0, "repeat" to true)
		val listed = call("workspace_inspect", req("scope" to "parameters", "query" to "ParamWheel")).getValue("items").jsonArray
		assertEquals(true, listed.single { it.jsonObject["id"]?.jsonPrimitive?.content == "ParamWheel" }.jsonObject["repeat"]?.jsonPrimitive?.boolean)
		val overview = call("workspace_overview", req()).getValue("parameters").jsonObject.getValue("items").jsonArray
		assertEquals(true, overview.single { it.jsonObject["id"]?.jsonPrimitive?.content == "ParamWheel" }.jsonObject["repeat"]?.jsonPrimitive?.boolean)
		edit("parameter_update", "parameter_id" to "ParamWheel", "repeat" to false)
		assertFalse(host.captureQueries().snapshot().parameters.single { it.id == "ParamWheel" }.repeat)
	}

	@Test fun editorDataFollowsAConvertedParameterAndItsUndo() = agent {
		val created = edit("parameter_create", "parameter_id" to "ParamWheel", "name" to "Wheel", "min" to 0, "max" to 360, "default" to 0)
		val node = created.getValue("history_node_id").jsonPrimitive.content
		edit("snapshot_create", "name" to "Turned", "values" to mapOf("ParamWheel" to 90))
		edit("preview_set", "values" to mapOf("ParamWheel" to 45))
		edit("timeline_bookmark_set", "parameter" to "ParamWheel", "bookmarked" to true)

		edit("object_convert_id", "renames" to listOf(mapOf("target" to "parameter:ParamWheel", "id" to "ParamSpin")))
		fun saved() = host.savedProjectData().data
		assertEquals(mapOf(ParameterId("ParamSpin") to 90f), saved().parameterSnapshots.single().values, "the saved pose kept the old ID")
		assertEquals(listOf("ParamSpin"), saved().timelineAids.bookmarks, "the bookmark kept the old ID")
		suspend fun authored(id: String): Float? = call("workspace_inspect", req("scope" to "preview")).getValue("preview").jsonObject
			.getValue("values").jsonObject[id]?.jsonPrimitive?.float
		assertEquals(45f, authored("ParamSpin"), "the authored pose lost the converted parameter's value")

		edit("history_checkout", "node_id" to node)
		assertEquals(mapOf(ParameterId("ParamWheel") to 90f), saved().parameterSnapshots.single().values, "undoing the conversion left the saved pose on the new ID")
		assertEquals(listOf("ParamWheel"), saved().timelineAids.bookmarks)
		assertEquals(45f, authored("ParamWheel"), "undoing the conversion lost the authored value")
	}

	@Test fun anAgentReadsTheIndicesItAddsPointsBy() = agent {
		val puppet = assertNotNull(host.captureQueries().currentPuppet())
		val drawable = puppet.drawables.first { it.mesh != null }
		val target = "mesh:${drawable.id.raw}"
		val before = call("mesh_get_topology", req("target" to target, "section" to "triangles"))
		val count = before.getValue("vertex_count").jsonPrimitive.int
		assertEquals(drawable.mesh!!.triangleCount, before.getValue("items").jsonArray.size)
		val corners = before.getValue("items").jsonArray.first().jsonArray.drop(1).map { it.jsonPrimitive.int }
		val vertices = call("mesh_get_topology", req("target" to target, "limit" to 4096)).getValue("items").jsonArray.associate { item ->
			item.jsonArray[0].jsonPrimitive.int to (item.jsonArray[1].jsonPrimitive.float to item.jsonArray[2].jsonPrimitive.float)
		}
		val x = corners.map { vertices.getValue(it).first }.average().toFloat()
		val y = corners.map { vertices.getValue(it).second }.average().toFloat()
		val outline = call("mesh_get_topology", req("target" to target, "section" to "outline"))
		assertTrue(outline.getValue("outline_edge_count").jsonPrimitive.int > 0)

		val added = edit("canvas_topology", "id" to drawable.id.raw, "action" to "add_points", "vertices" to emptyList<Int>(),
			"anchors" to listOf(mapOf("x" to x, "y" to y)))
		val made = added.getValue("new_vertices").jsonArray.map { it.jsonPrimitive.int }
		assertEquals(1, made.size, "add_points reported $made")
		val after = call("mesh_get_topology", req("target" to target, "offset" to made.single(), "limit" to 1))
		assertEquals(count + 1, after.getValue("vertex_count").jsonPrimitive.int)
		val point = after.getValue("items").jsonArray.single().jsonArray
		assertEquals(x, point[1].jsonPrimitive.float, 1e-3f); assertEquals(y, point[2].jsonPrimitive.float, 1e-3f)
	}
}
