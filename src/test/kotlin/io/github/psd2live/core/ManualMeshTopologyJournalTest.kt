package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The point tool's topology commands as journal entries: what they migrate, and that replay rebuilds them. */
class ManualMeshTopologyJournalTest {
    private val pull = ParameterId("ParamPull")
    private val sheet = DrawableId("sheet")
    private val patch = DrawableId("patch")

    /**
     * A 3x3 grid mesh (vertex r * 3 + c at 10 * (c, r)) whose ParamPull key and pin weights are linear in position,
     * so anything derived by weights from its vertices can be checked against the same linear field; a second mesh is
     * glued to its corners 0 and 8.
     */
    private fun fixture(): PuppetModel {
        val positions = FloatArray(18) { if (it % 2 == 0) (it / 2 % 3) * 10f else (it / 2 / 3) * 10f }
        val indices = ArrayList<Int>()
        for (r in 0..1) for (c in 0..1) { val a = r * 3 + c; indices += listOf(a, a + 1, a + 4, a, a + 4, a + 3) }
        val pulled = FloatArray(18) { if (it % 2 == 0) positions[it] * 0.1f else positions[it] * 0.2f }
        val grid = KeyformGrid(listOf(KeyformAxis(pull, floatArrayOf(0f, 1f))), listOf(
            KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(18))), KeyformCell(intArrayOf(1), MeshDeltaForm(pulled))))
        val sheetDrawable = Drawable(sheet, "Sheet", null, BlendMode.Normal, emptyList(),
            DrawableMesh(positions, FloatArray(18) { positions[it] / 20f }, indices.toIntArray()), grid, texturePage = 0)
        val small = floatArrayOf(0f, 0f, 20f, 20f, 0f, 20f)
        val patchDrawable = Drawable(patch, "Patch", null, BlendMode.Normal, emptyList(),
            DrawableMesh(small, FloatArray(6) { small[it] / 20f }, intArrayOf(0, 1, 2)), null, texturePage = 0)
        val drawables = listOf(sheetDrawable, patchDrawable)
        return PuppetModel(listOf(Parameter(pull, "Pull", 0f, 1f, 0f)), emptyList(), emptyList(), drawables,
            drawables.map { OrgChild.Drawable(it.id) }, null,
            glues = listOf(Glue(sheet, patch, listOf(GluePair(0, 0, 0.5f, 0.5f), GluePair(8, 1, 0.5f, 0.5f)), id = "Seam")),
            canvasWidth = 40f, canvasHeight = 40f,
            vertexGroups = listOf(VertexGroup("pin", sheet, VertexGroupKind.PIN, FloatArray(9) { (it % 3) / 2f })),
        ).withDerivedRenderRoot()
    }

    private fun topology(action: String, fields: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("op", "canvas_topology"); put("id", sheet.raw); put("action", action); put("vertices", JsonArray(emptyList())); fields()
    }

    private fun mesh(model: PuppetModel) = model.drawables.single { it.id == sheet }.mesh!!

    @Test fun addedPointsTakeTheirKeysWeightsAndGlueFromTheMeshAroundThem() {
        val model = fixture()
        val command = topology("add_points") {
            put("anchors", buildJsonArray { add(buildJsonObject { put("x", 6f); put("y", 3f) }); add(buildJsonObject { put("x", 26f); put("y", 5f) }) })
        }
        val (edited, journal) = RigAuthoringJournal.compile(model, JsonArray(listOf(command)))
        val after = mesh(edited)
        assertEquals(11, after.vertexCount)
        val pulled = edited.drawables.single { it.id == sheet }.geometryGrid!!.cells.single { it.coordinate[0] == 1 }.form.positionDeltas
        val weights = edited.vertexGroups.single().weights
        assertEquals(11, weights.size)
        for (vertex in 9..10) {
            val x = after.positions[vertex * 2]; val y = after.positions[vertex * 2 + 1]
            assertEquals(x * 0.1f, pulled[vertex * 2], 1e-4f, "key x of $vertex")
            assertEquals(y * 0.2f, pulled[vertex * 2 + 1], 1e-4f, "key y of $vertex")
            assertEquals(x / 20f, after.uvs[vertex * 2], 1e-5f, "uv of $vertex")
        }
        // Inside the old outline the weight interpolates as the keys do.
        assertEquals(0.3f, weights[9], 1e-4f)
        assertEquals(listOf(0 to 0, 8 to 1), edited.glues.single().pairs.map { it.indexA to it.indexB })
        val replayed = journal.fold(model) { current, entry -> RigAuthoringJournal.apply(current, entry) }
        assertContentEquals(after.positions, mesh(replayed).positions)
        assertContentEquals(after.indices, mesh(replayed).indices)
    }

    /**
     * The point tool's line, journalled as `knife` since it was a tool of its own: a project that recorded one replays it
     * unchanged, and every two neighbouring anchors end joined by an edge - which is what sets it apart from `add_points`.
     */
    @Test fun aLineJoinsItsAnchorsByEdgesAndReplaysAsRecorded() {
        val model = fixture()
        val command = topology("knife") {
            put("anchors", buildJsonArray {
                add(buildJsonObject { put("vertex", 3) })
                add(buildJsonObject { put("x", 9f); put("y", 13f) })
                add(buildJsonObject { put("x", 26f); put("y", 12f) })
            })
        }
        val (edited, journal) = RigAuthoringJournal.compile(model, JsonArray(listOf(command)))
        assertEquals("knife", journal.single().getValue("action").jsonPrimitive.content)
        val after = mesh(edited)
        val edges = org.umamo.edit.MeshTopology.uniqueEdges(after.indices)
        fun vertexAt(x: Float, y: Float) = (0 until after.vertexCount).single {
            kotlin.math.abs(after.positions[it * 2] - x) < 1e-3f && kotlin.math.abs(after.positions[it * 2 + 1] - y) < 1e-3f
        }
        val chain = listOf(3, vertexAt(9f, 13f), vertexAt(26f, 12f))
        // Each segment splits the edges it crosses, so it runs as a chain of edges through every vertex lying on it.
        for ((a, b) in chain.zipWithNext()) {
            val ax = after.positions[a * 2]; val ay = after.positions[a * 2 + 1]
            val dx = after.positions[b * 2] - ax; val dy = after.positions[b * 2 + 1] - ay
            val length = dx * dx + dy * dy
            val along = (0 until after.vertexCount).mapNotNull { v ->
                val px = after.positions[v * 2] - ax; val py = after.positions[v * 2 + 1] - ay
                val t = (px * dx + py * dy) / length
                if (t < -1e-4f || t > 1f + 1e-4f || kotlin.math.abs(px * dy - py * dx) > 1e-2f * kotlin.math.sqrt(length)) null else v to t
            }.sortedBy { it.second }.map { it.first }
            assertEquals(a, along.first()); assertEquals(b, along.last())
            for ((u, v) in along.zipWithNext()) assertTrue(org.umamo.edit.MeshElement.Edge.of(u, v) in edges, "edge $u-$v on $a-$b")
        }
        // The point outside the grid grew the outline, and the keys of the new points follow the linear field.
        val pulled = edited.drawables.single { it.id == sheet }.geometryGrid!!.cells.single { it.coordinate[0] == 1 }.form.positionDeltas
        assertEquals(9f * 0.1f, pulled[chain[1] * 2], 1e-4f)
        assertEquals(13f * 0.2f, pulled[chain[1] * 2 + 1], 1e-4f)
        val replayed = RigAuthoringJournal.apply(model, command)
        assertContentEquals(after.positions, mesh(replayed).positions)
        assertContentEquals(after.indices, mesh(replayed).indices)
    }

    @Test fun deletedEdgesDropTheVertexTheyFreeAndRemapEverythingElse() {
        val model = fixture()
        val command = topology("delete_edges") {
            put("edges", buildJsonArray { add(buildJsonArray { add(0); add(1) }); add(buildJsonArray { add(0); add(3) }) })
        }
        val edited = RigAuthoringJournal.apply(model, command)
        assertEquals(8, mesh(edited).vertexCount)
        assertEquals(6, mesh(edited).triangleCount)
        // Corner 0 is gone with its glue pair; corner 8 is now vertex 7.
        assertEquals(listOf(7 to 1), edited.glues.single().pairs.map { it.indexA to it.indexB })
        assertContentEquals((1 until 9).map { (it % 3) / 2f }.toFloatArray(), edited.vertexGroups.single().weights)
        val flipped = RigAuthoringJournal.apply(model, topology("delete_edges") {
            put("edges", buildJsonArray { add(buildJsonArray { add(0); add(4) }) })
        })
        assertTrue(org.umamo.edit.MeshElement.Edge.of(1, 3) in org.umamo.edit.MeshTopology.uniqueEdges(mesh(flipped).indices))
        assertContentEquals(mesh(model).positions, mesh(flipped).positions)
        // A deletion that would pinch the centre between two fans changes nothing and says so.
        assertFailsWith<IllegalArgumentException> {
            RigAuthoringJournal.apply(model, topology("delete_edges") {
                put("edges", buildJsonArray { add(buildJsonArray { add(0); add(1) }); add(buildJsonArray { add(7); add(8) }) })
            })
        }
    }

    @Test fun retriangulateKeepsEveryVertexAndItsKeys() {
        val model = fixture()
        // Shear the grid so its diagonals are the long ones, then let re-triangulation turn them.
        val squashed = model.copy(drawables = model.drawables.map { drawable ->
            if (drawable.id != sheet) drawable else drawable.copy(mesh = drawable.mesh!!.let { mesh ->
                val positions = FloatArray(mesh.positions.size) { if (it % 2 == 0) mesh.positions[it] + 2f * mesh.positions[it + 1] else mesh.positions[it] }
                DrawableMesh(positions, FloatArray(positions.size) { if (it % 2 == 0) positions[it] / 80f else positions[it] / 20f }, mesh.indices)
            })
        })
        val edited = RigAuthoringJournal.apply(squashed, topology("retriangulate") {})
        assertContentEquals(mesh(squashed).positions, mesh(edited).positions)
        assertTrue(!mesh(squashed).indices.contentEquals(mesh(edited).indices))
        assertContentEquals(squashed.drawables.single { it.id == sheet }.geometryGrid!!.cells[1].form.positionDeltas,
            edited.drawables.single { it.id == sheet }.geometryGrid!!.cells[1].form.positionDeltas)
        assertFailsWith<IllegalArgumentException> { RigAuthoringJournal.apply(edited, topology("retriangulate") {}) }
    }

    @Test fun theJudgeShapeEvaluatesOnlyTheParentChainYetMatchesTheWholeModel() {
        val base = fixture()
        val lattice = FloatArray(18).also { for (r in 0..2) for (c in 0..2) { it[(r * 3 + c) * 2] = -5f + 25f * c + r; it[(r * 3 + c) * 2 + 1] = -5f + 22f * r } }
        val warp = Deformer.Warp(DeformerId("body"), "Body", null, null, 2, 2, true,
            KeyformGrid(listOf(KeyformAxis(pull, floatArrayOf(0f, 1f))), listOf(
                KeyformCell(intArrayOf(0), WarpLatticeForm(lattice.copyOf())), KeyformCell(intArrayOf(1), WarpLatticeForm(lattice.copyOf())))))
        val turn = Deformer.Rotation(DeformerId("turn"), "Turn", warp.id, null, 0f,
            KeyformGrid(listOf(KeyformAxis(pull, floatArrayOf(0f, 1f))), listOf(
                KeyformCell(intArrayOf(0), RotationPivotForm(0.5f, 0.5f, 15f, 1f)), KeyformCell(intArrayOf(1), RotationPivotForm(0.5f, 0.5f, 40f, 1f)))))
        val model = base.copy(deformers = listOf(warp, turn), drawables = base.drawables.map { if (it.id == sheet) it.copy(parentDeformerId = turn.id) else it })
            .withDerivedRenderRoot()
        val whole = org.umamo.render.eval.CpuDeformationEvaluator().evaluate(model, mapOf(pull to 0f)).worldPositions[sheet]!!
        val judge = CanvasTopology.shapes(model, sheet).judge!!
        assertEquals(whole.size, judge.size)
        for (k in whole.indices) assertEquals(whole[k], judge[k], 1e-3f, "coordinate $k")
        assertTrue(whole.indices.any { kotlin.math.abs(whole[it] - mesh(model).positions[it]) > 1f }, "the chain moves the mesh")
    }
}
