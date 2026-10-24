package io.github.psd2live.core.modifier

import io.github.psd2live.core.*
import io.github.psd2live.core.sim.*
import io.github.psd2live.project.*
import kotlin.test.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.runtime.model.*

class ModifierStackOrderTest {
	private val preview: RigPreviewModel by lazy {
		val raster = LayerRaster(64, 128, ByteArray(64 * 128 * 4) { if (it % 4 == 3) 255.toByte() else 120.toByte() })
		val layer = WorkspaceSourceLayer(LayerId("skirt"), "skirt", "", SourceLayerKind.Raster, true, 0, LayerBounds(20, 20, 64, 128),
			1f, false, LayerBlend.Normal, ChannelMask.ALL, raster, null, null, false)
		PSD2LivePipeline().buildPreview(WorkspaceSourceArt(128, 168, listOf(layer), emptyList()), PipelineConfig(atlasSize = 512, meshSpacing = 16))
	}

	private val mesh by lazy { preview.authored.rig.puppet.drawables.first { it.mesh != null } }

	/** A baked simulation of one mode on the mesh, its bake current for the stack it is baked in. */
	private fun simulation(overlay: RigEditOverlay = RigEditOverlay()): RigSimEdit {
		val count = mesh.mesh!!.vertexCount
		val mode = SimBakedAxis("ParamSimSkirt_1", floatArrayOf(-30f, 0f, 30f), mapOf(mesh.id.raw to listOf(
			FloatArray(count * 2) { if (it % 2 == 0) -0.05f else 0f }, FloatArray(count * 2), FloatArray(count * 2) { if (it % 2 == 0) 0.05f else 0f })))
		val bare = RigSimEdit("Skirt", "Skirt", SimKind.CLOTH, listOf(mesh.id.raw), blendShapes = false)
		val with = overlay.copy(simEdits = overlay.simEdits + bare)
		val model = SimAuthoring.unbakedModel(with, SimAuthoring.AuthoredRigs(preview::authoredPuppet), "Skirt")
		return bare.copy(bake = SimBakeResult(SimBake.fingerprint(model, bare, SimBake.preceding(with, bare, model)), mapOf(mesh.id.raw to count),
			emptyList(), listOf(SimBakedMode(mode, 1f, 1f))))
	}

	private fun shift(overlay: RigEditOverlay) = ModifierAuthoring.put(preview, overlay, buildJsonObject {
		put("id", "Shift"); put("kind", "transform"); putJsonArray("targets") { add(mesh.id.raw) }
		putJsonObject("settings") { putJsonArray("offset") { add(12); add(0) } }
	})

	@Test
	fun `a stack never ordered runs as documents always ran, and moving a generator orders it`() {
		val overlay = shift(RigEditOverlay(simEdits = listOf(simulation())))
		assertEquals(listOf("simulation:Skirt", "Shift"), ModifierStack.entries(overlay).map { it.id })
		assertTrue(overlay.stackOrder.isEmpty())
		val moved = ModifierAuthoring.move(overlay, buildJsonObject { put("id", "simulation:Skirt"); put("to", "last") })
		assertEquals(listOf("Shift", "simulation:Skirt"), moved.stackOrder)
		assertEquals(listOf("Shift", "simulation:Skirt"), ModifierStack.entries(moved).map { it.id })
		assertNotEquals(WorkspaceRevisions.reference(WorkspaceDocument(preview.analysis.source as WorkspaceSourceArt, emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay)),
			WorkspaceRevisions.reference(WorkspaceDocument(preview.analysis.source as WorkspaceSourceArt, emptyMap(), emptySet(), emptyMap(), emptyMap(), moved)))
		// A new modifier joins after the ordered entries, a new generator before them.
		val more = ModifierAuthoring.put(preview, moved, buildJsonObject { put("id", "Late"); put("kind", "twist"); putJsonArray("targets") { add(mesh.id.raw) } })
		assertEquals(listOf("Shift", "simulation:Skirt", "Late"), ModifierStack.entries(more).map { it.id })
	}

	@Test
	fun `a simulation reads only what runs before it, and its bake goes stale when that changes`() {
		val sim = simulation()
		val overlay = shift(RigEditOverlay(simEdits = listOf(sim)))
		val model = preview.rig.puppet
		assertFalse(SimBake.stale(model, sim, overlay), "the modifier runs after it: its bake is as it was")
		val before = ModifierAuthoring.move(overlay, buildJsonObject { put("id", "Shift"); put("to", "first") })
		assertTrue(SimBake.stale(model, sim, before), "a modifier before it changes what it bakes on")
		assertTrue(ModifierStack.before(overlay, "simulation:Skirt").modifiers.isEmpty(), "a bake does not read the modifier after it")
		assertEquals(listOf("Shift"), ModifierStack.before(before, "simulation:Skirt").modifiers.map { it.id })
		// The two orders give different rigs: the modifier reads the simulation's keyforms only when it comes after.
		val after = overlay.applyTo(preview.baseRig.puppet).drawables.single { it.id == mesh.id }.geometryGrid!!
		val first = before.applyTo(preview.baseRig.puppet).drawables.single { it.id == mesh.id }.geometryGrid!!
		assertEquals(after.axes.map { it.parameterId }.toSet(), first.axes.map { it.parameterId }.toSet())
	}

	@Test
	fun `the order saves and restores with the project`() {
		val overlay = ModifierAuthoring.move(shift(RigEditOverlay(simEdits = listOf(simulation()))), buildJsonObject { put("id", "Shift"); put("to", "first") })
		val document = WorkspaceDocument(preview.analysis.source as WorkspaceSourceArt, emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay)
		assertEquals(ProjectFormatV2.NODE_SCHEMA_MODIFIERS, ProjectFormatV2.schemaOf("rig", buildJsonObject {
			putJsonObject("rigEdits") { putJsonArray("stackOrder") { overlay.stackOrder.forEach { add(it) } } }
		}))
		assertEquals(listOf("Shift", "simulation:Skirt"), document.rigEdits.stackOrder)
	}
}
