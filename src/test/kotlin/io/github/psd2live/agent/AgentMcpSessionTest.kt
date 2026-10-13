package io.github.psd2live.agent

import io.github.psd2live.application.WorkspaceBackend
import io.github.psd2live.application.WorkspaceOperations
import io.github.psd2live.ui.state.agentSessionLogEntry
import io.github.psd2live.ui.state.agentSessionLogEntries
import io.github.psd2live.ui.state.LogLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.lang.reflect.Proxy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class AgentMcpSessionTest {
    @Test fun httpLifecycleDistinguishesSameNamedSessionsRepeatedNotificationsAndServerShutdown() {
        val events = CopyOnWriteArrayList<AgentSessionEvent>()
        val observer = object : AgentCallObserver {
            override fun onCall(record: AgentCallRecord) = Unit
            override fun onSessionEvent(event: AgentSessionEvent) { events.add(event) }
        }
        // Handshake and discovery need no model; any unexpected workspace access fails the test.
        val workspace = Proxy.newProxyInstance(WorkspaceBackend::class.java.classLoader, arrayOf(WorkspaceBackend::class.java)) { _, method, _ ->
            error("Unexpected workspace access: ${method.name}")
        } as WorkspaceBackend
        WorkspaceOperations(workspace).use { operations ->
            AgentMcpService(workspace, AgentMcpConfig(port = 0, token = AgentMcpCredentials.generateToken()), operations, observer).use { service ->
                val connection = service.start()
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().use { client ->
                    fun request(method: String, body: String? = null, session: String? = null, version: String = "2025-03-26"): HttpResponse<String> {
                        val builder = HttpRequest.newBuilder(URI(connection.endpoint)).timeout(Duration.ofSeconds(5))
                            .header("Authorization", "Bearer ${connection.token}")
                            .header("Accept", "application/json, text/event-stream")
                            .header("MCP-Protocol-Version", version)
                        if (session != null) builder.header("Mcp-Session-Id", session)
                        if (body != null) builder.header("Content-Type", "application/json")
                        return client.send(builder.method(method, body?.let(HttpRequest.BodyPublishers::ofString)
                            ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
                    }
                    fun initialize(): String {
                        val response = request("POST", """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"diagnostic-test","version":"1"}}}""")
                        assertEquals(200, response.statusCode(), response.body())
                        assertNotNull(Json.parseToJsonElement(response.body()).jsonObject["result"])
                        val session = response.headers().firstValue("mcp-session-id").orElseThrow()
                        assertEquals(202, request("POST", """{"jsonrpc":"2.0","method":"notifications/initialized"}""", session).statusCode())
                        return session
                    }
                    fun awaitEvents(count: Int) = runBlocking {
                        withTimeout(5_000) { while (events.size < count) delay(5) }
                    }

                    val first = initialize()
                    awaitEvents(1)
                    val second = initialize()
                    awaitEvents(2)
                    assertNotEquals(first, second)
                    assertEquals(listOf(1, 2), events.map { it.activeSessions })
                    assertEquals(setOf("diagnostic-test 1"), events.map { it.client }.toSet())
                    assertTrue(events[0].firstClientConnection)
                    assertFalse(events[1].firstClientConnection)

                    assertEquals(202, request("POST", """{"jsonrpc":"2.0","method":"notifications/initialized"}""", first).statusCode())
                    awaitEvents(3)
                    assertEquals(AgentSessionEventKind.INITIALIZED_REPEATED, events.last().kind)
                    assertEquals(2, events.last().initializationCount)
                    assertEquals(2, events.last().activeSessions)
                    assertEquals(200, request("POST", """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", first).statusCode())
                    assertEquals(3, events.size, "An ordinary request must not log another connection")

                    assertEquals(200, request("DELETE", session = first).statusCode())
                    awaitEvents(5)
                    assertEquals(AgentSessionEventKind.DELETE_REQUESTED, events[3].kind)
                    assertEquals(first, events[4].sessionId)
                    assertEquals(AgentSessionCloseReason.CLIENT_DELETE, events[4].closeReason)
                    assertEquals(1, events[4].activeSessions, "The other same-named session is still active")
                    assertEquals(404, request("DELETE", session = first).statusCode())
                    assertEquals(5, events.size, "Repeated DELETE must not close a session twice")

                    assertEquals(400, request("DELETE", session = second, version = "invalid").statusCode())
                    awaitEvents(6)
                    assertEquals(AgentSessionEventKind.DELETE_REQUESTED, events.last().kind)
                    assertEquals(200, request("POST", """{"jsonrpc":"2.0","id":3,"method":"tools/list"}""", second).statusCode())
                    service.close()
                    awaitEvents(7)
                    assertEquals(second, events.last().sessionId)
                    assertEquals(AgentSessionCloseReason.SERVER_STOP, events.last().closeReason)
                    assertEquals(0, events.last().activeSessions)
                    assertTrue(events.all { it.lifetimeMs >= 0 })
                    val logs = events.flatMap(::agentSessionLogEntries)
                    assertEquals(1, logs.count { it.level == LogLevel.SUCCESS })
                    assertEquals(events.size, logs.count { it.level == LogLevel.DEBUG })
                    assertFalse(logs.single { it.level == LogLevel.SUCCESS }.message.contains(first))
                    val message = agentSessionLogEntry(events[4]).message
                    assertTrue(first in message, message)
                    assertTrue("DELETE" in message, message)
                    assertFalse(connection.token in message)
                }
            }
        }
    }

    @Test fun rejectedDeleteAndObserverFailuresDoNotMislabelLaterTransportClosure() {
        var now = 0L
        val events = mutableListOf<AgentSessionEvent>()
        val diagnostics = AgentSessionDiagnostics(object : AgentCallObserver {
            override fun onCall(record: AgentCallRecord) = Unit
            override fun onSessionEvent(event: AgentSessionEvent) {
                events.add(event)
                error("Diagnostic sink unavailable")
            }
        }) { now }
        diagnostics.opened("session")
        diagnostics.initialized("session", "client")
        diagnostics.deleteRequested("session")
        diagnostics.deleteFinished("session")
        now = 9_000_000_000
        diagnostics.closed("session")
        diagnostics.closed("session")
        assertEquals(3, events.size)
        assertEquals(AgentSessionCloseReason.TRANSPORT_CLOSED, events.last().closeReason)
        assertEquals(9_000L, events.last().lifetimeMs)
        assertEquals(0, events.last().activeSessions)
    }

    @Test fun temporaryReconnectsStayInDebugAndAnotherClientStillGetsAnAnnouncement() {
        val events = mutableListOf<AgentSessionEvent>()
        val observer = object : AgentCallObserver {
            override fun onCall(record: AgentCallRecord) = Unit
            override fun onSessionEvent(event: AgentSessionEvent) { events.add(event) }
        }
        val diagnostics = AgentSessionDiagnostics(observer)
        repeat(20) { index ->
            val id = "temporary-$index"
            diagnostics.opened(id)
            diagnostics.initialized(id, "client A 1")
            diagnostics.initialized(id, "client A 1")
            diagnostics.deleteRequested(id)
            diagnostics.closed(id)
        }
        val logs = events.flatMap(::agentSessionLogEntries)
        assertEquals(1, logs.count { it.level.severity >= LogLevel.INFO.severity })
        assertEquals(events.size, logs.count { it.level == LogLevel.DEBUG })

        diagnostics.opened("other-client")
        diagnostics.initialized("other-client", "client B 1")
        assertTrue(events.last().firstClientConnection)
        assertEquals(LogLevel.SUCCESS, agentSessionLogEntries(events.last()).first().level)

        diagnostics.serverStopped()
        val restarted = AgentSessionDiagnostics(observer)
        restarted.opened("restarted")
        restarted.initialized("restarted", "client A 1")
        assertTrue(events.last().firstClientConnection, "A restarted endpoint must announce the client again")
    }
}
