package io.github.psd2live.ui

import io.github.psd2live.application.WorkspaceCheckTimeout
import io.github.psd2live.ui.state.MeshSplitCheck
import kotlinx.coroutines.*
import kotlin.test.*

class MeshSplitCheckTest {
    @Test fun memoryFailureClearsLoadingReportsTheCauseAndAllowsRetry() = runBlocking {
        val check = MeshSplitCheck(this)
        val failure = CompletableDeferred<Throwable>()
        var finished = 0
        check.start({ throw OutOfMemoryError() }, { fail("Unexpected completion") }, { failure.complete(it) }, { finished++ })
        assertIs<OutOfMemoryError>(withTimeout(5000) { failure.await() })
        yield()
        assertFalse(check.running)
        assertEquals(1, finished)
        val result = CompletableDeferred<Int>()
        check.start({ 7 }, { result.complete(it) }, { fail("Retry failed") })
        assertEquals(7, withTimeout(5000) { result.await() })
    }

    @Test fun dismissalInterruptsPixelWorkAndDoesNotReportCancellationAsFailure() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val check = MeshSplitCheck(scope)
            val entered = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            check.start({
                withContext(Dispatchers.Default) {
                    entered.complete(Unit)
                    try { while (true) currentCoroutineContext().ensureActive() }
                    finally { stopped.complete(Unit) }
                }
            }, { fail("Cancelled check completed") }, { fail("Cancellation was reported as failure") })
            withTimeout(5000) { entered.await() }
            check.cancel()
            assertFalse(check.running)
            withTimeout(5000) { stopped.await() }
        } finally { scope.cancel() }
    }

    @Test fun aLateOldResultCannotClearOrPublishIntoANewerCheck() = runBlocking {
        val check = MeshSplitCheck(this)
        val entered = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val releaseNew = CompletableDeferred<Unit>()
        val newest = CompletableDeferred<Int>()
        check.start({
            withContext(NonCancellable) { entered.complete(Unit); releaseOld.await() }; 1
        }, { fail("Old result published") }, { fail("Old failure published") }, { fail("Old cleanup published") })
        entered.await()
        check.cancel()
        check.start({ releaseNew.await(); 2 }, { newest.complete(it) }, { fail("New check failed") })
        releaseOld.complete(Unit)
        yield()
        assertTrue(check.running)
        releaseNew.complete(Unit)
        assertEquals(2, withTimeout(5000) { newest.await() })
    }

    @Test fun aCheckDeadlineReportsTimeoutAndReleasesLoading() = runBlocking {
        val check = MeshSplitCheck(this, timeoutMillis = 20)
        val failure = CompletableDeferred<Throwable>()
        check.start({ awaitCancellation() }, { fail("Unexpected completion") }, { failure.complete(it) })
        assertIs<WorkspaceCheckTimeout>(withTimeout(5000) { failure.await() })
        yield()
        assertFalse(check.running)
    }

}
