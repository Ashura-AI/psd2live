package io.github.psd2live.ui

import io.github.psd2live.ui.state.PreviewFrameRecovery
import kotlin.test.*

class PreviewFrameRecoveryTest {
    @Test fun repeatedFailuresBackOffButNewInputsAndRecoveryResetTheEpisode() {
        val recovery = PreviewFrameRecovery()
        val model = Any()
        val error = IllegalStateException("Failed preview")
        assertTrue(recovery.shouldAttempt(model, "first", 0))
        assertTrue(recovery.failed(error, 0))
        assertFalse(recovery.shouldAttempt(model, "first", 249_999_999))
        assertTrue(recovery.shouldAttempt(model, "first", 250_000_000))
        assertFalse(recovery.failed(error, 250_000_000))
        assertFalse(recovery.shouldAttempt(model, "first", 749_999_999))
        assertTrue(recovery.shouldAttempt(model, "changed pose", 300_000_000))
        assertTrue(recovery.failed(error, 300_000_000))
        assertTrue(recovery.shouldAttempt(Any(), "changed pose", 300_000_001))
        assertTrue(recovery.failed(error, 300_000_001))
        recovery.succeeded()
        assertTrue(recovery.shouldAttempt(model, "first", 300_000_002))
        assertTrue(recovery.failed(error, 300_000_002))
    }
}
