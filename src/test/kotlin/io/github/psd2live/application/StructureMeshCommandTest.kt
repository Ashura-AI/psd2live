package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.interior.*
import io.github.psd2live.core.mesh.structure.StructureKind
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import kotlin.math.*
import kotlin.test.*

/** A cloth panel with one drawn crease, remeshed through the document command as the GUI and MCP do. */
class StructureMeshCommandTest {
	private val size = 200

	private fun document(): WorkspaceDocument {
		val rgba = ByteArray(size * size * 4)
		val path = (0..40).map { i -> val t = i / 40.0; (55 + 90 * t) to (60 + 70 * t - 25 * sin(PI * t)) }
		for (y in 0 until size) for (x in 0 until size) {
			if (hypot(max(0.0, abs(x - 100.0) - 60), max(0.0, abs(y - 100.0) - 55)) >= 18) continue
			var best = Double.POSITIVE_INFINITY; var at = 0.0
			for (k in 0 until path.size - 1) {
				val (ax, ay) = path[k]; val (bx, by) = path[k + 1]
				val dx = bx - ax; val dy = by - ay
				val t = (((x + .5 - ax) * dx + (y + .5 - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
				val d = hypot(x + .5 - (ax + dx * t), y + .5 - (ay + dy * t))
				if (d < best) { best = d; at = (k + t) / (path.size - 1) }
			}
			val ink = ((.4 + 1.6 * sin(PI * at)) + .5 - best).coerceIn(0.0, 1.0) * sin(PI * at).pow(.5)
			val shade = (216 - 142 * ink).roundToInt()
			val o = (y * size + x) * 4
			rgba[o] = shade.toByte(); rgba[o + 1] = (shade - 16).toByte(); rgba[o + 2] = (shade - 32).toByte(); rgba[o + 3] = -1
		}
		val layer = WorkspaceSourceLayer(LayerId("cloth"), "cloth", "", SourceLayerKind.Raster, true, 0, LayerBounds(0, 0, size, size), 1f,
			false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(size, size, rgba), null, null, false)
		return WorkspaceDocument(WorkspaceSourceArt(size, size, listOf(layer), emptyList()), emptyMap(), emptySet(),
			mapOf("cloth" to LayerClassificationOverride(type = LayerType.PRESET, tag = SemanticTag.OBJECTS, side = Side.NONE)), emptyMap(),
			RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(PipelineConfig(atlasSize = 512, generatePhysics = false, exportMoc3 = false)))
	}

	private fun op(name: String, request: JsonObject) = WorkspaceDocumentOperation(name, request)
	private fun target(model: RigPreviewModel) = model.rig.puppet.drawables.single { it.mesh != null && model.rig.layerIdByDrawableId[it.id.raw] == "cloth" }

	@Test
	fun theRemeshIsOneRebuildRecordThatReplaysToTheSameMesh() = runBlocking {
		val document = document()
		val builder = WorkspacePreviewBuilder()
		val model = builder.build(document)
		val drawable = target(model)
		val request = buildJsonObject { put("target", "mesh:${drawable.id.raw}") }
		val plan = WorkspaceStructureMeshEdits.plan(model, document.rigEdits, drawable.id.raw, WorkspaceStructureMeshEdits.settings(request))
		assertTrue(plan.mesh.lines.any { it.line.kind == StructureKind.FOLD }, "${plan.analysis.lines.map { it.kind }}")
		val after = WorkspaceDocumentEdits.apply(op(WorkspaceStructureMeshEdits.OP, request), document, model)
		val added = after.rigEdits.authoringJournal.drop(document.rigEdits.authoringJournal.size)
		assertEquals(listOf(RasterMeshJournal.OP), added.map { it.getValue("op").jsonPrimitive.content })
		val rebuilt = target(builder.build(after)).mesh!!
		assertEquals(plan.replacement.vertexCount, rebuilt.vertexCount)
		assertContentEquals(plan.replacement.indices, rebuilt.indices)
		for (i in rebuilt.positions.indices) assertEquals(plan.replacement.positions[i], rebuilt.positions[i], 1e-3f)
		// The analysis does not run again on replay: a fresh build of the saved document is the same mesh.
		val again = target(WorkspacePreviewBuilder().build(after)).mesh!!
		assertContentEquals(rebuilt.positions, again.positions)
		assertContentEquals(rebuilt.indices, again.indices)
	}

	@Test
	fun anInteriorEffectKeepsItsFoldsTakesDetectedLinesAndBakesOnTheNewMesh() = runBlocking {
		val document = document()
		val model = WorkspacePreviewBuilder().build(document)
		val drawable = target(model)
		val fold = buildJsonObject {
			put("id", "fold0"); put("purpose", "FOLD")
			put("points", buildJsonArray { add(buildJsonArray { add(100); add(150) }); add(buildJsonArray { add(150); add(150) }) })
		}
		val withEffect = WorkspaceDocumentEdits.apply(op("interior_put", buildJsonObject {
			put("target", drawable.id.raw); put("motion", InteriorMotionSettings(enabled = false).toJson())
			put("guides", buildJsonArray { add(fold) })
		}), document, model)
		val effectModel = WorkspacePreviewBuilder().build(withEffect)
		// The batch the command makes: remesh, then bake the effect on the candidate the remesh built.
		val remeshed = WorkspaceDocumentEdits.apply(op(WorkspaceStructureMeshEdits.OP, buildJsonObject { put("target", drawable.id.raw) }),
			withEffect, effectModel)
		val stale = remeshed.rigEdits.interiorEffects.single()
		val remeshModel = WorkspacePreviewBuilder().build(remeshed)
		assertNotNull(stale.issue(remeshModel.authored.rig.puppet), "the old bake must not apply to the new mesh")
		val baked = WorkspaceDocumentEdits.apply(op("interior_put", buildJsonObject { put("target", drawable.id.raw) }), remeshed, remeshModel)
		val effect = baked.rigEdits.interiorEffects.single()
		val finalModel = WorkspacePreviewBuilder().build(baked)
		assertNull(effect.issue(finalModel.authored.rig.puppet))
		assertEquals(InteriorMeshStamp.of(target(finalModel).mesh!!), effect.meshStamp)
		assertEquals("fold0", effect.guides.first().id)
		assertEquals(InteriorGuidePurpose.FOLD, effect.guides.first().purpose)
		val detected = effect.guides.filter { it.detected != null }
		assertTrue(detected.isNotEmpty())
		assertTrue(detected.all { it.purpose == InteriorGuidePurpose.TOPOLOGY && it.id.startsWith("line") })
		assertTrue(detected.any { it.detected == StructureKind.FOLD })
		// Detected kinds survive the recipe's JSON, and drawn guides write no field for it.
		val copy = NativeInteriorEffect.fromJson(Json.parseToJsonElement(effect.toJson().toString()).jsonObject)
		assertEquals(effect.guides, copy.guides)
		assertFalse("detected" in effect.toJson().toString().substringAfter("\"fold0\"").substringBefore("}"))
		// A second remesh replaces detected lines and keeps the authored fold.
		val twice = WorkspaceStructureMeshEdits.guides(effect.guides, emptyList())
		assertEquals(listOf("fold0"), twice.map { it.id })
	}

	@Test
	fun aRemeshThatCannotFitTheLimitChangesNothing(): Unit = runBlocking {
		val document = document()
		val model = WorkspacePreviewBuilder().build(document)
		val drawable = target(model)
		assertFails {
			WorkspaceDocumentEdits.apply(op(WorkspaceStructureMeshEdits.OP, buildJsonObject {
				put("target", drawable.id.raw); put("settings", buildJsonObject { put("max_vertices", 16) })
			}), document, model)
		}
		assertFailsWith<IllegalArgumentException> { WorkspaceStructureMeshEdits.settings(buildJsonObject {
			put("settings", buildJsonObject { put("density", 1) }) }) }
	}
}
