package io.github.psd2live.core.modifier

import io.github.psd2live.core.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.project.*
import io.github.psd2live.format.model.ModifierStackIR
import io.github.psd2live.targets.runtime.P2lrt
import kotlin.math.hypot
import kotlin.test.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*

class ModifierExactTest {
	private val preview: RigPreviewModel by lazy {
		val w = 96
		val h = 144
		val raster = LayerRaster(w, h, ByteArray(w * h * 4) { if (it % 4 == 3) 255.toByte() else 140.toByte() })
		val layer = WorkspaceSourceLayer(LayerId("cloth"), "cloth", "", SourceLayerKind.Raster, true, 0, LayerBounds(12, 8, w, h),
			1f, false, LayerBlend.Normal, ChannelMask.ALL, raster, null, null, false)
		PSD2LivePipeline().buildPreview(WorkspaceSourceArt(128, 168, listOf(layer), emptyList()), PipelineConfig(atlasSize = 512, meshSpacing = 16))
	}

	private val target: String by lazy { preview.authored.rig.puppet.drawables.first { it.mesh != null }.id.raw }

	private val driver = buildJsonObject { put("id", "ParamModTurn"); put("name", "Turn"); put("min", -10); put("max", 10); put("default", 0) }

	private fun turn(degrees: Float) = buildJsonObject {
		put("id", "Turn"); put("kind", "transform")
		putJsonArray("targets") { add(target) }
		putJsonArray("keys") {
			addJsonObject { put("setting", "rotation"); putJsonObject("at") { put("ParamModTurn", -10) }; put("value", -degrees) }
			addJsonObject { put("setting", "rotation"); putJsonObject("at") { put("ParamModTurn", 10) }; put("value", degrees) }
		}
		put("parameters", JsonArray(listOf(driver)))
	}

	private fun canvas(model: PuppetModel, pose: Map<String, Float>): FloatArray {
		val world = CpuDeformationEvaluator().evaluate(model, pose.mapKeys { ParameterId(it.key) }).worldPositions.getValue(DrawableId(target))
		return FloatArray(world.size) { if (it % 2 == 0) world[it] else -world[it] }
	}

	private fun distance(a: FloatArray, b: FloatArray) = (0 until a.size / 2).maxOf { hypot(a[it * 2] - b[it * 2], a[it * 2 + 1] - b[it * 2 + 1]) }

	/** The rig with [overlay]'s modifiers, and the target's stack as the runtime reads it. */
	private fun stack(overlay: RigEditOverlay): Pair<PuppetModel, ModifierStackIR> {
		val before = overlay.copy(modifiers = emptyList()).applyTo(preview.baseRig.puppet)
		return overlay.applyTo(preview.baseRig.puppet) to ModifierExact.stacks(before, overlay.modifiers).single { it.mesh == target }
	}

	/** What advanced mode shows at [pose]: [model] with the stack's correction added to the target before its parents. */
	private fun corrected(model: PuppetModel, stack: ModifierStackIR, pose: Map<String, Float>): FloatArray {
		val add = ModifierExact.correction(stack) { pose[it] ?: 0f }
		return canvas(model.copy(drawables = model.drawables.map { d ->
			if (d.id.raw != target) d else d.copy(mesh = DrawableMesh(FloatArray(add.size) { d.mesh!!.positions[it] + add[it] }, d.mesh!!.uvs, d.mesh!!.indices))
		}), pose)
	}

	@Test
	fun `the correction is zero at the sampled keys and follows the turn between them`() {
		val overlay = ModifierAuthoring.put(preview, RigEditOverlay(), turn(60f))
		val (model, stack) = stack(overlay)
		val keys = model.drawables.single { it.id.raw == target }.geometryGrid!!.axes.single { it.parameterId.raw == "ParamModTurn" }.keys
		assertEquals(keys.toList(), stack.axes.single().keys.toArray().toList(), "the stack is sampled where its keyforms are")
		assertTrue(stack.start.axes.isEmpty(), "an unkeyed mesh starts from one shape")
		for (k in keys) assertTrue(ModifierExact.correction(stack) { k }.all { it == 0f }, "at $k")
		val (plainStack, _) = ModifierSampler.stack(ModifierSampler.withCreated(overlay.copy(modifiers = emptyList()).applyTo(preview.baseRig.puppet), overlay.modifiers),
			target, overlay.modifiers)!!
		for (i in 0 until keys.size - 1) {
			val v = (keys[i] + keys[i + 1]) / 2
			val pose = mapOf("ParamModTurn" to v)
			val exact = plainStack.canvasAt(pose)
			assertTrue(distance(exact, corrected(model, stack, pose)) < 0.02f, "at $v: ${distance(exact, corrected(model, stack, pose))}")
			assertTrue(distance(exact, ModifierExact.canvasAt(stack) { pose[it] ?: 0f }) < 1e-3f)
		}
	}

	@Test
	fun `a keyed mesh starts from its own keyforms at each of their keys`() {
		var overlay = ModifierAuthoring.put(preview, RigEditOverlay(), turn(60f))
		overlay = ModifierAuthoring.put(preview, overlay, buildJsonObject {
			put("id", "Shift"); put("kind", "transform"); putJsonArray("targets") { add(target) }
			putJsonArray("keys") { addJsonObject { put("setting", "offset"); putJsonObject("at") { put("ParamModTurn", 10) }; putJsonArray("value") { add(25); add(5) } } }
		})
		// The turn written into the journal: the shift starts from the mesh as those keyforms pose it.
		val applied = ModifierAuthoring.apply(preview, overlay, buildJsonObject { put("id", "Turn") })
		val (model, stack) = stack(applied)
		assertEquals(listOf("ParamModTurn"), stack.start.axes.map { it.parameter })
		for (v in listOf(-10f, -4f, 0f, 3.3f, 10f)) {
			// A shift keyed linearly moves the keyforms' own chords alike: nothing to correct.
			assertTrue(ModifierExact.correction(stack) { v }.all { kotlin.math.abs(it) < 1e-3f }, "at $v")
			assertTrue(distance(canvas(model, mapOf("ParamModTurn" to v)), ModifierExact.canvasAt(stack) { v }) < 0.05f, "at $v")
		}
	}

	@Test
	fun `runtime exports carry the stacks and a file without them is unchanged`() {
		val overlay = ModifierAuthoring.put(preview, RigEditOverlay(), turn(60f))
		val withModifiers = PSD2LivePipeline().buildPreview(preview.analysis, preview.config.copy(rigEdits = overlay))
		val ir = RigIrCompiler.compile(withModifiers, simulations = true)
		assertEquals(listOf(target), ir.advanced.modifiers.map { it.mesh })
		assertEquals(ir, RigIrBinary.decode(RigIrBinary.encode(ir)))
		assertTrue(RigIrCompiler.compile(withModifiers).advanced.modifiers.isEmpty(), "only the runtime's targets read them")
		fun tags(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)
		assertTrue("MODS" in tags(P2lrt.write(ir)))
		val bare = ir.copy(advanced = ir.advanced.copy(modifiers = emptyList()))
		assertFalse("MODS" in tags(P2lrt.write(bare)))
		assertFalse("MODS" in tags(P2lrt.write(ir, P2lrt.Options(advanced = false))))
		assertContentEquals(P2lrt.write(bare, P2lrt.Options(advanced = false)), P2lrt.write(ir, P2lrt.Options(advanced = false)))
	}
}
