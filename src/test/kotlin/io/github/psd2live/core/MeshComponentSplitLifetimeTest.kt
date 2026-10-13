package io.github.psd2live.core

import io.github.psd2live.project.WorkspaceSourceLayer
import kotlinx.coroutines.*
import org.umamo.format.art.*
import org.umamo.runtime.model.DrawableMesh
import kotlin.test.*

class MeshComponentSplitLifetimeTest {
    private fun source(width: Int, height: Int, rgba: ByteArray) = WorkspaceSourceLayer(
        LayerId("layer"), "Layer", "", SourceLayerKind.Raster, true, 0, LayerBounds(10, 20, width, height),
        1f, false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(width, height, rgba), null, null, false)

    @Test fun manySeparatedIslandsKeepPixelBoundariesAndPiecesOutliveTheScan() = runBlocking {
        val rgba = ByteArray(8 * 2 * 4) { offset -> if (offset % 4 == 3) 255.toByte() else (offset / 4 % 8).toByte() }
        val art = source(8, 2, rgba)
        val vertices = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 3f, 0f, 4f, 0f, 3f, 1f, 6f, 0f, 7f, 0f, 6f, 1f)
        val mesh = DrawableMesh(vertices, vertices.copyOf(), IntArray(9) { it })
        val scan = async {
            val context = currentCoroutineContext()
            MeshComponentSplit.detect(mesh, art, vertices) { context.ensureActive() }!!
        }
        val plan = scan.await()
        scan.join()
        assertFalse(scan.isActive)
        val pieces = plan.pieces(listOf("One", "Two", "Three"))
        assertEquals(listOf(LayerBounds(10, 20, 3, 2), LayerBounds(13, 20, 3, 2), LayerBounds(16, 20, 2, 2)), pieces.map { it.bounds })
        pieces.forEach { piece ->
            for (y in 0 until piece.raster.height) for (x in 0 until piece.raster.width) {
                val local = (y * piece.raster.width + x) * 4
                val original = (y * 8 + x + piece.bounds.left - 10) * 4
                assertContentEquals(rgba.copyOfRange(original, original + 4), piece.raster.rgba.copyOfRange(local, local + 4))
            }
        }
    }

    @Test fun overlappingProjectionsStillAssignBothTriangleIslandsTheirSourcePixels() {
        val rgba = ByteArray(4 * 4 * 4)
        rgba[3] = 255.toByte()
        rgba[15 * 4 + 3] = 255.toByte()
        val art = source(4, 4, rgba)
        val vertices = floatArrayOf(0f, 0f, 4f, 0f, 0f, 4f, 4f, 4f, 4f, 1f, 1f, 4f)
        val mesh = DrawableMesh(vertices, vertices.copyOf(), IntArray(6) { it })
        val plan = MeshComponentSplit.detect(mesh, art, vertices)!!
        val pieces = plan.pieces(listOf("One", "Two"))
        assertEquals(listOf(LayerBounds(10, 20, 1, 1), LayerBounds(13, 23, 1, 1)), pieces.map { it.bounds })
        assertTrue(pieces.all { it.raster.rgba[3] == 255.toByte() })
    }
}
