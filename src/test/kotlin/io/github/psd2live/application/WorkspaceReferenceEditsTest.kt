package io.github.psd2live.application

import io.github.psd2live.project.LayerCanvasRect
import io.github.psd2live.project.LayoutReference
import io.github.psd2live.project.WorkspaceLayoutCodec
import io.github.psd2live.scenario.Characters
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceReferenceEditsTest {
	private val document = WorkspaceMaterialEdits.addLinked(Characters.head(false), "head.psd")
	private val face = document.layout.layerMaterials.getValue("face")
	private val hair = document.layout.layerMaterials.getValue("hair")

	@Test
	fun referencesAreAddedTurnedLockedLaidUnderAndReordered() {
		val one = WorkspaceMaterialEdits.addReference(document, buildJsonObject { put("material_id", face) })
		val two = WorkspaceMaterialEdits.addReference(one, buildJsonObject { put("material_id", hair); put("opacity", 0.8f) })
		val (first, second) = two.layout.references
		assertEquals(LayoutReference.FIRST_ID, first.id)
		assertTrue(second.id.startsWith("ref_") && second.materialId == hair && second.opacity == 0.8f)
		val turned = WorkspaceMaterialEdits.reference(two, buildJsonObject {
			put("id", second.id); put("rotation", -30f); put("locked", true); put("under", true)
		}).layout.reference(second.id)!!
		assertEquals(330f, turned.rotation)
		assertTrue(turned.locked && turned.under && turned.rect == second.rect)
		// Without an id the first one is meant, as before there could be several.
		val faded = WorkspaceMaterialEdits.reference(two, buildJsonObject { put("opacity", 0.25f) })
		assertEquals(listOf(0.25f, 0.8f), faded.layout.references.map { it.opacity })
		val ordered = WorkspaceMaterialEdits.orderReference(two, second.id, 0)
		assertEquals(listOf(second.id, first.id), ordered.layout.references.map { it.id })
		val removed = WorkspaceMaterialEdits.reference(ordered, buildJsonObject { put("id", second.id); put("material_id", JsonNull) })
		assertEquals(listOf(first.id), removed.layout.references.map { it.id })
	}

	@Test
	fun aSingleReferenceIsWrittenAsDocumentsBeforeSeveralWrote() {
		val single = document.layout.copy(references = listOf(LayoutReference(face, LayerCanvasRect(1f, 2f, 30f, 40f), 0.4f)))
		val written = WorkspaceLayoutCodec.encodeLayout(single)!!
		assertEquals(setOf("materialId", "rect", "opacity"), written.getValue("reference").jsonObject.keys)
		assertNull(written["references"])
		val several = single.copy(references = single.references + LayoutReference(hair, LayerCanvasRect(0f, 0f, 10f, 10f), id = "ref_x",
			rotation = 15f, locked = true, under = true))
		val read = WorkspaceLayoutCodec.decode(WorkspaceLayoutCodec.encodeLayout(several), null) { _, _, _ -> ByteArray(0) }
		assertEquals(several.references, read.references)
		assertEquals(single.references, WorkspaceLayoutCodec.decode(written, null) { _, _, _ -> ByteArray(0) }.references)
	}

	@Test
	fun aReferenceMovesBetweenBackgroundLayerAndForegroundKeepingItsPin() = kotlinx.coroutines.runBlocking {
		val builder = WorkspacePreviewBuilder()
		val model = builder.build(document)
		val parameter = model.rig.puppet.parameters.first()
		val image = WorkspaceMaterialEdits.addReference(document, buildJsonObject {
			put("material_id", face); put("opacity", 0.5f)
			put("pin", buildJsonObject { put(parameter.id.raw, parameter.max) })
		})
		val reference = image.layout.references.single()
		suspend fun mode(document: io.github.psd2live.project.WorkspaceDocument, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
			WorkspaceReferenceModes.apply(document, builder.build(document), buildJsonObject(build))
		val behind = mode(image) { put("reference_id", reference.id); put("mode", "background") }
		assertTrue(behind.layout.references.single().under)
		// Into the stack: a reference layer showing the same material over the same rectangle, pinned the same.
		val layered = mode(behind) { put("reference_id", reference.id); put("mode", "layer") }
		assertTrue(layered.layout.references.isEmpty())
		val layer = layered.layout.referenceLayerIds.single()
		assertEquals(face, layered.layout.layerMaterials[layer])
		assertEquals(mapOf(parameter.id.raw to parameter.max), layered.layout.referencePins[layer])
		// And out again in front: an image once more, the layer gone.
		val front = mode(layered) { put("layer_id", layer); put("mode", "foreground") }
		val back = front.layout.references.single()
		assertTrue(!back.under && back.materialId == face && back.pins == reference.pins)
		assertTrue(front.layout.referenceLayerIds.isEmpty() && front.source.layers.none { it.id.raw == layer })
		assertEquals(reference.rect, back.rect)
	}
}
