package io.github.psd2live.core.modifier

import io.github.psd2live.core.*
import io.github.psd2live.core.sim.*
import io.github.psd2live.project.*
import kotlin.test.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.runtime.model.*

class ModifierStackOrderTest {
	init { TestKinds.register() }

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
		val more = ModifierAuthoring.put(preview, moved, buildJsonObject { put("id", "Late"); put("kind", "transform"); putJsonArray("targets") { add(mesh.id.raw) } })
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
	fun `a mesh's stages are its own keyforms then each parent deformer's, each with what acts on it`() {
		val overlay = shift(RigEditOverlay(simEdits = listOf(simulation())))
		val model = overlay.applyTo(preview.baseRig.puppet)
		val stages = RigStages.chain(model, overlay, mesh.id.raw)
		val own = stages.first()
		assertEquals("mesh", own.kind)
		assertNull(own.owner)
		assertEquals(listOf("simulation:Skirt", "Shift"), own.entries.map { it.id }, "the mesh's own stage holds the entries on it, in stack order")
		assertTrue(own.axes.any { it.owner == RigStages.Owner(RigStages.Owner.Kind.SIMULATION, "Skirt") }, "the bake's axes are the simulation's")
		// Then every parent, innermost first, as the hierarchy nests them.
		val parents = generateSequence(model.drawables.single { it.id == mesh.id }.parentDeformerId?.raw) { id -> model.deformers.single { it.id.raw == id }.parent?.raw }.toList()
		assertEquals(parents, stages.drop(1).map { it.id })
		assertTrue(stages.drop(1).all { it.owner != null && it.entries.isEmpty() })
		// A modifier on a warp acts on that stage alone.
		val warp = stages.drop(1).firstOrNull { it.kind == "warp" } ?: return
		val onWarp = ModifierAuthoring.put(preview, overlay, buildJsonObject {
			put("id", "Lift"); put("kind", "transform"); putJsonArray("targets") { add(warp.key) }
		})
		val again = RigStages.chain(onWarp.applyTo(preview.baseRig.puppet), onWarp, mesh.id.raw)
		assertEquals(listOf("Lift"), again.single { it.key == warp.key }.entries.map { it.id })
		assertEquals(listOf("simulation:Skirt", "Shift"), again.first().entries.map { it.id })
	}

	@Test
	fun `a deformer is the base rig's by its ID, a swing's when the swing wraps it, else the user's`() {
		assertEquals(RigStages.Owner(RigStages.Owner.Kind.COMPONENT, "eyes"), RigStages.deformerOwner(RigEditOverlay(), "DeformEyeGaze"))
		assertEquals(RigStages.Owner(RigStages.Owner.Kind.COMPONENT, "breath"), RigStages.deformerOwner(RigEditOverlay(), "DeformBodyZBreath"))
		assertEquals(RigStages.Owner(RigStages.Owner.Kind.COMPONENT, "body"), RigStages.deformerOwner(RigEditOverlay(), "DeformBodyXY"))
		assertEquals(RigStages.Owner(RigStages.Owner.Kind.MANUAL, ""), RigStages.deformerOwner(RigEditOverlay(), "WarpMine"))
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
