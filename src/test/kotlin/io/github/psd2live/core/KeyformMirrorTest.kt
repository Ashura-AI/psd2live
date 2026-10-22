package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyformMirrorTest {
    @Test fun warpTurnMirrorsOntoTheOppositeKey() {
        val model = fixture()
        val (mirrored, journal) = RigAuthoringJournal.compile(model, JsonArray(listOf(mirror("warp:face") { put("from", key("ParamAngleX" to 30f)) })))
        assertEquals(listOf("set"), journal.map { it.getValue("op").jsonPrimitive.content })
        assertEquals(-30f, journal.single().getValue("key").jsonObject.getValue("ParamAngleX").jsonPrimitive.float)
        val rest = RigGeometryTools.geometry(model, "warp", "face", emptyMap()).points
        val turned = RigGeometryTools.geometry(model, "warp", "face", mapOf("ParamAngleX" to 30f)).points
        val result = RigGeometryTools.geometry(mirrored, "warp", "face", mapOf("ParamAngleX" to -30f)).points
        for (r in 0..2) for (c in 0..2) {
            val i = (r * 3 + c) * 2; val m = (r * 3 + (2 - c)) * 2
            assertEquals(rest[i] - (turned[m] - rest[m]), result[i], 1e-3f, "x of $r,$c")
            assertEquals(rest[i + 1] + (turned[m + 1] - rest[m + 1]), result[i + 1], 1e-3f, "y of $r,$c")
        }
        // The positive side is untouched, and replaying the journal gives the compiled rig.
        assertTrue(turned.contentEquals(RigGeometryTools.geometry(mirrored, "warp", "face", mapOf("ParamAngleX" to 30f)).points))
        val replayed = journal.fold(model) { current, entry -> RigAuthoringJournal.apply(current, entry) }
        assertTrue(result.contentEquals(RigGeometryTools.geometry(replayed, "warp", "face", mapOf("ParamAngleX" to -30f)).points))
    }

    @Test fun allKeysOnOneObjectCopyOnlyTheChosenSide() {
        val model = fixture()
        val journal = RigAuthoringJournal.compile(model, JsonArray(listOf(mirror("warp:face") { put("all_keys", true) }))).second
        assertEquals(listOf(-30f), journal.map { it.getValue("key").jsonObject.getValue("ParamAngleX").jsonPrimitive.float })
        val back = KeyformMirror.compile(model, mirror("warp:face") { put("all_keys", true); put("side", "negative") })
        assertEquals(listOf(30f), back.map { it.getValue("key").jsonObject.getValue("ParamAngleX").jsonPrimitive.float })
    }

    @Test fun eyeMirrorsOntoTheOppositeEyeThroughItsParent() {
        val model = fixture()
        val (mirrored, journal) = RigAuthoringJournal.compile(model, JsonArray(listOf(mirror("mesh:ArtMeshEyeL") {
            put("destination", "auto"); put("from", key("ParamEyeLOpen" to 0f))
        })))
        val set = journal.single()
        assertEquals("mesh:ArtMeshEyeR", set.getValue("target").jsonPrimitive.content)
        assertEquals(mapOf("ParamEyeROpen" to 0f), set.getValue("key").jsonObject.mapValues { it.value.jsonPrimitive.float })
        val left = RigGeometryTools.geometry(model, "mesh", "ArtMeshEyeL", mapOf("ParamEyeLOpen" to 0f))
        val right = RigGeometryTools.geometry(mirrored, "mesh", "ArtMeshEyeR", mapOf("ParamEyeROpen" to 0f))
        // Right vertex j reflects onto left vertex 1, 0, 3, 2: each takes that one's move with x turned around.
        for ((j, i) in listOf(1, 0, 3, 2).withIndex()) {
            assertEquals(-(left.points[i * 2] - left.base[i * 2]), right.points[j * 2] - right.base[j * 2], 1e-4f, "dx of $j")
            assertEquals(left.points[i * 2 + 1] - left.base[i * 2 + 1], right.points[j * 2 + 1] - right.base[j * 2 + 1], 1e-4f, "dy of $j")
        }
    }

    @Test fun keysAndSidesSwap() {
        assertEquals("ParamEyeROpen", KeyformMirror.swapSide("ParamEyeLOpen"))
        assertEquals("ArtMeshEyeR2", KeyformMirror.swapSide("ArtMeshEyeL2"))
        assertEquals("DeformArmHang_L", KeyformMirror.swapSide("DeformArmHang_R"))
        assertEquals("右目", KeyformMirror.swapSide("左目"))
        assertNull(KeyformMirror.swapSide("ParamAngleX"))
        assertNull(KeyformMirror.swapSide("ArtMeshLayer"))
        val model = fixture()
        val reflect = model.parameters.filter { KeyformMirror.reflects(model, it) }.map { it.id.raw }
        assertEquals(listOf("ParamAngleX"), reflect)
        assertFailsWith<IllegalArgumentException> { KeyformMirror.compile(model, mirror("warp:face") { put("from", key("ParamAngleX" to 30f)); put("key", key("ParamEyeLOpen" to 0f)) }) }
    }

    private fun mirror(target: String, fields: JsonObjectBuilder.() -> Unit) = buildJsonObject { put("op", "mirror"); put("target", target); fields() }

    private fun key(vararg values: Pair<String, Float>) = JsonObject(values.associate { it.first to JsonPrimitive(it.second) })

    /**
     * A face warp turned unevenly at ParamAngleX 30 (its -30 key left at rest), two eyes inside it - the left one
     * closing on ParamEyeLOpen - and an arm rotation turned at ParamAngleX 30.
     */
    private fun fixture(): PuppetModel {
        val angle = ParameterId("ParamAngleX")
        val eyeL = ParameterId("ParamEyeLOpen")
        val lattice = FloatArray(18).also { for (r in 0..2) for (c in 0..2) { it[(r * 3 + c) * 2] = 20f + 30f * c; it[(r * 3 + c) * 2 + 1] = 20f + 30f * r } }
        val turned = lattice.copyOf().also { for (r in 0..2) for (c in 0..2) { it[(r * 3 + c) * 2] += 5f + 2f * c + r; it[(r * 3 + c) * 2 + 1] += 1f * r - c } }
        val face = Deformer.Warp(DeformerId("face"), "Face", null, null, 2, 2, true,
            KeyformGrid(listOf(KeyformAxis(angle, floatArrayOf(-30f, 0f, 30f))), listOf(
                KeyformCell(intArrayOf(0), WarpLatticeForm(lattice.copyOf())), KeyformCell(intArrayOf(1), WarpLatticeForm(lattice.copyOf())),
                KeyformCell(intArrayOf(2), WarpLatticeForm(turned)))))
        val arm = Deformer.Rotation(DeformerId("arm"), "Arm", null, null, 0f,
            KeyformGrid(listOf(KeyformAxis(angle, floatArrayOf(-30f, 0f, 30f))), listOf(
                KeyformCell(intArrayOf(0), RotationPivotForm(30f, 50f, 0f, 1f)), KeyformCell(intArrayOf(1), RotationPivotForm(30f, 50f, 0f, 1f)),
                KeyformCell(intArrayOf(2), RotationPivotForm(34f, 50f, 20f, 1f)))))
        fun eye(id: String, x0: Float, grid: KeyformGrid<MeshDeltaForm>?): Drawable {
            val positions = floatArrayOf(x0, 0.4f, x0 + 0.2f, 0.4f, x0 + 0.2f, 0.6f, x0, 0.6f)
            return Drawable(DrawableId(id), id, face.id, BlendMode.Normal, emptyList(),
                DrawableMesh(positions, positions.copyOf(), intArrayOf(0, 1, 2, 0, 2, 3)), grid, texturePage = 0)
        }
        val closing = KeyformGrid(listOf(KeyformAxis(eyeL, floatArrayOf(0f, 1f))), listOf(
            KeyformCell(intArrayOf(0), MeshDeltaForm(floatArrayOf(0.02f, 0.1f, 0.01f, 0.12f, 0f, 0f, 0f, 0f))),
            KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(8)))))
        val drawables = listOf(eye("ArtMeshEyeL", 0.1f, closing), eye("ArtMeshEyeR", 0.7f, null))
        return PuppetModel(listOf(Parameter(angle, "Angle X", -30f, 30f, 0f), Parameter(eyeL, "Eye L", 0f, 1f, 1f),
            Parameter(ParameterId("ParamEyeROpen"), "Eye R", 0f, 1f, 1f), Parameter(ParameterId("ParamAngleY"), "Angle Y", -10f, 30f, 0f)),
            emptyList(), listOf(face, arm), drawables, drawables.map { OrgChild.Drawable(it.id) }, null,
            canvasWidth = 100f, canvasHeight = 100f).withDerivedRenderRoot()
    }
}
