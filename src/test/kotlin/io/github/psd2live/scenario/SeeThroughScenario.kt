package io.github.psd2live.scenario

import io.github.psd2live.application.WorkspaceFailure
import io.github.psd2live.core.IrGeometryEvaluator
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.core.SeeThroughLayers
import io.github.psd2live.format.eval.NativeGeometryEvaluator
import io.github.psd2live.format.eval.P2lRuntime
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The eyebrows show through the bangs: the finished rig copies each one over the front hair, clipped to it at partial
 * opacity. The copy follows its original through hand edits, opacity and draw-order changes, a setting change and a
 * reopen, reaches the moc3, cmo3 and p2lrt exports, and refuses edits of its own.
 */
class SeeThroughScenario {
	@TempDir lateinit var temp: Path

	@Test fun browCopiesSitOverTheBangsAndFollowTheirOriginals() = Studio.run(temp, Characters.browed()) {
		val hair = mesh("front_hair"); val brow = mesh("brow_l"); val other = mesh("brow_r")
		val copy = SeeThroughLayers.copyId(DrawableId(brow)).raw
		fun drawable(id: String) = puppet.drawables.single { it.id.raw == id }
		fun copies() = now.model.rig.seeThroughCopies
		fun shown(pose: Map<String, Float>) = CpuDeformationEvaluator().evaluate(puppet, pose.mapKeys { ParameterId(it.key) })
		fun nextAbove(order: Float) = puppet.drawables.filter { it.drawOrder > order && it.id.raw !in copies() }.minOfOrNull { it.drawOrder }

		expect("copies", copies() == mapOf(copy to brow, SeeThroughLayers.copyId(DrawableId(other)).raw to other)) { "copies: ${copies()}" }
		val made = drawable(copy)
		val next = nextAbove(drawable(hair).drawOrder)
		expect("structure", made.maskedBy == listOf(DrawableId(hair)) && made.opacity == drawable(brow).opacity * SeeThroughLayers.DEFAULT_OPACITY &&
			!made.isSelectable && made.parentDeformerId == drawable(brow).parentDeformerId && made.drawOrder > drawable(hair).drawOrder &&
			(next == null || made.drawOrder < next)) { "copy ${made.maskedBy} ${made.opacity} ${made.isSelectable} ${made.parentDeformerId} ${made.drawOrder}, hair ${drawable(hair).drawOrder}, next $next" }
		// Just in front of its brow, in the brow's part: hiding or fading that part takes the copy with it.
		val children = puppet.parts.single { part -> part.children.any { it is OrgChild.Drawable && it.id.raw == brow } }.children
		expect("part", children.indexOf(OrgChild.Drawable(DrawableId(copy))) == children.indexOf(OrgChild.Drawable(DrawableId(brow))) - 1) {
			"the copy is not just in front of its brow: $children" }
		val browY = puppet.parameters.first { it.id.raw.startsWith("ParamBrowL") }.id.raw
		expect("generated keyforms", motion(brow, browY, 1f) > 0.5 && abs(motion(copy, browY, 1f) - motion(brow, browY, 1f)) < 1e-3) {
			"the copy moves ${motion(copy, browY, 1f)}, its brow ${motion(brow, browY, 1f)}" }

		// Nothing recorded on a copy would last: an edit naming one is refused.
		for ((name, operation, request) in listOf(
			Triple("key the copy", "keyform_apply", req("changes" to listOf(mapOf("target" to "mesh:$copy", "key" to mapOf("ParamAngleX" to 0), "op" to "seed")))),
			Triple("fade the copy", "rig_edit_structure", req("edits" to listOf(mapOf("action" to "static", "kind" to "mesh", "id" to copy, "opacity" to 0.2)))),
			Triple("order the copy", "layer_draw_order", req("target" to "mesh:$copy", "order" to 900)),
		)) {
			val failure = WorkspaceFailure.from(refused(name, operation, request))
			expect(name, failure.code == "generated_copy" && failure.details["original"]?.jsonPrimitive?.content == brow) { "refused as ${failure.toJson()}" }
		}

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

		// The opacity is read when the copies are made: changing it replays, it does not regenerate.
		val journal = now.document.rigEdits.authoringJournal
		edit("raise the see-through opacity", "settings_update", req("changes" to mapOf("seeThroughOpacity" to 0.8)))
		expect("replayed", now.document.rigEdits.authoringJournal == journal) { "the opacity change regenerated the rig" }
		follows("opacity", 0.8f)
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
		fun pairs(model: PuppetModel) = listOf(emptyMap(), mapOf(ParameterId("ParamFrown") to 1f))
			.map { pose -> CpuDeformationEvaluator().evaluate(model, pose).opacity.let { it[DrawableId(copy)] to it[DrawableId(brow)] } }
		expect("cmo3", written.maskedBy == listOf(DrawableId(hair)) && pairs(readBack).all { (c, b) -> c != null && b != null && abs(c - 0.8f * b) < 1e-3 }) {
			"the cmo3 copy is clipped by ${written.maskedBy}, at opacities (copy, brow) ${pairs(readBack)}" }

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

		// An imported model is the file's: its copy is an ordinary mesh, kept as written, never made again.
		Studio.run(Files.createTempDirectory(temp, "import"), checks = emptySet()) {
			importCmo3(cmo3)
			expect("imported", now.model.rig.seeThroughCopies.isEmpty() && puppet.drawables.count { it.id.raw == copy } == 1 &&
				pairs(puppet).zip(pairs(readBack)).all { (a, b) -> a.first != null && b.first != null && abs(a.first!! - b.first!!) < 1e-3 }) {
				"the imported copy: ${now.model.rig.seeThroughCopies}, opacities ${pairs(puppet)} against ${pairs(readBack)}" }
		}

		// The other brow's own opacity reaches its copy (this one's is keyed).
		val otherCopy = SeeThroughLayers.copyId(DrawableId(other))
		edit("fade the other brow", "rig_edit_structure", req("edits" to listOf(mapOf("action" to "static", "kind" to "mesh", "id" to other, "opacity" to 0.5))))
		val rest = shown(emptyMap()).opacity
		expect("static opacity", abs(rest.getValue(DrawableId(other)) - 0.5f) < 0.01f && abs(rest.getValue(otherCopy) - 0.4f) < 0.01f) {
			"the other brow shows ${rest[DrawableId(other)]}, its copy ${rest[otherCopy]}" }

		// The copies draw where the bangs are now: behind the brows there is nothing to show through.
		edit("put the bangs behind the brows", "layer_draw_order", req("target" to "layer:front_hair", "order" to 5))
		expect("behind", copies().isEmpty()) { "copies with the bangs behind the brows: ${copies()}" }
		edit("raise the bangs above everything", "layer_draw_order", req("target" to "layer:front_hair", "order" to 990))
		expect("raised", copy in copies() && drawable(copy).drawOrder > drawable(hair).drawOrder &&
			(nextAbove(drawable(hair).drawOrder)?.let { drawable(copy).drawOrder < it } ?: true)) {
			"the copy draws at ${drawable(copy).drawOrder}, the bangs at ${drawable(hair).drawOrder}" }

		edit("turn the copies off", "settings_update", req("changes" to mapOf("seeThroughBrows" to false)))
		expect("off", copies().isEmpty() && puppet.drawables.none { it.id.raw == copy }) { "copies remain" }
	}

	/**
	 * Making the copies on the finished rig of [Characters.browed] by hand: hidden bangs make none, a missing original or
	 * bang and a taken id are reported, a mesh already named like a copy is left alone, and a drag preview moves the copy.
	 */
	@Test fun copiesAreMadeOnlyOfWhatIsThere() = Studio.run(temp, Characters.browed(), checks = emptySet()) {
		val hair = mesh("front_hair"); val brow = mesh("brow_l")
		val candidates = now.model.rig.seeThrough.orEmpty()
		assertEquals(setOf(brow, mesh("brow_r")), candidates.map { it.original }.toSet())
		assertTrue(candidates.all { it.bangs == listOf(hair) && !it.eyes })
		val copies = now.model.rig.seeThroughCopies.keys
		val plain = puppet.copy(drawables = puppet.drawables.filter { it.id.raw !in copies })
		val settings = SeeThroughLayers.Settings.of(now.model.config)
		fun with(model: PuppetModel, id: String, change: (Drawable) -> Drawable) = model.copy(drawables = model.drawables.map { if (it.id.raw == id) change(it) else it })

		assertEquals(copies, SeeThroughLayers.apply(plain, candidates, settings).copies.keys)
		assertTrue(SeeThroughLayers.apply(with(plain, hair) { it.copy(isVisible = false) }, candidates, settings).copies.isEmpty(), "hidden bangs")
		val noBrow = SeeThroughLayers.apply(plain.copy(drawables = plain.drawables.filter { it.id.raw != brow }), candidates, settings)
		assertEquals(listOf(SeeThroughLayers.Note(brow, SeeThroughLayers.DropReason.ORIGINAL_MISSING)), noBrow.notes)
		val noHair = SeeThroughLayers.apply(plain.copy(drawables = plain.drawables.filter { it.id.raw != hair }), candidates, settings)
		assertEquals(setOf(SeeThroughLayers.DropReason.MASKS_MISSING), noHair.notes.map { it.reason }.toSet())
		// A mesh of the document's own with a copy's id is never overwritten.
		val taken = SeeThroughLayers.copyId(DrawableId(brow))
		val own = plain.drawables.single { it.id.raw == hair }.copy(id = taken, name = "own")
		val result = SeeThroughLayers.apply(plain.copy(drawables = plain.drawables + own), candidates, settings)
		assertEquals(own, result.model.drawables.single { it.id == taken })
		assertTrue(SeeThroughLayers.Note(brow, SeeThroughLayers.DropReason.ID_TAKEN) in result.notes)
		// An eye-line setting copies nothing here: there are no eye lines.
		assertTrue(SeeThroughLayers.apply(plain, candidates, settings.copy(brows = false, eyes = true)).copies.isEmpty())
		// A drag preview moves the copy with its brow before the commit.
		val moved = with(puppet, brow) { it.copy(geometryGrid = null) }
		assertEquals(null, SeeThroughLayers.refresh(moved, now.model.rig.seeThroughCopies).drawables.single { it.id == taken }.geometryGrid)

		// The project report lists what could not be made.
		val report = io.github.psd2live.core.quality.GeneratedOverrideQuality.report(emptyList(), emptyList(), noBrow.notes.map { it.original to it.reason.wire })
		io.github.psd2live.application.validateOperationSchema(report, io.github.psd2live.application.WorkspaceQualitySchemas.generatedOverrides)
		val overrides = inspect().getValue("quality").jsonObject.getValue("overrides").jsonObject
		assertEquals(3, overrides.getValue("checks").jsonArray.size)
	}

	@Test fun aDocumentWithTheCopiesOffMakesNone() {
		val document = Characters.browed()
		Studio.run(temp, document.copy(settings = WorkspaceSettingsCodec.encode(PipelineConfig(atlasSize = 512, generatePhysics = false,
			exportMoc3 = false, seeThroughBrows = false)))) {
			expect("off", now.model.rig.seeThroughCopies.isEmpty() && puppet.drawables.none { it.id.raw.endsWith(SeeThroughLayers.SUFFIX) }) {
				"copies without the setting" }
			// Turning them on replays: the base already knows what the bangs cover.
			edit("turn the copies on", "settings_update", req("changes" to mapOf("seeThroughBrows" to true)))
			expect("on", now.model.rig.seeThroughCopies.size == 2) { "copies: ${now.model.rig.seeThroughCopies}" }
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
		// A mesh-only model has none whatever its settings say.
		assertEquals(SeeThroughLayers.Settings.OFF, SeeThroughLayers.Settings.of(fresh.copy(meshOnly = true)))
	}
}
