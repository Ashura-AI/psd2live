package io.github.psd2live.ui

import androidx.compose.ui.window.WindowExceptionHandler
import io.github.psd2live.ui.state.UiFailureReports
import io.github.psd2live.ui.state.reportingWindowExceptionHandler
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class UiFailureReportsTest {
    @TempDir lateinit var directory: Path

    @Test fun recordsTheFullCauseChainBeforeTheOriginalWindowHandlerRuns() {
        val report = UiFailureReports(directory.resolve("logs/ui-error.log"))
        val cause = IllegalArgumentException("layouts are not part of the same hierarchy")
        val failure = IllegalStateException("UI operation failed", cause)
        val handler = reportingWindowExceptionHandler(WindowExceptionHandler { received ->
            assertSame(failure, received)
            val saved = Files.readString(report.path)
            assertTrue(saved.contains(cause.message!!))
            assertTrue(saved.contains("Caused by:"))
            assertTrue(saved.contains("UiFailureReportsTest"))
            throw received
        }) { report.record(it, "compose-window") }
        assertSame(failure, assertFailsWith<IllegalStateException> { handler.onException(failure) })
    }

    @Test fun aFailedDiagnosticWriteStillRunsTheOriginalHandlerWithTheSameException() {
        val blockedParent = directory.resolve("not-a-directory")
        Files.writeString(blockedParent, "file")
        val report = UiFailureReports(blockedParent.resolve("ui-error.log"))
        val failure = IllegalArgumentException("layouts are not part of the same hierarchy")
        var called = false
        val handler = reportingWindowExceptionHandler(WindowExceptionHandler { received ->
            called = true
            assertSame(failure, received)
            throw received
        }) { report.record(it, "compose-window") }
        assertSame(failure, assertFailsWith<IllegalArgumentException> { handler.onException(failure) })
        assertTrue(called)
    }
}
