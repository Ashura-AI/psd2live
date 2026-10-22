package org.umamo.interop.moc3

import kotlinx.serialization.json.*
import org.umamo.format.moc3.Moc3
import org.umamo.interop.mocVersion
import org.umamo.render.canvasToParentSpaceFor
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Expression, pose and motion sidecars name the objects as the moc wrote them, and leave out parts it did not write. */
class Moc3SidecarIdsTest {
	/** An id longer than a moc record holds, so the lowering shortens it. */
	private val longParameter = "Param" + "X".repeat(70)

	private fun puppet(): PuppetModel {
		val parameter = ParameterId(longParameter)
		val rotation = Deformer.Rotation(DeformerId("rot"), "rot", null, null, 20f,
			KeyformGrid(listOf(KeyformAxis(parameter, floatArrayOf(-1f, 1f))),
				listOf(KeyformCell(intArrayOf(0), RotationPivotForm(32f, 32f, 0f, 1f)),
					KeyformCell(intArrayOf(1), RotationPivotForm(32f, 32f, 10f, 1f)))))
		val mesh = DrawableMesh(floatArrayOf(0f, 0f, 8f, 0f, 0f, 4f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2))
		val drawables = listOf("a", "guide").map {
			Drawable(DrawableId("mesh-$it"), "mesh-$it", rotation.id, BlendMode.Normal, emptyList(), mesh, null, texturePage = 0)
		}
		val parts = listOf(Part(PartId("PartA"), "A", listOf(OrgChild.Drawable(drawables[0].id))),
			Part(PartId("PartGuide"), "Guide", listOf(OrgChild.Drawable(drawables[1].id)), isSketch = true))
		return restMeshesToCanvasSpace(PuppetModel(listOf(Parameter(parameter, "p", -1f, 1f, 0f)), parts,
			listOf(rotation), drawables, parts.map { OrgChild.Part(it.id) }, null,
			canvasWidth = 64f, canvasHeight = 64f).withDerivedRenderRoot())
	}

	private fun sidecar(kind: Moc3Sidecars.SidecarKind, name: String, json: String) = Moc3Sidecars.PassThroughSidecar(kind, name, json)

	private val exp3 = """{"Type":"Live2D Expression","Parameters":[{"Id":"$longParameter","Value":0.5,"Blend":"Add"},{"Id":"ParamElsewhere","Value":1,"Blend":"Overwrite"}]}"""
	private val pose3 = """{"Type":"Live2D Pose","FadeInTime":0.5,"Groups":[[{"Id":"PartA","Link":["PartGuide"]},{"Id":"PartGuide","Link":[]}],[{"Id":"PartGuide","Link":[]}]]}"""
	private val motion3 = """{"Version":3,"Curves":[{"Target":"Parameter","Id":"$longParameter","Segments":[0,0,0,1,1]},{"Target":"PartOpacity","Id":"PartA","Segments":[0,1]}]}"""

	@Test fun sidecarsUseTheWrittenIdsAndDropUnwrittenParts() {
		val model = puppet()
		val bundle = Moc3Sidecars.bundle(model, "m", pages = emptyList(), canvasToParentSpace = canvasToParentSpaceFor(model), sidecars = listOf(
			sidecar(Moc3Sidecars.SidecarKind.Expression, "m.e.exp3.json", exp3),
			sidecar(Moc3Sidecars.SidecarKind.Pose, "m.pose3.json", pose3),
			sidecar(Moc3Sidecars.SidecarKind.Motion, "m.idle.motion3.json", motion3)))
		val files = bundle.files.associate { it.name to it.bytes.decodeToString() }
		val written = Moc3.readCdi3(files.getValue("m.cdi3.json")).parameters.single().id
		assertNotEquals(longParameter, written)
		val expression = Json.parseToJsonElement(files.getValue("m.e.exp3.json")).jsonObject.getValue("Parameters").jsonArray
		assertEquals(listOf(written, "ParamElsewhere"), expression.map { it.jsonObject.getValue("Id").jsonPrimitive.content })
		assertEquals("0.5", expression.first().jsonObject.getValue("Value").jsonPrimitive.content)
		val groups = Json.parseToJsonElement(files.getValue("m.pose3.json")).jsonObject.getValue("Groups").jsonArray
		assertEquals(listOf(listOf("PartA" to emptyList<String>())), groups.map { group -> group.jsonArray.map { entry ->
			entry.jsonObject.getValue("Id").jsonPrimitive.content to entry.jsonObject.getValue("Link").jsonArray.map { it.jsonPrimitive.content }
		} })
		val curves = Json.parseToJsonElement(files.getValue("m.idle.motion3.json")).jsonObject.getValue("Curves").jsonArray
		assertEquals(listOf(written, "PartA"), curves.map { it.jsonObject.getValue("Id").jsonPrimitive.content })
	}

	@Test fun aPoseOfUnwrittenPartsGoesWithItsReference() {
		val model = puppet()
		val pose = sidecar(Moc3Sidecars.SidecarKind.Pose, "m.pose3.json", """{"Type":"Live2D Pose","Groups":[[{"Id":"PartGuide","Link":[]}]]}""")
		val bundle = Moc3Sidecars.bundle(model, "m", pages = emptyList(), canvasToParentSpace = canvasToParentSpaceFor(model), sidecars = listOf(pose))
		assertNull(bundle.files.firstOrNull { it.name == "m.pose3.json" })
		assertNull(Moc3.readModel3(bundle.files.single { it.name == "m.model3.json" }.bytes.decodeToString()).fileReferences.pose)
	}

	@Test fun anUnchangedSidecarKeepsItsText() {
		val model = puppet()
		val ids = org.umamo.interop.moc3.export.Moc3Export.toMocDocument(model, model.runtimeTarget.mocVersion(), canvasToParentSpaceFor(model)).writtenIds
		val motion = sidecar(Moc3Sidecars.SidecarKind.Motion, "m.motion3.json", """{"Version":3,"Curves":[{"Target":"PartOpacity","Id":"PartA","Segments":[0,1]}]}""")
		assertSame(motion, Moc3Sidecars.withWrittenIds(motion, model, ids))
	}
}
