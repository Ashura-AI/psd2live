package org.umamo.interop.moc3

import org.umamo.format.moc3.Moc3
import org.umamo.render.canvasToParentSpaceFor
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Moc3UserDataTest {
	private fun puppet(vararg userData: String): PuppetModel {
		val parameter = ParameterId("p")
		val rotation = Deformer.Rotation(DeformerId("rot"), "rot", null, null, 20f,
			KeyformGrid(listOf(KeyformAxis(parameter, floatArrayOf(-1f, 1f))),
				listOf(KeyformCell(intArrayOf(0), RotationPivotForm(32f, 32f, 0f, 1f)),
					KeyformCell(intArrayOf(1), RotationPivotForm(32f, 32f, 10f, 1f)))))
		val mesh = DrawableMesh(floatArrayOf(0f, 0f, 8f, 0f, 0f, 4f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2))
		val drawables = userData.mapIndexed { index, value ->
			Drawable(DrawableId("mesh-$index"), "mesh-$index", rotation.id, BlendMode.Normal, emptyList(), mesh, null,
				texturePage = 0, userData = value)
		}
		return restMeshesToCanvasSpace(PuppetModel(listOf(Parameter(parameter, "p", -1f, 1f, 0f)), emptyList(),
			listOf(rotation), drawables, drawables.map { OrgChild.Drawable(it.id) }, null,
			canvasWidth = 64f, canvasHeight = 64f).withDerivedRenderRoot())
	}

	private fun bundle(model: PuppetModel, includeUserData: Boolean = true) = Moc3Sidecars.bundle(model, "m",
		pages = emptyList(), canvasToParentSpace = canvasToParentSpaceFor(model),
		options = Moc3ExportOptions(includeUserData = includeUserData))

	@Test fun userDataIsWrittenAndReferencedFromTheManifest() {
		val files = bundle(puppet("tap:head", "", "ゆれ")).files.associateBy { it.name }
		val manifest = Moc3.readModel3(files.getValue("m.model3.json").bytes.decodeToString())
		assertEquals("m.userdata3.json", manifest.fileReferences.userData)
		val userData = Moc3.readUserData3(files.getValue("m.userdata3.json").bytes.decodeToString())
		assertEquals(listOf("mesh-0" to "tap:head", "mesh-2" to "ゆれ"), userData.userData.map { it.id to it.value })
		assertEquals(listOf("ArtMesh"), userData.userData.map { it.target }.distinct())
		assertEquals(2, userData.meta.userDataCount)
		assertEquals("tap:head".length + "ゆれ".encodeToByteArray().size, userData.meta.totalUserDataSize)
	}

	@Test fun theOptionAndEmptyUserDataLeaveTheFileOut() {
		for (files in listOf(bundle(puppet("tap:head"), includeUserData = false).files, bundle(puppet("", "")).files)) {
			assertNull(files.firstOrNull { it.name.endsWith(".userdata3.json") })
			assertNull(Moc3.readModel3(files.single { it.name == "m.model3.json" }.bytes.decodeToString()).fileReferences.userData)
		}
	}

	@Test fun theBundleIsDeterministic() {
		val first = bundle(puppet("a", "b")).files
		val second = bundle(puppet("a", "b")).files
		assertEquals(first.map { it.name }, second.map { it.name })
		first.zip(second).forEach { (a, b) -> assertContentEquals(a.bytes, b.bytes, a.name) }
	}
}
