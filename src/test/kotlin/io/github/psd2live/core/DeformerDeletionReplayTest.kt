package io.github.psd2live.core

import io.github.psd2live.history.WorkspaceHistoryTree
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.export.Moc3Export
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.render.canvasToParentSpaceFor
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.*
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeformerDeletionReplayTest {
	@TempDir lateinit var temp: Path

	@Test fun deletionReplaysAcrossHistoryAndProjectReopenAndMocExport() = runBlocking {
		val before = deletionFixture()
		val rig = BuiltRig(before, emptyMap(), emptyMap(), emptyMap(), 32f, 32f, 16f, 16f, emptyList())
		val checkpoint = RigCheckpoint.encode(AuthoredRig(rig, emptyList()), "synthetic-deletion")
		val document = WorkspaceDocument(WorkspaceSourceArt(64, 64, emptyList(), emptyList()),
			emptyMap(), emptySet(), emptyMap(), emptyMap(), RigEditOverlay(authoringJournal = listOf(checkpoint)))
		val revision = WorkspaceRevisions.of(document)
		val history = WorkspaceHistoryTree(document, revision, revision)
		val root = history.head().node.id
		val edited = document.copy(rigEdits = document.rigEdits.copy(authoringJournal = listOf(checkpoint, deletionCommand())))
		val editedRevision = WorkspaceRevisions.of(edited)
		val committed = history.commit(root, edited, editedRevision, editedRevision, "Delete parent rotation", "user")
		val after = replay(edited)
		assertTrue(after.deformers.none { it.id.raw == "parent" })
		assertTrue(before.deformers.any { it.id.raw == "parent" })
		assertSameGeometry(before, after)
		assertSameGeometry(before, replay(history.checkout(root).snapshot))
		assertSameGeometry(after, replay(history.checkout(committed.node.id).snapshot))

		val store = WorkspaceStore(temp.resolve("store"))
		store.persistHistory("deletion", history.state())
		val repository = ProjectRepository(writeHeadCache = false)
		val file = temp.resolve("deletion.psd2live")
		repository.save(ProjectSaveCapture("deletion", history.state(), JsonObject(emptyMap()), null, store), file)
		repository.open(file).use { opened ->
			assertEquals(history.state().selections.map { it.node }, opened.history.state().selections.map { it.node })
			assertEquals(edited.rigEdits.authoringJournal, opened.history.head().snapshot.rigEdits.authoringJournal)
			assertSameGeometry(before, replay(opened.history.selectionAt(root).snapshot))
			val reopened = replay(opened.history.head().snapshot)
			assertEquals(after.deformers.map { it.id }, reopened.deformers.map { it.id })
			assertSameGeometry(after, reopened)
			assertSameGeometry(after, deletionExport(reopened))
		}
	}

	private fun replay(document: WorkspaceDocument): PuppetModel =
		requireNotNull(document.rigEdits.authoredFromCheckpoint()).first.rig.puppet

	private fun assertSameGeometry(expected: PuppetModel, actual: PuppetModel) {
		val evaluator = CpuDeformationEvaluator()
		val before = evaluator.evaluate(expected, emptyMap()).worldPositions
		val after = evaluator.evaluate(actual, emptyMap()).worldPositions
		assertEquals(before.keys, after.keys)
		for ((id, positions) in before) {
			val result = after.getValue(id)
			assertEquals(positions.size, result.size)
			for (index in positions.indices) assertEquals(positions[index], result[index], 0.001f, "$id component $index")
		}
	}
}

/** Small asymmetric meshes exercise both direct children and children of a nested rotation. */
internal fun deletionFixture(): PuppetModel {
	val parameter = ParameterId("p")
	val parent = Deformer.Rotation(DeformerId("parent"), "parent", null, null, 35f,
		KeyformGrid(listOf(KeyformAxis(parameter, floatArrayOf(-1f, 1f))),
			listOf(KeyformCell(intArrayOf(0), RotationPivotForm(32f, 32f, 0f, 1f)),
				KeyformCell(intArrayOf(1), RotationPivotForm(40f, 32f, 20f, 1.2f)))), flipX = true, flipY = true)
	val child = Deformer.Rotation(DeformerId("child"), "child", parent.id, null, 20f,
		KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), RotationPivotForm(3f, 2f, 5f, 1.2f)))))
	val positions = floatArrayOf(0f, 0f, 8f, 0f, 0f, 4f)
	val mesh = DrawableMesh(positions, floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2))
	val drawables = listOf(parent.id, child.id).mapIndexed { index, id ->
		Drawable(DrawableId("mesh-$index"), "mesh-$index", id, BlendMode.Normal, emptyList(), mesh, null, texturePage = 0)
	}
	return PuppetModel(listOf(Parameter(parameter, "p", -1f, 1f, -0.5f)), emptyList(),
		listOf(parent, child), drawables, drawables.map { OrgChild.Drawable(it.id) }, null,
		canvasWidth = 64f, canvasHeight = 64f).withDerivedRenderRoot()
}

internal fun deletionCommand(): JsonObject = buildJsonObject {
	put("op", "structure")
	putJsonArray("edits") { add(buildJsonObject { put("action", "delete"); put("kind", "rotation"); put("id", "parent") }) }
}

internal fun deletionExport(model: PuppetModel): PuppetModel {
	val canvas = restMeshesToCanvasSpace(model)
	val lowered = Moc3Export.toMocDocument(canvas, canvasToParentSpace = canvasToParentSpaceFor(canvas))
	return Moc3Import.fromMocDocument(Moc3.read(Moc3.write(lowered.document)), null)
}
