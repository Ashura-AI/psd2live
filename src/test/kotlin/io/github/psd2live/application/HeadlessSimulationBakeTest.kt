package io.github.psd2live.application

import kotlinx.coroutines.*
import java.util.Collections
import kotlin.test.*

/** A bake on the headless host reports to the process job running it and stops when that job is cancelled. */
class HeadlessSimulationBakeTest {
    private val presentation = HeadlessPresentation()
    private val work = object : WorkspaceSimulationWork {
        override fun <T> run(id: String, action: ((Float) -> Unit, () -> Boolean) -> T): T = presentation.simulationBake(id, action)
    }

    @Test fun aHeadlessBakeReportsJobProgress() = runBlocking {
        val reported = Collections.synchronizedList(mutableListOf<Pair<Float, String>>())
        val result = withContext(WorkspaceJobContext { fraction, message -> reported += fraction to message }) {
            work.cancellable(currentCoroutineContext()).run("Hair") { progress, _ -> progress(0.5f); progress(1f); "baked" }
        }
        assertEquals("baked", result)
        assertEquals(listOf(0.1f + 0.75f * 0.5f to "Baking simulation Hair", 0.85f to "Baking simulation Hair"), reported)
    }

    @Test fun cancellingTheJobStopsAHeadlessBake() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default + WorkspaceJobContext { _, _ -> }) {
            work.cancellable(coroutineContext).run("Hair") { progress, _ ->
                while (true) { progress(0.25f); started.complete(Unit); Thread.sleep(1) }
            }
        }
        started.await()
        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }
}
