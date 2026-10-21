package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import kotlin.test.*

/** The object edits through the document commands the GUI and MCP share: paste, mesh duplication, find and replace, ID conversion. */
class ObjectEditsCommandTest {
	private val size = 96

	private fun layer(id: String, left: Int, top: Int, width: Int, height: Int, order: Int): WorkspaceSourceLayer {
		val rgba = ByteArray(width * height * 4) { if (it % 4 == 3) -1 else 90 }
		return WorkspaceSourceLayer(LayerId(id), id, "", SourceLayerKind.Raster, true, order, LayerBounds(left, top, width, height), 1f,
			false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(width, height, rgba), null, null, false)
	}

	private fun document(): WorkspaceDocument {
		val layers = listOf(layer("EyeL", 10, 20, 30, 24, 0), layer("EyeClosedL", 10, 20, 30, 24, 1), layer("Body", 30, 50, 40, 40, 2))
		return WorkspaceDocument(WorkspaceSourceArt(size, size, layers, emptyList()), emptyMap(), emptySet(),
			layers.associate { it.id.raw to LayerClassificationOverride(type = LayerType.PRESET, tag = SemanticTag.OBJECTS, side = Side.NONE) }, emptyMap(),
			RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(PipelineConfig(atlasSize = 512, generatePhysics = false, exportMoc3 = false)))
	}

	private fun op(name: String, request: JsonObject) = WorkspaceDocumentOperation(name, request)
	private fun mesh(model: RigPreviewModel, layer: String) = model.rig.puppet.drawables.first { model.rig.layerIdByDrawableId[it.id.raw] == layer && it.mesh != null }

	@Test
	fun pasteDuplicateRenameAndConvertReplayFromTheDocument() = runBlocking {
		val builder = WorkspacePreviewBuilder()
		var document = document()
		var model = builder.build(document)
		val eye = mesh(model, "EyeL"); val closed = mesh(model, "EyeClosedL")
		suspend fun apply(name: String, request: JsonObject) {
			document = WorkspaceDocumentEdits.apply(op(name, request), document, model)
			model = builder.build(document)
		}

		// Paste: the copy gets the ID fixed in advance and shows the original's layer.
		val ids = RigObjectPaste.plan(model.rig.puppet, buildJsonObject { putJsonArray("sources") { add("mesh:${eye.id.raw}") } }).ids
		apply(WorkspaceObjectEdits.PASTE, buildJsonObject { putJsonArray("sources") { add("mesh:${eye.id.raw}") } })
		val copyId = ids.getValue("mesh:${eye.id.raw}")
		assertEquals("${eye.id.raw}_copy", copyId)
		assertEquals("EyeL", model.rig.layerIdByDrawableId[copyId])
		assertContentEquals(eye.mesh!!.positions, model.rig.puppet.drawables.single { it.id.raw == copyId }.mesh!!.positions)

		// Mesh duplication: the closed eye takes the open eye's topology.
		apply(WorkspaceObjectEdits.MESH_DUPLICATE, buildJsonObject { put("source", "mesh:${eye.id.raw}"); putJsonArray("targets") { add("mesh:${closed.id.raw}") } })
		val duplicated = model.rig.puppet.drawables.single { it.id == closed.id }.mesh!!
		assertContentEquals(eye.mesh!!.indices, duplicated.indices)
		for (i in duplicated.positions.indices) assertEquals(eye.mesh!!.positions[i], duplicated.positions[i], 1e-3f)

		// Find and replace: names swap sides, the copy and both eyes alike.
		val found = WorkspaceObjectEdits.find(model.rig.puppet, buildJsonObject { putJsonArray("kinds") { add("mesh") }; put("swap_sides", true) })
		assertTrue(found.isNotEmpty() && found.all { it.after.endsWith("R") })
		apply(WorkspaceObjectEdits.RENAME, buildJsonObject { putJsonArray("kinds") { add("mesh") }; put("swap_sides", true) })
		assertEquals("EyeR", model.rig.puppet.drawables.single { it.id == eye.id }.name)

		// ID conversion: the motion curve and the user's physics group follow the parameter, the layer the mesh.
		apply("parameter_create", buildJsonObject { put("parameter_id", "ParamBlink"); put("name", "Blink"); put("min", 0); put("max", 1); put("default", 1) })
		document = document.copy(rigEdits = document.rigEdits.copy(
			motionClips = listOf(MotionClip("m1", "Blink", curves = listOf(MotionCurve("ParamBlink", listOf(MotionKey(0f, 1f), MotionKey(1f, 0f)))))),
			physicsEdits = listOf(RigPhysicsEdit("custom", "Custom", inputs = listOf(PhysicsInput("ParamBlink")), outputs = listOf(PhysicsOutput("ParamBlink2"))))))
		model = builder.build(document)
		apply(WorkspaceObjectEdits.CONVERT_ID, buildJsonObject { putJsonArray("renames") {
			add(buildJsonObject { put("target", "parameter:ParamBlink"); put("id", "ParamEyeBlink") })
			add(buildJsonObject { put("target", "mesh:$copyId"); put("id", "ArtMeshEyeCopy") })
		} })
		assertTrue(model.rig.puppet.parameters.any { it.id.raw == "ParamEyeBlink" } && model.rig.puppet.parameters.none { it.id.raw == "ParamBlink" })
		assertEquals("ParamEyeBlink", document.rigEdits.motionClips.single().curves.single().parameterId)
		assertEquals("ParamEyeBlink", document.rigEdits.physicsEdits.single().inputs.single().parameter)
		assertEquals("EyeL", model.rig.layerIdByDrawableId["ArtMeshEyeCopy"])
		// A taken ID is refused before anything is written.
		assertFailsWith<IllegalArgumentException> {
			WorkspaceDocumentEdits.apply(op(WorkspaceObjectEdits.CONVERT_ID, buildJsonObject { putJsonArray("renames") {
				add(buildJsonObject { put("target", "mesh:ArtMeshEyeCopy"); put("id", eye.id.raw) })
			} }), document, model)
		}
		// The saved document rebuilds to the same rig.
		val rebuilt = WorkspacePreviewBuilder().build(document).rig.puppet
		assertEquals(model.rig.puppet.drawables.map { it.id to it.name }, rebuilt.drawables.map { it.id to it.name })
		assertEquals(model.rig.puppet.parameters.map { it.id }, rebuilt.parameters.map { it.id })
	}

	@Test
	fun sidesSwapOnlyWhereTheyAreWordsOfTheirOwn() {
		assertEquals("ArmR", WorkspaceObjectEdits.swapSides("ArmL"))
		assertEquals("Hair_R_01", WorkspaceObjectEdits.swapSides("Hair_L_01"))
		assertEquals("ParamEyeROpen", WorkspaceObjectEdits.swapSides("ParamEyeLOpen"))
		assertEquals("RightArm", WorkspaceObjectEdits.swapSides("LeftArm"))
		assertEquals("右目", WorkspaceObjectEdits.swapSides("左目"))
		assertEquals("Lip", WorkspaceObjectEdits.swapSides("Lip"))
		assertEquals("Cleft", WorkspaceObjectEdits.swapSides("Cleft"))
		assertEquals("eye_r", WorkspaceObjectEdits.swapSides("eye_l"))
	}
}
