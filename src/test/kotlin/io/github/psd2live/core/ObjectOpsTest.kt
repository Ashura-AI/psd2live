package io.github.psd2live.core

import io.github.psd2live.history.WorkspaceHistoryTree
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Copy and paste of objects, mesh duplication and ID conversion as journal entries. */
class ObjectOpsTest {
	@TempDir lateinit var temp: Path

	private val p = ParameterId("p")

	/** A rotation holding a child rotation and two meshes, one masking the other, glued, with a path and a vertex group. */
	private fun fixture(): PuppetModel {
		val parent = Deformer.Rotation(DeformerId("parent"), "Parent", null, null, 0f,
			KeyformGrid(listOf(KeyformAxis(p, floatArrayOf(0f, 1f))),
				listOf(KeyformCell(intArrayOf(0), RotationPivotForm(32f, 32f, 0f, 1f)), KeyformCell(intArrayOf(1), RotationPivotForm(32f, 32f, 30f, 1f)))))
		val child = Deformer.Rotation(DeformerId("child"), "Child", parent.id, null, 0f,
			KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), RotationPivotForm(4f, 2f, 10f, 1f)))))
		val mesh = DrawableMesh(floatArrayOf(0f, 0f, 8f, 0f, 0f, 8f, 8f, 8f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), intArrayOf(0, 1, 2, 1, 3, 2))
		val keyed = KeyformGrid(listOf(KeyformAxis(p, floatArrayOf(0f, 1f))),
			listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(8))), KeyformCell(intArrayOf(1), MeshDeltaForm(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 2f)))))
		val a = Drawable(DrawableId("a"), "ArmL", parent.id, BlendMode.Normal, emptyList(), mesh, keyed, texturePage = 0)
		val b = Drawable(DrawableId("b"), "HandL", child.id, BlendMode.Normal, listOf(a.id), mesh, null, texturePage = 0)
		val other = Drawable(DrawableId("c"), "Body", null, BlendMode.Normal, emptyList(), mesh, null, texturePage = 0)
		val part = Part(PartId("P"), "Arm", listOf(OrgChild.Drawable(a.id), OrgChild.Drawable(b.id)))
		return PuppetModel(listOf(Parameter(p, "P", 0f, 1f, 0f)), listOf(part), listOf(parent, child), listOf(a, b, other),
			listOf(OrgChild.Part(part.id), OrgChild.Drawable(other.id)), null,
			glues = listOf(Glue(a.id, b.id, listOf(GluePair(1, 0, 0.5f, 0.5f)))),
			parameterTree = listOf(ParameterNode.Group(ParameterGroupId("G"), "Group", true, listOf(ParameterNode.Param(p)))),
			canvasWidth = 64f, canvasHeight = 64f,
			deformPaths = listOf(DeformPath("path", a.id, listOf(DeformPathPoint(0, 1, 2, 1f, 0f, 0f), DeformPathPoint(1, 3, 2, 0f, 1f, 0f)))),
			vertexGroups = listOf(VertexGroup("tip", a.id, VertexGroupKind.PIN, floatArrayOf(0f, 0f, 1f, 1f)))).withDerivedRenderRoot()
	}

	private fun paste(vararg sources: String, extra: JsonObject.() -> JsonObject = { this }) = buildJsonObject {
		put("op", RigObjectPaste.OP); putJsonArray("sources") { sources.forEach { add(it) } }
	}.extra()

	private fun world(model: PuppetModel, value: Float) = CpuDeformationEvaluator().evaluate(model, mapOf(p to value)).worldPositions

	@Test fun pasteCopiesADeformerWithItsSubtreeAndPointsReferencesAtTheCopies() {
		val model = fixture()
		val (pasted, journal) = RigAuthoringJournal.compile(model, JsonArray(listOf(paste("rotation:parent"))))
		assertEquals(listOf(RigObjectPaste.OP), journal.map { it["op"]!!.jsonPrimitive.content })
		// The same request on the same rig fixes the same IDs.
		assertEquals(journal, RigAuthoringJournal.compile(model, JsonArray(listOf(paste("rotation:parent")))).second)
		assertEquals(listOf("parent", "parent_copy", "child", "child_copy"), pasted.deformers.map { it.id.raw })
		assertEquals(listOf("a", "a_copy", "b", "b_copy", "c"), pasted.drawables.map { it.id.raw })
		val childCopy = pasted.deformers.single { it.id.raw == "child_copy" }
		assertEquals("parent_copy", childCopy.parent?.raw)
		val aCopy = pasted.drawables.single { it.id.raw == "a_copy" }
		val bCopy = pasted.drawables.single { it.id.raw == "b_copy" }
		assertEquals("parent_copy", aCopy.parentDeformerId?.raw)
		assertEquals(listOf("a_copy"), bCopy.maskedBy.map { it.raw })
		assertEquals("ArmL", aCopy.name)
		assertEquals(listOf("a", "a_copy", "b", "b_copy"), pasted.parts.single().children.map { (it as OrgChild.Drawable).id.raw })
		assertTrue(pasted.glues.any { it.meshA.raw == "a_copy" && it.meshB.raw == "b_copy" })
		assertEquals(listOf("path", "path_copy"), pasted.deformPaths.map { it.id })
		assertEquals("a_copy", pasted.deformPaths.last().drawableId.raw)
		assertEquals(listOf("a", "a_copy"), pasted.vertexGroups.map { it.drawableId.raw })
		// Copies sit where their originals do, at every pose.
		for (value in listOf(0f, 1f)) {
			val positions = world(pasted, value)
			for (id in listOf("a", "b")) assertTrue(positions.getValue(DrawableId(id)).contentEquals(positions.getValue(DrawableId("${id}_copy"))), "$id at $value")
		}
	}

	@Test fun pasteFixesRequestedIdsAndPlacesTheRoots() {
		val model = fixture()
		val request = paste("mesh:a", "parameter:p") {
			JsonObject(this + mapOf("parent" to JsonPrimitive("root"), "part" to JsonPrimitive("root"),
				"ids" to buildJsonObject { put("mesh:a", "ArmCopy") }))
		}
		val (pasted, journal) = RigAuthoringJournal.compile(model, JsonArray(listOf(request)))
		assertEquals(listOf(RigObjectPaste.OP, "structure"), journal.map { it["op"]!!.jsonPrimitive.content })
		val copy = pasted.drawables.single { it.id.raw == "ArmCopy" }
		assertEquals(null, copy.parentDeformerId)
		assertTrue(OrgChild.Drawable(copy.id) in pasted.rootChildren)
		// Bound to the root on the canvas, the copy still shows where the original does at rest (the original's glue aside).
		val rest = world(pasted.copy(glues = emptyList()), 0f)
		val original = rest.getValue(DrawableId("a")); val moved = rest.getValue(copy.id)
		for (i in original.indices) assertEquals(original[i], moved[i], 1e-3f)
		// A copied parameter is a new axis with the original's range, beside it in its folder.
		assertEquals(listOf("p", "p_copy"), pasted.parameters.map { it.id.raw })
		assertEquals(listOf("p", "p_copy"), (pasted.parameterTree.single() as ParameterNode.Group).children.map { (it as ParameterNode.Param).id.raw })
		assertFailsWith<IllegalArgumentException> {
			RigAuthoringJournal.compile(model, JsonArray(listOf(paste("mesh:a") { JsonObject(this + ("ids" to buildJsonObject { put("mesh:a", "b") })) })))
		}
	}

	@Test fun convertIdRewritesEveryReferenceAndRefusesTakenIds() {
		val model = fixture()
		val renames = listOf(RigIdConversion.Rename("parameter", "p", "ParamArm"), RigIdConversion.Rename("mesh", "a", "ArtMeshArm"),
			RigIdConversion.Rename("rotation", "parent", "RotationArm"), RigIdConversion.Rename("part", "P", "PartArm"),
			RigIdConversion.Rename("param_group", "G", "GroupArm"))
		val converted = RigAuthoringJournal.compile(model, JsonArray(listOf(RigIdConversion.encode(renames)))).first
		assertEquals(listOf("ParamArm"), converted.parameters.map { it.id.raw })
		val arm = converted.drawables.single { it.id.raw == "ArtMeshArm" }
		assertEquals("RotationArm", arm.parentDeformerId?.raw)
		assertEquals("ParamArm", arm.geometryGrid!!.axes.single().parameterId.raw)
		assertEquals("RotationArm", converted.deformers.single { it.id.raw == "child" }.parent?.raw)
		assertEquals(listOf("ArtMeshArm"), converted.drawables.single { it.id.raw == "b" }.maskedBy.map { it.raw })
		assertEquals("ArtMeshArm", converted.glues.single().meshA.raw)
		assertEquals("ArtMeshArm", converted.deformPaths.single().drawableId.raw)
		assertEquals("ArtMeshArm", converted.vertexGroups.single().drawableId.raw)
		assertEquals(listOf(OrgChild.Part(PartId("PartArm")), OrgChild.Drawable(DrawableId("c"))), converted.rootChildren)
		val group = converted.parameterTree.single() as ParameterNode.Group
		assertEquals("GroupArm", group.id.raw); assertEquals(ParameterNode.Param(ParameterId("ParamArm")), group.children.single())
		for (value in listOf(0f, 1f)) {
			val before = world(model, value); val after = CpuDeformationEvaluator().evaluate(converted, mapOf(ParameterId("ParamArm") to value)).worldPositions
			assertTrue(before.getValue(DrawableId("a")).contentEquals(after.getValue(DrawableId("ArtMeshArm"))))
		}
		val taken = assertFailsWith<IllegalArgumentException> {
			RigIdConversion.apply(model, RigIdConversion.encode(listOf(RigIdConversion.Rename("mesh", "a", "b"))))
		}
		assertTrue("already exists" in taken.message.orEmpty())
		// Warps and rotations share one namespace; IDs keep Cubism's alphabet.
		assertTrue(RigIdConversion.problem(model, listOf(RigIdConversion.Rename("rotation", "child", "parent"))) != null)
		assertTrue(RigIdConversion.problem(model, listOf(RigIdConversion.Rename("mesh", "a", "1bad"))) != null)
	}

	@Test fun conversionsComposeAndForwardWhatReplaysAfterTheJournal() {
		val override = buildJsonObject {
			put("op", GeneratedOverrides.OP); put("generator", "swing:s"); put("target", "warp:W")
			putJsonObject("key") { put("p", 1f) }; putJsonArray("base") {}; putJsonArray("points") {}
		}
		val panel = buildJsonObject { put("op", "structure"); putJsonArray("edits") { add(buildJsonObject {
			put("action", "move"); put("kind", "parameter"); put("id", "Swing1"); put("before_id", "p"); put("before_kind", "parameter")
		}) } }
		val first = RigIdConversion.encode(listOf(RigIdConversion.Rename("parameter", "p", "q"), RigIdConversion.Rename("warp", "W", "W2")))
		val second = RigIdConversion.encode(listOf(RigIdConversion.Rename("parameter", "q", "r")))
		val journal = listOf(override, panel, first, second)
		assertEquals(mapOf("p" to "r"), RigIdConversion.renames(journal).parameters)
		val forwarded = RigIdConversion.forwarded(journal)
		assertEquals("warp:W2", forwarded[0]["target"]!!.jsonPrimitive.content)
		assertEquals(setOf("r"), forwarded[0]["key"]!!.jsonObject.keys)
		assertEquals("r", forwarded[1]["edits"]!!.jsonArray.single().jsonObject["before_id"]!!.jsonPrimitive.content)
		assertEquals(journal.subList(2, 4), forwarded.subList(2, 4))
		// Generated physics names standard parameters by their standard IDs, and finds them under the new ones.
		val overlay = RigEditOverlay(authoringJournal = listOf(RigIdConversion.encode(listOf(RigIdConversion.Rename("parameter", "ParamAngleX", "PARAM_ANGLE_X")))))
		val hair = PhysicsCatalog.groups(PhysicsGenerator.Presets(true, false, false), PhysicsGenerator.Presets(true, false, false), overlay,
			setOf("PARAM_ANGLE_X", "ParamAngleZ", "ParamHairFront")).single()
		assertEquals(listOf("PARAM_ANGLE_X", "ParamAngleZ"), hair.setting.inputs.map { it.parameter })
		assertEquals(null, hair.issue)
		assertEquals(listOf("PARAM_ANGLE_X"), MotionPresets.renamed(listOf(MotionCurve("ParamAngleX", listOf(MotionKey(0f, 0f)))), overlay.idRenames.parameters).map { it.parameterId })
	}

	@Test fun meshDuplicationCopiesTopologyOntoAnotherLayerAndMigratesItsKeyforms() {
		val source = ArtSource(ArtSourceId("psd"), "art", null, "psd", listOf(
			ArtSourceLayer("eye-open", "Eye open", "", 0, 0, 16, 16, true), ArtSourceLayer("eye-closed", "Eye closed", "", 0, 0, 16, 16, true)))
		val tiles = listOf(AtlasTile(AtlasTileId("t1"), "t1", 16, 16, source = SourceLayerRef(source.id, "eye-open", true)),
			AtlasTile(AtlasTileId("t2"), "t2", 16, 16, source = SourceLayerRef(source.id, "eye-closed", true)))
		fun uvs(positions: FloatArray) = FloatArray(positions.size) { positions[it] / 16f }
		val dense = floatArrayOf(2f, 2f, 14f, 2f, 2f, 14f, 14f, 14f, 8f, 8f)
		val open = Drawable(DrawableId("open"), "Eye open", null, BlendMode.Normal, emptyList(),
			DrawableMesh(dense, uvs(dense), intArrayOf(0, 1, 4, 1, 3, 4, 3, 2, 4, 2, 0, 4)), null, atlasTileId = tiles[0].id)
		val coarse = floatArrayOf(0f, 0f, 16f, 0f, 0f, 16f, 16f, 16f)
		val closed = Drawable(DrawableId("closed"), "Eye closed", null, BlendMode.Normal, emptyList(),
			DrawableMesh(coarse, uvs(coarse), intArrayOf(0, 1, 2, 1, 3, 2)),
			KeyformGrid(listOf(KeyformAxis(p, floatArrayOf(0f, 1f))), listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(8))),
				KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(8) { if (it % 2 == 1) 4f else 0f })))), atlasTileId = tiles[1].id)
		val model = PuppetModel(listOf(Parameter(p, "P", 0f, 1f, 0f)), emptyList(), emptyList(), listOf(open, closed),
			listOf(OrgChild.Drawable(open.id), OrgChild.Drawable(closed.id)), null, canvasWidth = 16f, canvasHeight = 16f,
			atlas = PuppetAtlas(tiles = tiles), sources = listOf(source)).withDerivedRenderRoot()
		val request = buildJsonObject { put("op", RigMeshDuplication.OP); put("source", "mesh:open"); putJsonArray("targets") { add("mesh:closed") } }
		val (duplicated, journal) = RigAuthoringJournal.compile(model, JsonArray(listOf(request)))
		assertEquals(listOf(RasterMeshJournal.OP), journal.map { it["op"]!!.jsonPrimitive.content })
		val mesh = assertNotNull(duplicated.drawables.single { it.id.raw == "closed" }.mesh)
		assertTrue(mesh.positions.contentEquals(dense))
		assertTrue(mesh.indices.contentEquals(open.mesh!!.indices))
		for (i in dense.indices) assertEquals(dense[i] / 16f, mesh.uvs[i], 1e-6f)
		// The closed eye's keyform moves the copied vertices as it moved its own.
		val keyed = duplicated.drawables.single { it.id.raw == "closed" }.geometryGrid!!.cells.single { it.coordinate[0] == 1 }.form.positionDeltas
		for (v in 0 until 5) assertEquals(4f, keyed[v * 2 + 1], 1e-4f)
		// Replay of the recorded entry reaches the same mesh.
		val replayed = RigAuthoringJournal.replay(model, journal.single())
		assertTrue(replayed.drawables.single { it.id.raw == "closed" }.mesh!!.positions.contentEquals(dense))
		assertEquals(0, RigMeshDuplication.plan(model, request).single().outside)
	}

	@Test fun pasteAndConversionSurviveHistoryReopenAndExport() = runBlocking {
		val model = fixture()
		val rig = BuiltRig(model, mapOf("a" to 0, "b" to 0, "c" to 0), emptyMap(), mapOf("a" to "layer-a", "b" to "layer-b", "c" to "layer-c"),
			32f, 32f, 16f, 16f, emptyList())
		val checkpoint = RigCheckpoint.encode(AuthoredRig(rig, emptyList()), "synthetic-object-ops")
		val document = WorkspaceDocument(WorkspaceSourceArt(64, 64, emptyList(), emptyList()), emptyMap(), emptySet(), emptyMap(), emptyMap(),
			RigEditOverlay(authoringJournal = listOf(checkpoint)))
		val (_, entries) = RigAuthoringJournal.compile(model, JsonArray(listOf(paste("rotation:parent"),
			RigIdConversion.encode(listOf(RigIdConversion.Rename("mesh", "a_copy", "ArmCopy"), RigIdConversion.Rename("parameter", "p", "ParamArm"))))))
		val edited = document.copy(rigEdits = document.rigEdits.copy(authoringJournal = listOf(checkpoint) + entries))
		val revision = WorkspaceRevisions.of(document)
		val history = WorkspaceHistoryTree(document, revision, revision)
		val root = history.head().node.id
		val editedRevision = WorkspaceRevisions.of(edited)
		history.commit(root, edited, editedRevision, editedRevision, "Paste and convert", "user")
		val (authored, _) = requireNotNull(edited.rigEdits.authoredFromCheckpoint())
		val after = authored.rig.puppet
		assertTrue(after.drawables.any { it.id.raw == "ArmCopy" } && after.parameters.single().id.raw == "ParamArm")
		// The pasted mesh shows its original's layer, under the ID it was converted to.
		assertEquals("layer-a", authored.rig.layerIdByDrawableId["ArmCopy"])
		assertTrue("a_copy" !in authored.rig.layerIdByDrawableId)
		assertTrue(authored.visibilityTargets.contains("ArmCopy" to "layer-a"))

		val store = WorkspaceStore(temp.resolve("store"))
		store.persistHistory("object-ops", history.state())
		val repository = ProjectRepository(writeHeadCache = false)
		val file = temp.resolve("object-ops.psd2live")
		repository.save(ProjectSaveCapture("object-ops", history.state(), JsonObject(emptyMap()), null, store), file)
		repository.open(file).use { opened ->
			assertEquals(edited.rigEdits.authoringJournal, opened.history.head().snapshot.rigEdits.authoringJournal)
			val reopened = requireNotNull(opened.history.head().snapshot.rigEdits.authoredFromCheckpoint()).first.rig.puppet
			assertEquals(after.drawables.map { it.id }, reopened.drawables.map { it.id })
			val exported = deletionExport(reopened)
			assertTrue(exported.drawables.any { it.id.raw == "ArmCopy" })
			assertEquals(listOf("ParamArm"), exported.parameters.map { it.id.raw })
			val evaluator = CpuDeformationEvaluator()
			val shown = evaluator.evaluate(reopened, mapOf(ParameterId("ParamArm") to 1f)).worldPositions
			val read = evaluator.evaluate(exported, mapOf(ParameterId("ParamArm") to 1f)).worldPositions
			for (id in listOf("a", "ArmCopy")) {
				val a = shown.getValue(DrawableId(id)); val b = read.getValue(DrawableId(id))
				for (i in a.indices) assertEquals(a[i], b[i], 0.01f, "$id component $i")
			}
		}
	}

	@Test fun exportRefusesSharedIdsAndWarnsAboutForeignCharacters() {
		val model = fixture()
		assertTrue(RigIdCheck.checked("test", model).isEmpty())
		val shared = model.copy(drawables = model.drawables.map { if (it.id.raw == "c") it.copy(id = DrawableId("a")) else it })
		assertEquals(listOf("mesh a"), RigIdCheck.conflicts(shared))
		assertFailsWith<IllegalArgumentException> { RigIdCheck.checked("test", shared) }
		val foreign = model.copy(parameters = model.parameters.map { it.copy(id = ParameterId("パラメータ")) })
		assertEquals(listOf("parameter パラメータ"), RigIdCheck.invalid(foreign))
		assertEquals(1, RigIdCheck.checked("test", foreign).size)
		// Parameter folders are exported by ID too.
		val folders = model.copy(parameterTree = model.parameterTree + ParameterNode.Group(ParameterGroupId("G"), "Again", true, emptyList()))
		assertEquals(listOf("param_group G"), RigIdCheck.conflicts(folders))
		// A virtual bone the runtime's advanced mode carries shares the deformers' IDs.
		val bone = io.github.psd2live.format.model.Deformer.Rotation("parent", "Bone", null, null, 0f, null)
		assertEquals(listOf("deformer parent"), RigIdCheck.conflicts(model, io.github.psd2live.format.model.AdvancedIR(virtualBones = listOf(bone))))
		assertFailsWith<IllegalArgumentException> { RigIdCheck.checked("test", model, io.github.psd2live.format.model.AdvancedIR(virtualBones = listOf(bone))) }
	}

	/** A part's blend shapes - its draw order over a parameter - name the parameter by ID like any keyform. */
	@Test fun convertIdRenamesThePartBlendShapesParameterAndLimits() {
		val model = fixture()
		val q = ParameterId("q")
		val binding = BlendShapeBinding(p, floatArrayOf(0f, 1f), 0, listOf<PartForm?>(null, PartForm(600f)),
			listOf(BlendWeightLimit(q, listOf(BlendWeightLimitPoint(0f, 1f)))))
		val withShape = model.copy(parameters = model.parameters + Parameter(q, "Q", 0f, 1f, 0f),
			parts = model.parts.map { it.copy(blendShapes = listOf(binding)) })
		val converted = RigIdConversion.apply(withShape, RigIdConversion.encode(listOf(RigIdConversion.Rename("parameter", "p", "ParamArm"),
			RigIdConversion.Rename("parameter", "q", "ParamLimit"))))
		val shape = converted.parts.single().blendShapes.single()
		assertEquals("ParamArm", shape.parameterId.raw)
		assertEquals("ParamLimit", shape.limits.single().parameterId.raw)
	}

	/** A generator parameter the document adopted keeps its late panel phase when its ID is converted. */
	@Test fun adoptedGeneratorParameterKeepsItsPanelPhaseUnderItsNewId() {
		val adopted = buildJsonObject {
			put("op", "structure"); putJsonArray("edits") {}
			putJsonArray(RigEditOverlay.PANEL_AFTER_GENERATION) { add("ParamSwing") }
		}
		val move = buildJsonObject { put("kind", "parameter"); put("action", "move"); put("id", "ParamHair") }
		val overlay = RigEditOverlay.Empty.copy(authoringJournal = listOf(adopted,
			RigIdConversion.encode(listOf(RigIdConversion.Rename("parameter", "ParamSwing", "ParamHair")))))
		assertTrue(overlay.generatedPanelEdit(move))
	}

	/** A regeneration merge reads the generated rig under the IDs the journal's conversions gave its objects. */
	@Test fun generatedRigTakesTheJournalsConversionsForTheMerge() {
		val generated = fixture()
		val journal = listOf(RigIdConversion.encode(listOf(RigIdConversion.Rename("mesh", "a", "Arm"))),
			RigIdConversion.encode(listOf(RigIdConversion.Rename("mesh", "Arm", "ArtMeshArm"), RigIdConversion.Rename("rotation", "parent", "RotationArm"))))
		val converted = RigIdConversion.converted(generated, journal)
		assertEquals(listOf("ArtMeshArm", "b", "c"), converted.drawables.map { it.id.raw })
		assertEquals("RotationArm", converted.drawables.first().parentDeformerId?.raw)
		assertEquals(generated, RigIdConversion.converted(generated, emptyList()))
	}
}
