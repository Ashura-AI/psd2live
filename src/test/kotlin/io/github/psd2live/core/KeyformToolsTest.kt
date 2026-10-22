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

    @Test fun pasteBlendsByRatioAndFlips() {
        val model = fixture()
        val half = RigAuthoringJournal.compile(model, JsonArray(listOf(tool("paste", "warp:face") {
            put("from", key("ParamAngleX" to 30f)); put("key", key("ParamAngleX" to 0f)); put("ratio", 0.5f)
        }))).first
        val right = RigGeometryTools.geometry(model, "warp", "face", mapOf("ParamAngleX" to 30f)).points
        val neutral = RigGeometryTools.geometry(model, "warp", "face", mapOf("ParamAngleX" to 0f)).points
        val blended = RigGeometryTools.geometry(half, "warp", "face", mapOf("ParamAngleX" to 0f)).points
        for (i in blended.indices) assertEquals((right[i] + neutral[i]) / 2f, blended[i], 1e-4f, "coordinate $i")
        // A horizontal flip pairs each lattice point with the one in the mirrored column and turns its move around.
        val flipped = RigAuthoringJournal.compile(model, JsonArray(listOf(tool("paste", "warp:face") {
            put("from", key("ParamAngleX" to 30f)); put("key", key("ParamAngleX" to 0f)); put("flip", "horizontal")
        }))).first
        val result = RigGeometryTools.geometry(flipped, "warp", "face", mapOf("ParamAngleX" to 0f)).points
        for (r in 0..2) for (c in 0..2) {
            val i = (r * 3 + c) * 2; val m = (r * 3 + (2 - c)) * 2
            assertEquals(neutral[i] - (right[m] - neutral[m]), result[i], 1e-4f, "x of $r,$c")
            assertEquals(neutral[i + 1] + (right[m + 1] - neutral[m + 1]), result[i + 1], 1e-4f, "y of $r,$c")
        }
        val vertical = RigAuthoringJournal.compile(model, JsonArray(listOf(tool("paste", "warp:face") {
            put("from", key("ParamAngleX" to 30f)); put("key", key("ParamAngleX" to 0f)); put("flip", "vertical")
        }))).first
        val down = RigGeometryTools.geometry(vertical, "warp", "face", mapOf("ParamAngleX" to 0f)).points
        for (r in 0..2) for (c in 0..2) {
            val i = (r * 3 + c) * 2; val m = ((2 - r) * 3 + c) * 2
            assertEquals(neutral[i] + (right[m] - neutral[m]), down[i], 1e-4f, "x of $r,$c")
            assertEquals(neutral[i + 1] - (right[m + 1] - neutral[m + 1]), down[i + 1], 1e-4f, "y of $r,$c")
        }
        val rotation = RigAuthoringJournal.compile(model, JsonArray(listOf(tool("paste", "rotation:arm") {
            put("from", key("ParamAngleX" to 30f)); put("key", key("ParamAngleX" to -30f)); put("flip", "horizontal")
        }))).first
        val form = (rotation.deformers.single { it.id.raw == "arm" } as Deformer.Rotation).geometryGrid!!.cells.first().form
        assertEquals(-20f, form.angle, 1e-4f); assertEquals(26f, form.originX, 1e-4f)
    }

    @Test fun aMeshFlipPairsVerticesByTheirMirroredRest() {
        val model = fixture()
        val flipped = applyAll(model, tool("paste", "mesh:eye") {
            put("from", key("ParamEyeOpen" to 0f)); put("key", key("ParamEyeOpen" to 1f)); put("flip", "horizontal")
        })
        val closed = RigGeometryTools.geometry(model, "mesh", "eye", mapOf("ParamEyeOpen" to 0f))
        val result = RigGeometryTools.geometry(flipped, "mesh", "eye", mapOf("ParamEyeOpen" to 1f))
        // The eye's rest is a square: vertex 0 mirrors 1 and 2 mirrors 3.
        for ((i, j) in listOf(0 to 1, 1 to 0, 2 to 3, 3 to 2)) {
            assertEquals(-(closed.points[j * 2] - closed.base[j * 2]), result.points[i * 2] - result.base[i * 2], 1e-5f, "dx of $i")
            assertEquals(closed.points[j * 2 + 1] - closed.base[j * 2 + 1], result.points[i * 2 + 1] - result.base[i * 2 + 1], 1e-5f, "dy of $i")
        }
        val lopsided = model.withDrawable("twin") { it.copy(mesh = DrawableMesh(floatArrayOf(0.6f, 0.4f, 0.9f, 0.4f, 0.65f, 0.6f, 0.6f, 0.55f),
            FloatArray(8), it.mesh!!.indices)) }
        assertFailsWith<IllegalArgumentException> { KeyformTools.compile(lopsided, tool("paste", "mesh:twin") {
            put("from", key("ParamAngleX" to 30f)); put("key", key("ParamAngleX" to 0f)); put("flip", "horizontal")
        }) }
    }

    @Test fun anotherObjectTakesAShapeOnlyWithTheSameTrianglesAndParent() {
        val model = fixture()
        val paste = tool("paste", "mesh:eye") { put("from", key("ParamEyeOpen" to 0f)); put("destination", "mesh:twin"); put("key", key("ParamAngleX" to 30f)) }
        val shuffled = model.withDrawable("twin") { it.copy(mesh = DrawableMesh(it.mesh!!.positions, it.mesh!!.uvs, intArrayOf(0, 1, 3, 1, 2, 3))) }
        assertTrue(KeyformTools.misfit(shuffled, KeyformTools.snapshot(shuffled, "mesh:eye", mapOf("ParamEyeOpen" to 0f)), "mesh:twin")!!.contains("triangles"))
        assertFailsWith<IllegalArgumentException> { KeyformTools.compile(shuffled, paste) }
        val elsewhere = model.withDrawable("twin") { it.copy(parentDeformerId = null) }
        assertFailsWith<IllegalArgumentException> { KeyformTools.compile(elsewhere, paste) }
        // Without the shape, the channels still go across.
        val channels = KeyformTools.compile(elsewhere, JsonObject(paste + ("channels" to JsonArray(listOf(JsonPrimitive("opacity"))))))
        assertEquals(listOf(true), channels.map { "channels" in it && "geometry" !in it })
    }

    @Test fun aCopyNoLongerFitsItsOwnObjectOnceItsPointsChange() {
        val model = fixture()
        val copied = KeyformTools.snapshot(model, "mesh:eye", mapOf("ParamEyeOpen" to 0f))
        val rebuilt = model.withDrawable("eye") { it.copy(mesh = DrawableMesh(FloatArray(6) { i -> i * 0.1f }, FloatArray(6), intArrayOf(0, 1, 2)), geometryGrid = null) }
        assertTrue(KeyformTools.misfit(rebuilt, copied, "mesh:eye")!!.contains("copy it again"))
        val failure = assertFailsWith<IllegalArgumentException> {
            KeyformTools.paste(rebuilt, copied, "mesh:eye", mapOf("ParamEyeOpen" to 1f), null, 1f, null, listOf("geometry"))
        }
        assertTrue(failure.message!!.contains("copy it again"))
        assertEquals(null, KeyformTools.misfit(model, copied, "mesh:eye"))
    }

    @Test fun aFlipRefusesTwoPointsSharingOneMirroredPartner() {
        // Vertex 2 sits next to vertex 0's mirror place too, so 0 would be the partner of both 1 and 2.
        val crowded = fixture().withDrawable("twin") { it.copy(mesh = DrawableMesh(floatArrayOf(0f, 0f, 1f, 0f, 0.99f, 0f, 0.5f, 1f),
            FloatArray(8), intArrayOf(0, 1, 3, 1, 2, 3))) }
        val failure = assertFailsWith<IllegalArgumentException> { KeyformTools.compile(crowded, tool("paste", "mesh:twin") {
            put("from", key("ParamAngleX" to 30f)); put("key", key("ParamAngleX" to 0f)); put("flip", "horizontal")
        }) }
        assertTrue(failure.message!!.contains("not symmetric"))
    }

    @Test fun snapshotsSwapTwoShapesInOneBatch() {
        val model = fixture()
        val eye = KeyformTools.snapshot(model, "mesh:eye", mapOf("ParamEyeOpen" to 0f))
        val twin = KeyformTools.snapshot(model, "mesh:twin", mapOf("ParamAngleX" to 30f))
        val sets = KeyformTools.paste(model, eye, "mesh:twin", mapOf("ParamAngleX" to 30f), null, 1f, null, listOf("geometry")) +
            KeyformTools.paste(model, twin, "mesh:eye", mapOf("ParamEyeOpen" to 0f), null, 1f, null, listOf("geometry"))
        val swapped = sets.fold(model) { current, set -> RigAuthoringJournal.apply(current, set) }
        fun moves(m: PuppetModel, id: String, k: Map<String, Float>) = RigGeometryTools.geometry(m, "mesh", id, k).let { g -> FloatArray(8) { g.points[it] - g.base[it] } }
        val eyeMoves = moves(model, "eye", mapOf("ParamEyeOpen" to 0f)); val twinMoves = moves(model, "twin", mapOf("ParamAngleX" to 30f))
        val newTwin = moves(swapped, "twin", mapOf("ParamAngleX" to 30f)); val newEye = moves(swapped, "eye", mapOf("ParamEyeOpen" to 0f))
        for (i in 0 until 8) { assertEquals(eyeMoves[i], newTwin[i], 1e-6f); assertEquals(twinMoves[i], newEye[i], 1e-6f) }
    }

    @Test fun pasteCarriesOnlyTheChosenChannels() {
        val model = fixture()
        val faded = applyAll(model, buildJsonObject {
            put("op", "multi_key"); put("targets", JsonArray(listOf(JsonPrimitive("mesh:eye")))); put("opacity", 0.25f)
            put("keys", JsonArray(listOf(key("ParamEyeOpen" to 0f))))
        })
        val (pasted, journal) = RigAuthoringJournal.compile(faded, JsonArray(listOf(tool("paste", "mesh:eye") {
            put("from", key("ParamEyeOpen" to 0f)); put("key", key("ParamEyeOpen" to 1f)); put("channels", JsonArray(listOf(JsonPrimitive("opacity"))))
        })))
        assertEquals(listOf(false), journal.map { "geometry" in it })
        val owner = KeyformOwner.Drawable(DrawableId("eye"))
        assertEquals(0.25f, (KeyformTools.channelAt(pasted, owner, FormChannel.OPACITY, mapOf("ParamEyeOpen" to 1f)) as ChannelValue.Scalar).value, 1e-5f)
    }

    @Test fun multiKeyEditsTheMatchingKeyformsByMethod() {
        val model = fixture()
        val owner = KeyformOwner.Drawable(DrawableId("eye"))
        fun opacity(m: PuppetModel, open: Float) = (KeyformTools.channelAt(m, owner, FormChannel.OPACITY, mapOf("ParamEyeOpen" to open)) as ChannelValue.Scalar).value
        val closedOnly = applyAll(model, buildJsonObject {
            put("op", "multi_key"); put("targets", JsonArray(listOf(JsonPrimitive("mesh:eye")))); put("opacity", 0.5f)
            put("keys", JsonArray(listOf(key("ParamEyeOpen" to 0f))))
        })
        assertEquals(0.5f, opacity(closedOnly, 0f), 1e-5f); assertEquals(1f, opacity(closedOnly, 1f), 1e-5f)
        val halved = applyAll(closedOnly, buildJsonObject {
            put("op", "multi_key"); put("targets", JsonArray(listOf(JsonPrimitive("mesh:eye")))); put("opacity", 0.5f); put("method", "multiply")
        })
        assertEquals(0.25f, opacity(halved, 0f), 1e-5f); assertEquals(0.5f, opacity(halved, 1f), 1e-5f)
        assertFailsWith<IllegalArgumentException> { KeyformTools.compile(model, buildJsonObject {
            put("op", "multi_key"); put("targets", JsonArray(listOf(JsonPrimitive("warp:face")))); put("drawOrder", 600)
        }) }
        assertFailsWith<IllegalArgumentException> { KeyformTools.compile(model, buildJsonObject {
            put("op", "multi_key"); put("targets", JsonArray(listOf(JsonPrimitive("mesh:eye")))); put("drawOrder", 2); put("method", "multiply")
        }) }
    }

    @Test fun multiKeyWritesAChannelAtItsOwnKeys() {
        // The twin's shape is keyed on ParamAngleX; its opacity only on ParamEyeOpen.
        val keyed = applyAll(fixture(), buildJsonObject {
            put("op", "set"); put("target", "mesh:twin"); put("key", key("ParamEyeOpen" to 0f)); putJsonObject("channels") { put("opacity", 0.8f) }
        })
        val edited = applyAll(keyed, buildJsonObject {
            put("op", "multi_key"); put("targets", JsonArray(listOf(JsonPrimitive("mesh:twin")))); put("opacity", 0.5f); put("method", "multiply")
        })
        val owner = KeyformOwner.Drawable(DrawableId("twin"))
        assertEquals(listOf("ParamEyeOpen"), edited.channelGridsOf(owner)!![FormChannel.OPACITY]!!.axes.map { it.parameterId.raw })
        assertEquals(0.4f, (KeyformTools.channelAt(edited, owner, FormChannel.OPACITY, mapOf("ParamEyeOpen" to 0f)) as ChannelValue.Scalar).value, 1e-5f)
    }

    @Test fun restoringEveryKeyGivesTheDefaultKeyShapeEverywhere() {
        val model = fixture()
        val (restored, journal) = RigAuthoringJournal.compile(model, JsonArray(listOf(tool("reset", "warp:face") { put("all_keys", true) })))
        val neutral = RigGeometryTools.geometry(model, "warp", "face", mapOf("ParamAngleX" to 0f)).points
        for (value in listOf(-30f, 30f)) assertTrue(neutral.contentEquals(RigGeometryTools.geometry(restored, "warp", "face", mapOf("ParamAngleX" to value)).points))
        assertEquals(2, journal.size, "the neutral key already has its shape")
    }

    private fun applyAll(model: PuppetModel, command: JsonObject) = RigAuthoringJournal.compile(model, JsonArray(listOf(command))).first

    private fun PuppetModel.withDrawable(id: String, change: (Drawable) -> Drawable) =
        copy(drawables = drawables.map { if (it.id.raw == id) change(it) else it })

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
