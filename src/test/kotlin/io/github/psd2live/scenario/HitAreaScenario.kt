package io.github.psd2live.scenario

import io.github.psd2live.application.WorkspaceHitAreaEdits
import io.github.psd2live.core.RigHitArea
import io.github.psd2live.format.compile.CapabilityProfile
import io.github.psd2live.format.compile.CapabilityScan
import io.github.psd2live.format.compile.ExportOptions
import io.github.psd2live.format.compile.Feature
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.HitAreaIR
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.Moc3Target
import io.github.psd2live.targets.runtime.P2lrt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hit areas set up on a generated figure: the Head and Body presets, an area of its own, renames and reordering - each
 * edit replayed cold and undone - kept through a save, hit-tested as the runtime does, and written to model3.json and
 * the .p2lrt file.
 */
class HitAreaScenario {
	@TempDir lateinit var temp: Path

	@Test fun hitAreasAreEditedKeptHitTestedAndExported() = Studio.run(temp, Characters.figure()) {
		val face = mesh("face"); val top = mesh("top"); val skirt = mesh("skirt")
		fun areas() = now.document.rigEdits.hitAreas
		edit("set up Head and Body", WorkspaceHitAreaEdits.PRESET, req())
		expect("presets", areas().map { Triple(it.id, it.name, it.meshes) } ==
			listOf(Triple("HitAreaHead", "Head", listOf(face)), Triple("HitAreaBody", "Body", listOf(top)))) { "presets ${areas()}" }
		edit("a skirt area before Body", WorkspaceHitAreaEdits.PUT, req("id" to "HitAreaSkirt", "name" to "Skirt",
			"meshes" to listOf("mesh:$skirt"), "before_id" to "HitAreaBody"))
		edit("Body covers the skirt too", WorkspaceHitAreaEdits.PUT, req("id" to "HitAreaBody", "name" to "Torso", "meshes" to listOf("mesh:$top", "mesh:$skirt")))
		expect("order", areas().map { it.id } == listOf("HitAreaHead", "HitAreaSkirt", "HitAreaBody") && areas()[2].name == "Torso") { "areas ${areas()}" }
		refused("an unknown mesh", WorkspaceHitAreaEdits.PUT, req("id" to "HitAreaHat", "meshes" to listOf("mesh:nothing")))
		refused("a taken ID", WorkspaceHitAreaEdits.PUT, req("id" to "HitAreaSkirt", "new_id" to "HitAreaHead"))
		refused("a new area without meshes", WorkspaceHitAreaEdits.PUT, req("id" to "HitAreaHat", "name" to "Hat"))
		refused("an ID outside Cubism's alphabet", WorkspaceHitAreaEdits.PUT, req("id" to "Hit-Hat", "meshes" to listOf("mesh:$face")))

		// The first area hit wins: the skirt is in Skirt before it is in Body.
		fun hit(x: Float, y: Float) = WorkspaceHitAreaEdits.test(areas(), now.model, emptyMap(), x, y)?.id
		expect("hit test", hit(210f, 70f) == "HitAreaHead" && hit(210f, 270f) == "HitAreaSkirt" && hit(210f, 180f) == "HitAreaBody" && hit(10f, 10f) == null) {
			"head ${hit(210f, 70f)}, skirt ${hit(210f, 270f)}, top ${hit(210f, 180f)}, outside ${hit(10f, 10f)}"
		}

		val kept = areas()
		reopen()
		expect("reopened", areas() == kept) { "reopened as ${areas()}" }

		val model3 = export("moc3").single { it.toString().endsWith(".model3.json") }
		val entries = Json.parseToJsonElement(Files.readString(model3)).jsonObject.getValue("HitAreas").jsonArray.map {
			it.jsonObject.getValue("Name").jsonPrimitive.content to it.jsonObject.getValue("Id").jsonPrimitive.content
		}
		expect("model3", entries == listOf("Head" to face, "Skirt" to skirt, "Torso" to top, "Torso" to skirt)) { "model3 HitAreas $entries" }
		expect("model3 loss", losses.any { it.getValue("object").jsonPrimitive.content == "HitAreaBody" && it.getValue("handling").jsonPrimitive.content == "approximated" }) {
			"losses $losses"
		}
		val p2lrt = String(Files.readAllBytes(export("p2lrt").single { it.toString().endsWith(".p2lrt") }), Charsets.ISO_8859_1)
		expect("p2lrt", "HITA" in p2lrt && "HitAreaSkirt" in p2lrt) { "the runtime file has no HITA chunk with the document's areas" }
		export("psd-pose")
		expect("psd-pose loss", losses.count { it.getValue("note").jsonPrimitive.content.startsWith("Hit areas") } == 3) { "losses $losses" }

		edit("delete Skirt", WorkspaceHitAreaEdits.DELETE, req("id" to "HitAreaSkirt"))
		expect("deleted", areas().map { it.id } == listOf("HitAreaHead", "HitAreaBody")) { "areas ${areas()}" }
	}

	@Test fun splitsCarryTheMeshOverAndDeletionsTakeItOut() = Studio.run(temp, Characters.figure()) {
		val face = mesh("face"); val top = mesh("top"); val skirt = mesh("skirt")
		fun areas() = now.document.rigEdits.hitAreas
		edit("Body on the top and the skirt", WorkspaceHitAreaEdits.PUT, req("id" to "HitAreaBody", "meshes" to listOf("mesh:$top", "mesh:$skirt")))
		edit("Skirt on the skirt", WorkspaceHitAreaEdits.PUT, req("id" to "HitAreaSkirt", "meshes" to listOf("mesh:$skirt")))
		edit("Head on the face", WorkspaceHitAreaEdits.PUT, req("id" to "HitAreaHead", "meshes" to listOf("mesh:$face")))
		edit("split the top in two", "source_split_polygon", req("layer_id" to "top", "names" to listOf("TopL", "TopR"),
			"piece_ids" to listOf("top_l", "top_r"), "polygon" to listOf(listOf(150, 100), listOf(210, 100), listOf(210, 250), listOf(150, 250))))
		val pieces = meshes("top_l") + meshes("top_r")
		expect("split", pieces.size == 2 && areas().first { it.id == "HitAreaBody" }.meshes == pieces + skirt) { "pieces $pieces, areas ${areas()}" }
		expect("split hit", WorkspaceHitAreaEdits.test(areas(), now.model, emptyMap(), 180f, 180f)?.id == "HitAreaBody") { "the left piece is not hit" }
		edit("delete the skirt layer", "layer_delete", req("layer_id" to "skirt"))
		expect("deleted", areas().map { it.id to it.meshes } == listOf("HitAreaBody" to pieces, "HitAreaHead" to listOf(face))) {
			"after deleting the skirt ${areas()}"
		}
		reopen()
	}

	@Test fun anAreaWhoseMeshesAreGoneStaysTheDocumentsOwnAndIsReported() {
		val ghost = RigHitArea("HitAreaGhost", "Ghost", listOf("ArtMeshGone"))
		val figure = Characters.figure()
		Studio.run(temp, figure.copy(rigEdits = figure.rigEdits.copy(hitAreas = listOf(ghost))), checks = emptySet()) {
			val face = mesh("face")
			// The mesh already gone can stay while the area changes; a new one must exist.
			edit("add the face beside the gone mesh", WorkspaceHitAreaEdits.PUT, req("id" to "HitAreaGhost", "meshes" to listOf("mesh:ArtMeshGone", "mesh:$face")))
			refused("a new missing mesh", WorkspaceHitAreaEdits.PUT, req("id" to "HitAreaGhost", "meshes" to listOf("mesh:ArtMeshGone", "mesh:AlsoGone")))
			edit("back to the gone mesh alone", WorkspaceHitAreaEdits.PUT, req("id" to "HitAreaGhost", "meshes" to listOf("mesh:ArtMeshGone")))
			val p2lrt = String(Files.readAllBytes(export("p2lrt").single { it.toString().endsWith(".p2lrt") }), Charsets.ISO_8859_1)
			expect("no fallback", "HITA" !in p2lrt) { "the runtime file fell back to the automatic areas" }
			expect("p2lrt loss", losses.any { it.getValue("object").jsonPrimitive.content == "HitAreaGhost" && it.getValue("handling").jsonPrimitive.content == "dropped" }) {
				"losses $losses"
			}
			val model3 = export("moc3").single { it.toString().endsWith(".model3.json") }
			expect("model3", "HitAreas" !in Json.parseToJsonElement(Files.readString(model3)).jsonObject) { Files.readString(model3) }
			expect("moc3 loss", losses.any { it.getValue("object").jsonPrimitive.content == "HitAreaGhost" }) { "losses $losses" }
		}
	}

	@Test fun aHiddenMeshTheMocLeavesOutIsReportedAndACmo3ReplacementKeepsTheAreas() = Studio.run(temp, Characters.figure(), checks = emptySet()) {
		val face = mesh("face"); val skirt = mesh("skirt")
		edit("Skirt on the skirt", WorkspaceHitAreaEdits.PUT, req("id" to "HitAreaSkirt", "meshes" to listOf("mesh:$skirt")))
		edit("Head on the face", WorkspaceHitAreaEdits.PUT, req("id" to "HitAreaHead", "meshes" to listOf("mesh:$face")))
		edit("hide the skirt", "object_edit_appearance", req("edits" to listOf(mapOf("action" to "visibility", "kind" to "mesh", "id" to skirt, "visible" to false))))
		expect("hidden is not hit", WorkspaceHitAreaEdits.test(now.document.rigEdits.hitAreas, now.model, emptyMap(), 210f, 270f) == null) {
			"the hidden skirt was hit"
		}
		val model3 = export("moc3", mapOf("hidden_meshes" to "false")).single { it.toString().endsWith(".model3.json") }
		val names = Json.parseToJsonElement(Files.readString(model3)).jsonObject.getValue("HitAreas").jsonArray.map { it.jsonObject.getValue("Name").jsonPrimitive.content }
		expect("model3", names == listOf("Head")) { "model3 HitAreas $names" }
		expect("loss", losses.any { it.getValue("object").jsonPrimitive.content == skirt && it.getValue("handling").jsonPrimitive.content == "dropped" }) { "losses $losses" }
		val cmo3 = Files.readAllBytes(export("cmo3").single { it.toString().endsWith(".cmo3") })
		val (_, replaced) = io.github.psd2live.core.Cmo3ModelImport.prepare(cmo3, io.github.psd2live.core.Cmo3ImportMode.REPLACE, now.model, now.model.config)
		expect("replace", replaced.rigEdits.hitAreas == now.document.rigEdits.hitAreas) { "the replacement keeps ${replaced.rigEdits.hitAreas}" }
	}

	@Test fun targetsReportWhatTheyCannotSayAboutHitAreas() {
		val ir = RigIR(Canvas(1f, 1f), emptyList(), hitAreas = listOf(HitAreaIR("HitAreaBody", "Body", listOf("top", "skirt")), HitAreaIR("HitAreaHead", "Head", listOf("face"))))
		assertEquals(listOf("HitAreaBody", "HitAreaHead"), P2lrt.version1Losses(ir).map { it.objectId })
		// Only the meshes the rig has count (this rig has none); a target without hit areas drops each area, one with them
		// each mesh that is gone.
		assertEquals(emptyList(), Moc3Target.hitAreaLosses(ir))
		assertEquals(listOf("HitAreaBody", "HitAreaHead"), CapabilityScan.scan(ir, CapabilityProfile(), ExportOptions("x")).map { it.objectId })
		assertEquals(listOf("HitAreaBody", "HitAreaBody", "HitAreaHead"), CapabilityScan.scan(ir, CapabilityProfile(hitAreas = true), ExportOptions("x"))
			.filter { it.feature == Feature.STRUCTURE }.map { it.objectId })
	}
}
