package io.github.psd2live.application

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class WorkspaceResourceFailureTest {
    @Test fun memoryFailuresAreClassifiedByTypeEvenWhenWrappedOrWithoutAMessage() {
        for (failure in listOf(OutOfMemoryError(), java.util.concurrent.ExecutionException(OutOfMemoryError("Java heap space")))) {
            val classified = WorkspaceFailure.from(failure)
            assertEquals("out_of_memory", classified.code)
            assertTrue(classified.message.contains("Not enough memory"))
        }
        assertEquals("invalid_state", WorkspaceFailure.from(IllegalStateException("Java heap space")).code)
        assertEquals("stack_overflow", WorkspaceFailure.from(StackOverflowError()).code)
        assertFailsWith<IllegalArgumentException> { WorkspaceFailure.from(CancellationException()) }
    }

    @Test fun aResourceFailureTerminatesTheJobAndADurableCommitStillWins() = runBlocking {
        WorkspaceJobs(Dispatchers.Unconfined).use { jobs ->
            val failed = jobs.start("import", null, "initial") { throw OutOfMemoryError() }
            val terminal = jobs.wait(failed.id)
            assertEquals(WorkspaceJobStatus.FAILED, terminal.status)
            assertEquals("out_of_memory", terminal.error!!.code)
            val output = WorkspaceOperationOutput(JsonObject(emptyMap()))
            val committed = jobs.start("edit", "project", "state") {
                currentCoroutineContext()[WorkspaceJobCompletion]!!.committed(output)
                throw OutOfMemoryError()
            }
            assertEquals(WorkspaceJobStatus.COMPLETED, jobs.wait(committed.id).status)
            assertEquals(output, jobs.wait(committed.id).result)
        }
    }
}
