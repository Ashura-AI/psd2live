package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.interior.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.edit.MeshManualOps
import org.umamo.edit.MeshTopology
import org.umamo.format.art.*
import org.umamo.runtime.model.DrawableMesh
import kotlin.math.*
import kotlin.test.*

/** Hand placement through the document command the canvas and MCP share: replay, history, project restore and interior meshes. */
class ManualMeshCommandTest {
	private val size = 160

	/** One opaque rounded panel with a soft vertical gradient, so the interior effect has texture to register. */
	private fun document(): WorkspaceDocument {
		val rgba = ByteArray(size * size * 4)
		for (y in 0 until size) for (x in 0 until size) {
			if (hypot(max(0.0, abs(x - 80.0) - 45), max(0.0, abs(y - 80.0) - 45)) >= 14) continue
			val shade = (200 - y / 2 - 30 * sin(x / 9.0)).roundToInt().coerceIn(0, 255)
			val o = (y * size + x) * 4
			rgba[o] = shade.toByte(); rgba[o + 1] = (shade - 20).toByte(); rgba[o + 2] = (shade - 40).toByte(); rgba[o + 3] = -1
		}
		val layer = WorkspaceSourceLayer(LayerId("panel"), "panel", "", SourceLayerKind.Raster, true, 0, LayerBounds(0, 0, size, size), 1f,
			false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(size, size, rgba), null, null, false)
		return WorkspaceDocument(WorkspaceSourceArt(size, size, listOf(layer), emptyList()), emptyMap(), emptySet(),
			mapOf("panel" to LayerClassificationOverride(type = LayerType.PRESET, tag = SemanticTag.OBJECTS, side = Side.NONE)), emptyMap(),
			RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(PipelineConfig(atlasSize = 512, generatePhysics = false, exportMoc3 = false)))
	}

	private fun op(name: String, request: JsonObject) = WorkspaceDocumentOperation(name, request)
	private fun mesh(model: RigPreviewModel): DrawableMesh = target(model).mesh!!
	private fun target(model: RigPreviewModel) = model.rig.puppet.drawables.single { it.mesh != null && model.rig.layerIdByDrawableId[it.id.raw] == "panel" }

	/** The centroid of the triangle [ordinal] in the mesh's own space: always inside the mesh. */
	private fun inside(mesh: DrawableMesh, ordinal: Int): Pair<Float, Float> {
		val corners = (0..2).map { mesh.indices[ordinal * 3 + it] }
		return corners.sumOf { mesh.positions[it * 2].toDouble() }.toFloat() / 3f to corners.sumOf { mesh.positions[it * 2 + 1].toDouble() }.toFloat() / 3f
	}

	private fun addPoints(id: String, vararg points: Pair<Float, Float>) = op("canvas_topology", buildJsonObject {
		put("id", id); put("action", "add_points"); put("vertices", JsonArray(emptyList()))
		put("anchors", buildJsonArray { points.forEach { (x, y) -> add(buildJsonObject { put("x", x); put("y", y) }) } })
	})

	@Test
	fun pointsEdgesAndRetriangulationReplayFromTheJournal(): Unit = runBlocking {
		val document = document()
		val builder = WorkspacePreviewBuilder()
		val model = builder.build(document)
		val id = target(model).id.raw
		val before = mesh(model)
		val added = WorkspaceDocumentEdits.apply(addPoints(id, inside(before, 0), inside(before, before.triangleCount / 2)), document, model)
		assertEquals(listOf("canvas_topology"), added.rigEdits.authoringJournal.map { it.getValue("op").jsonPrimitive.content })
		val addedModel = builder.build(added)
		assertEquals(before.vertexCount + 2, mesh(addedModel).vertexCount)
		assertTrue(MeshManualOps.isLegal(mesh(addedModel)))
		// An inner edge of the edited mesh, deleted: it flips, so the vertices stay and the triangulation changes.
		val edges = MeshTopology.edgeUses(mesh(addedModel).indices).filterValues { it.size == 2 }.keys
		val edge = edges.first { MeshManualOps.deleteEdges(mesh(addedModel), setOf(it)) != null }
		val deleted = WorkspaceDocumentEdits.apply(op("canvas_topology", buildJsonObject {
			put("id", id); put("action", "delete_edges"); put("vertices", JsonArray(emptyList()))
			put("edges", buildJsonArray { add(buildJsonArray { add(edge.endpointLow); add(edge.endpointHigh) }) })
		}), added, addedModel)
		val deletedModel = builder.build(deleted)
		assertEquals(mesh(addedModel).vertexCount, mesh(deletedModel).vertexCount)
		assertFalse(edge in MeshTopology.uniqueEdges(mesh(deletedModel).indices))
		// A fresh build of the saved document replays the same mesh.
		val again = mesh(WorkspacePreviewBuilder().build(deleted))
		assertContentEquals(mesh(deletedModel).positions, again.positions)
		assertContentEquals(mesh(deletedModel).indices, again.indices)
		// A command the mesh cannot take fails rather than writing a no-op.
		assertFails {
			WorkspaceDocumentEdits.apply(op("canvas_topology", buildJsonObject {
				put("id", id); put("action", "add_points"); put("vertices", JsonArray(emptyList()))
				put("anchors", buildJsonArray { add(buildJsonObject { put("x", before.positions[0]); put("y", before.positions[1]) }) })
			}), document, model)
		}
	}

	@Test
	fun aPointAddedToAnInteriorMeshBakesTheEffectAgain(): Unit = runBlocking {
		val document = document()
		val builder = WorkspacePreviewBuilder()
		val base = builder.build(document)
		val id = target(base).id.raw
		val withEffect = WorkspaceDocumentEdits.apply(op("interior_put", buildJsonObject {
			put("target", id); put("motion", InteriorMotionSettings(enabled = false).toJson())
		}), document, base)
		val model = builder.build(withEffect)
		val stamp = withEffect.rigEdits.interiorEffects.single().meshStamp
		val after = WorkspaceDocumentEdits.apply(addPoints(id, inside(mesh(model), 0)), withEffect, model)
		val effect = after.rigEdits.interiorEffects.single()
		assertNotEquals(stamp, effect.meshStamp)
		val rebuilt = builder.build(after)
		assertNull(effect.issue(rebuilt.authored.rig.puppet))
		assertEquals(mesh(model).vertexCount + 1, mesh(rebuilt).vertexCount)
	}
}
