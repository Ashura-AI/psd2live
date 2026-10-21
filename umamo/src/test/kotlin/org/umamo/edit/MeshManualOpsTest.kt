package org.umamo.edit

import org.umamo.runtime.model.DrawableMesh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeshManualOpsTest {
	/** A 3x3 grid, 10 apart, every cell split along its down-right diagonal; vertex r * 3 + c, UV = position / 20. */
	private fun grid(): DrawableMesh {
		val positions = FloatArray(18) { if (it % 2 == 0) (it / 2 % 3) * 10f else (it / 2 / 3) * 10f }
		val indices = ArrayList<Int>()
		for (r in 0..1) for (c in 0..1) {
			val a = r * 3 + c
			indices += listOf(a, a + 1, a + 4, a, a + 4, a + 3)
		}
		return DrawableMesh(positions, FloatArray(18) { positions[it] / 20f }, indices.toIntArray())
	}

	private fun edges(mesh: DrawableMesh) = MeshTopology.uniqueEdges(mesh.indices).toSet()

	/** Every vertex samples the texel its position maps to, so the picture has not moved. */
	private fun assertAffineUv(mesh: DrawableMesh) {
		for (i in mesh.positions.indices) assertEquals(mesh.positions[i] / 20f, mesh.uvs[i], 1e-5f, "uv $i")
	}

	@Test fun aPointInsideAFaceSplitsItAndKeepsThePicture() {
		val result = assertNotNull(MeshManualOps.addPoints(grid(), listOf(6f to 3f)))
		val mesh = result.edit.newMesh
		assertEquals(10, mesh.vertexCount)
		assertEquals(10, mesh.triangleCount)
		assertTrue(MeshManualOps.isLegal(mesh))
		assertEquals(6f, mesh.positions[18], 1e-5f); assertEquals(3f, mesh.positions[19], 1e-5f)
		assertAffineUv(mesh)
		assertEquals(setOf<MeshElement>(MeshElement.Vertex(9)), result.newElements)
		// The kept vertices keep their index and the new one derives from the old ones by weights summing to one.
		(0 until 9).forEach { assertEquals(VertexSource.FromOld(it), result.edit.vertexSources[it]) }
		val weights = when (val source = result.edit.vertexSources[9]) {
			is VertexSource.WeightedOf -> source.weights.sum()
			is VertexSource.BarycentricOf -> source.wa + source.wb + source.wc
			else -> error("unexpected $source")
		}
		assertEquals(1f, weights, 1e-5f)
	}

	@Test fun aPointOnAnEdgeSplitsTheEdgeAndAPointOutsideGrowsTheOutline() {
		val onEdge = assertNotNull(MeshManualOps.addPoints(grid(), listOf(5f to 0f))).edit.newMesh
		assertEquals(10, onEdge.vertexCount)
		assertEquals(9, onEdge.triangleCount)
		assertFalse(MeshElement.Edge.of(0, 1) in edges(onEdge))
		assertTrue(MeshManualOps.isLegal(onEdge))
		val outside = assertNotNull(MeshManualOps.addPoints(grid(), listOf(26f to 5f))).edit.newMesh
		assertEquals(9, outside.triangleCount)
		assertTrue(9 in MeshManualOps.outlineVertices(outside.indices))
		assertTrue(MeshManualOps.isLegal(outside))
		assertAffineUv(outside)
	}

	@Test fun aStrokeOfPointsIsOneEditAndPointsOnVerticesAreSkipped() {
		assertNull(MeshManualOps.addPoints(grid(), listOf(10f to 10f)))
		val result = assertNotNull(MeshManualOps.addPoints(grid(), listOf(3f to 6f, 10f to 10f, 13f to 16f, 13f to 16f)))
		assertEquals(11, result.edit.newMesh.vertexCount)
		assertEquals(setOf<MeshElement>(MeshElement.Vertex(9), MeshElement.Vertex(10)), result.newElements)
		assertTrue(MeshManualOps.isLegal(result.edit.newMesh))
	}

	@Test fun deletingAnInnerEdgeFlipsItAcrossItsQuad() {
		val mesh = assertNotNull(MeshManualOps.deleteEdges(grid(), setOf(MeshElement.Edge.of(0, 4)))).edit.newMesh
		assertEquals(9, mesh.vertexCount)
		assertEquals(8, mesh.triangleCount)
		assertFalse(MeshElement.Edge.of(0, 4) in edges(mesh))
		assertTrue(MeshElement.Edge.of(1, 3) in edges(mesh))
		assertTrue(MeshManualOps.isLegal(mesh))
	}

	@Test fun anInnerEdgeOfAConcaveQuadIsNotDeleted() {
		// A dart: vertex 3 sits inside the triangle of the other three, so the quad's other diagonal runs outside it.
		val positions = floatArrayOf(0f, 0f, 5f, 8f, 10f, 0f, 5f, 3f)
		val dart = DrawableMesh(positions, positions.copyOf(), intArrayOf(0, 3, 1, 3, 2, 1))
		assertNull(MeshManualOps.deleteEdges(dart, setOf(MeshElement.Edge.of(1, 3))))
	}

	@Test fun deletingAnOutlineEdgeRemovesItsTriangleAndRefusesAPinch() {
		val result = assertNotNull(MeshManualOps.deleteEdges(grid(), setOf(MeshElement.Edge.of(0, 1))))
		assertEquals(7, result.edit.newMesh.triangleCount)
		assertEquals(9, result.edit.newMesh.vertexCount)
		assertTrue(MeshManualOps.isLegal(result.edit.newMesh))
		// Removing the corner's other triangle too leaves vertex 0 unused, and it goes with them.
		val corner = assertNotNull(MeshManualOps.deleteEdges(grid(), setOf(MeshElement.Edge.of(0, 1), MeshElement.Edge.of(0, 3))))
		assertEquals(8, corner.edit.newMesh.vertexCount)
		assertEquals((1 until 9).map { VertexSource.FromOld(it) }, corner.edit.vertexSources)
		// After the top-left triangle goes, the centre is on the outline; taking the bottom-right one too would leave
		// it joining two fans by one point.
		assertNull(MeshManualOps.deleteEdges(grid(), setOf(MeshElement.Edge.of(0, 1), MeshElement.Edge.of(7, 8))))
		assertNull(MeshManualOps.deleteEdges(grid(), setOf(MeshElement.Edge.of(0, 8))))
	}

	@Test fun retriangulatingFlipsSliversAndStopsWhenThereIsNothingLeft() {
		val positions = floatArrayOf(0f, 0f, 10f, -2f, 20f, 0f, 10f, 2f)
		val rhombus = DrawableMesh(positions, FloatArray(8) { positions[it] / 20f }, intArrayOf(0, 1, 2, 0, 2, 3))
		val tidy = assertNotNull(MeshManualOps.retriangulate(rhombus, emptySet())).edit.newMesh
		assertTrue(MeshElement.Edge.of(1, 3) in edges(tidy))
		assertFalse(MeshElement.Edge.of(0, 2) in edges(tidy))
		assertNull(MeshManualOps.retriangulate(tidy, emptySet()))
	}

	@Test fun legalityAndTheOutline() {
		assertTrue(MeshManualOps.isLegal(grid()))
		assertEquals((0 until 9).toSet() - 4, MeshManualOps.outlineVertices(grid().indices))
		val unused = grid().let { DrawableMesh(it.positions + floatArrayOf(40f, 40f), it.uvs + floatArrayOf(2f, 2f), it.indices) }
		assertFalse(MeshManualOps.isLegal(unused))
		val tripled = grid().let { DrawableMesh(it.positions, it.uvs, it.indices + intArrayOf(0, 4, 2)) }
		assertFalse(MeshManualOps.isLegal(tripled))
	}
}
