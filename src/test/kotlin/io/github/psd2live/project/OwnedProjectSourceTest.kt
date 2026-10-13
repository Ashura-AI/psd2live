package io.github.psd2live.project

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class OwnedProjectSourceTest {
    @Test fun aRelativeStoreRootStillProducesAnAbsoluteOwnedImportAndKeepsItsOriginalBytes() {
        val working = Path.of("").toAbsolutePath()
        val temporary = Files.createTempDirectory(Files.createDirectories(working.resolve("build/tmp")), "owned-source-")
        try {
            val input = temporary.resolve("input.psd")
            val bytes = byteArrayOf(1, 2, 3)
            Files.write(input, bytes)
            val owned = OwnedProjectSource.capture(working.relativize(temporary.resolve("store")), input)
            assertTrue(owned.path.isAbsolute)
            Files.write(input, byteArrayOf(4))
            Files.delete(input)
            assertContentEquals(bytes, Files.readAllBytes(owned.path))
            assertEquals(owned.path, OwnedProjectSource.resolve(temporary.resolve("store"), owned.auxiliary))
        } finally { temporary.toFile().deleteRecursively() }
    }
}
