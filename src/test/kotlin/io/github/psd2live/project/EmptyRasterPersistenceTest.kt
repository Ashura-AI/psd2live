package io.github.psd2live.project

import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.history.WorkspaceHistoryTree
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import org.umamo.format.psd.PsdReader
import org.umamo.format.psd.PsdWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.test.*

class EmptyRasterPersistenceTest {
    @TempDir lateinit var directory: Path

    private fun artwork(): WorkspaceSourceArt {
        val sizes = listOf(4 to 4, 0 to 0, 0 to 4, 4 to 0)
        return WorkspaceSourceArt(4, 4, sizes.mapIndexed { index, (width, height) ->
            WorkspaceSourceLayer(LayerId("lyid:${index + 1}"), "Layer $index", "", SourceLayerKind.Raster,
                true, index, LayerBounds(0, 0, width, height), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
                LayerRaster(width, height, ByteArray(width * height * 4) { 255.toByte() }), null, null, false)
        }, emptyList())
    }

    @Test fun importedEmptyLayersSurviveHistoryArchiveReopenAndPsdExport() = runBlocking {
        val psd = directory.resolve("source.psd")
        Files.write(psd, PsdWriter.write(artwork()))
        val source = PsdReader.read(Files.readAllBytes(psd))
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), RigEditOverlay.Empty,
            generationSource = source, meshSource = source)
        val revision = WorkspaceRevisions.of(document)
        val history = WorkspaceHistoryTree(document, revision, revision)
        val edited = document.copy(layerVisibility = mapOf("lyid:1" to false))
        val editedRevision = WorkspaceRevisions.of(edited)
        history.commit(history.head().node.id, edited, editedRevision, editedRevision, "Hide layer", "user")
        val store = WorkspaceStore(directory.resolve("store"))
        store.persistHistory("project", history.state())
        val reloaded = WorkspaceStore(store.root).loadHistory("project")!!
        assertEquals(history.state().headNodeId, reloaded.state().headNodeId)
        assertEquals(2, reloaded.state().selections.size)
        assertEquals(editedRevision, WorkspaceRevisions.of(reloaded.head().snapshot))
        val repository = ProjectRepository(writeHeadCache = false)
        val archive = directory.resolve("empty-layers.psd2live")
        repository.save(ProjectSaveCapture("project", history.state(), JsonObject(emptyMap()), psd, store), archive)
        repository.open(archive).use { opened ->
            assertEquals(editedRevision, WorkspaceRevisions.of(opened.history.head().snapshot))
            for (selection in opened.history.state().selections) {
                for (restored in listOf(selection.snapshot.source, selection.snapshot.generationSource!!, selection.snapshot.meshSource!!)) {
                    assertEquals(source.layers.map { it.raster.width to it.raster.height }, restored.layers.map { it.raster.width to it.raster.height })
                    source.layers.zip(restored.layers).forEach { (before, after) ->
                        assertEquals(before.id, after.id)
                        assertContentEquals(before.raster.rgba, after.raster.rgba)
                    }
                }
            }
            val exported = PsdReader.read(PsdWriter.write(opened.history.head().snapshot.source))
            assertEquals(source.layers.map { it.raster.width to it.raster.height }, exported.layers.map { it.raster.width to it.raster.height })
        }
    }

    @Test fun emptyBlobsAreStillVerifiedRatherThanSilentlySynthesized() {
        val document = WorkspaceDocument(artwork(), emptyMap(), emptySet(), emptyMap(), emptyMap(), RigEditOverlay.Empty)
        val revision = WorkspaceRevisions.of(document)
        val store = WorkspaceStore(directory.resolve("store"))
        store.persistHistory("project", WorkspaceHistoryTree(document, revision, revision).state())
        val blob = Files.walk(store.root).use { files -> files.filter { it.toString().endsWith(".rgba.gz") }.findFirst().orElseThrow() }
        GZIPOutputStream(Files.newOutputStream(blob)).use { it.write(byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { WorkspaceStore(store.root).loadHistory("project") }
    }
}
