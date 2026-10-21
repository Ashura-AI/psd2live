package io.github.psd2live.scenario

import io.github.psd2live.core.IrGeometryEvaluator
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.core.SeeThroughLayers
import io.github.psd2live.format.eval.NativeGeometryEvaluator
import io.github.psd2live.format.eval.P2lRuntime
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.ParameterId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The eyebrows show through the bangs: the generated rig copies each one over the front hair, clipped to it at partial
 * opacity, and the copy follows its original through hand edits, a setting change, a reopen and the moc3, cmo3 and p2lrt
 * exports.
 */
class SeeThroughScenario {
	@TempDir lateinit var temp: Path

	@Test fun browCopiesSitOverTheBangsAndFollowTheirOriginals() = Studio.run(temp, Characters.browed()) {
		val hair = mesh("front_hair"); val brow = mesh("brow_l"); val other = mesh("brow_r")
		val copy = SeeThroughLayers.copyId(DrawableId(brow)).raw
		fun drawable(id: String) = puppet.drawables.single { it.id.raw == id }
		fun shown(pose: Map<String, Float>) = CpuDeformationEvaluator().evaluate(puppet, pose.mapKeys { ParameterId(it.key) })

		expect("copies", puppet.drawables.count { SeeThroughLayers.isCopy(it.id) } == 2 &&
			puppet.drawables.any { it.id == SeeThroughLayers.copyId(DrawableId(other)) }) { "copies: ${puppet.drawables.filter { SeeThroughLayers.isCopy(it.id) }.map { it.id.raw }}" }
		val made = drawable(copy)
		val next = puppet.drawables.filter { it.drawOrder > drawable(hair).drawOrder && !SeeThroughLayers.isCopy(it.id) }.minOfOrNull { it.drawOrder }
		expect("structure", made.maskedBy == listOf(DrawableId(hair)) && made.opacity == SeeThroughLayers.DEFAULT_OPACITY && !made.isSelectable &&
			made.parentDeformerId == drawable(brow).parentDeformerId && made.drawOrder > drawable(hair).drawOrder &&
			(next == null || made.drawOrder < next)) { "copy ${made.maskedBy} ${made.opacity} ${made.isSelectable} ${made.parentDeformerId} ${made.drawOrder}, hair ${drawable(hair).drawOrder}, next $next" }
		expect("part", puppet.parts.single { part -> part.children.any { it is OrgChild.Drawable && it.id.raw == hair } }
			.children.any { it is OrgChild.Drawable && it.id.raw == copy }) { "the copy is not in the bangs' part" }
		val browY = puppet.parameters.first { it.id.raw.startsWith("ParamBrowL") }.id.raw
		expect("generated keyforms", motion(brow, browY, 1f) > 0.5 && abs(motion(copy, browY, 1f) - motion(brow, browY, 1f)) < 1e-3) {
			"the copy moves ${motion(copy, browY, 1f)}, its brow ${motion(brow, browY, 1f)}" }

		// A key names every axis the brow is bound on: the generated ones at their defaults.
		fun key() = (drawable(brow).geometryGrid?.axes.orEmpty().map { it.parameterId.raw } - "ParamFrown")
			.associateWith { id -> puppet.parameters.single { it.id.raw == id }.default } + ("ParamFrown" to 1f)
		edits("frown the brow on a new axis",
			"parameter_create" to req("parameter_id" to "ParamFrown", "name" to "Frown", "min" to 0, "max" to 1),
			"rig_deform" to req("changes" to listOf(mapOf("target" to "mesh:$brow", "key" to key(),
				"operations" to listOf(mapOf("type" to "translate", "delta" to listOf(0, 0.5)))))))
		edit("fade the brow on the axis", "keyform_apply", req("changes" to listOf(
			mapOf("target" to "mesh:$brow", "key" to key(), "op" to "set", "channels" to mapOf("opacity" to 0.4)))))
		fun follows(label: String, alpha: Float) {
			val pose = mapOf("ParamFrown" to 1f)
			expect(label, motion(brow, "ParamFrown", 1f) > 1.0 && bounds(copy, pose).contentEquals(bounds(brow, pose))) {
				"the copy is at ${bounds(copy, pose).toList()}, its brow at ${bounds(brow, pose).toList()}" }
			val faded = shown(pose).opacity[DrawableId(copy)]
			expect(label, faded != null && abs(faded - 0.4f * alpha) < 0.01f) { "the copy shows opacity $faded at Frown 1" }
		}
		follows("hand edits", SeeThroughLayers.DEFAULT_OPACITY)

		edit("raise the see-through opacity", "settings_update", req("changes" to mapOf("seeThroughOpacity" to 0.8)))
		expect("opacity", drawable(copy).opacity == 0.8f) { "the copy's opacity is ${drawable(copy).opacity}" }
		follows("regenerated", 0.8f)
		reopen()
		follows("reopened", 0.8f)

		edit("export moc3 too", "settings_update", req("changes" to mapOf("exportMoc3" to true)))
		val moc3 = moc3()
		exportMatchesEditor(this, moc3)
		expect("moc3", moc3.drawables.single { it.id.raw == copy }.maskedBy == listOf(DrawableId(hair))) { "the moc3 copy is not clipped by the bangs" }
		val cmo3 = export("cmo3").single { it.toString().endsWith(".cmo3") }
		val readBack = step("read the cmo3 back") { Cmo3Import.fromModelSource(Cmo3.read(Files.readAllBytes(cmo3)).root as CModelSource) }
		val written = readBack.drawables.single { it.id.raw == copy }
		// The copy shows at its opacity times the brow's, wherever the brow's own keyforms land in the cmo3.
		val poses = listOf(emptyMap(), mapOf(ParameterId("ParamFrown") to 1f))
		val pairs = poses.map { pose -> CpuDeformationEvaluator().evaluate(readBack, pose).opacity.let { it[DrawableId(copy)] to it[DrawableId(brow)] } }
		expect("cmo3", written.maskedBy == listOf(DrawableId(hair)) && pairs.all { (c, b) -> c != null && b != null && abs(c - 0.8f * b) < 1e-3 }) {
			"the cmo3 copy is clipped by ${written.maskedBy}, at opacities (copy, brow) $pairs" }

		// The runtime target compiles the same rig: the copy keeps its masks and plays where the editor shows it.
		val ir = RigIrCompiler.compile(now.model)
		expect("p2lrt", ir.meshes.single { it.id == copy }.maskedBy == listOf(hair)) { "the compiled copy is not clipped by the bangs" }
		val p2lrt = export("p2lrt").single { it.toString().endsWith(".p2lrt") }
		P2lRuntime.load()?.let { runtime ->
			runtime.load(Files.readAllBytes(p2lrt)).close()
			NativeGeometryEvaluator(runtime).open(ir).use { native -> IrGeometryEvaluator.open(ir).use { editor ->
				for (pose in listOf(emptyMap(), mapOf("ParamFrown" to 1f), mapOf("ParamAngleX" to 30f))) {
					val a = editor.evaluate(pose).positions.getValue(copy); val b = native.evaluate(pose).positions.getValue(copy)
					expect("p2lrt", a.indices.maxOf { abs(a[it] - b[it]) } < 0.5f) { "at $pose the native runtime moves the copy elsewhere" }
				}
			} }
		} ?: println("p2lrt: the native runtime is not built; its playback was not compared")

		edit("turn the copies off", "settings_update", req("changes" to mapOf("seeThroughBrows" to false)))
		expect("off", puppet.drawables.none { SeeThroughLayers.isCopy(it.id) }) { "copies remain" }
	}

	@Test fun aDocumentWithTheCopiesOffMakesNone() {
		val document = Characters.browed()
		Studio.run(temp, document.copy(settings = WorkspaceSettingsCodec.encode(PipelineConfig(atlasSize = 512, generatePhysics = false,
			exportMoc3 = false, seeThroughBrows = false)))) {
			expect("off", puppet.drawables.none { SeeThroughLayers.isCopy(it.id) }) { "copies without the setting" }
		}
	}

	@Test fun documentsSavedBeforeTheSettingMakeNoCopies() {
		val saved = WorkspaceSettingsCodec.encode(PipelineConfig()).let { JsonObject(it - "seeThroughBrows" - "seeThroughEyes" - "seeThroughOpacity") }
		val decoded = WorkspaceSettingsCodec.decode(saved)
		assertEquals(false, decoded.seeThroughBrows)
		assertEquals(false, decoded.seeThroughEyes)
		val fresh = WorkspaceSettingsCodec.decode(WorkspaceSettingsCodec.encode(PipelineConfig()))
		assertEquals(true, fresh.seeThroughBrows)
		assertEquals(SeeThroughLayers.DEFAULT_OPACITY, fresh.seeThroughOpacity)
		// A patch without the key keeps what the document has.
		assertEquals(true, WorkspaceSettingsCodec.decode(JsonObject(emptyMap()), fresh).seeThroughBrows)
	}
}
