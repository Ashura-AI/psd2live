package io.github.psd2live.project

import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WorkspaceProjectResourcesTest {
    @TempDir lateinit var root: Path

    @Test fun orphanReclamationPreservesRecoveryAndLiveOwnersAndNeverTouchesUnregisteredDirectories() {
        val orphan = Files.createTempDirectory("psd2live-project-")
        val recovery = Files.createTempDirectory("psd2live-project-")
        val live = Files.createTempDirectory("psd2live-project-")
        val unregistered = Files.createTempDirectory("psd2live-project-")
        val registry = WorkspaceProjectResources(root)
        try {
            registry.adopt(live)
            val file = root.resolve("project-resources.json")
            val existing = ProjectArchive.readJson(file).getValue("directories").jsonArray
            fun dead(path: Path) = buildJsonObject { put("path", path.toString()); put("owner", "-1|expired") }
            ProjectArchive.writeJson(file, buildJsonObject {
                put("version", 1); put("directories", JsonArray(existing + listOf(dead(orphan), dead(recovery))))
            })
            registry.reclaimOrphans(recovery.resolve("workspace"))
            assertFalse(Files.exists(orphan))
            assertTrue(Files.isDirectory(recovery))
            assertTrue(Files.isDirectory(live))
            assertTrue(Files.isDirectory(unregistered))
            assertFailsWith<IllegalArgumentException> { registry.release(root) }
        } finally {
            listOf(orphan, recovery, live, unregistered).forEach(ProjectArchive::deleteTemporaryDirectory)
        }
    }
}
