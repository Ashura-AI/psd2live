package io.github.psd2live.project

import kotlin.test.*

class CoalescingPersistenceTest {
    @Test fun pendingSnapshotsAreReplacedWithoutPassingAFlushOrMixingProjects() {
        val queue = ArrayDeque<() -> Unit>()
        val output = mutableListOf<String>()
        val writes = CoalescingPersistence { queue.addLast(it) }
        repeat(1000) { revision -> writes.submit("first") { output += "first:$revision" } }
        writes.submit("second") { output += "second" }
        queue.addLast { output += "flush" }
        assertEquals(3, queue.size)
        while (queue.isNotEmpty()) queue.removeFirst().invoke()
        assertEquals(listOf("first:999", "second", "flush"), output)
    }

    @Test fun aSnapshotArrivingWhileWritingCannotMoveAheadOfAnExistingFlush() {
        val queue = ArrayDeque<() -> Unit>()
        val output = mutableListOf<String>()
        val writes = CoalescingPersistence { queue.addLast(it) }
        writes.submit("first") {
            output += "writing first"
            writes.submit("first") { output += "later snapshot" }
        }
        queue.addLast { output += "flush" }
        while (queue.isNotEmpty()) queue.removeFirst().invoke()
        assertEquals(listOf("writing first", "flush", "later snapshot"), output)
    }

    @Test fun aFailedWriteDoesNotRetainAPoisonedDestination() {
        val queue = ArrayDeque<() -> Unit>()
        val output = mutableListOf<String>()
        val writes = CoalescingPersistence { queue.addLast(it) }
        writes.submit("first") { error("I/O failed") }
        assertFailsWith<IllegalStateException> { queue.removeFirst().invoke() }
        writes.submit("first") { output += "retry" }
        queue.removeFirst().invoke()
        assertEquals(listOf("retry"), output)
    }
}
