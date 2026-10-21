package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KeyformToolsTest {
    @Test fun resetPutsAKeyBackToItsDefaultKeyAndReplays() {
        val model = fixture()
        val (reset, journal) = RigAuthoringJournal.compile(model, JsonArray(listOf(tool("reset", "warp:face") { put("key", key("ParamAngleX" to 30f)) })))
        assertEquals(listOf("set"), journal.map { it.getValue("op").jsonPrimitive.content })
        val neutral = RigGeometryTools.geometry(model, "warp", "face", mapOf("ParamAngleX" to 0f)).points
        val result = RigGeometryTools.geometry(reset, "warp", "face", mapOf("ParamAngleX" to 30f)).points
        assertTrue(neutral.contentEquals(result))
        val replayed = journal.fold(model) { current, entry -> RigAuthoringJournal.apply(current, entry) }
        assertTrue(result.contentEquals(RigGeometryTools.geometry(replayed, "warp", "face", mapOf("ParamAngleX" to 30f)).points))
    }

    @Test fun resetOfPickedVerticesLeavesTheOthers() {
        val model = fixture()
        val closed = RigGeometryTools.geometry(model, "mesh", "eye", mapOf("ParamEyeOpen" to 0f))
        val reset = RigAuthoringJournal.compile(model, JsonArray(listOf(tool("reset", "mesh:eye") {
            put("key", key("ParamEyeOpen" to 0f)); put("to", "rest"); put("vertices", JsonArray(listOf(JsonPrimitive(1))))
        }))).first
        val result = RigGeometryTools.geometry(reset, "mesh", "eye", mapOf("ParamEyeOpen" to 0f))
        assertEquals(result.base[2], result.points[2], 1e-6f); assertEquals(result.base[3], result.points[3], 1e-6f)
        for (i in listOf(0, 1, 4, 5, 6, 7)) assertEquals(closed.points[i], result.points[i], 1e-6f, "coordinate $i")
        assertFailsWith<IllegalArgumentException> {
            KeyformTools.compile(model, tool("reset", "warp:face") { put("key", key("ParamAngleX" to 30f)); put("to", "rest") })
        }
    }

    @Test fun interpolateRemakesAMiddleKeyFromItsNeighbours() {
        val model = fixture()
        val compiled = RigAuthoringJournal.compile(model, JsonArray(listOf(tool("interpolate", "warp:face") { put("key", key("ParamAngleX" to 0f)) }))).first
        val left = RigGeometryTools.geometry(model, "warp", "face", mapOf("ParamAngleX" to -30f)).points
        val right = RigGeometryTools.geometry(model, "warp", "face", mapOf("ParamAngleX" to 30f)).points
        val middle = RigGeometryTools.geometry(compiled, "warp", "face", mapOf("ParamAngleX" to 0f)).points
        for (i in middle.indices) assertEquals((left[i] + right[i]) / 2f, middle[i], 1e-4f, "coordinate $i")
        // An end key has nothing on one side to interpolate from.
        assertFailsWith<IllegalArgumentException> { KeyformTools.compile(model, tool("interpolate", "warp:face") { put("key", key("ParamAngleX" to 30f)) }) }
    }

    @Test fun interpolateTurnsARotationHalfway() {
        val model = fixture()
        val compiled = RigAuthoringJournal.compile(model, JsonArray(listOf(tool("interpolate", "rotation:arm") { put("key", key("ParamAngleX" to 0f)) }))).first
        val form = (compiled.deformers.single { it.id.raw == "arm" } as Deformer.Rotation).geometryGrid!!.let { grid ->
            grid.cells.single { grid.axes[0].keys[it.coordinate[0]] == 0f }.form
        }
        assertEquals(32f, form.originX, 1e-4f); assertEquals(5f, form.angle, 1e-4f)
    }

    @Test fun pasteCarriesAMeshShapeOntoAnotherKeyAndObject() {
        val model = fixture()
        val (pasted, journal) = RigAuthoringJournal.compile(model, JsonArray(listOf(tool("paste", "mesh:eye") {
            put("from", key("ParamEyeOpen" to 0f)); put("destination", "mesh:twin"); put("key", key("ParamAngleX" to 30f))
        })))
        assertEquals("mesh:twin", journal.single().getValue("target").jsonPrimitive.content)
        val source = RigGeometryTools.geometry(model, "mesh", "eye", mapOf("ParamEyeOpen" to 0f))
        val result = RigGeometryTools.geometry(pasted, "mesh", "twin", mapOf("ParamAngleX" to 30f))
        for (i in result.points.indices) assertEquals(source.points[i] - source.base[i], result.points[i] - result.base[i], 1e-6f, "move $i")
        assertFailsWith<IllegalArgumentException> {
            KeyformTools.compile(model, tool("paste", "mesh:eye") { put("from", key("ParamEyeOpen" to 0f)); put("destination", "warp:face"); put("key", key("ParamAngleX" to 0f)) })
        }
    }

    @Test fun aKeyMustNameEveryBoundAxis() {
        val model = fixture()
        val failure = assertFailsWith<IllegalArgumentException> { KeyformTools.compile(model, tool("reset", "warp:face") { put("key", key("ParamEyeOpen" to 0f)) }) }
        assertTrue(failure.message!!.startsWith("KEY_INCOMPLETE"))
    }

    private fun tool(op: String, target: String, fields: JsonObjectBuilder.() -> Unit) = buildJsonObject { put("op", op); put("target", target); fields() }

    private fun key(vararg values: Pair<String, Float>) = JsonObject(values.associate { it.first to JsonPrimitive(it.second) })

    /**
     * A face warp keyed on ParamAngleX -30/0/30 with each side moved differently, an arm rotation on the same axis, an
     * eye mesh closing on ParamEyeOpen and an unbound twin mesh with the eye's vertex count.
     */
    private fun fixture(): PuppetModel {
        val angle = ParameterId("ParamAngleX")
        val open = ParameterId("ParamEyeOpen")
        val lattice = FloatArray(18).also { for (r in 0..2) for (c in 0..2) { it[(r * 3 + c) * 2] = 20f + 30f * c; it[(r * 3 + c) * 2 + 1] = 20f + 30f * r } }
        val left = lattice.copyOf().also { for (i in it.indices step 2) it[i] -= 4f }
        val right = lattice.copyOf().also { for (i in it.indices step 2) { it[i] += 6f + i * 0.1f; it[i + 1] += 2f } }
        val face = Deformer.Warp(DeformerId("face"), "Face", null, null, 2, 2, true,
            KeyformGrid(listOf(KeyformAxis(angle, floatArrayOf(-30f, 0f, 30f))), listOf(
                KeyformCell(intArrayOf(0), WarpLatticeForm(left)), KeyformCell(intArrayOf(1), WarpLatticeForm(lattice.copyOf())),
                KeyformCell(intArrayOf(2), WarpLatticeForm(right)))))
        val arm = Deformer.Rotation(DeformerId("arm"), "Arm", null, null, 0f,
            KeyformGrid(listOf(KeyformAxis(angle, floatArrayOf(-30f, 0f, 30f))), listOf(
                KeyformCell(intArrayOf(0), RotationPivotForm(30f, 50f, -10f, 1f)), KeyformCell(intArrayOf(1), RotationPivotForm(30f, 50f, 0f, 1f)),
                KeyformCell(intArrayOf(2), RotationPivotForm(34f, 50f, 20f, 1f)))))
        fun mesh(id: String, x0: Float, grid: KeyformGrid<MeshDeltaForm>?): Drawable {
            val positions = floatArrayOf(x0, 0.4f, x0 + 0.2f, 0.4f, x0 + 0.2f, 0.6f, x0, 0.6f)
            return Drawable(DrawableId(id), id, face.id, BlendMode.Normal, emptyList(),
                DrawableMesh(positions, positions.copyOf(), intArrayOf(0, 1, 2, 0, 2, 3)), grid, texturePage = 0)
        }
        val closing = KeyformGrid(listOf(KeyformAxis(open, floatArrayOf(0f, 1f))), listOf(
            KeyformCell(intArrayOf(0), MeshDeltaForm(floatArrayOf(0.02f, 0.1f, 0.01f, 0.12f, 0.03f, -0.05f, 0f, -0.04f))),
            KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(8)))))
        val twinGrid = KeyformGrid(listOf(KeyformAxis(angle, floatArrayOf(0f, 30f))), listOf(
            KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(8))), KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(8) { 0.01f }))))
        val drawables = listOf(mesh("eye", 0.1f, closing), mesh("twin", 0.6f, twinGrid))
        return PuppetModel(listOf(Parameter(angle, "Angle X", -30f, 30f, 0f), Parameter(open, "Eye Open", 0f, 1f, 1f)),
            emptyList(), listOf(face, arm), drawables, drawables.map { OrgChild.Drawable(it.id) }, null,
            canvasWidth = 100f, canvasHeight = 100f).withDerivedRenderRoot()
    }
}
