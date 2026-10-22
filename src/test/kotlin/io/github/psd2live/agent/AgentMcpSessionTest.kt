package io.github.psd2live.agent

import kotlin.test.*

class AgentMcpSessionTest {

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

}
